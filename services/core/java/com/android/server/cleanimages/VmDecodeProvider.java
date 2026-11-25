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

import com.android.internal.diamaneos.cleancopies.CleanCopyGate;

import de.diamaneos.decode.client.GateCallers;
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
 * calling app (its full uid, never the request: GateCallers), then the origin the app names within its own lane
 * (the authority of the URI it opened, else its bytes). One app runs at most {@link #APP_RUNNING} requests and one
 * origin at most {@link #ORIGIN_RUNNING}, so an app always leaves a thread to the others and an origin a thread to the
 * rest of its app. Places (running and waiting): {@link #ORIGIN_MAX} for an origin, {@link #APP_MAX} for an app,
 * {@link #TOTAL_MAX} in all. An origin is only the caller's word, so naming another one only reorders the caller's own
 * lane. The decode service's lane is the app's uid and name and the origin together, so no caller is ever charged to
 * another app's lane. No binder thread waits for the VM.
 *
 * <p>Whose image a request is: an app's own user, from its binder identity. An image of another user
 * ({@link DecodeGate#EXTRA_OWNER_USER}) is never the caller's own bytes: the system server acquires it from the source
 * the caller names and may read ({@link ForeignSources}), decoded for that app ({@code forApp}). The system server's
 * own requests carry what DecodeGate took on the
 * thread that made them: a scope's user, an incoming call's caller. With neither, the image is of no known user and the
 * decode service keeps it apart from every user's.
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
    /**
     * Bytes the waiting and running requests of one calling app hold (their memory files stay alive while a request
     * holds its descriptor), and of all apps. Each is also reserved in the process's image memory (DecodeMemory).
     */
    static final long APP_BYTES = 64L << 20;
    static final long TOTAL_BYTES = 128L << 20;
    // What a request on a pipe or socket is charged: what its descriptor holds is the pipe's buffer only.
    private static final long STREAM_BYTES = 64 * 1024;
    // A uid refused as a caller is refused without a lookup for this long.
    private static final long REFUSED_FOR_MS = 10_000;
    private static final int MAX_ORIGIN_CHARS = 256;
    // A probe of these formats also reads the turn from a decode of at most this many pixels a side.
    private static final int TURN_PROBE_SIDE = 16;

    /** One request: where it waits, what it runs, and the bytes its descriptor holds (reserved until it ends). */
    private static final class Task {
        final String app;
        final String origin;
        Runnable work;
        final IRemoteCallback callback;
        final ParcelFileDescriptor fd;
        final long end;
        final java.util.concurrent.atomic.AtomicInteger dispatch = new java.util.concurrent.atomic.AtomicInteger();
        boolean admitted;
        boolean refusedForever;
        long bytes;
        android.graphics.DecodeMemory.Reservation memory;

        Task(String app, String origin, ParcelFileDescriptor fd, long end, Runnable work, IRemoteCallback callback) {
            this.app = app;
            this.origin = origin;
            this.work = work;
            this.callback = callback;
            this.fd = fd;
            this.end = end;
        }
    }

    // All queue storage exists before admission. Publishing or withdrawing a task allocates nothing.
    private static final class TaskQueue {
        private final Task[] tasks = new Task[ORIGIN_MAX];
        private int size;
        boolean isEmpty() { return size == 0; }
        void add(Task task) {
            if (size == tasks.length) throw new IllegalStateException("origin queue capacity");
            tasks[size++] = task;
        }
        Task remove() {
            if (size == 0) throw new IllegalStateException("empty origin queue");
            final Task first = tasks[0];
            remove(first);
            return first;
        }
        boolean remove(Task task) {
            for (int i = 0; i < size; i++) {
                if (tasks[i] == task) {
                    System.arraycopy(tasks, i + 1, tasks, i, size - i - 1);
                    tasks[--size] = null;
                    return true;
                }
            }
            return false;
        }
    }

    /** The requests of one origin, or (with its origins, in turn order) of one app. */
    private static final class Lane {
        final TaskQueue queue = new TaskQueue();
        LinkedHashMap<String, Lane> origins = new LinkedHashMap<>();
        int running;
        int held;
        String lastOrigin;
        long bytes; // of an app: what its requests hold
    }

    private final Object mLock = new Object();
    private LinkedHashMap<String, Lane> mApps = new LinkedHashMap<>(); // in turn order, guarded by mLock
    private String mLastApp;
    private int mRunning; // guarded by mLock
    private int mHeld; // guarded by mLock
    private long mBytes; // guarded by mLock
    // Allowed uids with their packages and refused uids until when, forgotten when a package or user changes.
    private final GateCallers mCallers = new GateCallers();
    private final ThreadPoolExecutor mWorkers = new ThreadPoolExecutor(THREADS, THREADS, 30, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(TOTAL_MAX), r -> {
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
            // The system server's own decodes: no binder, no activity manager. Whose image each is came with it,
            // taken on the thread that made the request (DecodeGate): the user a scope names, else the user of the
            // app whose binder call the thread served, which is also the app decoded for (forApp). A request with
            // neither is the system server's own work of no known user (VmDecode OWNER_USER).
            registerGlobalLocks();
            DecodeGate.setLocal(this::takeLocal);
            forgetCallersOnChanges();
        }
        return true;
    }

    // The system server's own request: its owner and the app it is for as DecodeGate took them.
    private Bundle takeLocal(String method, Bundle extras) {
        final int user = extras == null ? -1 : extras.getInt(DecodeGate.EXTRA_OWNER_USER, -1);
        final int forUid = extras == null ? -1 : extras.getInt(DecodeGate.EXTRA_FOR_UID, -1);
        // Charged to the app it is for, else the system server's own lane.
        return take(method, extras, forUid >= 0 ? forUid : Process.SYSTEM_UID, user, forUid, null);
    }

    // A caller decision is forgotten when a package of its uid, or its user, changes: looked up again on its next call.
    private void forgetCallersOnChanges() {
        final android.content.BroadcastReceiver receiver = new android.content.BroadcastReceiver() {
            @Override
            public void onReceive(android.content.Context context, android.content.Intent intent) {
                final int uid = intent.getIntExtra(android.content.Intent.EXTRA_UID, -1);
                final int user = intent.getIntExtra(android.content.Intent.EXTRA_USER_HANDLE, -1);
                if (android.content.Intent.ACTION_USER_REMOVED.equals(intent.getAction()) && user >= 0) {
                    mCallers.forgetUser(user, UserHandle.PER_USER_RANGE);
                } else if (uid >= 0) {
                    mCallers.forgetUid(uid);
                } else {
                    mCallers.forgetAll();
                }
            }
        };
        final android.content.IntentFilter packages = new android.content.IntentFilter();
        packages.addAction(android.content.Intent.ACTION_PACKAGE_ADDED);
        packages.addAction(android.content.Intent.ACTION_PACKAGE_REMOVED);
        packages.addAction(android.content.Intent.ACTION_PACKAGE_CHANGED);
        packages.addAction(android.content.Intent.ACTION_PACKAGE_REPLACED);
        packages.addDataScheme("package");
        final android.content.IntentFilter users = new android.content.IntentFilter();
        users.addAction(android.content.Intent.ACTION_USER_REMOVED);
        try {
            getContext().registerReceiverAsUser(receiver, UserHandle.ALL, packages, null, null);
            getContext().registerReceiverAsUser(receiver, UserHandle.ALL, users, null, null);
        } catch (RuntimeException e) {
            Log.e(TAG, "caller decisions are not forgotten on package changes: " + e);
        }
    }

    // The system server's global locks no thread may wait for the VM under: the activity manager's, the window
    // manager's, the package manager's, and Telecom's (it runs in the system server and stores calling apps' icons
    // under its lock). Each is required: one not found yet is looked for until it is, and meanwhile no system server
    // thread waits for the VM (DecodeGate, GateLocks).
    private static void registerGlobalLocks() {
        DecodeGate.addGlobalLockSource("the activity manager's lock", () -> {
            final android.os.IBinder am = android.os.ServiceManager.getService(
                    android.content.Context.ACTIVITY_SERVICE);
            return am instanceof com.android.server.am.ActivityManagerService ? am : null;
        });
        DecodeGate.addGlobalLockSource("the window manager's lock", () -> {
            final android.os.IBinder atm = android.os.ServiceManager.getService(
                    android.content.Context.ACTIVITY_TASK_SERVICE);
            return atm instanceof com.android.server.wm.ActivityTaskManagerService
                    ? ((com.android.server.wm.ActivityTaskManagerService) atm).getGlobalLock() : null;
        });
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
        if (Build.IS_DEBUGGABLE && "testUriIcon".equals(method)) {
            final String[] packages = getContext().getPackageManager().getPackagesForUid(uid);
            if (packages == null || !java.util.Arrays.asList(packages).contains(CLIENT_TESTS)) {
                throw new SecurityException("decode tests only");
            }
            final long start = SystemClock.uptimeMillis();
            android.graphics.drawable.Icon.createWithContentUri(extras.getString("uri")).loadDrawable(getContext());
            final Bundle result = new Bundle();
            result.putLong("elapsed", SystemClock.uptimeMillis() - start);
            return result;
        }
        callerPackages(uid);
        // A remote descriptor has no proof of a foreign source user, even for a privileged peer.
        final int named = extras == null ? -1 : extras.getInt(DecodeGate.EXTRA_OWNER_USER, -1);
        final int owner = GateCallers.unverifiedUser(uid, named, UserHandle.PER_USER_RANGE);
        if (owner < 0) {
            // DiamaneOS: bytes the caller sent itself go as the user it may act for, another user's source is
            // acquired here from what the caller may read.
            final int forBytes = OwnerUsers.forBytes(getContext(), uid, named,
                    extras.getString(DecodeGate.EXTRA_SOURCE));
            if (forBytes >= 0) {
                return take(method, extras, uid, forBytes, uid, attributedPackage());
            }
            return ForeignSources.take(getContext(), this::take, method, extras, uid, named, attributedPackage());
        }
        return take(method, extras, uid, owner, uid, attributedPackage());
    }

    // The package the call's transport attributes it to, checked against the calling uid, else null.
    private String attributedPackage() {
        try {
            return getCallingPackage();
        } catch (SecurityException e) {
            return null;
        }
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

    // The packages of an allowed calling uid (looked up once until a package or user changes), else a
    // SecurityException.
    private String[] callerPackages(int uid) {
        final String[] known = mCallers.allowed(uid);
        if (known != null) {
            return known;
        }
        if (mCallers.refused(uid, SystemClock.uptimeMillis())) {
            throw new SecurityException("the decode gate is for system processes only");
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
            mCallers.refuse(uid, SystemClock.uptimeMillis() + REFUSED_FOR_MS);
            throw new SecurityException("the decode gate is for system processes only");
        }
        if (packages == null) {
            packages = new String[0];
        }
        mCallers.allow(uid, packages);
        return packages;
    }

    // Takes a probe or a decode charged to {@code callingUid} (its transport's package {@code attributed}), of
    // {@code ownerUser} (-1: no known user), for the app {@code forUid} (-1: the system server's own): the reply says
    // whether it was taken.
    private Bundle take(String method, Bundle extras, int callingUid, int ownerUser, int forUid, String attributed) {
        if (extras == null) {
            return refusal("no request");
        }
        final boolean probe = DecodeGate.METHOD_PROBE.equals(method) || DecodeGate.METHOD_HEADER.equals(method);
        final boolean frames = DecodeGate.METHOD_FRAMES.equals(method)
                || DecodeGate.METHOD_FRAME_WINDOW.equals(method);
        final boolean region = DecodeGate.METHOD_REGION.equals(method) || DecodeGate.METHOD_REGION_WIDE.equals(method) || DecodeGate.METHOD_REGION_STRAIGHT.equals(method)
                || DecodeGate.METHOD_REGION_STRAIGHT_WIDE.equals(method) || DecodeGate.METHOD_GAIN_REGION.equals(method);
        final ParcelFileDescriptor given = extras.getParcelable(DecodeGate.EXTRA_FD, ParcelFileDescriptor.class);
        if (!probe && !frames && !region && !DecodeGate.METHOD_DECODE.equals(method)
                && !DecodeGate.METHOD_STRAIGHT_WEBP.equals(method) && !DecodeGate.METHOD_GAINMAP.equals(method)) {
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
        Task task = null;
        try {
        final long bytes = heldBytes(fd);
        if (bytes < 0) return refusal("an immutable staged input is required", true);
        if (bytes > DecodeGate.MAX_BYTES) return refusal("the input exceeds the decode limit", true);
        final IRemoteCallback callback = IRemoteCallback.Stub.asInterface(binder);
        final long time = Math.max(1, Math.min(DecodeGate.SERVER_MS, extras.getLong(DecodeGate.EXTRA_TIME,
                DecodeGate.SERVER_MS)));
        final long end = SystemClock.uptimeMillis() + time;
        // The principal is the full uid. Its name: the transport's package (the system server's own: "android"), the
        // uid's only package, else the uid. The origin only orders work within the principal's own lane.
        final String app = GateCallers.principal(callingUid);
        final String display = callingUid == Process.SYSTEM_UID && forUid < 0 ? "android"
                : GateCallers.display(callingUid, attributed, packagesOf(callingUid));
        final String named = extras.getString(DecodeGate.EXTRA_AUTHORITY);
        final String origin = named == null ? "bytes" : named.length() > MAX_ORIGIN_CHARS ? "long" : named;
        final String lane = GateCallers.lane(callingUid, display, origin);
        final int left = extras.getInt(DecodeGate.EXTRA_LEFT);
        final int top = extras.getInt(DecodeGate.EXTRA_TOP);
        final int sample = extras.getInt(DecodeGate.EXTRA_SAMPLE, 1);
        task = new Task(app, origin, fd, end,
                () -> run(method, fd, lane, ownerUser, forUid, width, height, left, top, sample, end, callback), callback);
        final String full = enqueue(task, bytes);
        if (full != null) {
            closeQuietly(fd);
            return refusal(full, task.refusedForever);
        }
        final Bundle reply = new Bundle();
        reply.putBoolean(DecodeGate.EXTRA_TAKEN, true);
        return reply;
        } finally {
            if (task == null || !task.admitted) closeQuietly(fd);
        }
    }

    // The packages of {@code uid}: an allowed caller's as looked up, else (an app the system server decodes for) asked.
    private String[] packagesOf(int uid) {
        final String[] known = mCallers.allowed(uid);
        if (known != null) {
            return known;
        }
        final long identity = Binder.clearCallingIdentity();
        try {
            return getContext().getPackageManager().getPackagesForUid(uid);
        } catch (RuntimeException e) {
            return null;
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    // What a request's descriptor holds: a regular file's (a memory file's) size, a stream's pipe buffer.
    private static long heldBytes(ParcelFileDescriptor fd) {
        try {
            // Only sealed memfds may wait in this process. Metadata of arbitrary provider files is never read here.
            final int seals = Os.fcntlInt(fd.getFileDescriptor(), OsConstants.F_GET_SEALS, 0);
            if ((seals & (1 | 2 | 4 | OsConstants.F_SEAL_WRITE)) != (1 | 2 | 4 | OsConstants.F_SEAL_WRITE)) return -1;
            return Math.max(0, Os.fstat(fd.getFileDescriptor()).st_size);
        } catch (ErrnoException e) {
            return -1;
        }
    }

    // Puts {@code task} holding {@code bytes} in its lanes and starts what may run: null, or why it was not taken.
    private String enqueue(Task task, long bytes) {
        final android.graphics.DecodeMemory.Reservation memory;
        try {
            memory = android.graphics.DecodeMemory.reserve(bytes);
        } catch (android.graphics.DecodeMemory.NoRoomException e) {
            task.refusedForever = !e.later;
            return e.later ? "no room for the image's bytes now" : "the input cannot fit in this process";
        }
        try {
            final String refused = enqueueLocked(task, bytes, memory);
            if (refused == null) {
                // The task is the timer's token: done() takes the timer back, so it keeps no callback after the end.
                if (!com.android.internal.os.BackgroundThread.getHandler().postAtTime(() -> {
                    if (withdraw(task)) {
                        task.work = null;
                        try { closeQuietly(task.fd); } finally {
                            try { task.memory.close(); } finally {
                                deliver(task.callback, refusal("out of time in the queue"));
                            }
                        }
                    }
                }, task, task.end)) {
                    withdraw(task);
                    return "queue timer unavailable";
                }
                pump();
            }
            return refused;
        } catch (RuntimeException | Error e) {
            withdraw(task);
            throw e;
        } finally {
            if (!task.admitted) memory.close();
        }
    }

    private boolean withdraw(Task task) {
        synchronized (mLock) {
            final Lane app = mApps.get(task.app);
            final Lane origin = app != null ? app.origins.get(task.origin) : null;
            if (origin == null || !origin.queue.remove(task)) return false;
            task.dispatch.compareAndSet(0, 2);
            origin.held--;
            app.held--;
            app.bytes -= task.bytes;
            mHeld--;
            mBytes -= task.bytes;
            task.admitted = false;
            pruneLocked(app);
            return true;
        }
    }

    private LinkedHashMap<String, Lane> liveOrigins(Lane app) {
        final LinkedHashMap<String, Lane> origins = new LinkedHashMap<>();
        if (app != null) for (Map.Entry<String, Lane> entry : app.origins.entrySet()) {
            if (entry.getValue().held > 0) origins.put(entry.getKey(), entry.getValue());
        }
        return origins;
    }

    private LinkedHashMap<String, Lane> liveApps() {
        final LinkedHashMap<String, Lane> apps = new LinkedHashMap<>();
        for (Map.Entry<String, Lane> entry : mApps.entrySet()) {
            if (entry.getValue().held > 0) apps.put(entry.getKey(), entry.getValue());
        }
        return apps;
    }

    // Failed bookkeeping construction leaves valid zero-count entries for the next admission to prune.
    private void pruneLocked(Lane app) {
        try {
            final LinkedHashMap<String, Lane> apps = liveApps();
            final LinkedHashMap<String, Lane> origins = liveOrigins(app);
            app.origins = origins;
            mApps = apps;
        } catch (OutOfMemoryError ignored) {
            // Counts and storage release do not depend on removing empty map nodes.
        }
    }

    private String enqueueLocked(Task task, long bytes, android.graphics.DecodeMemory.Reservation memory) {
        synchronized (mLock) {
            final Lane previous = mApps.get(task.app);
            Lane origin = previous == null ? null : previous.origins.get(task.origin);
            if (mHeld >= TOTAL_MAX) return "too many images waiting";
            if ((previous != null && previous.held >= APP_MAX) || (origin != null && origin.held >= ORIGIN_MAX)) {
                return "too many images of " + task.app + " waiting";
            }
            if (bytes > TOTAL_BYTES - mBytes || bytes > APP_BYTES - (previous != null ? previous.bytes : 0)) {
                return "too many bytes of images waiting";
            }
            final Lane app = new Lane();
            if (previous != null) {
                app.running = previous.running;
                app.held = previous.held;
                app.bytes = previous.bytes;
                app.lastOrigin = previous.lastOrigin;
            }
            if (origin == null) origin = new Lane();
            final LinkedHashMap<String, Lane> apps = liveApps();
            final LinkedHashMap<String, Lane> origins = liveOrigins(previous);
            origins.put(task.origin, origin);
            app.origins = origins;
            apps.put(task.app, app);
            // No construction follows this point until the complete transition is published.
            origin.queue.add(task);
            origin.held++;
            app.held++;
            app.bytes += bytes;
            mHeld++;
            mBytes += bytes;
            task.bytes = bytes;
            task.memory = memory;
            task.admitted = true;
            mApps = apps;
        }
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
                    if (!next.dispatch.compareAndSet(0, 1)) return;
                    try {
                        final Runnable work = next.work;
                        if (work != null) work.run();
                    } finally {
                        next.work = null;
                        if (next.dispatch.compareAndSet(1, 2)) {
                            try { closeQuietly(next.fd); } finally { done(next, true); }
                        }
                    }
                });
            } catch (RuntimeException | Error e) {
                if (next.dispatch.compareAndSet(0, 2)) {
                    next.work = null;
                    try { closeQuietly(next.fd); } finally {
                        try { done(next, false); } finally {
                            deliver(next.callback, refusal("decode worker unavailable"));
                        }
                    }
                }
                return;
            }
        }
    }

    // The next request to run: from the first app in turn below its running cap, its first origin in turn below its
    // own. Both go to the end of their turn.
    private Task nextLocked() {
        for (int pass = 0; pass < 2; pass++) {
            boolean after = mLastApp == null;
            for (Map.Entry<String, Lane> entry : mApps.entrySet()) {
                final boolean last = entry.getKey().equals(mLastApp);
                final boolean select = pass == 0 ? after : !after || last;
                if (last) after = true;
                final Lane app = entry.getValue();
                if (!select || app.running >= APP_RUNNING) continue;
                final Task task = nextOriginLocked(app);
                if (task != null) {
                    app.running++;
                    mLastApp = entry.getKey();
                    return task;
                }
            }
        }
        return null;
    }

    private Task nextOriginLocked(Lane app) {
        for (int pass = 0; pass < 2; pass++) {
            boolean after = app.lastOrigin == null;
            for (Map.Entry<String, Lane> entry : app.origins.entrySet()) {
                final boolean last = entry.getKey().equals(app.lastOrigin);
                final boolean select = pass == 0 ? after : !after || last;
                if (last) after = true;
                final Lane origin = entry.getValue();
                if (!select || origin.running >= ORIGIN_RUNNING || origin.queue.isEmpty()) continue;
                final Task task = origin.queue.remove();
                origin.running++;
                app.lastOrigin = entry.getKey();
                return task;
            }
        }
        return null;
    }

    // A request ended, or could not start: its places are free.
    private void done(Task task, boolean again) {
        com.android.internal.os.BackgroundThread.getHandler().removeCallbacksAndMessages(task);
        try {
            synchronized (mLock) {
                mRunning--;
                mHeld--;
                final Lane app = mApps.get(task.app);
                final Lane origin = app == null ? null : app.origins.get(task.origin);
                if (origin != null) { origin.running--; origin.held--; }
                if (app != null) { app.running--; app.held--; app.bytes -= task.bytes; }
                mBytes -= task.bytes;
                if (app != null) pruneLocked(app);
            }
        } finally {
            if (task.memory != null) task.memory.close();
        }
        if (again) {
            try { pump(); } catch (OutOfMemoryError ignored) { }
        }
    }

    private void run(String method, ParcelFileDescriptor fd, String lane, int ownerUser, int forUid, int width,
            int height, int regionLeft, int regionTop, int sample, long end, IRemoteCallback callback) {
        // The decode service answers NO_ROOM while other images hold its memory and asks to be asked again shortly:
        // so it is, within this request's own deadline, as a stock decode would simply wait for its memory.
        Bundle reply;
        long pause = NO_ROOM_FIRST_PAUSE_MS;
        try {
            while (true) {
                reply = attempt(method, fd, lane, ownerUser, forUid, width, height, regionLeft, regionTop, sample,
                        end);
                if (!reply.getBoolean(NO_ROOM, false) || SystemClock.uptimeMillis() + pause >= end) break;
                SystemClock.sleep(pause);
                pause = Math.min(pause * 2, NO_ROOM_MAX_PAUSE_MS);
            }
            reply.remove(NO_ROOM);
        } finally {
            closeQuietly(fd);
        }
        deliver(callback, reply);
    }

    private static final String NO_ROOM = "noRoom";
    static final long NO_ROOM_FIRST_PAUSE_MS = 50;
    static final long NO_ROOM_MAX_PAUSE_MS = 400;

    // One decode of the request: the reply, with NO_ROOM set when the decode service had no room for it now.
    private Bundle attempt(String method, ParcelFileDescriptor fd, String lane, int ownerUser, int forUid, int width,
            int height, int regionLeft, int regionTop, int sample, long end) {
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
                    reply.putInt(DecodeGate.EXTRA_COLOR_SPACE, info.colorSpace);
                    reply.putFloatArray(DecodeGate.EXTRA_COLOR_DESCRIPTION, info.colorDescription);
                    reply.putInt(DecodeGate.EXTRA_BIT_DEPTH, info.bitDepth);
                    reply.putBoolean(DecodeGate.EXTRA_HAS_ALPHA, info.hasAlpha);
                    // WebP too: stock's WebP codec reads the orientation of its EXIF chunk.
                    if (DecodeGate.METHOD_HEADER.equals(method) && (info.format == IDecodeService.FORMAT_JPEG
                            || info.format == IDecodeService.FORMAT_HEIF
                            || info.format == IDecodeService.FORMAT_AVIF
                            || info.format == IDecodeService.FORMAT_WEBP)) {
                        // The turn and the mirror the file asks for (its orientation), from a tiny decode in the VM.
                        final VmDecode.Decoded tiny = VmDecode.decode(getContext(), fd, lane, ownerUser,
                                VmDecode.PRIORITY_USER_WAITING, TURN_PROBE_SIDE, TURN_PROBE_SIDE,
                                VmDecode.deadlineIn(Math.max(1, end - SystemClock.uptimeMillis())), true);
                        reply.putInt(DecodeGate.EXTRA_ROTATION, tiny.rotation);
                        reply.putBoolean(DecodeGate.EXTRA_MIRROR, tiny.mirrored);
                    }
                } else if (DecodeGate.METHOD_REGION.equals(method) || DecodeGate.METHOD_REGION_WIDE.equals(method) || DecodeGate.METHOD_REGION_STRAIGHT.equals(method)
                || DecodeGate.METHOD_REGION_STRAIGHT_WIDE.equals(method)) {
                    final VmDecode.Decoded region = VmDecode.decodeRegionReply(getContext(), fd, lane,
                            ownerUser, VmDecode.PRIORITY_USER_WAITING, regionLeft, regionTop, width, height, sample,
                            DecodeGate.METHOD_REGION_STRAIGHT_WIDE.equals(method) ? IDecodeService.PIXEL_FORMAT_STRAIGHT_RGBA_F16
                                    : DecodeGate.METHOD_REGION_STRAIGHT.equals(method)
                                            ? IDecodeService.PIXEL_FORMAT_STRAIGHT_RGBA_8888
                                    : DecodeGate.METHOD_REGION_WIDE.equals(method) ? IDecodeService.PIXEL_FORMAT_RGBA_F16
                                    : IDecodeService.PIXEL_FORMAT_RGBA_8888,
                            VmDecode.deadlineIn(left), true);
                    reply.putParcelable(DecodeGate.EXTRA_BITMAP, region.bitmap);
                    reply.putInt(DecodeGate.EXTRA_SOURCE_ERROR, region.sourceError);
                    reply.putInt(DecodeGate.EXTRA_ROTATION, 0);
                } else if (DecodeGate.METHOD_GAIN_REGION.equals(method)) {
                    final VmDecode.Decoded gain = VmDecode.decodeGainRegion(getContext(), fd, lane, ownerUser,
                            VmDecode.PRIORITY_USER_WAITING, regionLeft, regionTop, width, height, sample,
                            VmDecode.deadlineIn(left));
                    reply.putParcelable(DecodeGate.EXTRA_BITMAP, gain.bitmap);
                    reply.putInt(DecodeGate.EXTRA_ROTATION, 0);
                } else if (DecodeGate.METHOD_GAINMAP.equals(method)) {
                    final VmDecode.Decoded gain = VmDecode.decodeGainmap(getContext(), fd, lane, ownerUser,
                            VmDecode.PRIORITY_USER_WAITING, width, height, VmDecode.deadlineIn(left));
                    reply.putParcelable(DecodeGate.EXTRA_BITMAP, gain.bitmap);
                    reply.putInt(DecodeGate.EXTRA_ROTATION, 0);
                } else if (DecodeGate.METHOD_FRAMES.equals(method)) {
                    final VmDecode.Animation animation = VmDecode.decodeFrames(getContext(), fd, lane, ownerUser,
                            VmDecode.PRIORITY_USER_WAITING, width, height, VmDecode.deadlineIn(left));
                    reply.putParcelable(DecodeGate.EXTRA_BITMAP, animation.frames);
                    reply.putInt(DecodeGate.EXTRA_FRAME_COUNT, animation.frameCount);
                    reply.putIntArray(DecodeGate.EXTRA_DURATIONS, animation.durations);
                    reply.putInt(DecodeGate.EXTRA_LOOPS, animation.loopCount);
                } else if (DecodeGate.METHOD_FRAME_WINDOW.equals(method)) {
                    // The window's first frame, size and band travel in the region fields (left, top, sample).
                    final VmDecode.Animation animation = VmDecode.decodeFrames(getContext(), fd, lane, ownerUser,
                            VmDecode.PRIORITY_USER_WAITING, width, height, regionLeft, regionTop, sample,
                            VmDecode.deadlineIn(left));
                    reply.putInt(DecodeGate.EXTRA_FIRST, animation.firstFrame);
                    reply.putInt(DecodeGate.EXTRA_TOTAL, animation.totalFrames);
                    reply.putInt(DecodeGate.EXTRA_FRAME_HEIGHT, animation.frameHeight);
                    reply.putParcelable(DecodeGate.EXTRA_BITMAP, animation.frames);
                    reply.putInt(DecodeGate.EXTRA_FRAME_COUNT, animation.frameCount);
                    reply.putIntArray(DecodeGate.EXTRA_DURATIONS, animation.durations);
                    reply.putInt(DecodeGate.EXTRA_LOOPS, animation.loopCount);
                } else {
                    final VmDecode.Decoded decoded = VmDecode.decode(getContext(), fd, lane, ownerUser,
                            VmDecode.PRIORITY_USER_WAITING, width, height, VmDecode.deadlineIn(left), true,
                            DecodeGate.METHOD_STRAIGHT_WEBP.equals(method) ? 2 : 0);
                    reply.putInt(DecodeGate.EXTRA_SOURCE_ERROR, decoded.sourceError);
                    reply.putParcelable(DecodeGate.EXTRA_BITMAP, decoded.bitmap);
                    reply.putInt(DecodeGate.EXTRA_ROTATION, decoded.rotation);
                    reply.putBoolean(DecodeGate.EXTRA_MIRROR, decoded.mirrored);
                }
            }
        } catch (VmDecode.RejectedException e) {
            if (e.sourceError != 0) reply.putInt(DecodeGate.EXTRA_SOURCE_ERROR, e.sourceError);
            reply.putBoolean(DecodeGate.EXTRA_REFUSED, true);
            reply.putString(DecodeGate.EXTRA_ERROR, "refused: " + e.getMessage());
        } catch (VmDecode.UnavailableException e) {
            reply.putString(DecodeGate.EXTRA_ERROR, "unavailable: " + e.getMessage());
            if (e.isNoRoom()) reply.putBoolean(NO_ROOM, true);
        } catch (RuntimeException | OutOfMemoryError e) {
            Log.w(TAG, "image of " + lane + " not made: " + e);
            reply.putString(DecodeGate.EXTRA_ERROR, "not made");
        }
        return reply;
    }

    private static void deliver(IRemoteCallback callback, Bundle reply) {
        // A local callback can run on another app's incoming Binder thread.
        final long identity = Binder.clearCallingIdentity();
        try {
            callback.sendResult(reply);
        } catch (RemoteException e) {
            // The caller is gone.
        } finally {
            Binder.restoreCallingIdentity(identity);
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
        return refusal(why, false);
    }

    private static Bundle refusal(String why, boolean terminal) {
        final Bundle reply = new Bundle();
        reply.putBoolean(DecodeGate.EXTRA_TAKEN, false);
        reply.putBoolean(DecodeGate.EXTRA_REFUSED, terminal);
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
