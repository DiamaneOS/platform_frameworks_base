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

package de.diamaneos.decode.client;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;

import de.diamaneos.sanitizer.decode.IDecodeService;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The connection to the decode service, the same rules as MediaProvider's client: binds on first use, only to the
 * system app that holds the service, and puts a deadline on every call. A service that is missing, not a system app,
 * slow or gone makes every call fail with {@link VmDecode.UnavailableException}. Nothing here decodes a file.
 */
final class DecodeClient {
    private static final String TAG = "VmDecode";

    static final String SERVICE_PACKAGE = "de.diamaneos.sanitizer";
    static final String SERVICE_ACTION = "de.diamaneos.sanitizer.action.DECODE";

    private static final long CONNECT_TIMEOUT_USER_WAITING_MS = 3_000;
    private static final long CONNECT_TIMEOUT_BACKGROUND_MS = 15_000;
    private static final long CALL_TIMEOUT_USER_WAITING_MS = 15_000;
    private static final long CALL_TIMEOUT_BACKGROUND_MS = 60_000;

    // A call stuck past its deadline keeps its thread until the service answers or the binder dies. Few threads, so
    // a stuck service makes calls fail fast instead of piling threads up.
    private static final int MAX_THREADS = 4;

    // Calls in flight per owner (queued, running, or stuck past a deadline), so one app's slow or large files cannot
    // fill the queue or hold every thread. Requests without a known owner share a slightly larger lane.
    private static final int MAX_CALLS_PER_OWNER = 2;
    private static final int MAX_CALLS_UNKNOWN_OWNER = 4;
    private final Map<String, Integer> mOwnerCalls = new HashMap<>(); // guarded by itself

    /** One call to the service. */
    interface Call<T> {
        T run(IDecodeService service) throws RemoteException, VmDecode.RejectedException,
                VmDecode.UnavailableException;
    }

    private final Context mContext;
    private final ThreadPoolExecutor mExecutor = new ThreadPoolExecutor(MAX_THREADS, MAX_THREADS, 30,
            TimeUnit.SECONDS, new LinkedBlockingQueue<>(64), new ThreadPoolExecutor.AbortPolicy());

    private final Object mLock = new Object();
    private IDecodeService mService; // guarded by mLock
    private boolean mBinding; // guarded by mLock

    private Connection mConnection; // the connection of the current generation, guarded by mLock

    // One connection object per bind: undoing or dropping one binding never touches another (the activity manager
    // removes every binding of a connection object at once). Its callbacks only act while it is current.
    private final class Connection implements ServiceConnection {
        final int generation;

        Connection(int generation) {
            this.generation = generation;
        }

        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            synchronized (mLock) {
                if (generation != mGeneration) {
                    return;
                }
                mService = IDecodeService.Stub.asInterface(binder);
                mDownUntil = 0;
                mLock.notifyAll();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            synchronized (mLock) {
                if (generation == mGeneration) {
                    mService = null;
                }
            }
        }

        @Override
        public void onBindingDied(ComponentName name) {
            unbind(generation);
        }

        @Override
        public void onNullBinding(ComponentName name) {
            unbind(generation);
        }
    }

    /** The service of one generation of binding, for a call. */
    private static final class Bound {
        final IDecodeService service;
        final int generation;

        Bound(IDecodeService service, int generation) {
            this.service = service;
            this.generation = generation;
        }
    }

    DecodeClient(Context context) {
        final Context app = context.getApplicationContext();
        mContext = app != null ? app : context;
        mExecutor.allowCoreThreadTimeOut(true);
    }

