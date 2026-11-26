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

    /** One call to the service. */
    interface Call<T> {
        T run(IDecodeService service) throws RemoteException, VmDecode.RejectedException,
                VmDecode.UnavailableException;
    }

    private final Context mContext;
    // In the system server one of the threads serves only its own calls (made without an owner: the wallpaper), so
    // apps' calls can never hold every thread. The others serve apps round robin across owners (CallPool).
    private static final boolean SYSTEM_PROCESS = android.os.Process.myUid() == android.os.Process.SYSTEM_UID;
    private static final int APP_THREADS = SYSTEM_PROCESS ? MAX_THREADS - 1 : MAX_THREADS;
    private final CallPool mPool = new CallPool(APP_THREADS, MAX_THREADS - APP_THREADS, 64,
            MAX_CALLS_UNKNOWN_OWNER);
    private final OwnerSlots mOwnerCalls = new OwnerSlots(APP_THREADS, MAX_CALLS_PER_OWNER, MAX_CALLS_UNKNOWN_OWNER,
            DecodeClient::isSystemLane, VmDecode::isUnknownOwner, SystemClock::uptimeMillis);

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
                // The system server warns about every blocking call out of it (Binder.setWarnOnBlocking). Its calls
                // to the decode service block only this client's own threads (CallPool), each with a deadline, never
                // a binder thread, the main thread or a thread holding a lock, and the service is a privileged app of
                // the system image, not one an app store can replace: blocking calls are allowed on it here.
                mService = IDecodeService.Stub.asInterface(SYSTEM_PROCESS ? Binder.allowBlocking(binder) : binder);
                mDownUntil = 0;
                mLock.notifyAll();
            }
            recoveryEvent(); // a service (re)started: the recovery watch asks it now
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
        mContext = ownContext(context);
    }

    // The context the service is looked up and bound with: the process's own, for its own user. Never a context a
    // caller passed for one image: in SystemUI the first may be a notification's package context of a work profile,
    // whose application context is null, and once that profile is removed every new bind would find no service.
    static Context ownContext(Context context) {
        Context own = context.getApplicationContext();
        if (own == null) {
            own = android.app.ActivityThread.currentApplication();
        }
        if (own == null) {
            own = context;
        }
        if (own.getUserId() != android.os.UserHandle.myUserId()) {
            own = own.createContextAsUser(android.os.Process.myUserHandle(), 0);
        }
        return own;
    }

    /**
     * Runs {@code call} against the service within the default deadline for {@code priority}, or by
     * {@code deadline} (uptime milliseconds) when that is earlier. 0 means no deadline of the caller's own.
     */
    <T> T call(int priority, long deadline, String owner, Call<T> call)
            throws VmDecode.UnavailableException, VmDecode.RejectedException {
        try {
            final T result = callOnce(priority, deadline, owner, call);
            answered();
            return result;
        } catch (VmDecode.UnavailableException e) {
            // Whoever showed a placeholder for it can ask again once the service answers (VmDecode.unavailable).
            VmDecode.countUnavailable();
            if (e.isNoRoom()) {
                // The service answered: no room for this image now. It is asked for again shortly.
                answered();
                VmDecode.noRoom();
            }
            throw e;
        }
    }

    private <T> T callOnce(int priority, long deadline, String owner, Call<T> call)
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
        claimSlot(lane, SystemClock.uptimeMillis() + connectTimeout);
        // The slot is released exactly once: by the task when it ran, otherwise by whoever wins "claimed" here.
        final AtomicBoolean claimed = new AtomicBoolean();
        final AtomicBoolean started = new AtomicBoolean();
        final Bound bound;
        try {
            if (deadline > 0) {
                // The wait for a slot came out of the caller's time.
                final long left = deadline - SystemClock.uptimeMillis();
                if (left <= 0) {
                    throw new VmDecode.UnavailableException("deadline passed while waiting for a call thread");
                }
                connectTimeout = Math.min(connectTimeout, left);
            }
            // A connect wait that the caller's own deadline cut short says nothing about the service.
            bound = connect(connectTimeout, connectTimeout >= (waiting
                    ? CONNECT_TIMEOUT_USER_WAITING_MS : CONNECT_TIMEOUT_BACKGROUND_MS));
        } catch (VmDecode.UnavailableException | RuntimeException e) {
            claimed.set(true);
            releaseSlot(lane);
            if (e instanceof VmDecode.UnavailableException) {
                suspect(); // no service to call: down, gone or not connecting
            }
            throw e;
        }
        long callTimeout = defaultCallTimeout;
        if (deadline > 0) {
            callTimeout = Math.min(callTimeout, Math.max(1, deadline - SystemClock.uptimeMillis()));
        }
        final long callStart = SystemClock.uptimeMillis();
        // 0 running, 1 ended, 2 given up while it ran (a stuck call, counted until it ends).
        final java.util.concurrent.atomic.AtomicInteger state = new java.util.concurrent.atomic.AtomicInteger();
        final Future<T> future;
        try {
            future = mPool.submit(lane, isSystemLane(lane), () -> {
                if (!claimed.compareAndSet(false, true)) {
                    return null; // given up before it started
                }
                started.set(true);
                boolean answered = false;
                try {
                    final T result = call.run(bound.service);
                    answered = true;
                    return result;
                } finally {
                    releaseSlot(lane);
                    if (state.getAndSet(1) == 2) {
                        stuckEnded(bound.generation, lane);
                        if (answered) {
                            // Answered after its caller gave up: what was left out for it may be asked for again.
                            VmDecode.lateAnswer();
                        }
                    }
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
            mPool.cancel(future); // its place in the fair queue goes too
            // A missed deadline alone says nothing about the service: a hostile image may simply be slow. The binding
            // is dropped when the service cannot serve: most of the call threads are stuck in calls of this binding
            // past their deadlines, or the VM itself does not answer a probe of a tiny image this client made
            // (checkHealth, in the background, one at a time per binding). The probe is asked after a call that
            // missed its full deadline, or after calls of two different apps that were in flight together and both
            // missed a deadline of at least WEDGE_MIN_DEADLINE_MS within WEDGE_WINDOW_MS. A dropped binding is not
            // asked again for WEDGED_RETRY_MS. Calls without an app (SystemUI's own image loader, the wallpaper)
            // never count as an app of their own, and the two-app rule waits until the service of a binding has
            // answered once (a VM still starting is slow, not wedged). Only the binding the call was made on is ever
            // dropped. A call that only waited in the queue ends that call.
            if (started.get()) {
                final int stuck = state.compareAndSet(0, 2) ? stuckStarted(bound.generation, lane) : 0;
                boolean suspect = false;
                if (callTimeout >= defaultCallTimeout) {
                    suspect = true;
                } else if (callTimeout >= WEDGE_MIN_DEADLINE_MS && !VmDecode.isUnknownOwner(lane)) {
                    synchronized (mLock) {
                        if (mWarm && bound.generation == mGeneration) {
                            final long now = SystemClock.uptimeMillis();
                            suspect = mLastMissOwner != null && !mLastMissOwner.equals(lane)
                                    && now - mLastMissAt <= WEDGE_WINDOW_MS
                                    // In flight together: this call started before the other one ended.
                                    && callStart < mLastMissAt;
                            mLastMissOwner = suspect ? null : lane;
                            mLastMissAt = now;
                        }
                    }
                }
                if (stuck >= MAX_THREADS - 1) {
                    // The owners whose calls are stuck are turned away first, and the VM itself is asked: only when
                    // it does not answer is the binding dropped for everyone.
                    Log.w(TAG, "decode calls stuck in the service, their owners are turned away for a while");
                    backOffStuckOwners(bound.generation);
                    checkHealth(bound);
                } else if (suspect) {
                    checkHealth(bound);
                }
                suspect(); // the call ran in the service and got no answer in time
            }
            throw new VmDecode.UnavailableException("decode service missed the deadline", e);
        } catch (InterruptedException e) {
            if (claimed.compareAndSet(false, true)) {
                releaseSlot(lane);
            }
            future.cancel(true);
            mPool.cancel(future); // its place in the fair queue goes too
            if (started.get()) {
                // The caller gave up while the call ran in the service: that it got no answer up to then is worth
                // checking (a caller's own timeout often ends the wait first).
                suspect();
            }
            Thread.currentThread().interrupt();
            throw new VmDecode.UnavailableException("interrupted", e);
        } catch (ExecutionException e) {
            final Throwable cause = e.getCause();
            if (cause instanceof VmDecode.RejectedException) {
                throw (VmDecode.RejectedException) cause;
            }
            if (cause instanceof VmDecode.UnavailableException
                    && ((VmDecode.UnavailableException) cause).isNoRoom()) {
                throw (VmDecode.UnavailableException) cause; // an answer of a working service, nothing to check
            }
            // The service said it cannot decode now (its VM is down), or the binder call failed (the service died).
            suspect();
            if (cause instanceof VmDecode.UnavailableException) {
                throw (VmDecode.UnavailableException) cause;
            }
            throw new VmDecode.UnavailableException("decode call failed", cause);
        }
    }

    // Calls of a binding that missed their deadlines and still hold a call thread, guarded by mLock.
    private int mStuckGeneration = -1;
    private int mStuckCalls;

    private int stuckStarted(int generation, String owner) {
        final int stuck;
        synchronized (mLock) {
            if (mStuckGeneration != generation) {
                mStuckGeneration = generation;
                mStuckCalls = 0;
                mStuckOwners.clear();
            }
            mStuckOwners.merge(owner, 1, Integer::sum);
            stuck = ++mStuckCalls;
        }
        // Calls of this owner that wait for a slot stop waiting (outside mLock: OwnerSlots asks it).
        mOwnerCalls.wake();
        return stuck;
    }

    private void backOffStuckOwners(int generation) {
        final java.util.List<String> owners;
        synchronized (mLock) {
            if (mStuckGeneration != generation) {
                return;
            }
            owners = new java.util.ArrayList<>(mStuckOwners.keySet());
        }
        final long until = SystemClock.uptimeMillis() + BACK_OFF_MS;
        synchronized (mBackedOff) {
            for (String owner : owners) {
                if (!owner.isEmpty()) {
                    mBackedOff.put(owner, until);
                }
            }
        }
    }

    private void stuckEnded(int generation, String owner) {
        synchronized (mLock) {
            if (mStuckGeneration == generation && mStuckCalls > 0) {
                mStuckCalls--;
                final int left = mStuckOwners.getOrDefault(owner, 1) - 1;
                if (left <= 0) {
                    mStuckOwners.remove(owner);
                } else {
                    mStuckOwners.put(owner, left);
                }
            }
        }
    }

    private void dropWedged(int generation) {
        synchronized (mLock) {
            if (generation != mGeneration) {
                return;
            }
            mDownUntil = SystemClock.uptimeMillis() + WEDGED_RETRY_MS;
        }
        unbind(generation);
    }

    // The binding whose health is being checked, guarded by mLock: one check per binding at a time, a check of an
    // older binding does not hold up one of the current.
    private int mHealthGeneration = -1;
    private boolean mHealthRunning;
    private static final long PROBE_MS = 3_000;
    private static final String HEALTH_OWNER = "de.diamaneos.decode.health";
    private static volatile byte[] sHealthPng;

    // In the background, never on the caller's thread: asks the VM for the header of a one pixel PNG this process
    // wrote itself, through the same path as decode jobs (the service hands it to the VM). No answer within
    // PROBE_MS, a failed binder call or an answer that the VM is not available: the binding is dropped.
    private void checkHealth(Bound bound) {
        synchronized (mLock) {
            if (bound.generation != mGeneration
                    || (mHealthRunning && mHealthGeneration == bound.generation)) {
                return;
            }
            mHealthGeneration = bound.generation;
            mHealthRunning = true;
        }
        try {
            final Thread health = new Thread(() -> {
                try {
                    if (!vmAnswers(bound)) {
                        Log.w(TAG, "decode VM does not answer, binding dropped");
                        dropWedged(bound.generation);
                    }
                } finally {
                    healthDone(bound.generation);
                }
            }, "VmDecodeHealth");
            health.setDaemon(true);
            health.start();
        } catch (Throwable e) {
            healthDone(bound.generation);
        }
    }

    private void healthDone(int generation) {
        synchronized (mLock) {
            if (mHealthGeneration == generation) {
                mHealthRunning = false;
            }
        }
    }

    private boolean vmAnswers(Bound bound) {
        final java.util.concurrent.CompletableFuture<Boolean> answered =
                new java.util.concurrent.CompletableFuture<>();
        // Set when the wait for the answer gave up: only then is the probe's end news for the recovery watch.
        final AtomicBoolean gaveUp = new AtomicBoolean();
        try {
            final Thread probe = new Thread(() -> {
                android.os.ParcelFileDescriptor read = null;
                try {
                    final android.os.ParcelFileDescriptor[] pipe = android.os.ParcelFileDescriptor.createPipe();
                    read = pipe[0];
                    try (java.io.OutputStream out =
                            new android.os.ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])) {
                        out.write(healthPng());
                    }
                    final de.diamaneos.sanitizer.decode.DecodeRequest request =
                            new de.diamaneos.sanitizer.decode.DecodeRequest();
                    request.file = read;
                    request.ownerPackage = HEALTH_OWNER;
                    // The client's own test image: of this process's user.
                    request.ownerUser = android.os.UserHandle.myUserId();
                    request.priority = IDecodeService.PRIORITY_USER_WAITING;
                    request.mimeTypeHint = "image/png";
                    final de.diamaneos.sanitizer.decode.ImageInfo info = bound.service.probe(request);
                    answered.complete(info != null && info.status != IDecodeService.STATUS_UNAVAILABLE);
                } catch (Throwable e) {
                    answered.complete(false);
                } finally {
                    if (read != null) {
                        try {
                            read.close();
                        } catch (java.io.IOException ignored) {
                            // Nothing to do.
                        }
                    }
                    // A probe stuck in a stopped service ends when the service answers or dies: the recovery watch
                    // looks again then.
                    mProbesRunning.decrementAndGet();
                    if (gaveUp.get()) {
                        recoveryEvent();
                    }
                }
            }, "VmDecodeProbe");
            probe.setDaemon(true);
            mProbesRunning.incrementAndGet();
            try {
                probe.start();
            } catch (Throwable e) {
                mProbesRunning.decrementAndGet();
                throw e;
            }
            try {
                return answered.get(PROBE_MS, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                gaveUp.set(true);
                if (answered.isDone() && mProbesRunning.get() == 0) {
                    recoveryEvent(); // it ended just now
                }
                return false;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    // A one pixel PNG written here from pixels, never decoded here.
    private static byte[] healthPng() {
        byte[] png = sHealthPng;
        if (png == null) {
            final android.graphics.Bitmap one =
                    android.graphics.Bitmap.createBitmap(1, 1, android.graphics.Bitmap.Config.ARGB_8888);
            final java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            one.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
            one.recycle();
            png = out.toByteArray();
            sHealthPng = png;
        }
        return png;
    }

    // An owner with nothing in flight always finds a call thread: the others may fill all but the last one. An owner
    // over its share waits for its turn until waitEnd (uptime), unless a call of its own is stuck in the service
    // (OwnerSlots). An owner that held up calls of this binding until the binding looked wedged is turned away for a
    // while.
    private void claimSlot(String lane, long waitEnd) throws VmDecode.UnavailableException {
        synchronized (mBackedOff) {
            final Long backedOff = mBackedOff.get(lane);
            if (backedOff != null) {
                if (SystemClock.uptimeMillis() < backedOff) {
                    throw new VmDecode.UnavailableException("decodes of " + lane + " held the service up, later");
                }
                mBackedOff.remove(lane);
            }
        }
        final String refused = mOwnerCalls.claim(lane, () -> hasStuckCalls(lane), waitEnd);
        if (refused != null) {
            throw new VmDecode.UnavailableException(refused);
        }
    }

    // Whether a call of {@code lane} is stuck in the service past its deadline.
    private boolean hasStuckCalls(String lane) {
        synchronized (mLock) {
            return mStuckOwners.getOrDefault(lane, 0) > 0;
        }
    }

    // Owners turned away until a time (uptime), guarded by itself.
    private final Map<String, Long> mBackedOff = new HashMap<>();
    private static final long BACK_OFF_MS = 30_000;
    // The owners of the calls stuck in the current binding, guarded by mLock.
    private final Map<String, Integer> mStuckOwners = new HashMap<>();

    // The recovery watch. A call that failed for the service (no connection, no answer in time, the service's own
    // "unavailable", a dead binder) makes it suspect, and a watcher thread asks the VM for the health image. A "no
    // room now" answer does not: the service works (VmDecode.noRoom). When the VM does not answer, the service is
    // down until it answers again: then VmDecode tells everyone who showed a placeholder meanwhile
    // (VmDecode.addRecoveryListener), so what is blank is asked for again with no user action. A probe that answers
    // at once (a slow image, not the service) ends the suspicion and tells the state listeners only. The watcher
    // waits for events between probes: a connection made, a probe stuck in a stopped service that ended (it ends when
    // the service answers or dies). A wait that grows up to RECOVERY_MAX_MS is the last resort,
    // and after RECOVERY_TRIES probes the watch ends: the next call that the service answers ends the trouble then.
    private final Object mRecovery = new Object();
    private boolean mDown; // a probe failed and nothing answered since, guarded by mRecovery
    private boolean mWatching; // guarded by mRecovery
    private int mRecoveryEvents; // guarded by mRecovery
    private final java.util.concurrent.atomic.AtomicInteger mProbesRunning =
            new java.util.concurrent.atomic.AtomicInteger();
    private static final long RECOVERY_FIRST_MS = 1_000;
    private static final long RECOVERY_MAX_MS = 30_000;
    private static final int RECOVERY_TRIES = 20;

    /** True while the service is known not to answer (a probe failed and nothing answered since). */
    boolean isDown() {
        synchronized (mRecovery) {
            return mDown;
        }
    }

    /** True while the service is down or a failed call is being checked. */
    boolean isSuspect() {
        synchronized (mRecovery) {
            return mDown || mWatching;
        }
    }

    // A call failed for the service: the watch starts, unless it runs.
    private void suspect() {
        synchronized (mRecovery) {
            if (mWatching) {
                return; // the watch runs: more failures are no news
            }
            mWatching = true;
        }
        try {
            final Thread watch = new Thread(this::watch, "VmDecodeRecovery");
            watch.setDaemon(true);
            watch.start();
        } catch (Throwable e) {
            synchronized (mRecovery) {
                mWatching = false;
            }
        }
    }

    private void recoveryEvent() {
        synchronized (mRecovery) {
            mRecoveryEvents++;
            mRecovery.notifyAll();
        }
    }

    // The service answered a call: a service that was down is back.
    private void answered() {
        final boolean wasDown;
        synchronized (mRecovery) {
            wasDown = mDown;
            mDown = false;
        }
        if (wasDown) {
            try {
                final Thread back = new Thread(() -> back(true), "VmDecodeBack");
                back.setDaemon(true);
                back.start();
            } catch (Throwable e) {
                back(true);
            }
        }
    }

    private void watch() {
        long wait = RECOVERY_FIRST_MS;
        int tries = 0;
        long lastProbe = 0;
        while (true) {
            // Probes are at least RECOVERY_FIRST_MS apart, whatever the events.
            final long gap = lastProbe + RECOVERY_FIRST_MS - SystemClock.uptimeMillis();
            if (lastProbe != 0 && gap > 0) {
                SystemClock.sleep(gap);
            }
            lastProbe = SystemClock.uptimeMillis();
            final Boolean answers = probeForRecovery();
            final boolean wasDown;
            synchronized (mRecovery) {
                if (answers == null) {
                    // A probe is still running: its end is the next event.
                    final int seen = mRecoveryEvents;
                    final long end = SystemClock.uptimeMillis() + RECOVERY_MAX_MS;
                    long left;
                    while (mRecoveryEvents == seen && mProbesRunning.get() > 0
                            && (left = end - SystemClock.uptimeMillis()) > 0) {
                        try {
                            mRecovery.wait(left);
                        } catch (InterruptedException e) {
                            mWatching = false;
                            return;
                        }
                    }
                    continue;
                }
                if (answers) {
                    wasDown = mDown;
                    mDown = false;
                    mWatching = false;
                } else {
                    mDown = true;
                    if (++tries >= RECOVERY_TRIES) {
                        mWatching = false;
                        return;
                    }
                    final int seen = mRecoveryEvents;
                    final long end = SystemClock.uptimeMillis() + wait;
                    long left;
                    while (mRecoveryEvents == seen && (left = end - SystemClock.uptimeMillis()) > 0) {
                        try {
                            mRecovery.wait(left);
                        } catch (InterruptedException e) {
                            mWatching = false;
                            return;
                        }
                    }
                    wait = Math.min(wait * 2, RECOVERY_MAX_MS);
                    continue;
                }
            }
            back(wasDown);
            return;
        }
    }

    // The service answers again. After it was down, owners turned away for stuck calls of the old service may call
    // again, and VmDecode tells its listeners.
    private void back(boolean wasDown) {
        if (wasDown) {
            synchronized (mBackedOff) {
                mBackedOff.clear();
            }
            synchronized (mLock) {
                mLastMissOwner = null;
            }
            Log.i(TAG, "decode service answers again");
        }
        VmDecode.serviceAnswers(wasDown);
    }

    // One probe of the VM for the watch: whether it answered, or null when an earlier probe (of the watch or of a
    // health check) still runs, whose end is an event.
    private Boolean probeForRecovery() {
        if (mProbesRunning.get() > 0) {
            return null;
        }
        final Bound bound;
        try {
            bound = connect(CONNECT_TIMEOUT_BACKGROUND_MS, true);
        } catch (VmDecode.UnavailableException | RuntimeException e) {
            return false;
        }
        return vmAnswers(bound);
    }

    // The system server's own calls (no owner): served by a thread of their own.
    private static boolean isSystemLane(String lane) {
        return SYSTEM_PROCESS && lane.isEmpty();
    }

    private void releaseSlot(String lane) {
        mOwnerCalls.release(lane);
    }

    // After a bind or connect that failed, calls fail at once until this time (uptime), so a VM that is down costs
    // nothing per image instead of the whole wait every time. A connection that comes up clears it.
    private static final long DOWN_RETRY_MS = 10_000;
    // After a connect wait that a caller's short deadline cut off.
    private static final long SHORT_DOWN_MS = 1_500;
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
        int staleNow = -1;
        synchronized (mLock) {
            if (mService != null && mService.asBinder().isBinderAlive()) {
                return new Bound(mService, mGeneration);
            }
            mService = null;
            if (SystemClock.uptimeMillis() < mDownUntil) {
                throw new VmDecode.UnavailableException("decode service is down, not asked again yet");
            }
            if (mBinding && SystemClock.uptimeMillis() - mBindStart >= STALE_BIND_MS) {
                // A binding that never connected: dropped here, whoever asks, and bound anew by the next call.
                staleNow = mGeneration;
            } else if (mBinding && !fullWait) {
                // Another caller is binding: a caller with a short deadline does not spend it waiting for the
                // connection, it fails at once (its image is made by a later call, for callers that ask again).
                throw new VmDecode.UnavailableException("decode service is connecting");
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
        if (staleNow >= 0) {
            unbind(staleNow);
            throw new VmDecode.UnavailableException("decode service never connected, bound anew next time");
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
                    // Calls fail at once for a while: the whole DOWN_RETRY_MS after a full wait, a short window
                    // after a wait the caller's deadline cut short.
                    mDownUntil = SystemClock.uptimeMillis() + (fullWait ? DOWN_RETRY_MS : SHORT_DOWN_MS);
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
