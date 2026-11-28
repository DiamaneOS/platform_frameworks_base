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

package android.graphics;

import android.annotation.NonNull;
import android.os.SystemClock;

import java.util.HashMap;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * DiamaneOS: the way back for an image shown as a placeholder. A visible consumer that loads an image through the
 * decode gate (or through its own VM load, as VmIconLoads does) registers here once, and loads inside
 * {@link #load}. Gate calls made inside it that are not answered in time stay tied to it: once one is answered, its
 * answer is held for this registration (also one too large for the gate's cache) and the consumer's reload runs on its
 * executor, so it loads again and finds the answer. A file it reads on a thread that must not wait is read to its end
 * after the call returned and kept for it ({@link GateInput}), so the reload need not read it again. A failure of
 * the moment (no VM yet, no room now, out of time before the call) runs the reload after a growing wait. A refusal of the image itself (broken, unsupported, too large
 * ever) ends it: the placeholder stays. Each attempt stops after {@link #MAX_ROUNDS} reloads or
 * {@link #MAX_AGE_MS}, releasing its held storage. Weak visible demand can begin another attempt on its own slow
 * timer, a binding recovery or relevant memory capacity. Success and {@link #cancel} end it. Thread-safe.
 *
 * @hide
 */
public final class GateRefill {
    /** Reloads at most. */
    public static final int MAX_ROUNDS = 6;
    /** An attempt releases storage and suspends after this long. */
    public static final long MAX_AGE_MS = 60_000;
    /** The waits before a reload after a failure of the moment, growing, the last one repeated. */
    static final long[] RETRY_MS = {1_000, 2_000, 4_000, 8_000};
    // Answers held for this registration, at most.
    private static final int MAX_HELD = 8;

    private static final ThreadLocal<GateRefill> sCurrent = new ThreadLocal<>();
    private static android.os.Handler sTimer; // guarded by GateRefill.class

    private final Executor mExecutor;
    private final Runnable mReload;
    private long mStart = SystemClock.uptimeMillis();
    private boolean mSuspended;
    private long mGeneration;
    private long mNeedBytes;
    private static final java.util.WeakHashMap<GateRefill, Boolean> sDemand = new java.util.WeakHashMap<>();
    private static final java.util.concurrent.atomic.AtomicBoolean sCapacityPosted = new java.util.concurrent.atomic.AtomicBoolean();
    private final Object mLock = new Object();
    // Answers of this registration's calls, by the gate's key. Guarded by mLock.
    private final HashMap<String, DecodeGate.Answer> mHeld = new HashMap<>();
    // Input read for this registration after its caller left, by its source (GateInput). Guarded by mLock.
    private final HashMap<String, DecodeGate.Bytes> mInputs = new HashMap<>();
    private final java.util.WeakHashMap<Object, Long> mInputIds = new java.util.WeakHashMap<>();
    private long mNextInputId;
    private boolean mEnded; // guarded by mLock
    private int mRounds; // reloads run, guarded by mLock
    private int mRetries; // reloads after a failure of the moment, guarded by mLock
    private boolean mReloadDue; // a reload is posted or waiting, guarded by mLock
    // What the load under way found: calls waited for, a failure of the moment, a refusal. Guarded by mLock.
    private int mWaitingCalls;
    private boolean mFailedForNow;
    private boolean mRefused;

    /** A registration whose {@code reload} runs on {@code executor} when what it waits for may be had. */
    public GateRefill(@NonNull Executor executor, @NonNull Runnable reload) {
        mExecutor = executor;
        mReload = reload;
        synchronized (sDemand) { sDemand.put(this, Boolean.TRUE); }
        expires();
    }

    private void expires() {
        final long generation = mGeneration;
        final java.lang.ref.WeakReference<GateRefill> self = new java.lang.ref.WeakReference<>(this);
        timer().postDelayed(() -> {
            final GateRefill refill = self.get();
            if (refill == null) return;
            synchronized (refill.mLock) {
                if (!refill.mEnded && refill.mGeneration == generation) refill.suspendLocked();
            }
        }, MAX_AGE_MS);
    }

    private static java.util.List<GateRefill> demand() {
        synchronized (sDemand) { return new java.util.ArrayList<>(sDemand.keySet()); }
    }

    /** A new binding or verified recovery can restart still-visible weak demand after its bounded attempt. */
    public static void recovered() {
        for (GateRefill refill : demand()) refill.resume(true);
    }

    static void capacityAvailable() {
        if (!sCapacityPosted.compareAndSet(false, true)) return;
        try {
            timer().post(() -> {
                sCapacityPosted.set(false);
                final long available = DecodeMemory.available();
                for (GateRefill refill : demand()) {
                    synchronized (refill.mLock) {
                        if (refill.mNeedBytes <= 0 || refill.mNeedBytes > available) continue;
                        refill.mNeedBytes = 0;
                    }
                    refill.resume(true);
                }
            });
        } catch (RuntimeException | Error e) {
            sCapacityPosted.set(false);
        }
    }

    void needs(long bytes) {
        synchronized (mLock) { mNeedBytes = Math.max(mNeedBytes, bytes); }
    }

    private void resume(boolean recovery) {
        synchronized (mLock) {
            if (mEnded) return;
            if (mSuspended || recovery) {
                mSuspended = false;
                mStart = SystemClock.uptimeMillis();
                mGeneration++;
                mWaitingCalls = 0; // the calls of the older generation no longer count (answered drops them)
                mRounds = 0;
                mRetries = 0;
                mReloadDue = false;
                expires();
            }
        }
        schedule(0);
    }

    /**
     * Runs {@code load} (the consumer's decode) with this registration on the calling thread. Returns what it returns.
     * What it left out is waited for as the class comment says.
     */
    public <T> T load(@NonNull Supplier<T> load) {
        final GateRefill outer = sCurrent.get();
        synchronized (mLock) {
            mFailedForNow = false;
            mRefused = false;
        }
        sCurrent.set(this);
        try {
            return load.get();
        } finally {
            sCurrent.set(outer);
            loadEnded();
        }
    }

    /** A failure of the moment outside the gate (the consumer's own VM load): the reload runs after a wait. */
    public void retryLater() {
        synchronized (mLock) {
            mFailedForNow = true;
        }
        loadEnded();
    }

    /** Ends the registration: no reload runs from now on and held answers and input are dropped. */
    public void cancel() {
        synchronized (mLock) {
            endLocked();
        }
    }

    private void endLocked() {
        mEnded = true;
        mGeneration++;
        clearLocked();
    }

    private void suspendLocked() {
        mSuspended = true;
        mGeneration++;
        mReloadDue = false;
        mWaitingCalls = 0;
        clearLocked();
        final long generation = mGeneration;
        final java.lang.ref.WeakReference<GateRefill> self = new java.lang.ref.WeakReference<>(this);
        timer().postDelayed(() -> {
            final GateRefill refill = self.get();
            if (refill == null) return;
            synchronized (refill.mLock) {
                if (refill.mEnded || !refill.mSuspended || refill.mGeneration != generation) return;
            }
            refill.resume(true);
        }, 5 * 60_000L);
    }

    private void clearLocked() {
        for (DecodeGate.Answer answer : mHeld.values()) answer.release();
        mHeld.clear();
        for (DecodeGate.Bytes bytes : mInputs.values()) {
            bytes.close();
        }
        mInputs.clear();
        mInputIds.clear();
    }

    /** True until the registration ended. */
    public boolean isActive() {
        synchronized (mLock) {
            return !endedLocked() && !mSuspended;
        }
    }

    private boolean endedLocked() {
        if (!mEnded && !mSuspended && SystemClock.uptimeMillis() - mStart > MAX_AGE_MS) {
            suspendLocked();
        }
        return mEnded;
    }

    // The gate: the registration of this thread's load, or null.
    static GateRefill current() {
        final GateRefill refill = sCurrent.get();
        return refill != null && refill.isActive() ? refill : null;
    }

    // The gate: an answer held for this registration under the gate's key, or null.
    DecodeGate.Answer held(String key) {
        synchronized (mLock) {
            final DecodeGate.Answer answer = endedLocked() ? null : mHeld.get(key);
            return answer != null ? answer.retain() : null;
        }
    }

    String inputKey(Object source, String part) {
        synchronized (mLock) {
            Long id = mInputIds.get(source);
            if (id == null) {
                id = ++mNextInputId;
                mInputIds.put(source, id);
            }
            return id + ":" + part;
        }
    }

    // GateInput: the input of {@code source} read for this registration, shared with the caller (null: none).
    DecodeGate.Bytes input(String source) {
        synchronized (mLock) {
            final DecodeGate.Bytes bytes = endedLocked() ? null : mInputs.get(source);
            return bytes != null ? bytes.share() : null;
        }
    }

    // GateInput: the input of {@code source} was read after its caller left (null: it could not be). The read was
    // waited for (waitsFor) and the load runs again.
    void inputRead(String source, DecodeGate.Bytes bytes) {
        inputRead(source, bytes, null);
    }

    void inputRead(String source, DecodeGate.Bytes bytes, DecodeGate.Failure failure) {
        synchronized (mLock) {
            mWaitingCalls = Math.max(0, mWaitingCalls - 1);
            if (bytes == null || endedLocked() || mSuspended || mInputs.size() >= MAX_HELD) {
                if (bytes != null) {
                    bytes.close();
                }
                if (bytes == null && !mEnded) {
                    if (failure == null || failure.again) mFailedForNow = true;
                    else mRefused = true;
                }
            } else {
                try {
                    final DecodeGate.Bytes old;
                    boolean inserted = false;
                    try {
                        old = mInputs.put(source, bytes);
                        inserted = true;
                    } finally {
                        if (!inserted) {
                            // HashMap can fail resizing after it inserted the new entry.
                            if (mInputs.get(source) == bytes) mInputs.remove(source);
                            bytes.close();
                        }
                    }
                    if (old != null) old.close();
                } catch (RuntimeException | OutOfMemoryError e) {
                    bytes = null;
                    mFailedForNow = true;
                }
            }
        }
        if (bytes != null) {
            schedule(0);
        } else {
            loadEnded();
        }
    }

    /** The gate was installed in this process: the timer's thread starts now, not inside a first decode's time. */
    static void start() {
        timer();
    }

    // The timer of the refills, for GateInput's background reads too.
    static android.os.Handler timerForGate() {
        return timer();
    }

    // The gate: a call of this load was not answered in time, its answer comes to answered() with the generation
    // this returns.
    long waitsFor() {
        synchronized (mLock) {
            mWaitingCalls++;
            return mGeneration;
        }
    }

    // The gate: a failure in this load. again: the same call may give the image later.
    void failed(boolean again) {
        synchronized (mLock) {
            if (again) {
                mFailedForNow = true;
            } else {
                mRefused = true;
            }
        }
    }

    // The gate: a call this registration waits for got its answer (pixels, sizes, or an error). {@code generation}
    // is the one waitsFor gave: an answer of an older generation is dropped.
    void answered(String key, DecodeGate.Answer shared, long generation) {
        final android.os.Bundle answer = shared.bundle;
        final boolean reload;
        synchronized (mLock) {
            if (endedLocked() || mSuspended || mGeneration != generation) {
                return;
            }
            mWaitingCalls = Math.max(0, mWaitingCalls - 1);
            final String error = answer.getString(DecodeGate.EXTRA_ERROR);
            if (error != null && answer.getBoolean(DecodeGate.EXTRA_REFUSED)) {
                endLocked(); // The image itself was refused: the placeholder stays.
                return;
            }
            if (error != null) {
                mFailedForNow = true;
                reload = false;
            } else {
                if (mHeld.size() < MAX_HELD || mHeld.containsKey(key)) {
                    shared.retain();
                    final DecodeGate.Answer old;
                    try {
                        old = mHeld.put(key, shared);
                    } catch (RuntimeException | Error e) {
                        if (mHeld.get(key) != shared) shared.release();
                        throw e;
                    }
                    if (old != null) old.release();
                }
                reload = true;
            }
        }
        if (reload) {
            schedule(0);
        } else {
            loadEnded();
        }
    }

    // A load ended (or an answer came): what it found decides what comes next.
    private void loadEnded() {
        final long wait;
        synchronized (mLock) {
            if (endedLocked() || mSuspended || sCurrent.get() == this) {
                return; // Ended, or still loading: decided when the load ends.
            }
            if (mRefused) {
                endLocked();
                return;
            }
            if (mWaitingCalls > 0) {
                return; // An answer is coming: it reloads.
            }
            if (!mFailedForNow) {
                // Nothing left out: the image was had (or the consumer has nothing more to load).
                endLocked();
                return;
            }
            mFailedForNow = false;
            wait = RETRY_MS[Math.min(mRetries, RETRY_MS.length - 1)];
            mRetries++;
        }
        schedule(wait);
    }

    private void schedule(long delayMs) {
        synchronized (mLock) {
            if (endedLocked() || mSuspended || mReloadDue) {
                return;
            }
            if (mRounds >= MAX_ROUNDS) {
                suspendLocked();
                return;
            }
            mRounds++;
            mReloadDue = true;
        }
        final long generation;
        synchronized (mLock) { generation = mGeneration; }
        timer().postDelayed(() -> {
            synchronized (mLock) {
                if (mGeneration != generation) return;
                mReloadDue = false;
                if (endedLocked() || mSuspended) {
                    return;
                }
            }
            try {
                mExecutor.execute(() -> {
                    synchronized (mLock) {
                        if (mEnded || mSuspended || mGeneration != generation) return;
                    }
                    mReload.run();
                });
            } catch (RuntimeException | OutOfMemoryError e) {
                cancel();
            }
        }, Math.max(0, delayMs));
    }

    private static synchronized android.os.Handler timer() {
        if (sTimer == null) {
            final android.os.HandlerThread thread = new android.os.HandlerThread("GateRefill",
                    android.os.Process.THREAD_PRIORITY_BACKGROUND);
            thread.start();
            sTimer = new android.os.Handler(thread.getLooper());
        }
        return sTimer;
    }

    /** Tests: reloads run so far. */
    public int roundsForTest() {
        synchronized (mLock) {
            return mRounds;
        }
    }
}