    /**
     * Runs {@code call} against the service within the default deadline for {@code priority}, or by
     * {@code deadline} (uptime milliseconds) when that is earlier. 0 means no deadline of the caller's own.
     */
    <T> T call(int priority, long deadline, String owner, Call<T> call)
            throws VmDecode.UnavailableException, VmDecode.RejectedException {
        final String lane = owner == null ? "" : owner;
        final boolean waiting = priority == IDecodeService.PRIORITY_USER_WAITING;
        long connectTimeout = waiting ? CONNECT_TIMEOUT_USER_WAITING_MS : CONNECT_TIMEOUT_BACKGROUND_MS;
        final long defaultCallTimeout = waiting ? CALL_TIMEOUT_USER_WAITING_MS : CALL_TIMEOUT_BACKGROUND_MS;
        if (deadline > 0) {
            final long left = deadline - SystemClock.uptimeMillis();
            if (left <= 0) {
                throw new VmDecode.UnavailableException("deadline passed before the call");
            }
            connectTimeout = Math.min(connectTimeout, left);
        }
        claimSlot(lane);
        // The slot is released exactly once: by the task when it ran, otherwise by whoever wins "claimed" here.
        final AtomicBoolean claimed = new AtomicBoolean();
        final AtomicBoolean started = new AtomicBoolean();
        final Bound bound;
        try {
            // A connect wait that the caller's own deadline cut short says nothing about the service.
            bound = connect(connectTimeout, connectTimeout >= (waiting
                    ? CONNECT_TIMEOUT_USER_WAITING_MS : CONNECT_TIMEOUT_BACKGROUND_MS));
        } catch (VmDecode.UnavailableException | RuntimeException e) {
            claimed.set(true);
            releaseSlot(lane);
            throw e;
        }
        long callTimeout = defaultCallTimeout;
        if (deadline > 0) {
            callTimeout = Math.min(callTimeout, Math.max(1, deadline - SystemClock.uptimeMillis()));
        }
        final Future<T> future;
        try {
            future = mExecutor.submit(() -> {
                if (!claimed.compareAndSet(false, true)) {
                    return null; // given up before it started
                }
                started.set(true);
                try {
                    return call.run(bound.service);
                } finally {
                    releaseSlot(lane);
                }
            });
        } catch (RuntimeException e) {
            if (claimed.compareAndSet(false, true)) {
                releaseSlot(lane);
            }
            throw new VmDecode.UnavailableException("decode calls are all busy", e);
        }
        try {
            final T result = future.get(callTimeout, TimeUnit.MILLISECONDS);
            synchronized (mLock) {
                mLastMissOwner = null;
                if (bound.generation == mGeneration) {
                    // The service of this binding answered: it is past its warm-up.
                    mWarm = true;
                }
            }
            return result;
        } catch (TimeoutException e) {
            if (claimed.compareAndSet(false, true)) {
                releaseSlot(lane); // never started: only the queue was slow
            }
            future.cancel(true);
            // A service that took the call and then missed its own full deadline is not trusted to recover by itself:
            // drop the binding, the next call binds again. Calls it took that missed a deadline of at least
            // WEDGE_MIN_DEADLINE_MS, from two different apps within WEDGE_WINDOW_MS with no success between, mean
            // the same (a wedged service): it is also not asked for WEDGED_RETRY_MS. One app alone cannot trip that,
            // nor can a short deadline an app's own budget left. Calls without an app (SystemUI's own image loader,
            // the wallpaper) never count as an app of their own, and nothing counts before the service of a binding
            // has answered once (a VM still starting is slow, not wedged). Only the binding the call was made on is
            // ever dropped. A call that only waited in the queue ends that call.
            if (started.get()) {
                if (callTimeout >= defaultCallTimeout) {
                    unbind(bound.generation);
                } else if (callTimeout >= WEDGE_MIN_DEADLINE_MS && !lane.isEmpty()) {
                    boolean wedged = false;
                    synchronized (mLock) {
                        if (mWarm && bound.generation == mGeneration) {
                            final long now = SystemClock.uptimeMillis();
                            wedged = mLastMissOwner != null && !mLastMissOwner.equals(lane)
                                    && now - mLastMissAt <= WEDGE_WINDOW_MS;
                            mLastMissOwner = wedged ? null : lane;
                            mLastMissAt = now;
                            if (wedged) {
                                mDownUntil = now + WEDGED_RETRY_MS;
                            }
                        }
                    }
                    if (wedged) {
                        unbind(bound.generation);
                    }
                }
            }
            throw new VmDecode.UnavailableException("decode service missed the deadline", e);
        } catch (InterruptedException e) {
            if (claimed.compareAndSet(false, true)) {
                releaseSlot(lane);
            }
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new VmDecode.UnavailableException("interrupted", e);
        } catch (ExecutionException e) {
            final Throwable cause = e.getCause();
            if (cause instanceof VmDecode.RejectedException) {
                throw (VmDecode.RejectedException) cause;
            }
            if (cause instanceof VmDecode.UnavailableException) {
                throw (VmDecode.UnavailableException) cause;
            }
            throw new VmDecode.UnavailableException("decode call failed", cause);
        }
    }

