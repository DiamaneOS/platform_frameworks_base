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

    private final ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            synchronized (mLock) {
                mService = IDecodeService.Stub.asInterface(binder);
                mLock.notifyAll();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            synchronized (mLock) {
                mService = null;
            }
        }

        @Override
        public void onBindingDied(ComponentName name) {
            unbind();
        }

        @Override
        public void onNullBinding(ComponentName name) {
            unbind();
        }
    };

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
        final IDecodeService service;
        try {
            service = connect(connectTimeout);
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
                    return call.run(service);
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
            return future.get(callTimeout, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            if (claimed.compareAndSet(false, true)) {
                releaseSlot(lane); // never started: only the queue was slow
            }
            future.cancel(true);
            // A service that took the call and then missed its own full deadline is not trusted to recover by itself:
            // drop the binding, the next call binds again. A call that waited in the queue, or a caller's shorter
            // deadline, only ends that call.
            if (started.get() && callTimeout >= defaultCallTimeout) {
                unbind();
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

    private IDecodeService connect(long timeoutMs) throws VmDecode.UnavailableException {
        synchronized (mLock) {
            if (mService != null && mService.asBinder().isBinderAlive()) {
                return mService;
            }
            mService = null;
            if (!mBinding) {
                final Intent intent = serviceIntent();
                if (intent == null) {
                    throw new VmDecode.UnavailableException("no decode service in the system image");
                }
                try {
                    mBinding = mContext.bindService(intent, mConnection,
                            Context.BIND_AUTO_CREATE | Context.BIND_IMPORTANT);
                } catch (SecurityException e) {
                    throw new VmDecode.UnavailableException("not allowed to bind the decode service", e);
                }
                if (!mBinding) {
                    throw new VmDecode.UnavailableException("decode service did not bind");
                }
            }
            final long end = System.currentTimeMillis() + timeoutMs;
            while (mService == null) {
                final long left = end - System.currentTimeMillis();
                if (left <= 0) {
                    throw new VmDecode.UnavailableException("decode service did not connect in time");
                }
                try {
                    mLock.wait(left);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new VmDecode.UnavailableException("interrupted", e);
                }
            }
            return mService;
        }
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

    private void unbind() {
        synchronized (mLock) {
            if (mBinding) {
                try {
                    mContext.unbindService(mConnection);
                } catch (IllegalArgumentException ignored) {
                    // Already unbound.
                }
            }
            mBinding = false;
            mService = null;
        }
    }
}
