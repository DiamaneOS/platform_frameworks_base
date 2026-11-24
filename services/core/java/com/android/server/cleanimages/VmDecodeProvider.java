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

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.DecodeGate;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.IRemoteCallback;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.util.Log;
import android.util.SparseArray;
import android.util.SparseLongArray;

import com.android.internal.diamaneos.cleancopies.CleanCopyGate;

import de.diamaneos.decode.client.VmDecode;
import de.diamaneos.sanitizer.decode.IDecodeService;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * DiamaneOS: the decode gate's provider in the system server (authority {@link DecodeGate#AUTHORITY}): images that a
 * gated process (DecodeGate in the framework, and the icon loads of VmIconLoads) would have decoded are probed
 * ({@link DecodeGate#METHOD_PROBE}: stored size, format, animation, and with {@link DecodeGate#METHOD_HEADER} for
 * JPEG, HEIF and AVIF also the turn and the mirror the file asks for, read from a decode of at most 16 px), decoded
 * ({@link DecodeGate#METHOD_DECODE}), decoded frame by frame ({@link DecodeGate#METHOD_FRAMES}) or decoded as a
 * region at full detail ({@link DecodeGate#METHOD_REGION}) in the VM here.
 * The caller hands over the bytes as a descriptor and a callback, the call returns at once, and the callback gets the
 * answer within {@link DecodeGate#SERVER_MS}.
 *
 * <p>Requests wait in lanes of two levels and {@link #THREADS} run at a time, the lanes taken in turn: first the
 * calling app (known from the calling uid, never from the request), then the origin the app names within its own lane
 * (the authority of the URI it opened, else its bytes). One app runs at most {@link #APP_RUNNING} requests and one
 * origin at most {@link #ORIGIN_RUNNING}, so an app always leaves a thread to the others and an origin a thread to the
 * rest of its app. Places (running and waiting): {@link #ORIGIN_MAX} for an origin, {@link #APP_MAX} for an app,
 * {@link #TOTAL_MAX} in all. An origin is only the caller's word, so naming another one only reorders the caller's own
 * lane. The decode service's lane is the app's and the origin's together, so no caller is ever charged to another
 * app's lane. No binder thread waits for the VM.
 *
 * <p>Callers, checked by uid on every call: the system and phone uids, and processes of the system apps the gate takes
 * ({@link CleanCopyGate#gatedApp}). Ordinary apps are refused, and a refused uid is refused without a lookup for a
 * while. On debuggable builds also root, the decode client's tests and the package
 * {@code debug.diamaneos.decode_gate.force} names. The system server's own decodes come here directly. The provider's
 * threads are the gate's own: nothing on them goes through the gate again.
 */
public final class VmDecodeProvider extends ContentProvider {
    private static final String TAG = "VmDecodeGate";
    private static final String CLIENT_TESTS = "de.diamaneos.decode.client.tests";
    private static final String DECODE_SERVICE = "de.diamaneos.sanitizer";
    static final int THREADS = 4;
    /** Requests of one calling app that run at once, and of one origin within it. */
    static final int APP_RUNNING = 3;
    static final int ORIGIN_RUNNING = 2;
    /** Places (running and waiting) of one origin, of one calling app, and in all. */
    static final int ORIGIN_MAX = 8;
    static final int APP_MAX = 16;
    static final int TOTAL_MAX = 64;
    // A uid refused as a caller is refused without a lookup for this long.
    private static final long REFUSED_FOR_MS = 10_000;
    private static final int MAX_ORIGIN_CHARS = 256;
    // A probe of these formats also reads the turn from a decode of at most this many pixels a side.
    private static final int TURN_PROBE_SIDE = 16;

    /** One request: where it waits, and what it runs. */
    private static final class Task {
        final String app;
        final String origin;
        final Runnable work;

        Task(String app, String origin, Runnable work) {
            this.app = app;
            this.origin = origin;
            this.work = work;
        }
    }

    /** The requests of one origin, or (with its origins, in turn order) of one app. */
    private static final class Lane {
        final ArrayDeque<Task> queue = new ArrayDeque<>();
        final LinkedHashMap<String, Lane> origins = new LinkedHashMap<>();
        int running;
        int held;
    }

    private final Object mLock = new Object();
    private final LinkedHashMap<String, Lane> mApps = new LinkedHashMap<>(); // in turn order, guarded by mLock
    private int mRunning; // guarded by mLock
    private int mHeld; // guarded by mLock
    // Allowed uids with their packages, and refused uids until when, each guarded by itself.
    private final SparseArray<String[]> mAllowed = new SparseArray<>();
    private final SparseLongArray mRefused = new SparseLongArray();
    private final ThreadPoolExecutor mWorkers = new ThreadPoolExecutor(THREADS, THREADS, 30, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(), r -> {
                final Thread t = new Thread(() -> {
                    DecodeGate.markGateThread();
                    r.run();
                }, "VmDecodeGate");
                t.setDaemon(true);
                return t;
            });

    @Override
    public boolean onCreate() {
        mWorkers.allowCoreThreadTimeOut(true);
        if (Process.myUid() == Process.SYSTEM_UID && android.app.ActivityThread.isSystem()) {
            // The system server's own decodes: no binder, no activity manager. Their images are of the user of the
            // app whose binder call the thread serves, else the system user's (VmDecode OWNER_USER). They are the
            // system server's own, decoded for every user in one process as on Android: no app uid (forApp).
            DecodeGate.setLocal((method, extras) -> take(method, extras, Process.SYSTEM_UID,
                    UserHandle.getUserId(Binder.getCallingUid()), -1));
            registerGlobalLocks();
        }
        return true;
    }

    // The system server's global locks no thread may wait for the VM under: the activity manager's, the window
    // manager's, the package manager's, and Telecom's (it runs in the system server and stores calling apps' icons
    // under its lock). One that cannot be found is logged as an error.
    private static void registerGlobalLocks() {
        final android.os.IBinder am = android.os.ServiceManager.getService(android.content.Context.ACTIVITY_SERVICE);
        if (am instanceof com.android.server.am.ActivityManagerService) {
            DecodeGate.registerGlobalLock(am);
        } else {
            Log.e(TAG, "the activity manager's lock is not known: its holders may wait for the VM");
        }
        final android.os.IBinder atm = android.os.ServiceManager.getService(
                android.content.Context.ACTIVITY_TASK_SERVICE);
        if (atm instanceof com.android.server.wm.ActivityTaskManagerService) {
            DecodeGate.registerGlobalLock(((com.android.server.wm.ActivityTaskManagerService) atm).getGlobalLock());
        } else {
            Log.e(TAG, "the window manager's lock is not known: its holders may wait for the VM");
        }
        DecodeGate.addGlobalLockSource("the package manager's lock", VmDecodeProvider::packageManagerLock);
        DecodeGate.addGlobalLockSource("Telecom's lock", VmDecodeProvider::telecomLock);
    }

    // The package manager's lock (PackageManagerService.mLock), found from its local service, else null.
    private static Object packageManagerLock() {
        final Object internal = com.android.server.LocalServices.getService(
                android.content.pm.PackageManagerInternal.class);
        try {
            for (Class<?> c = internal == null ? null : internal.getClass(); c != null; c = c.getSuperclass()) {
                for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                    if (f.getType() == com.android.server.pm.PackageManagerService.class) {
                        f.setAccessible(true);
                        final Object pms = f.get(internal);
                        if (pms == null) {
                            return null;
                        }
                        final java.lang.reflect.Field lock =
                                com.android.server.pm.PackageManagerService.class.getDeclaredField("mLock");
                        lock.setAccessible(true);
                        return lock.get(pms);
                    }
                }
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            // Not found: the binder and main thread rules still hold.
        }
        return null;
    }

    // Telecom's lock (TelecomSystem.getInstance().getLock()), once Telecom runs in this process, else null.
    private static Object telecomLock() {
        final android.app.ActivityThread thread = android.app.ActivityThread.currentActivityThread();
        final android.app.LoadedApk telecom = thread == null ? null
                : thread.peekPackageInfo("com.android.server.telecom", true);
        if (telecom == null) {
            return null;
        }
        try {
            final Class<?> system = Class.forName("com.android.server.telecom.TelecomSystem", false,
                    telecom.getClassLoader());
            final Object instance = system.getMethod("getInstance").invoke(null);
            return instance == null ? null : system.getMethod("getLock").invoke(instance);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return null;
        }
    }


    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        final int uid = Binder.getCallingUid();
        if (DecodeGate.METHOD_BROKER.equals(method)) {
            return broker(uid);
        }
        callerPackages(uid);
        // The images an app hands over are of its own user (VmDecode OWNER_USER), decoded for that app (forApp).
        return take(method, extras, uid, UserHandle.getUserId(uid), uid);
    }

    // The decode service learns the system server's process from here (its pid): the provider runs only in the
    // system server, so the service can tell the system server from other processes of the system uid. Only the
    // decode service may ask.
    private Bundle broker(int uid) {
        final String[] packages;
        final long identity = Binder.clearCallingIdentity();
        try {
            packages = getContext().getPackageManager().getPackagesForUid(uid);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
        if (packages == null || !java.util.Arrays.asList(packages).contains(DECODE_SERVICE)) {
            throw new SecurityException("for the decode service only");
        }
        final Bundle reply = new Bundle();
        reply.putInt(DecodeGate.EXTRA_PID, Process.myPid());
        return reply;
    }

    // The packages of an allowed calling uid (looked up once), else a SecurityException.
    private String[] callerPackages(int uid) {
        synchronized (mAllowed) {
            final String[] known = mAllowed.get(uid);
            if (known != null) {
                return known;
            }
        }
        synchronized (mRefused) {
            if (SystemClock.uptimeMillis() < mRefused.get(uid, 0)) {
                throw new SecurityException("the decode gate is for system processes only");
            }
        }
        final int appId = UserHandle.getAppId(uid);
        boolean allowed = appId == Process.SYSTEM_UID || appId == Process.PHONE_UID
                || (Build.IS_DEBUGGABLE && appId == Process.ROOT_UID);
        String[] packages;
        final long identity = Binder.clearCallingIdentity();
        try {
            final PackageManager pm = getContext().getPackageManager();
            packages = pm.getPackagesForUid(uid);
            if (packages != null && !allowed) {
                for (String name : packages) {
                    try {
                        final ApplicationInfo ai = pm.getApplicationInfoAsUser(name, 0, UserHandle.getUserId(uid));
                        allowed |= CleanCopyGate.gatedApp(ai, name)
                                || (Build.IS_DEBUGGABLE && (CLIENT_TESTS.equals(name) || name.equals(
                                        android.os.SystemProperties.get("debug.diamaneos.decode_gate.force"))));
                    } catch (PackageManager.NameNotFoundException e) {
                        // Not this one.
                    }
                }
            }
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
        if (!allowed) {
            synchronized (mRefused) {
                mRefused.put(uid, SystemClock.uptimeMillis() + REFUSED_FOR_MS);
            }
            throw new SecurityException("the decode gate is for system processes only");
        }
        if (packages == null || packages.length == 0) {
            packages = new String[] {"uid" + uid};
        }
        synchronized (mAllowed) {
            mAllowed.put(uid, packages);
        }
        return packages;
    }

    // Takes a probe or a decode for the app {@code callingUid} runs, of {@code ownerUser}, for the app {@code forUid}
    // (-1: the system server's own): the reply says whether it was taken.
    private Bundle take(String method, Bundle extras, int callingUid, int ownerUser, int forUid) {
        if (extras == null) {
            return refusal("no request");
        }
        final boolean probe = DecodeGate.METHOD_PROBE.equals(method) || DecodeGate.METHOD_HEADER.equals(method);
        final boolean frames = DecodeGate.METHOD_FRAMES.equals(method);
        final boolean region = DecodeGate.METHOD_REGION.equals(method);
        final ParcelFileDescriptor given = extras.getParcelable(DecodeGate.EXTRA_FD, ParcelFileDescriptor.class);
        if (!probe && !frames && !region && !DecodeGate.METHOD_DECODE.equals(method)) {
            closeQuietly(given);
            return refusal("unknown method");
        }
        final IBinder binder = extras.getBinder(DecodeGate.EXTRA_CALLBACK);
        final int width = extras.getInt(DecodeGate.EXTRA_WIDTH);
        final int height = extras.getInt(DecodeGate.EXTRA_HEIGHT);
        if (given == null || binder == null || (!probe && (width < 1 || height < 1
                || width > DecodeGate.MAX_BOX || height > DecodeGate.MAX_BOX))) {
            closeQuietly(given);
            return refusal("bad request");
        }
        // A descriptor of its own: the caller closes the one it gave (in the system server, the same object).
        final ParcelFileDescriptor fd;
        try {
            fd = given.dup();
        } catch (IOException e) {
            return refusal("descriptor not taken");
        } finally {
            if (Binder.getCallingPid() != Process.myPid()) {
                closeQuietly(given);
            }
        }
        final IRemoteCallback callback = IRemoteCallback.Stub.asInterface(binder);
        final long time = Math.max(1, Math.min(DecodeGate.SERVER_MS, extras.getLong(DecodeGate.EXTRA_TIME,
                DecodeGate.SERVER_MS)));
        final long end = SystemClock.uptimeMillis() + time;
        final String app = app(callingUid, extras.getString(DecodeGate.EXTRA_CALLER));
        final String named = extras.getString(DecodeGate.EXTRA_AUTHORITY);
        final String origin = named == null ? "bytes" : named.length() > MAX_ORIGIN_CHARS ? "long" : named;
        final String lane = VmDecode.unknownOwner("gate:" + app + "/" + origin);
        final int left = extras.getInt(DecodeGate.EXTRA_LEFT);
        final int top = extras.getInt(DecodeGate.EXTRA_TOP);
        final int sample = extras.getInt(DecodeGate.EXTRA_SAMPLE, 1);
        final String full = enqueue(new Task(app, origin,
                () -> run(method, fd, lane, ownerUser, forUid, width, height, left, top, sample, end, callback)));
        if (full != null) {
            closeQuietly(fd);
            return refusal(full);
        }
        final Bundle reply = new Bundle();
        reply.putBoolean(DecodeGate.EXTRA_TAKEN, true);
        return reply;
    }

    // The calling app's lane: the package the caller names when its uid runs it, else the uid's first.
    private String app(int callingUid, String caller) {
        String[] packages;
        try {
            packages = callerPackages(callingUid);
        } catch (SecurityException e) {
            packages = new String[] {"uid" + callingUid};
        }
        if (caller != null) {
            for (String p : packages) {
                if (p.equals(caller)) {
                    return caller;
                }
            }
        }
        return packages[0];
    }

    // Puts {@code task} in its lanes and starts what may run: null, or why it was not taken.
    private String enqueue(Task task) {
        synchronized (mLock) {
            Lane app = mApps.get(task.app);
            Lane origin = app == null ? null : app.origins.get(task.origin);
            if (mHeld >= TOTAL_MAX) {
                return "too many images waiting";
            }
            if ((app != null && app.held >= APP_MAX) || (origin != null && origin.held >= ORIGIN_MAX)) {
                return "too many images of " + task.app + " waiting";
            }
            if (app == null) {
                app = new Lane();
                mApps.put(task.app, app);
            }
            if (origin == null) {
                origin = new Lane();
                app.origins.put(task.origin, origin);
            }
            origin.queue.add(task);
            origin.held++;
            app.held++;
            mHeld++;
        }
        pump();
        return null;
    }

    // Starts waiting requests while threads are free.
    private void pump() {
        for (;;) {
            final Task next;
            synchronized (mLock) {
                if (mRunning >= THREADS) {
                    return;
                }
                next = nextLocked();
                if (next == null) {
                    return;
                }
                mRunning++;
            }
            try {
                mWorkers.execute(() -> {
                    try {
                        next.work.run();
                    } finally {
                        done(next);
                    }
                });
            } catch (RejectedExecutionException e) {
                done(next);
                return;
            }
        }
    }

    // The next request to run: from the first app in turn below its running cap, its first origin in turn below its
    // own. Both go to the end of their turn.
    private Task nextLocked() {
        for (Map.Entry<String, Lane> a : mApps.entrySet()) {
            final Lane app = a.getValue();
            if (app.running >= APP_RUNNING) {
                continue;
            }
            for (Map.Entry<String, Lane> o : app.origins.entrySet()) {
                final Lane origin = o.getValue();
                if (origin.running >= ORIGIN_RUNNING || origin.queue.isEmpty()) {
                    continue;
                }
                final Task task = origin.queue.poll();
                origin.running++;
                app.running++;
                final String originName = o.getKey();
                final String appName = a.getKey();
                app.origins.remove(originName);
                app.origins.put(originName, origin);
                mApps.remove(appName);
                mApps.put(appName, app);
                return task;
            }
        }
        return null;
    }

    // A request ended, or could not start: its places are free.
    private void done(Task task) {
        synchronized (mLock) {
            mRunning--;
            mHeld--;
            final Lane app = mApps.get(task.app);
            final Lane origin = app == null ? null : app.origins.get(task.origin);
            if (origin != null) {
                origin.running--;
                if (--origin.held <= 0) {
                    app.origins.remove(task.origin);
                }
            }
            if (app != null) {
                app.running--;
                if (--app.held <= 0) {
                    mApps.remove(task.app);
                }
            }
        }
        pump();
    }

    private void run(String method, ParcelFileDescriptor fd, String lane, int ownerUser, int forUid, int width,
            int height, int regionLeft, int regionTop, int sample, long end, IRemoteCallback callback) {
        final Bundle reply = new Bundle();
        // The decodes below are for the calling app (forUid), or the system server's own (-1).
        try (VmDecode.ForApp app = VmDecode.forApp(forUid)) {
            final long left = end - SystemClock.uptimeMillis();
            if (left <= 0) {
                reply.putString(DecodeGate.EXTRA_ERROR, "out of time in the queue");
            } else {
                rewind(fd);
                if (DecodeGate.METHOD_PROBE.equals(method) || DecodeGate.METHOD_HEADER.equals(method)) {
                    final VmDecode.Info info = VmDecode.probeInfo(getContext(), fd, lane, ownerUser,
                            VmDecode.PRIORITY_USER_WAITING, VmDecode.deadlineIn(left));
                    reply.putInt(DecodeGate.EXTRA_WIDTH, info.width);
                    reply.putInt(DecodeGate.EXTRA_HEIGHT, info.height);
                    reply.putInt(DecodeGate.EXTRA_FORMAT, info.format);
                    reply.putBoolean(DecodeGate.EXTRA_ANIMATED, info.animated);
                    if (DecodeGate.METHOD_HEADER.equals(method) && (info.format == IDecodeService.FORMAT_JPEG
                            || info.format == IDecodeService.FORMAT_HEIF
                            || info.format == IDecodeService.FORMAT_AVIF)) {
                        // The turn and the mirror the file asks for (its orientation), from a tiny decode in the VM.
                        final VmDecode.Decoded tiny = VmDecode.decode(getContext(), fd, lane, ownerUser,
                                VmDecode.PRIORITY_USER_WAITING, TURN_PROBE_SIDE, TURN_PROBE_SIDE,
                                VmDecode.deadlineIn(Math.max(1, end - SystemClock.uptimeMillis())));
                        reply.putInt(DecodeGate.EXTRA_ROTATION, tiny.rotation);
                        reply.putBoolean(DecodeGate.EXTRA_MIRROR, tiny.mirrored);
                    }
                } else if (DecodeGate.METHOD_REGION.equals(method)) {
                    reply.putParcelable(DecodeGate.EXTRA_BITMAP, VmDecode.decodeRegion(getContext(), fd, lane,
                            ownerUser, VmDecode.PRIORITY_USER_WAITING, regionLeft, regionTop, width, height, sample,
                            VmDecode.deadlineIn(left)));
                    reply.putInt(DecodeGate.EXTRA_ROTATION, 0);
                } else if (DecodeGate.METHOD_FRAMES.equals(method)) {
                    final VmDecode.Animation animation = VmDecode.decodeFrames(getContext(), fd, lane, ownerUser,
                            VmDecode.PRIORITY_USER_WAITING, width, height, VmDecode.deadlineIn(left));
                    reply.putParcelable(DecodeGate.EXTRA_BITMAP, animation.frames);
                    reply.putInt(DecodeGate.EXTRA_FRAME_COUNT, animation.frameCount);
                    reply.putIntArray(DecodeGate.EXTRA_DURATIONS, animation.durations);
                    reply.putInt(DecodeGate.EXTRA_LOOPS, animation.loopCount);
                } else {
                    final VmDecode.Decoded decoded = VmDecode.decode(getContext(), fd, lane, ownerUser,
                            VmDecode.PRIORITY_USER_WAITING, width, height, VmDecode.deadlineIn(left));
                    reply.putParcelable(DecodeGate.EXTRA_BITMAP, decoded.bitmap);
                    reply.putInt(DecodeGate.EXTRA_ROTATION, decoded.rotation);
                    reply.putBoolean(DecodeGate.EXTRA_MIRROR, decoded.mirrored);
                }
            }
        } catch (VmDecode.RejectedException e) {
            reply.putBoolean(DecodeGate.EXTRA_REFUSED, true);
            reply.putString(DecodeGate.EXTRA_ERROR, "refused: " + e.getMessage());
        } catch (VmDecode.UnavailableException e) {
            reply.putString(DecodeGate.EXTRA_ERROR, "unavailable: " + e.getMessage());
        } catch (RuntimeException | OutOfMemoryError e) {
            Log.w(TAG, "image of " + lane + " not made: " + e);
            reply.putString(DecodeGate.EXTRA_ERROR, "not made");
        } finally {
            closeQuietly(fd);
        }
        try {
            callback.sendResult(reply);
        } catch (RemoteException e) {
            // The caller is gone.
        }
    }

    // A memory file or a regular file is read from its start, a stream from where it is. The decode service reads a
    // regular file at offsets (pread) and never moves the offset that every copy of the descriptor shares.
    private static void rewind(ParcelFileDescriptor fd) {
        try {
            Os.lseek(fd.getFileDescriptor(), 0, OsConstants.SEEK_SET);
        } catch (ErrnoException e) {
            // Not seekable: read as it comes.
        }
    }

    private static Bundle refusal(String why) {
        final Bundle reply = new Bundle();
        reply.putBoolean(DecodeGate.EXTRA_TAKEN, false);
        reply.putString(DecodeGate.EXTRA_ERROR, why);
        return reply;
    }

    private static void closeQuietly(ParcelFileDescriptor fd) {
        try {
            if (fd != null) {
                fd.close();
            }
        } catch (IOException ignored) {
            // Closed.
        }
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
            String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