    private void claimSlot(String lane) throws VmDecode.UnavailableException {
        synchronized (mOwnerCalls) {
            final int n = mOwnerCalls.getOrDefault(lane, 0);
            final int max = lane.isEmpty() ? MAX_CALLS_UNKNOWN_OWNER : MAX_CALLS_PER_OWNER;
            if (n >= max) {
                throw new VmDecode.UnavailableException("too many decodes in flight for " + lane);
            }
            mOwnerCalls.put(lane, n + 1);
        }
    }

    private void releaseSlot(String lane) {
        synchronized (mOwnerCalls) {
            final int n = mOwnerCalls.getOrDefault(lane, 1) - 1;
            if (n <= 0) {
                mOwnerCalls.remove(lane);
            } else {
                mOwnerCalls.put(lane, n);
            }
        }
    }

    // After a bind or connect that failed, calls fail at once until this time (uptime), so a VM that is down costs
    // nothing per image instead of the whole wait every time. A connection that comes up clears it.
    private static final long DOWN_RETRY_MS = 10_000;
    // After a service that took calls missed deadlines for two different apps in a row.
    private static final long WEDGED_RETRY_MS = 5_000;
    // Only a missed deadline at least this long counts towards a wedged service.
    private static final long WEDGE_MIN_DEADLINE_MS = 1_000;
    private long mDownUntil; // guarded by mLock
    // Counts binds. An unbind while a bind is on its way makes the binding thread undo its own bind.
    private int mGeneration; // guarded by mLock
    private boolean mBound; // a bind of the current generation returned true, guarded by mLock
    private String mLastMissOwner; // the app of the last missed deadline since a success, guarded by mLock
    private long mLastMissAt; // when it was missed (uptime), guarded by mLock
    // Two missed deadlines count together only within this time.
    private static final long WEDGE_WINDOW_MS = 5_000;
    private boolean mWarm; // the service of the current binding answered a call, guarded by mLock
    // A binding that never connects is dropped after this long, and bound anew on a later call.
    private static final long STALE_BIND_MS = 30_000;
    private long mBindStart; // uptime of the current bind, guarded by mLock

