/*
 * Copyright (C) 2026 DiamaneOS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.cleanimages;

import android.app.role.RoleManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.UserInfo;
import android.graphics.Bitmap;
import android.os.Binder;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IRemoteCallback;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.Log;
import android.util.LruCache;

import com.android.internal.diamaneos.cleancopies.CleanCopyClient;
import com.android.internal.diamaneos.cleancopies.CleanCopyGate;
import com.android.internal.diamaneos.cleancopies.CleanCopyKey;
import com.android.internal.diamaneos.cleancopies.CleanImageFile;

import de.diamaneos.decode.client.AppResourceImages;
import de.diamaneos.decode.client.CleanCopies;
import de.diamaneos.decode.client.CleanImageStore;
import de.diamaneos.decode.client.VmDecode;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * DiamaneOS: makes clean copies of apps' image files in the VM and keeps them ({@link CleanImageStore} in
 * /data/system). A system process that met a file without a copy asks for it ({@link CleanImageProvider}). Whose file
 * it is comes from here alone: the package the APK is installed as (its base or a split, for any user), or the active
 * install session the APK belongs to (the installer's dialog). What an asker or the APK's own resource table says the
 * package is never counts. The file's bytes are read from the APK as an opaque blob and decoded in the VM charged to
 * that package. Nothing of the file is parsed here: no image decoder, no XML. Two threads, one file at a time each:
 * the files a system process waits for on one, the warm-up's on the other, so a slow app's files never hold up a
 * visible icon. One package gets a limited number of decodes and a limited VM time per hour.
 */
public final class CleanImageMaker {
    private static final String TAG = "CleanImages";
    private static final String DIR = "diamaneos_clean_images";
    // The generation of what a copy holds: a change (a decoder whose output differs) removes every copy at the next
    // start, and they are made again.
    private static final int COPY_GENERATION = 1;
    private static final String GENERATION_FILE = "diamaneos_clean_images.generation";
    private static final int MAX_FILE_BYTES = 8 * 1024 * 1024;
    private static final int MAX_PENDING = 4096;
    // A decode a system process waits for, and one of the warm-up.
    private static final long URGENT_TIMEOUT_MS = 8_000;
    private static final long BACKGROUND_TIMEOUT_MS = 4_000;
    // A file the VM could not make now (down, busy) or that is not installed yet is tried again after this, then
    // twice as long each time up to RETRY_MAX_MS, at most RETRY_ATTEMPTS times. A file that ran out of time twice is
    // not tried again until the next start.
    private static final long RETRY_MS = 60_000;
    private static final long RETRY_MAX_MS = 30 * 60_000;
    private static final int RETRY_ATTEMPTS = 8;
    private static final int TIMEOUT_ATTEMPTS = 2;
    // One package's decodes per hour at most, by number and by VM time.
    private static final int BUDGET_PER_HOUR = 512;
    private static final long BUDGET_MS_PER_HOUR = 60_000;
    // The package map is looked up again for an APK it does not know at most this often.
    private static final long OWNERS_REBUILD_MS = 10_000;
    // A shortcut's icon picture is decoded in at most this long (waiting in the queue included), at most this large,
    // two at a time with up to six waiting.
    private static final long SHORTCUT_MS = 1_500;
    private static final int SHORTCUT_MAX_SIDE = 1024;
    // The callers that may have shortcut icons decoded: the share sheet and SystemUI (and the home app, by role).
    private static final String[] SHORTCUT_CALLERS = {"com.android.intentresolver", "com.android.systemui"};
    // Log lines about what askers sent, per minute.
    private static final int LOG_LINES_PER_MINUTE = 20;

    private static CleanImageMaker sInstance;

    /** Sent (to the launcher) when copies of an app's images were made: icons that showed the default can load. */
    public static final String ACTION_MADE = "de.diamaneos.action.CLEAN_COPIES_MADE";
    public static final String EXTRA_PACKAGE = "package";
    // The apps told: the launcher, which keeps icons in its own cache.
    private static final String[] TOLD = {"com.android.launcher3"};
    // Copies made close together are told once.
    private static final long TELL_DELAY_MS = 2_000;

