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
import android.os.Build;
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
import com.android.internal.diamaneos.cleancopies.CopyAudience;

import de.diamaneos.decode.client.AppResourceImages;
import de.diamaneos.decode.client.CleanCopies;
import de.diamaneos.decode.client.CopyPackages;
import de.diamaneos.decode.client.CopyIsolation;
import de.diamaneos.decode.client.CopyRequests;
import de.diamaneos.decode.client.CopyNotices;
import de.diamaneos.decode.client.CopyGenerations;
import de.diamaneos.decode.client.CleanImageStore;
import de.diamaneos.decode.client.MakeQueue;
import de.diamaneos.decode.client.VmDecode;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
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
 *
 * <p>Each copy belongs to one user incarnation. Installed APK assets use separate VM domains for that user and
 * package, apart from private input. Staged assets remain private to the install session. Direct reads are local
 * to the user, while an authorized rendering role can request a specific installed resource from a visible profile
 * and receive only that resource or its checked fallback. Completion notices follow those explicit subscriptions.

 */
public final class CleanImageMaker {
    private static final String TAG = "CleanImages";
    private static final String DIR = "diamaneos_clean_images";
    // The generation of what a copy holds: a change (a decoder whose output differs) removes every copy at the next
    // start, and they are made again.
    static final int COPY_GENERATION = 4;
    private static final String GENERATION_FILE = "diamaneos_clean_images.generation";
    private static final int MAX_FILE_BYTES = 8 * 1024 * 1024;
    private static final int MAX_PENDING = 4096;
    // A decode a system process waits for, and one of the warm-up.
    private static final long URGENT_TIMEOUT_MS = 8_000;
    private static final long BACKGROUND_TIMEOUT_MS = 4_000;
    // A file the VM could not make now (down, busy) or that is not installed yet is tried again after this, then
    // twice as long each time up to RETRY_MAX_MS, at most RETRY_ATTEMPTS times. A file that ran out of time twice is
    // not tried again until the next start. When the decode service answers again after an outage, every file that
    // waits for a retry or was given up is tried at once (serviceAnswers).
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
    // Files a system process waits for, files of the warm-up and files to try again, by key, each capped, one drain
    // per lane (guarded by mLock).
    private final CopyRequests mRequests = new CopyRequests();
    private final MakeQueue<Request> mQueue = new MakeQueue<>(MAX_PENDING);
    // Files the VM refused (not tried again until the next start).
    private final LruCache<String, Boolean> mRefused = new LruCache<>(4096);
    // Files that ran out of time twice or failed too often: not tried again until the next start, or until the decode
    // service answers again after an outage (the outage, not the file, may have made them fail).
    private final LruCache<String, Boolean> mGaveUp = new LruCache<>(4096);
    // Warm-up files that did not fit their APK's cap (a system process that asks for one may still have it made).
    private final LruCache<String, Boolean> mCapped = new LruCache<>(4096);
    // Apps with copies made since they were last told, by package and user (guarded by mLock).
    private final CopyNotices mMadeFor = new CopyNotices();
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
    private CopyPackages mApkOwners;
    private Map<String, TreeSet<Integer>> mPackageUsers;
    private long mOwnersBuilt; // elapsed time, guarded by mOwnersLock
    // Processes told when copies of a package were made (filling icons, hosted widgets), each with its uid (the
    // cookie): told only of the users it may see.
    private final RemoteCallbackList<IRemoteCallback> mWatchers = new RemoteCallbackList<>();
    // Moves on when packages change, so callers' kept admissions are checked again (CleanImageProvider).
    private final java.util.concurrent.atomic.AtomicInteger mPackagesGeneration =
            new java.util.concurrent.atomic.AtomicInteger();
    private final ThreadPoolExecutor mShortcuts = new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(6), r -> {
                final Thread t = new Thread(r, "CleanImagesShortcut");
                t.setDaemon(true);
                return t;
            });
    // Opens of shortcut icon URIs, one per provider and user at a time: a provider that never answers or never ends
    // its pipe holds only its own lane, never these two threads or another app's icons.
    private final OwnerLanes mShortcutLanes = new OwnerLanes("CleanImagesShortcutOpen");
    // The APK zip each maker thread has open, for a run of files of one APK.
    private final ThreadLocal<ZipHolder> mZips = ThreadLocal.withInitial(ZipHolder::new);

    private CleanImageMaker(Context context) {
        mContext = context;
        mStore = new CleanImageStore(new File(Environment.getDataSystemDirectory(), DIR + "_v" + COPY_GENERATION));
        final HandlerThread thread = new HandlerThread("CleanImages", Process.THREAD_PRIORITY_BACKGROUND);
        thread.start();
        mHandler = new Handler(thread.getLooper());
        final HandlerThread urgent = new HandlerThread("CleanImagesUrgent", Process.THREAD_PRIORITY_DEFAULT);
        urgent.start();
        mUrgentHandler = new Handler(urgent.getLooper());
        mShortcuts.allowCoreThreadTimeOut(true);
        VmDecode.addStateListener(this::serviceAnswers);
    }

    // The decode service answers (on the client's thread). After an outage the files that wait for a retry and those
    // given up are tried now, on the maker's threads. Either way the watching processes are told, so a thumbnail that
    // waits for the service (VmThumbnails) tries again.
    private void serviceAnswers(boolean wasDown) {
        if (wasDown) {
            mHandler.post(() -> {
                mGaveUp.evictAll();
                final List<Request> due;
                synchronized (mLock) {
                    due = mQueue.takeRetries();
                }
                for (Request request : due) {
                    request.timeouts = 0;
                    enqueue(request);
                }
                Log.i(TAG, "decode service back: " + due.size() + " files tried again");
            });
        }
        final Bundle answers = new Bundle();
        answers.putBoolean(CleanCopyClient.EXTRA_DECODE_ANSWERS, true);
        mHandler.post(() -> broadcastToWatchers(answers, UserHandle.USER_ALL));
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
            int kept = 3;
            try {
                kept = Integer.parseInt(new String(Files.readAllBytes(marker.toPath()), StandardCharsets.UTF_8));
            } catch (IOException | NumberFormatException e) {
                // The old store used the unversioned directory.
            }
            if (COPY_GENERATION != kept) {
                Log.i(TAG, "copies of another generation removed, they are made again");
                final String oldDir = kept < 4 ? DIR : DIR + "_v" + kept;
                new CleanImageStore(new File(Environment.getDataSystemDirectory(), oldDir)).clear();
                try {
                    Files.write(marker.toPath(), generation.getBytes(StandardCharsets.UTF_8));
                } catch (IOException e) {
                    Log.w(TAG, "copy generation not written: " + e);
                }
            }
            mStore.prepare(this::installedForAnyone, this::userAlive);
        });
    }

    private boolean userAlive(int user) {
        final long identity = Binder.clearCallingIdentity();
        try {
            return mContext.getSystemService(UserManager.class).getUserInfo(user) != null;
        } catch (RuntimeException e) {
            return true; // Not known now: kept.
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    /** A user was removed: the copies made for it go. */
    void forgetUser(int user) {
        // Publication and removal share the store lock, so a late make cannot repopulate the deleted namespace.
        mStore.forgetUser(user);
        mRequests.removeUser(user);
        packagesChanged();
        synchronized (mLock) {
            mQueue.removeIf(request -> request.user == user);
            mMadeFor.removeUser(user);
        }
    }

    private long userSerial(int user) {
        final long identity = Binder.clearCallingIdentity();
        try {
            final UserInfo info = mContext.getSystemService(UserManager.class).getUserInfo(user);
            return info == null || info.partial ? -1 : info.serialNumber;
        } catch (RuntimeException e) {
            return -1;
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
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
        CopyGenerations.Token incarnation;
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
     * Whose files an APK holds: the package the decode is charged to, the package told when its copies are made, the
     * user the copy is made for, and whether it is an install session (no identity, no stand-in).
     */
    private static final class Owner {
        final String charged;
        final String packageName;
        final int user;
        final boolean session;
        final int sessionId;

        Owner(String charged, String packageName, int user, boolean session) {
            this(charged, packageName, user, session, -1);
        }

        Owner(String charged, String packageName, int user, boolean session, int sessionId) {
            this.sessionId = sessionId;
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
        long modified;
        long length;

        ZipFile open(String apk) throws IOException {
            final File file = new File(apk);
            if (zip != null && apk.equals(path) && modified == file.lastModified() && length == file.length()) {
                return zip;
            }
            close();
            zip = new ZipFile(apk);
            path = apk;
            modified = file.lastModified();
            length = file.length();
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
     * Has the file made for {@code user}, when it holds together. {@code resource}: the resource name it was loaded
     * as (only to find its stand-in among the copies of the package the APK is installed as), or null. {@code urgent}:
     * a system process waits for it (a gate), else the warm-up asks, with {@code stamp} ({@link #nextStamp}) for its
     * app. Returns at once: the work is queued.
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
        final long serial = userSerial(user);
        final String key = CleanCopyKey.forSerial(CleanCopyKey.of(apk, file, density, width, height, user), serial);
        if (key == null || mRefused.get(key) != null || mGaveUp.get(key) != null
                || (!urgent && mCapped.get(key) != null)) {
            return;
        }
        final Request request = new Request(apk, file, density, width, height, resource, user, key, urgent,
                urgent ? 0 : stamp);
        request.incarnation = mStore.beginUser(user, serial);
        if (request.incarnation != null) {
            enqueue(request);
        }
    }

    private void enqueue(Request request) {
        if (!current(request)) {
            return;
        }
        final boolean drain;
        synchronized (mLock) {
            final Request waiting = mQueue.retrying(request.key);
            if (waiting != null) {
                if (!request.urgent) {
                    return; // Tried again on its own.
                }
                // A system process asks again (at most once a minute): tried now, its tries so far still count.
                if (mQueue.promoteRetry(request.key) == null) {
                    return;
                }
                request.attempts = waiting.attempts;
                request.timeouts = waiting.timeouts;
            }
            // One drain per lane: a file dropped from a full lane leaves no task behind.
            drain = mQueue.add(request.key, request, request.urgent);
        }
        if (!drain) {
            return;
        }
        if (request.urgent) {
            mUrgentHandler.post(() -> runNext(true));
        } else {
            mHandler.post(() -> runNext(false));
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
        // Only a copy made for the same user stands in.
        return mStore.fallbackKey(CleanCopyKey.forSerial(
                CleanCopyKey.identity(owner.packageName, user, resource, file, width, height), userSerial(user)),
                density);
    }

    /** Direct same-user readback. A server-wide exception is granted separately by exact UID and PID. */
    boolean mayServe(int uid, int user) {
        return CopyAudience.mayServe(UserHandle.getUserId(uid), Build.IS_DEBUGGABLE && uid == 0, user, -1);
    }

    private static boolean server(int uid, int pid) {
        return uid == Process.SYSTEM_UID && pid == Process.myPid();
    }

    private boolean mayRequest(int uid, int pid, String apk, int user) {
        final Owner asset = owner(apk, user);
        if (asset == null || userSerial(user) < 0) {
            return false;
        }
        if (server(uid, pid) || (Build.IS_DEBUGGABLE && uid == 0)) {
            return true;
        }
        if (UserHandle.getUserId(uid) == user) {
            return !asset.session || runs(uid, asset.charged) || runs(uid, "com.android.packageinstaller")
                    || runs(uid, "com.android.permissioncontroller");
        }
        if (asset.session) {
            return false;
        }
        final long identity = Binder.clearCallingIdentity();
        try {
            final UserManager users = mContext.getSystemService(UserManager.class);
            final UserInfo info = users.getUserInfo(user);
            if (info == null || !info.isEnabled() || info.partial
                    || users.isQuietModeEnabled(UserHandle.of(user)) || !users.isUserUnlocked(user)) {
                return false;
            }
            final com.android.server.pm.UserManagerInternal internalUsers = com.android.server.LocalServices
                    .getService(com.android.server.pm.UserManagerInternal.class);
            final android.content.pm.UserProperties properties = internalUsers == null ? null
                    : internalUsers.getUserProperties(user);
            final boolean hidden = properties != null && properties.getProfileApiVisibility()
                    == android.content.pm.UserProperties.PROFILE_API_VISIBILITY_HIDDEN;
            if (hidden && mContext.checkPermission(android.Manifest.permission.ACCESS_HIDDEN_PROFILES_FULL, pid, uid)
                    != PackageManager.PERMISSION_GRANTED) {
                final List<String> home = mContext.getSystemService(RoleManager.class)
                        .getRoleHoldersAsUser(RoleManager.ROLE_HOME, UserHandle.getUserHandleForUid(uid));
                final String[] ownPackages = mContext.getPackageManager().getPackagesForUid(uid);
                if (ownPackages == null || java.util.Collections.disjoint(home, List.of(ownPackages))
                        || mContext.checkPermission(android.Manifest.permission.ACCESS_HIDDEN_PROFILES, pid, uid)
                        != PackageManager.PERMISSION_GRANTED) {
                    return false;
                }
            }
            final android.content.pm.PackageManagerInternal internalPackages = com.android.server.LocalServices
                    .getService(android.content.pm.PackageManagerInternal.class);
            if (internalPackages == null || internalPackages.filterAppAccess(asset.packageName, uid, user)) {
                return false;
            }
            final boolean systemUi = UserHandle.getUserId(uid) == 0 && runs(uid, SYSTEMUI);
            final int surface = systemUi ? android.app.ActivityManager.getCurrentUser() : UserHandle.getUserId(uid);
            if (surface != user && parentOf(user) != surface) {
                return false;
            }
            final String[] packages = mContext.getPackageManager().getPackagesForUid(uid);
            if (packages == null) {
                return false;
            }
            final List<String> home = mContext.getSystemService(RoleManager.class)
                    .getRoleHoldersAsUser(RoleManager.ROLE_HOME, UserHandle.getUserHandleForUid(uid));
            for (String pkg : packages) {
                if (systemUi || home.contains(pkg) || "com.android.settings".equals(pkg)
                        || "com.android.intentresolver".equals(pkg)) {
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

    private boolean runs(int uid, String packageName) {
        final String[] packages = mContext.getPackageManager().getPackagesForUid(uid);
        return packages != null && List.of(packages).contains(packageName);
    }

    boolean authorizeRequest(int uid, int pid, String apk, String file, int density, int width, int height, int user) {
        if (!plausible(apk, file, density, width, height, user) || !mayRequest(uid, pid, apk, user)) {
            return false;
        }
        grantFallback(uid, apk, user, CleanCopyClient.key(mContext, apk, file, density, width, height, user));
        return true;
    }

    void grantFallback(int uid, String apk, int user, String key) {
        final Owner asset = owner(apk, user);
        if (asset != null && key != null) {
            mRequests.add(uid, key, new CopyRequests.Grant(apk, asset.packageName, user, userSerial(user),
                    asset.session ? asset.charged : null, asset.sessionId));
        }
    }

    private boolean grantCurrent(int uid, int pid, CopyRequests.Grant grant) {
        if (grant == null || grant.serial < 0 || grant.serial != userSerial(grant.user)) return false;
        final Owner asset = owner(grant.apk, grant.user);
        if (asset == null || !asset.packageName.equals(grant.packageName)) return false;
        if (asset.session) {
            if (grant.installer == null || !grant.installer.equals(asset.charged)
                    || grant.sessionId < 0 || grant.sessionId != asset.sessionId) return false;
        } else if (grant.installer != null || grant.sessionId >= 0) {
            return false;
        }
        return mayRequest(uid, pid, grant.apk, grant.user);
    }

    private boolean allowedKey(int uid, int pid, String key) {
        final int user = CleanCopyKey.userOf(key);
        final long serial = userSerial(user);
        if (key == null || serial < 0 || serial != CleanCopyKey.serialOf(key)) return false;
        if (server(uid, pid) || (Build.IS_DEBUGGABLE && uid == 0)) return true;
        final CopyRequests.Grant grant = mRequests.get(uid, key);
        if (grant != null && grant.user == user && grant.serial == serial && grantCurrent(uid, pid, grant)) {
            return true;
        }
        return UserHandle.getUserId(uid) == user && mayRequest(uid, pid, CleanCopyKey.apkOf(key), user);
    }

    boolean mayRead(int uid, int pid, String name) {
        return mayRead(uid, pid, mStore.keyOfFile(name), mStore.packageOfFile(name));
    }

    /** {@link #mayRead(int, int, String)} for a key and the package its copy was made for. */
    boolean mayRead(int uid, int pid, String key, String packageName) {
        final int user = CleanCopyKey.userOf(key);
        final long serial = userSerial(user);
        if (key == null || packageName == null || serial < 0 || serial != CleanCopyKey.serialOf(key)) return false;
        if (server(uid, pid) || (Build.IS_DEBUGGABLE && uid == 0)) return true;
        final CopyRequests.Grant grant = mRequests.get(uid, key);
        if (grant != null && grant.user == user && grant.serial == serial
                && packageName.equals(grant.packageName) && grantCurrent(uid, pid, grant)) return true;
        if (UserHandle.getUserId(uid) != user) return false;
        final Owner asset = owner(CleanCopyKey.apkOf(key), user);
        return asset != null && packageName.equals(asset.packageName)
                && mayRequest(uid, pid, CleanCopyKey.apkOf(key), user);
    }

    // The user {@code user} is a profile of, -1 for none.
    private int parentOf(int user) {
        final long identity = Binder.clearCallingIdentity();
        try {
            final UserHandle parent = mContext.getSystemService(UserManager.class)
                    .getProfileParent(UserHandle.of(user));
            return parent != null ? parent.getIdentifier() : -1;
        } catch (RuntimeException e) {
            return -1;
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    private static final String SYSTEMUI = "com.android.systemui";

    /** Moves on whenever packages change: an admission kept for a uid is checked again then. */
    int packagesGeneration() {
        return mPackagesGeneration.get();
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
     * Decodes a server-held shortcut input for the authenticated recipient. Cross-profile output uses a separate
     * item and recipient domain. At most 1.5 s including queue time, with two active jobs and six waiting.
     */
    Bitmap shortcutIcon(ShortcutAccess input, int forUid) {
        final String packageName = input.packageName;
        final int user = input.user;
        final long shortcutSerial = userSerial(user);
        final long sourceSerial = userSerial(input.sourceUser);
        final int recipient = UserHandle.getUserId(forUid);
        final long recipientSerial = userSerial(recipient);
        if (!installedFor(packageName, user) || shortcutSerial < 0 || sourceSerial < 0 || recipientSerial < 0) {
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
                // The input is the system server's own (ShortcutAccess), bound to the shortcut: its item domain.
                final String identity = packageName + "/profile" + user + "/" + input.id + "/to" + forUid
                        + "/recipient" + recipientSerial + "/source" + sourceSerial + "/shortcut" + shortcutSerial;
                final String lane = input.lane();
                try (ParcelFileDescriptor fd = lane == null ? input.open(mContext, held -> { })
                        : mShortcutLanes.open(lane, held -> input.open(mContext, held), left);
                        VmDecode.ForApp app = VmDecode.forApp(VmDecode.SHORTCUT_ITEM)) {
                    if (fd == null || userSerial(user) != shortcutSerial
                            || userSerial(input.sourceUser) != sourceSerial || userSerial(recipient) != recipientSerial
                            || !installedFor(packageName, user)) {
                        return null;
                    }
                    final Bitmap bitmap = VmDecode.decode(mContext, fd, identity, input.sourceUser,
                            VmDecode.PRIORITY_USER_WAITING, SHORTCUT_MAX_SIDE, SHORTCUT_MAX_SIDE,
                            VmDecode.deadlineIn(left)).bitmap;
                    return userSerial(user) == shortcutSerial && userSerial(input.sourceUser) == sourceSerial
                            && userSerial(recipient) == recipientSerial ? bitmap : null;
                }
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

    /**
     * Has {@code callback} of the process {@code uid} called with the package and user whenever copies of a package
     * were made for a user it may see ({@link #mayServe}).
     */
    boolean watch(android.os.IBinder callback, int uid, int pid) {
        if (callback == null) {
            return false;
        }
        final IRemoteCallback listener = IRemoteCallback.Stub.asInterface(callback);
        synchronized (mWatchers) {
            mWatchers.unregister(listener);
            return mWatchers.register(listener, new CopyAudience.Subscriber(uid, pid));
        }
    }

    /**
     * How many of {@code keys} have no copy and were not refused: files that may still be made (never a file of the
     * system image, which is decoded as before, nor a warm-up file that did not fit its APK's cap).
     */
    int open(List<String> keys, int uid) {
        return open(keys, uid, Process.myPid());
    }

    int open(List<String> keys, int uid, int pid) {
        int open = 0;
        for (int i = 0; i < keys.size(); i++) {
            final String key = keys.get(i);
            if (key != null && allowedKey(uid, pid, key)
                    && !CleanCopyKey.isSystemImage(CleanCopyKey.apkOf(key)) && !mStore.has(key)
                    && mRefused.get(key) == null && mGaveUp.get(key) == null && mCapped.get(key) == null) {
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
            mGaveUp.evictAll();
            mCapped.evictAll();
            synchronized (mLock) {
                mQueue.clearRetries();
            }
        }
    }

    /** An app was removed for a user: the copies made for that user go, all of them once it is gone for everyone. */
    void forgetPackage(String packageName, int user) {
        packagesChanged();
        mHandler.post(() -> {
            if (!installedForAnyone(packageName)) {
                mStore.forgetOwner(packageName);
            } else {
                mStore.forgetOwner(packageName, user);
            }
        });
    }

    /** Packages changed: which APK belongs to whom is looked up again, and so is which caller is admitted. */
    void packagesChanged() {
        synchronized (mOwnersLock) {
            mApkOwners = null;
            mPackageUsers = null;
        }
        mPackagesGeneration.incrementAndGet();
        mRequests.clear();
    }

    // True when the package is installed for the user.
    private boolean installedFor(String packageName, int user) {
        synchronized (mOwnersLock) {
            ownersLocked();
            final TreeSet<Integer> users = mPackageUsers.get(packageName);
            return users != null && users.contains(user);
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

    // The lane's one drain: a file, then itself again, until the lane is empty.
    private void runNext(boolean urgent) {
        final Request request;
        synchronized (mLock) {
            request = mQueue.next(urgent);
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
        } finally {
            synchronized (mLock) {
                mQueue.finished(request.key);
            }
        }
        (urgent ? mUrgentHandler : mHandler).post(() -> runNext(urgent));
    }

    private boolean current(Request request) {
        return mStore.current(request.incarnation) && userSerial(request.user) == request.incarnation.serial
                && request.key.equals(CleanCopyClient.key(mContext, request.apk, request.file, request.density,
                        request.width, request.height, request.user));
    }

    private void make(Request request) {
        if (!current(request)) {
            return;
        }
        final Owner owner = owner(request.apk, request.user);
        if (owner == null) {
            // Not an installed APK or install session (yet: an install that has not finished): tried again later.
            logLimited("not an installed APK: " + request.apk);
            failed(request);
            return;
        }
        // Existing copies use the same bounded and coalesced work lane as new copies.
        if (mStore.has(request.key) && mStore.verify(request.key)) {
            told(owner.packageName, request.incarnation);
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
        try (VmDecode.ForApp scope = VmDecode.forApp(VmDecode.APK_ASSETS)) {
            // Both installed and staged copies run apart from private input. A staged path and its installer
            // select a separate audience from installed assets and other install sessions.
            entry = AppResourceImages.makeCopy(mContext, bytes, request.density, request.width, request.height,
                    CopyIsolation.identity(owner.charged, request.apk, owner.session, request.incarnation.serial),
                    owner.user, timeout,
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
                mGaveUp.put(request.key, Boolean.TRUE);
                return;
            }
            failed(request);
            return;
        }
        charge(owner.charged, SystemClock.uptimeMillis() - start);
        // The identity is made here, of the package the APK is installed as: never of what an asker said.
        final String resourceIdentity = owner.session ? null : CleanCopyKey.identity(owner.packageName, owner.user,
                request.resource, request.file, request.width, request.height);
        final String identity = CleanCopyKey.forSerial(resourceIdentity != null ? resourceIdentity
                : CleanCopyKey.ownership(owner.packageName, owner.user), request.incarnation.serial);
        final long used = request.urgent ? System.currentTimeMillis() : request.stamp;
        final int saved = mStore.saveCopy(request.key, identity, entry.bitmap, entry.chunk, used, request.urgent,
                request.incarnation, () -> current(request) && owner(request.apk, request.user) != null);
        if (saved == CleanImageStore.TOO_LARGE) {
            // Making it again gives the same: never tried again (the store box should prevent this).
            logLimited("copy larger than the store takes, not made again: " + owner.packageName + " " + request.file);
            mRefused.put(request.key, Boolean.TRUE);
            return;
        }
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
        told(owner.packageName, request.incarnation);
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
        if (!current(request)) {
            return;
        }
        synchronized (mLock) {
            request.attempts++;
            if (request.attempts > RETRY_ATTEMPTS) {
                mQueue.dropRetry(request.key);
                mGaveUp.put(request.key, Boolean.TRUE);
                return;
            }
            request.due = SystemClock.elapsedRealtime()
                    + Math.min(RETRY_MS << Math.min(request.attempts - 1, 10), RETRY_MAX_MS);
            // At most MAX_PENDING wait: the oldest is given up (until the decode service answers after an outage).
            final Request dropped = mQueue.retry(request.key, request, request.due);
            if (dropped != null) {
                mGaveUp.put(dropped.key, Boolean.TRUE);
            }
            if (mQueue.needsRetryTimer()) {
                mHandler.postDelayed(this::retry, RETRY_MS);
            }
        }
    }

    // Queues the files whose time to try again has come.
    private void retry() {
        final List<Request> due;
        synchronized (mLock) {
            due = mQueue.due(SystemClock.elapsedRealtime());
            if (mQueue.needsRetryTimer()) {
                mHandler.postDelayed(this::retry, RETRY_MS);
            }
        }
        for (Request request : due) {
            enqueue(request);
        }
    }

    // Has the launcher and the watching processes told about the app's copies, once its files are made (debounced).
    private void told(String owner, CopyGenerations.Token incarnation) {
        synchronized (mLock) {
            if (!mStore.current(incarnation) || !mMadeFor.mark(owner, incarnation)) {
                return;
            }
        }
        mHandler.postDelayed(() -> tell(owner, incarnation), TELL_DELAY_MS);
    }

    // Tells the launcher and the watching processes that copies of the app's images were made, once none of its files
    // waits any more.
    private void tell(String owner, CopyGenerations.Token incarnation) {
        final int user = incarnation.user;
        synchronized (mLock) {
            if (!mStore.current(incarnation) || userSerial(user) != incarnation.serial) {
                mMadeFor.take(owner, incarnation);
                return;
            }
            if (mQueue.anyQueued(waiting -> waiting.incarnation == incarnation
                    && owner.equals(installedPackageOf(waiting.apk)))) {
                mHandler.postDelayed(() -> tell(owner, incarnation), TELL_DELAY_MS);
                return;
            }
            if (!mMadeFor.take(owner, incarnation)) {
                return;
            }
        }
        final long identity = Binder.clearCallingIdentity();
        try {
            // The launcher of the user and the launcher of its parent (which shows a work profile's apps): no
            // other user's launcher hears of this user's apps.
            final int[] told = CopyAudience.toldUsers(user, parentOf(user));
            for (String target : TOLD) {
                for (int to : told) {
                    final int launcherUid;
                    try {
                        launcherUid = mContext.getPackageManager().getPackageUidAsUser(target, to);
                    } catch (PackageManager.NameNotFoundException e) {
                        continue;
                    }
                    if (!subscribed(launcherUid, owner, user, incarnation.serial)) {
                        continue;
                    }
                    final Intent intent = new Intent(ACTION_MADE)
                            .setPackage(target)
                            .putExtra(EXTRA_PACKAGE, owner)
                            .putExtra(Intent.EXTRA_USER, UserHandle.of(user))
                            .addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);
                    mContext.sendBroadcastAsUser(intent, UserHandle.of(to));
                }
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "not told: " + e);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
        final Bundle made = new Bundle();
        made.putString(CleanCopyClient.EXTRA_PACKAGE, owner);
        made.putInt(CleanCopyClient.EXTRA_USER, user);
        broadcastToWatchers(made, user, incarnation.serial);
    }

    private boolean subscribed(int uid, String pkg, int user, long serial) {
        if (pkg == null || serial < 0 || serial != userSerial(user)) return false;
        for (CopyRequests.Grant grant : mRequests.grants(uid, pkg, user, serial)) {
            if (grantCurrent(uid, -1, grant)) return true;
        }
        return false;
    }

    // Sends {@code data} to every watching process that may see {@code user} (USER_ALL: to every one, for what names
    // no package or user). On the maker's thread (one broadcast at a time).
    private void broadcastToWatchers(Bundle data, int user) {
        broadcastToWatchers(data, user, -1);
    }

    private void broadcastToWatchers(Bundle data, int user, long serial) {
        final int count = mWatchers.beginBroadcast();
        try {
            for (int i = 0; i < count; i++) {
                final Object cookie = mWatchers.getBroadcastCookie(i);
                if (!(cookie instanceof CopyAudience.Subscriber subscriber)) {
                    continue;
                }
                final boolean itemAllowed = user == UserHandle.USER_ALL
                        || subscribed(subscriber.uid, data.getString(CleanCopyClient.EXTRA_PACKAGE), user, serial);
                if (!subscriber.mayNotify(Process.myPid(), CleanImageProvider.admitted(mContext, subscriber.uid),
                        itemAllowed)) {
                    continue;
                }
                try {
                    mWatchers.getBroadcastItem(i).sendResult(data);
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
            return mApkOwners.anyPackageOf(apk);
        }
    }

    /**
     * Whose installed base or split path this is for the requested user, else that user's active install session.
     * A path installed for another user never establishes an installation for this user. Unknown paths are
     * refreshed at most every 10 s to cover an install whose broadcast has not arrived yet.
     */
    private Owner owner(String apk, int user) {
        final String packageName;
        synchronized (mOwnersLock) {
            ownersLocked();
            if (!mApkOwners.contains(apk)
                    && SystemClock.elapsedRealtime() - mOwnersBuilt > OWNERS_REBUILD_MS) {
                mApkOwners = null;
                ownersLocked();
            }
            packageName = mApkOwners.anyPackageOf(apk);
        }
        // The cache discovers the package only. Its actual current user installation selects the path, including
        // an existing APK newly installed for another user and an uninstall whose broadcast has not run yet.
        if (packageName != null && installedPath(packageName, apk, user)) {
            return new Owner(packageName, packageName, user, false);
        }
        return sessionOwner(apk, user);
    }

    private boolean installedPath(String packageName, String apk, int user) {
        final long identity = Binder.clearCallingIdentity();
        try {
            final ApplicationInfo info = mContext.getPackageManager().getApplicationInfoAsUser(packageName,
                    PackageManager.MATCH_STATIC_SHARED_AND_SDK_LIBRARIES | PackageManager.MATCH_DISABLED_COMPONENTS
                            | PackageManager.MATCH_DIRECT_BOOT_AWARE | PackageManager.MATCH_DIRECT_BOOT_UNAWARE, user);
            return CopyPackages.selectedPath(apk, info.sourceDir, info.splitSourceDirs,
                    (info.flags & ApplicationInfo.FLAG_INSTALLED) != 0);
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            return false;
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    private void ownersLocked() {
        if (mApkOwners != null) {
            return;
        }
        final CopyPackages owners = new CopyPackages();
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
                            owners.add(apk, ai.packageName, user.id);
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
    private Owner sessionOwner(String apk, int user) {
        if (!apk.startsWith("/data/app/vmdl") || !apk.contains(".tmp/") || !apk.endsWith(".apk")) {
            return null;
        }
        final String folder = new File(apk).getParent();
        final long identity = Binder.clearCallingIdentity();
        try {
            for (UserInfo info : mContext.getSystemService(UserManager.class).getAliveUsers()) {
                if (info.id != user) {
                    continue;
                }
                final PackageInstaller installer = mContext.createContextAsUser(UserHandle.of(info.id), 0)
                        .getPackageManager().getPackageInstaller();
                for (PackageInstaller.SessionInfo session : installer.getAllSessions()) {
                    final String base = session.getResolvedBaseApkPath();
                    if (!session.isActive() || session.getUser().getIdentifier() != user
                            || base == null || folder == null
                            || !folder.equals(new File(base).getParent())) {
                        continue;
                    }
                    final String charged = session.getInstallerPackageName();
                    if (charged == null) {
                        return null;
                    }
                    // Told by the name the installer's dialog loads the icon for (its filling icon), no identity.
                    final String told = session.getAppPackageName() != null ? session.getAppPackageName() : charged;
                    return new Owner(charged, told, user, true, session.getSessionId());
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
        // Autofill can read copies and request the maker on a miss.
        de.diamaneos.decode.client.LateImages.setStore(new CleanCopies() {
            @Override
            public Entry load(String key) {
                return get(systemContext).mStore.load(key);
            }

            @Override
            public void missed(String key, String apkPath, String file, int density, int width, int height,
                    String resource, int userId) {
                get(systemContext).request(apkPath, file, density, width, height, resource, userId, true, 0);
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