    // fullWait: timeoutMs is the whole connect timeout, not a part the caller's deadline left. Only a full wait that
    // ends without a connection marks the service down.
    private Bound connect(long timeoutMs, boolean fullWait) throws VmDecode.UnavailableException {
        boolean bind = false;
        int generation = 0;
        Connection connection = null;
        synchronized (mLock) {
            if (mService != null && mService.asBinder().isBinderAlive()) {
                return new Bound(mService, mGeneration);
            }
            mService = null;
            if (SystemClock.uptimeMillis() < mDownUntil) {
                throw new VmDecode.UnavailableException("decode service is down, not asked again yet");
            }
            if (!mBinding) {
                mBinding = true;
                mBound = false;
                mWarm = false;
                generation = ++mGeneration;
                connection = new Connection(generation);
                mConnection = connection;
                mBindStart = SystemClock.uptimeMillis();
                bind = true;
            }
        }
        if (bind) {
            // Bound outside the lock: the package manager and the binder call never hold up the other callers.
            // Always as this process, for the user of its application context (user 0 for both SystemUI and the
            // system server, the only callers): a caller may be on an app's binder call, whose identity must not be
            // the one that binds. Only the bind runs with the identity cleared. The app stays the owner each decode
            // is charged to, as data in the request.
            boolean bound = false;
            boolean tried = false;
            String why = "decode service did not bind";
            final long token = Binder.clearCallingIdentity();
            try {
                final Intent intent = serviceIntent();
                if (intent == null) {
                    why = "no decode service in the system image";
                } else {
                    // Even a bind that fails or throws registered the connection: it is unbound below.
                    tried = true;
                    bound = mContext.bindServiceAsUser(intent, connection,
                            Context.BIND_AUTO_CREATE | Context.BIND_IMPORTANT, mContext.getUser());
                }
            } catch (SecurityException e) {
                Log.w(TAG, "not allowed to bind the decode service", e);
                why = "not allowed to bind the decode service";
            } finally {
                Binder.restoreCallingIdentity(token);
            }
            boolean undo = false;
            synchronized (mLock) {
                if (generation != mGeneration) {
                    // Unbound while this bind was on its way: the bind is undone here, after it registered.
                    undo = bound;
                    bound = false;
                    why = "decode service binding was dropped";
                } else if (!bound) {
                    mBinding = false;
                    mDownUntil = SystemClock.uptimeMillis() + DOWN_RETRY_MS;
                    mLock.notifyAll();
                } else {
                    mBound = true;
                }
            }
            if (undo || (tried && !bound)) {
                unbindQuietly(connection);
            }
            if (!bound) {
                throw new VmDecode.UnavailableException(why);
            }
        }
        int stale = -1;
        synchronized (mLock) {
            final long end = SystemClock.uptimeMillis() + timeoutMs;
            while (mService == null) {
                if (!mBinding) {
                    throw new VmDecode.UnavailableException("decode service did not bind");
                }
                final long left = end - SystemClock.uptimeMillis();
                if (left <= 0) {
                    if (fullWait) {
                        mDownUntil = SystemClock.uptimeMillis() + DOWN_RETRY_MS;
                    }
                    // A binding that has not connected for STALE_BIND_MS is dropped, so a later try binds anew. A
                    // younger one is left alone: the service may still be starting its VM.
                    if (SystemClock.uptimeMillis() - mBindStart >= STALE_BIND_MS) {
                        stale = mGeneration;
                        break;
                    }
                    throw new VmDecode.UnavailableException("decode service did not connect in time");
                }
                try {
                    mLock.wait(left);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new VmDecode.UnavailableException("interrupted", e);
                }
            }
            if (stale < 0) {
                return new Bound(mService, mGeneration);
            }
        }
        unbind(stale);
        throw new VmDecode.UnavailableException("decode service did not connect in time");
    }

    /** The service intent, only when a system app provides it. An app installed later can never stand in. */
    private Intent serviceIntent() {
        final Intent intent = new Intent(SERVICE_ACTION).setPackage(SERVICE_PACKAGE);
        final PackageManager pm = mContext.getPackageManager();
        final ResolveInfo info = pm.resolveService(intent, PackageManager.MATCH_SYSTEM_ONLY);
        if (info == null || info.serviceInfo == null || info.serviceInfo.applicationInfo == null) {
            return null;
        }
        final ApplicationInfo app = info.serviceInfo.applicationInfo;
        if ((app.flags & ApplicationInfo.FLAG_SYSTEM) == 0 || !SERVICE_PACKAGE.equals(app.packageName)) {
            Log.w(TAG, "decode service is not a system app, not used");
            return null;
        }
        return intent.setComponent(new ComponentName(app.packageName, info.serviceInfo.name));
    }

    // Drops the binding of generation {@code generation}, if it is still the current one (a stale call or callback
    // never drops a newer binding). unbindService runs after the lock is left (in the system server it takes the
    // activity manager's lock). A bind still on its way is undone by the thread that made it.
    private void unbind(int generation) {
        final Connection wasBound;
        synchronized (mLock) {
            if (generation != mGeneration) {
                return;
            }
            wasBound = mBinding && mBound ? mConnection : null;
            mBinding = false;
            mBound = false;
            mService = null;
            mConnection = null;
            mGeneration++;
            mLock.notifyAll();
        }
        if (wasBound != null) {
            unbindQuietly(wasBound);
        }
    }

    private void unbindQuietly(Connection connection) {
        try {
            mContext.unbindService(connection);
        } catch (IllegalArgumentException ignored) {
            // Already unbound.
        }
    }
}