    private final Context mContext;
    private final CleanImageStore mStore;
    // The warm-up's thread (its files, retries and tells), and the thread of files a system process waits for.
    private final Handler mHandler;
    private final Handler mUrgentHandler;
    private final Object mLock = new Object();
    // Files a system process waits for, and files of the warm-up, by key (guarded by mLock).
    private final LinkedHashMap<String, Request> mUrgent = new LinkedHashMap<>();
    private final LinkedHashMap<String, Request> mBackground = new LinkedHashMap<>();
    // Files to try again, by key, and the time of the next try (guarded by mLock).
    private final LinkedHashMap<String, Request> mRetry = new LinkedHashMap<>();
    private boolean mRetryScheduled; // guarded by mLock
    // Files the VM refused, or that failed too often (not tried again until the next start).
    private final LruCache<String, Boolean> mRefused = new LruCache<>(4096);
    // Warm-up files that did not fit their APK's cap (a system process that asks for one may still have it made).
    private final LruCache<String, Boolean> mCapped = new LruCache<>(4096);
    // Apps with copies made since they were last told, by package and user (guarded by mLock).
    private final Set<String> mMadeFor = new HashSet<>();
    // Decodes and VM milliseconds per package in the current hour (guarded by mLock).
    private final Map<String, long[]> mBudget = new HashMap<>();
    private long mBudgetHour; // guarded by mLock
    // The use time of the next app the warm-up makes copies of: one millisecond older each app, so when the store is
    // full the apps the pass made last (seen least) go first, and copies a system process asked for are the newest.
    private long mBackgroundClock; // guarded by mLock
    private int mLogLines; // guarded by mLock
    private long mLogMinute; // guarded by mLock
    // Which package each installed APK is (base and splits, by path), and its users (guarded by mOwnersLock).
    private final Object mOwnersLock = new Object();
    private Map<String, String> mApkOwners;
    private Map<String, TreeSet<Integer>> mPackageUsers;
    private long mOwnersBuilt; // elapsed time, guarded by mOwnersLock
    // Processes told when copies of a package were made (filling icons, hosted widgets).
    private final RemoteCallbackList<IRemoteCallback> mWatchers = new RemoteCallbackList<>();
    private final ThreadPoolExecutor mShortcuts = new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(6), r -> {
                final Thread t = new Thread(r, "CleanImagesShortcut");
                t.setDaemon(true);
                return t;
            });
    // The APK zip each maker thread has open, for a run of files of one APK.
    private final ThreadLocal<ZipHolder> mZips = ThreadLocal.withInitial(ZipHolder::new);

    private CleanImageMaker(Context context) {
        mContext = context;
        mStore = new CleanImageStore(new File(Environment.getDataSystemDirectory(), DIR));
        final HandlerThread thread = new HandlerThread("CleanImages", Process.THREAD_PRIORITY_BACKGROUND);
        thread.start();
        mHandler = new Handler(thread.getLooper());
        final HandlerThread urgent = new HandlerThread("CleanImagesUrgent", Process.THREAD_PRIORITY_DEFAULT);
        urgent.start();
        mUrgentHandler = new Handler(urgent.getLooper());
        mShortcuts.allowCoreThreadTimeOut(true);
    }

    /** Reads the store's index on its own thread, early, so no binder thread waits for it. */
    void indexStore() {
        mHandler.post(mStore::index);
    }

    /**
     * Once the phone has booted: copies of an older generation go, and copies of APKs that are gone go, unless they
     * still stand in for an installed app's resource until its new copy is made.
     */
    void prepareStore() {
        mHandler.post(() -> {
            final File marker = new File(Environment.getDataSystemDirectory(), GENERATION_FILE);
            final String generation = String.valueOf(COPY_GENERATION);
            String kept = null;
            try {
                kept = new String(Files.readAllBytes(marker.toPath()), StandardCharsets.UTF_8);
            } catch (IOException e) {
                // None yet.
            }
            if (!generation.equals(kept)) {
                Log.i(TAG, "copies of another generation removed, they are made again");
                mStore.clear();
                try {
                    Files.write(marker.toPath(), generation.getBytes(StandardCharsets.UTF_8));
                } catch (IOException e) {
                    Log.w(TAG, "copy generation not written: " + e);
                }
            }
            mStore.prepare(this::installedForAnyone);
        });
    }

    private boolean installedForAnyone(String packageName) {
        synchronized (mOwnersLock) {
            ownersLocked();
            return mPackageUsers.containsKey(packageName);
        }
    }

    static synchronized CleanImageMaker get(Context context) {
        if (sInstance == null) {
            sInstance = new CleanImageMaker(context);
        }
        return sInstance;
    }

    CleanImageStore store() {
        return mStore;
    }

    private static final class Request {
        final String apk;
        final String file;
        final int density;
        final int width;
        final int height;
        final String resource; // the resource name the file was loaded as, or null
        final int user;
        final String key;
        final long stamp; // the warm-up's use time for its app, 0 for a file a system process waits for
        boolean urgent;
        int attempts;
        int timeouts;
        long due;

        Request(String apk, String file, int density, int width, int height, String resource, int user, String key,
                boolean urgent, long stamp) {
            this.apk = apk;
            this.file = file;
            this.density = density;
            this.width = width;
            this.height = height;
            this.resource = resource;
            this.user = user;
            this.key = key;
            this.urgent = urgent;
            this.stamp = stamp;
        }
    }

    /**
     * Whose files an APK holds: the package the decode is charged to, the package and user told when its copies are
     * made, and whether it is an install session (no identity, no stand-in).
     */
    private static final class Owner {
        final String charged;
        final String packageName;
        final int user;
        final boolean session;

        Owner(String charged, String packageName, int user, boolean session) {
            this.charged = charged;
            this.packageName = packageName;
            this.user = user;
            this.session = session;
        }
    }

    // An APK zip kept open across a run of files of the same APK on one thread.
    private static final class ZipHolder {
        String path;
        ZipFile zip;

        ZipFile open(String apk) throws IOException {
            if (zip != null && apk.equals(path)) {
                return zip;
            }
            close();
            zip = new ZipFile(apk);
            path = apk;
            return zip;
        }

        void close() {
            if (zip != null) {
                try {
                    zip.close();
                } catch (IOException ignored) {
                    // Closed.
                }
            }
            zip = null;
            path = null;
        }
    }

    /** The use time for the next app the warm-up asks copies for (one per app, see mBackgroundClock). */
    long nextStamp() {
        synchronized (mLock) {
            final long now = System.currentTimeMillis();
            if (mBackgroundClock == 0 || mBackgroundClock > now) {
                mBackgroundClock = now;
            }
            return --mBackgroundClock;
        }
    }

    /**
     * Has the file made, when it holds together. {@code resource}: the resource name it was loaded as (only to find
     * its stand-in among the copies of the package the APK is installed as), or null. {@code urgent}: a system
     * process waits for it (a gate), else the warm-up asks, with {@code stamp} ({@link #nextStamp}) for its app.
     * Returns at once: the work is queued.
     */
    void request(String apk, String file, int density, int width, int height, String resource, int user,
            boolean urgent, long stamp) {
        if (!plausible(apk, file, density, width, height, user)) {
            logLimited("request refused: " + apk + " " + file);
            return;
        }
        if (CleanCopyKey.isSystemImage(apk)) {
            return; // Decoded as before everywhere: nothing to make.
        }
        if (resource != null && !CleanCopyKey.resourceName(resource)) {
            resource = null;
        }
        final String key = AppResourceImages.keyOf(apk, file, density, width, height);
        if (key == null || mRefused.get(key) != null || (!urgent && mCapped.get(key) != null)) {
            return;
        }
        final Request request = new Request(apk, file, density, width, height, resource, user, key, urgent,
                urgent ? 0 : stamp);
        if (mStore.has(key)) {
            // The asker missed a copy that is there: made while it looked, or one that does not check out (removed
            // and made again). Either way the launcher may still show the default.
            mHandler.post(() -> {
                if (mStore.verify(key)) {
                    final Owner owner = owner(apk, user);
                    if (owner != null) {
                        told(owner.packageName, owner.user);
                    }
                } else {
                    enqueue(request);
                }
            });
            return;
        }
        enqueue(request);
    }

    private void enqueue(Request request) {
        synchronized (mLock) {
            final Request waiting = mRetry.get(request.key);
            if (waiting != null) {
                if (!request.urgent) {
                    return; // Tried again on its own.
                }
                // A system process asks again (at most once a minute): tried now, its tries so far still count.
                mRetry.remove(request.key);
                request.attempts = waiting.attempts;
                request.timeouts = waiting.timeouts;
            }
            if (request.urgent) {
                mBackground.remove(request.key);
                if (mUrgent.containsKey(request.key)) {
                    return;
                }
                mUrgent.put(request.key, request);
                trimLocked(mUrgent);
            } else {
                if (mUrgent.containsKey(request.key) || mBackground.containsKey(request.key)) {
                    return;
                }
                mBackground.put(request.key, request);
                trimLocked(mBackground);
            }
        }
        if (request.urgent) {
            mUrgentHandler.post(() -> runNext(true));
        } else {
            mHandler.post(() -> runNext(false));
        }
    }

    private static void trimLocked(LinkedHashMap<String, Request> queue) {
        if (queue.size() > MAX_PENDING) {
            final Iterator<String> it = queue.keySet().iterator();
            it.next();
            it.remove();
        }
    }

    /**
     * The key of the newest copy of the same resource as the file (the resource {@code resource} of the package the
     * APK is installed as), the closest to {@code density}, or null.
     */
    String fallbackKey(String apk, String file, int density, int width, int height, String resource, int user) {
        if (!plausible(apk, file, density, width, height, user) || !CleanCopyKey.resourceName(resource)) {
            return null;
        }
        final Owner owner = owner(apk, user);
        if (owner == null || owner.session) {
            return null;
        }
        return mStore.fallbackKey(CleanCopyKey.identity(owner.packageName, resource, file, width, height), density);
    }

    /** True when the app {@code uid} runs may have shortcut icons decoded: the home app, the share sheet, SystemUI. */
    boolean shortcutCaller(int uid) {
        final long identity = Binder.clearCallingIdentity();
        try {
            if (UserHandle.getAppId(uid) == Process.SYSTEM_UID) {
                return true;
            }
            final String[] packages = mContext.getPackageManager().getPackagesForUid(uid);
            if (packages == null) {
                return false;
            }
            final List<String> home = mContext.getSystemService(RoleManager.class)
                    .getRoleHoldersAsUser(RoleManager.ROLE_HOME, UserHandle.getUserHandleForUid(uid));
            for (String name : packages) {
                if (List.of(SHORTCUT_CALLERS).contains(name) || home.contains(name)) {
                    return true;
                }
            }
            return false;
        } catch (RuntimeException e) {
            return false;
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    /**
     * A shortcut's icon picture decoded in the VM from {@code fd}, which the caller opened with its own grant, as
     * Android does, charged to {@code packageName}: at most 1.5 s from the call (a short wait in the queue
     * included), two at a time on threads of their own with up to six waiting. Null when it cannot be had (the queue
     * full, out of time, the VM down). Nothing is kept.
     */
    Bitmap shortcutIcon(ParcelFileDescriptor fd, String packageName, int user) {
        if (fd == null || packageName == null || packageName.isEmpty() || packageName.length() > 255 || user < 0) {
            return null;
        }
        final long end = SystemClock.uptimeMillis() + SHORTCUT_MS;
        final Future<Bitmap> decode;
        try {
            decode = mShortcuts.submit(() -> {
                final long left = end - SystemClock.uptimeMillis();
                if (left <= 0) {
                    return null;
                }
                return VmDecode.decode(mContext, fd, packageName, VmDecode.PRIORITY_USER_WAITING,
                        SHORTCUT_MAX_SIDE, SHORTCUT_MAX_SIDE, VmDecode.deadlineIn(left)).bitmap;
            });
        } catch (RejectedExecutionException e) {
            return null; // Two decodes running and six waiting already.
        }
        try {
            return decode.get(Math.max(1, end - SystemClock.uptimeMillis()), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            decode.cancel(true);
            return null;
        } catch (Exception e) {
            logLimited("shortcut icon of " + packageName + " not made: " + e);
            return null;
        }
    }

    /** Has {@code callback} called with the package and user whenever copies of a package were made. */
    boolean watch(android.os.IBinder callback) {
        return callback != null && mWatchers.register(IRemoteCallback.Stub.asInterface(callback));
    }

    /**
     * How many of {@code keys} have no copy and were not refused: files that may still be made (never a file of the
     * system image, which is decoded as before, nor a warm-up file that did not fit its APK's cap).
     */
    int open(List<String> keys) {
        int open = 0;
        for (int i = 0; i < keys.size(); i++) {
            final String key = keys.get(i);
            if (key != null && !CleanCopyKey.isSystemImage(CleanCopyKey.apkOf(key)) && !mStore.has(key)
                    && mRefused.get(key) == null && mCapped.get(key) == null) {
                open++;
            }
        }
        return open;
    }

    /** Removes the copies of the APK at {@code apk}. */
    void forget(String apk) {
        if (apk != null) {
            mStore.forgetApk(apk);
            mRefused.evictAll();
            mCapped.evictAll();
            synchronized (mLock) {
                mRetry.clear();
            }
        }
    }

    /** An app was removed for a user: its copies go once it is gone for every user (copies are of its APK). */
    void forgetPackage(String packageName, int user) {
        packagesChanged();
        mHandler.post(() -> {
            if (!installedForAnyone(packageName)) {
                mStore.forgetOwner(packageName);
            }
        });
    }

    /** Packages changed: which APK belongs to whom is looked up again. */
    void packagesChanged() {
        synchronized (mOwnersLock) {
            mApkOwners = null;
            mPackageUsers = null;
        }
    }

    // The request's fields as a gate makes them: an absolute APK path, a file in it, a density and a box.
    static boolean plausible(String apk, String file, int density, int width, int height, int user) {
        return apk != null && apk.startsWith("/") && !apk.contains("/../") && apk.indexOf(':') < 0
                && apk.indexOf('\0') < 0 && apk.length() < 1024
                && CleanCopyKey.entryName(file)
                && density >= 0 && density <= 0xffff
                && width >= 16 && height >= 16 && width <= 16384 && height <= 16384
                && user >= 0;
    }

    private void runNext(boolean urgent) {
        final Request request;
        synchronized (mLock) {
            final Iterator<Request> it = (urgent ? mUrgent : mBackground).values().iterator();
            if (!it.hasNext()) {
                request = null;
            } else {
                request = it.next();
                it.remove();
            }
        }
        if (request == null) {
            mZips.get().close(); // The run ended.
            return;
        }
        try {
            make(request);
        } catch (RuntimeException | OutOfMemoryError e) {
            logLimited("not made: " + request.file + ": " + e);
            failed(request);
        }
        (urgent ? mUrgentHandler : mHandler).post(() -> runNext(urgent));
    }

    private void make(Request request) {
        if (mStore.has(request.key)) {
            return;
        }
        final Owner owner = owner(request.apk, request.user);
        if (owner == null) {
            // Not an installed APK or install session (yet: an install that has not finished): tried again later.
            logLimited("not an installed APK: " + request.apk);
            failed(request);
            return;
        }
        if (!spend(owner.charged)) {
            logLimited("decode budget of " + owner.charged + " used up for this hour");
            failed(request);
            return;
        }
        final byte[] bytes;
        try {
            bytes = readEntry(mZips.get(), request.apk, request.file);
        } catch (IOException e) {
            mZips.get().close();
            logLimited("not read: " + owner.packageName + " " + request.file + ": " + e.getMessage());
            mRefused.put(request.key, Boolean.TRUE);
            return;
        }
        final long timeout = request.urgent ? URGENT_TIMEOUT_MS : BACKGROUND_TIMEOUT_MS;
        final long start = SystemClock.uptimeMillis();
        final CleanCopies.Entry entry;
        try {
            entry = AppResourceImages.makeCopy(mContext, bytes, request.density, request.width, request.height,
                    owner.charged, timeout,
                    request.urgent ? VmDecode.PRIORITY_USER_WAITING : VmDecode.PRIORITY_BACKGROUND);
        } catch (VmDecode.RejectedException e) {
            charge(owner.charged, SystemClock.uptimeMillis() - start);
            logLimited("refused by the VM: " + owner.packageName + " " + request.file + ": " + e.getMessage());
            mRefused.put(request.key, Boolean.TRUE);
            return;
        } catch (VmDecode.UnavailableException e) {
            final long spent = SystemClock.uptimeMillis() - start;
            charge(owner.charged, spent);
            if (spent >= timeout - 100 && ++request.timeouts >= TIMEOUT_ATTEMPTS) {
                // Out of time twice: a file this slow is not tried again until the next start.
                logLimited("out of time twice, not tried again: " + owner.packageName + " " + request.file);
                mRefused.put(request.key, Boolean.TRUE);
                return;
            }
            failed(request);
            return;
        }
        charge(owner.charged, SystemClock.uptimeMillis() - start);
        // The identity is made here, of the package the APK is installed as: never of what an asker said.
        final String identity = owner.session ? null : CleanCopyKey.identity(owner.packageName, request.resource,
                request.file, request.width, request.height);
        final long used = request.urgent ? System.currentTimeMillis() : request.stamp;
        final int saved = mStore.saveCopy(request.key, identity, entry.bitmap, entry.chunk, used, request.urgent);
        if (saved == CleanImageStore.OVER_CAP) {
            mCapped.put(request.key, Boolean.TRUE); // The warm-up does not push out a copy of the same APK.
            return;
        }
        if (saved != CleanImageStore.SAVED) {
            failed(request); // Not written (a full disk for a moment): tried again.
            return;
        }
        mCapped.remove(request.key);
        // A key made of a file that changed meanwhile is a key nobody asks for: it goes with the next prune.
        told(owner.packageName, owner.user);
    }

    // One more decode of the package's files this hour, if its budget (number and VM time) allows it.
    private boolean spend(String packageName) {
        synchronized (mLock) {
            final long[] spent = budgetLocked(packageName);
            if (spent[0] >= BUDGET_PER_HOUR || spent[1] >= BUDGET_MS_PER_HOUR) {
                return false;
            }
            spent[0]++;
            return true;
        }
    }

    // The VM time a decode of the package's file took, charged to its budget.
    private void charge(String packageName, long ms) {
        synchronized (mLock) {
            budgetLocked(packageName)[1] += Math.max(0, ms);
        }
    }

    private long[] budgetLocked(String packageName) {
        final long hour = SystemClock.elapsedRealtime() / (60 * 60_000);
        if (hour != mBudgetHour) {
            mBudgetHour = hour;
            mBudget.clear();
        }
        return mBudget.computeIfAbsent(packageName, k -> new long[2]);
    }

    // A file that could not be made now is tried again later (twice as late each time), until it was tried too
    // often: then it is refused until the next start.
    private void failed(Request request) {
        synchronized (mLock) {
            request.attempts++;
            if (request.attempts > RETRY_ATTEMPTS) {
                mRetry.remove(request.key);
                mRefused.put(request.key, Boolean.TRUE);
                return;
            }
            request.due = SystemClock.elapsedRealtime()
                    + Math.min(RETRY_MS << Math.min(request.attempts - 1, 10), RETRY_MAX_MS);
            mRetry.put(request.key, request);
            if (!mRetryScheduled) {
                mRetryScheduled = true;
                mHandler.postDelayed(this::retry, RETRY_MS);
            }
        }
    }

    // Queues the files whose time to try again has come.
    private void retry() {
        final long now = SystemClock.elapsedRealtime();
        final List<Request> due = new ArrayList<>();
        synchronized (mLock) {
            mRetryScheduled = false;
            for (Iterator<Request> it = mRetry.values().iterator(); it.hasNext(); ) {
                final Request request = it.next();
                if (request.due <= now) {
                    it.remove();
                    due.add(request);
                }
            }
            if (!mRetry.isEmpty()) {
                mRetryScheduled = true;
                mHandler.postDelayed(this::retry, RETRY_MS);
            }
        }
        for (Request request : due) {
            enqueue(request);
        }
    }

    // Has the launcher and the watching processes told about the app's copies, once its files are made (debounced).
    private void told(String owner, int user) {
        synchronized (mLock) {
            if (!mMadeFor.add(owner + '/' + user)) {
                return;
            }
        }
        mHandler.postDelayed(() -> tell(owner, user), TELL_DELAY_MS);
    }

    // Tells the launcher and the watching processes that copies of the app's images were made, once none of its files
    // waits any more.
    private void tell(String owner, int user) {
        synchronized (mLock) {
            for (LinkedHashMap<String, Request> queue : List.of(mUrgent, mBackground)) {
                for (Request waiting : queue.values()) {
                    if (owner.equals(installedPackageOf(waiting.apk))) {
                        mHandler.postDelayed(() -> tell(owner, user), TELL_DELAY_MS);
                        return;
                    }
                }
            }
            mMadeFor.remove(owner + '/' + user);
        }
        final long identity = Binder.clearCallingIdentity();
        try {
            for (String target : TOLD) {
                final Intent intent = new Intent(ACTION_MADE)
                        .setPackage(target)
                        .putExtra(EXTRA_PACKAGE, owner)
                        .putExtra(Intent.EXTRA_USER, UserHandle.of(user))
                        .addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);
                mContext.sendBroadcastAsUser(intent, UserHandle.ALL);
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "not told: " + e);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
        final Bundle made = new Bundle();
        made.putString(CleanCopyClient.EXTRA_PACKAGE, owner);
        made.putInt(CleanCopyClient.EXTRA_USER, user);
        final int count = mWatchers.beginBroadcast();
        try {
            for (int i = 0; i < count; i++) {
                try {
                    mWatchers.getBroadcastItem(i).sendResult(made);
                } catch (RemoteException e) {
                    // Gone: the list drops it.
                }
            }
        } finally {
            mWatchers.finishBroadcast();
        }
    }

    // The package an installed APK is (no install sessions), or null.
    private String installedPackageOf(String apk) {
        synchronized (mOwnersLock) {
            ownersLocked();
            return mApkOwners.get(apk);
        }
    }

    /**
     * Whose files the APK at {@code apk} holds: the package it is the base or a split of (as {@code user} when it is
     * installed for that user, else for the first user it is installed for: the system server's own gate asks as user
     * 0), else the active install session whose APK it is, else null. An APK the map does not know makes it look
     * again (at most every 10 s): an install whose broadcast has not arrived yet.
     */
    private Owner owner(String apk, int user) {
        synchronized (mOwnersLock) {
            ownersLocked();
            if (!mApkOwners.containsKey(apk)
                    && SystemClock.elapsedRealtime() - mOwnersBuilt > OWNERS_REBUILD_MS) {
                mApkOwners = null;
                ownersLocked();
            }
            final String packageName = mApkOwners.get(apk);
            if (packageName != null) {
                final TreeSet<Integer> users = mPackageUsers.get(packageName);
                final int owned = users.contains(user) ? user : users.first();
                return new Owner(packageName, packageName, owned, false);
            }
        }
        return sessionOwner(apk);
    }

    private void ownersLocked() {
        if (mApkOwners != null) {
            return;
        }
        final Map<String, String> owners = new HashMap<>();
        final Map<String, TreeSet<Integer>> users = new HashMap<>();
        final long identity = Binder.clearCallingIdentity();
        try {
            final PackageManager pm = mContext.getPackageManager();
            for (UserInfo user : mContext.getSystemService(UserManager.class).getAliveUsers()) {
                for (ApplicationInfo ai : pm.getInstalledApplicationsAsUser(
                        PackageManager.MATCH_STATIC_SHARED_AND_SDK_LIBRARIES
                                | PackageManager.MATCH_DISABLED_COMPONENTS
                                | PackageManager.MATCH_DIRECT_BOOT_AWARE
                                | PackageManager.MATCH_DIRECT_BOOT_UNAWARE, user.id)) {
                    final List<String> apks = new ArrayList<>();
                    apks.add(ai.sourceDir);
                    if (ai.splitSourceDirs != null) {
                        apks.addAll(List.of(ai.splitSourceDirs));
                    }
                    for (String apk : apks) {
                        if (apk != null) {
                            owners.put(apk, ai.packageName);
                        }
                    }
                    users.computeIfAbsent(ai.packageName, k -> new TreeSet<>()).add(user.id);
                }
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "installed APKs not listed: " + e);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
        mApkOwners = owners;
        mPackageUsers = users;
        mOwnersBuilt = SystemClock.elapsedRealtime();
    }

    // The active install session whose staged APK {@code apk} is (its base, or another APK in its staging folder: a
    // split), or null. The decode is charged to the session's installer, which the system records itself, never to
    // the package name the session declares. Only paths in a session's staging folder are looked up.
    private Owner sessionOwner(String apk) {
        if (!apk.startsWith("/data/app/vmdl") || !apk.contains(".tmp/") || !apk.endsWith(".apk")) {
            return null;
        }
        final String folder = new File(apk).getParent();
        final long identity = Binder.clearCallingIdentity();
        try {
            for (UserInfo user : mContext.getSystemService(UserManager.class).getAliveUsers()) {
                final PackageInstaller installer = mContext.createContextAsUser(UserHandle.of(user.id), 0)
                        .getPackageManager().getPackageInstaller();
                for (PackageInstaller.SessionInfo session : installer.getAllSessions()) {
                    final String base = session.getResolvedBaseApkPath();
                    if (!session.isActive() || base == null || folder == null
                            || !folder.equals(new File(base).getParent())) {
                        continue;
                    }
                    final String charged = session.getInstallerPackageName();
                    if (charged == null) {
                        return null;
                    }
                    // Told by the name the installer's dialog loads the icon for (its filling icon), no identity.
                    final String told = session.getAppPackageName() != null ? session.getAppPackageName() : charged;
                    return new Owner(charged, told, user.id, true);
                }
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "install sessions not listed: " + e);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
        return null;
    }

    // Logs what askers sent, at most LOG_LINES_PER_MINUTE lines a minute, without control characters, cut short.
    private void logLimited(String line) {
        synchronized (mLock) {
            final long minute = SystemClock.elapsedRealtime() / 60_000;
            if (minute != mLogMinute) {
                mLogMinute = minute;
                mLogLines = 0;
            }
            if (++mLogLines > LOG_LINES_PER_MINUTE) {
                return;
            }
        }
        final StringBuilder clean = new StringBuilder(Math.min(line.length(), 300));
        for (int i = 0; i < line.length() && clean.length() < 300; i++) {
            final char c = line.charAt(i);
            clean.append(Character.isISOControl(c) ? '?' : c);
        }
        Log.w(TAG, clean.toString());
    }

    /**
     * Sets the system server's own image gate (the clean icon cache): apps' images loaded here come from this store,
     * and a missing one is made. Called once, early in the system server's start.
     */
    public static void installGate(Context systemContext) {
        // Autofill's views (LateImages): the service's own bitmap files from this store first, kept here once made.
        de.diamaneos.decode.client.LateImages.setStore(new CleanCopies() {
            @Override
            public Entry load(String key) {
                return get(systemContext).mStore.load(key);
            }

            @Override
            public void save(String key, Bitmap bitmap, de.diamaneos.decode.client.NinePatchChunk chunk) {
                get(systemContext).mStore.save(key, bitmap, chunk);
            }

            @Override
            public void saveIfMissing(String key, Bitmap bitmap, de.diamaneos.decode.client.NinePatchChunk chunk) {
                get(systemContext).mStore.saveIfMissing(key, bitmap, chunk);
            }
        });
        CleanCopyGate.installForSystemServer(systemContext, new CleanCopyGate.Source() {
            @Override
            public CleanImageFile read(String key) {
                final File file = get(systemContext).mStore.fileFor(key);
                final long length = file.length();
                if (length <= 0 || length > CleanImageFile.MAX_FILE_BYTES) {
                    return null;
                }
                try {
                    final byte[] bytes = java.nio.file.Files.readAllBytes(file.toPath());
                    return CleanImageFile.read(bytes, bytes.length, key);
                } catch (IOException | RuntimeException e) {
                    return null;
                }
            }

            @Override
            public CleanImageFile fallback(String apkPath, String file, int density, int maxWidth, int maxHeight,
                    String resourceName, int userId) {
                final String key = get(systemContext).fallbackKey(apkPath, file, density, maxWidth, maxHeight,
                        resourceName, userId);
                return key == null ? null : read(key);
            }

            @Override
            public void request(String key, String apkPath, String file, int density, int maxWidth,
                    int maxHeight, String resourceName, int userId) {
                get(systemContext).request(apkPath, file, density, maxWidth, maxHeight, resourceName, userId,
                        true, 0);
            }
        });
    }

    // The entry's bytes, as stored in the APK's zip (inflated when compressed), never parsed here. The zip stays open
    // for the next file of the same APK on this thread.
    private static byte[] readEntry(ZipHolder zips, String apk, String file) throws IOException {
        final ZipFile zip = zips.open(apk);
        final ZipEntry entry = zip.getEntry(file);
        if (entry == null || entry.isDirectory() || entry.getSize() > MAX_FILE_BYTES) {
            throw new IOException("no such image file");
        }
        try (InputStream in = zip.getInputStream(entry)) {
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            final byte[] buffer = new byte[64 * 1024];
            int n;
            while ((n = in.read(buffer)) > 0) {
                if (out.size() + n > MAX_FILE_BYTES) {
                    throw new IOException("image file too large");
                }
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        }
    }
}
