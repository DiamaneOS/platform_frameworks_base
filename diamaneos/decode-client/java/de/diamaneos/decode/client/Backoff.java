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

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * When to try again something that failed: the first retry after {@link #FIRST_MS}, each further failure doubles the
 * wait up to {@link #MAX_MS}, and a success forgets it. Used for an image that could not be made (the VM was not up
 * yet, the app's quota was spent) and for a decode service that did not connect. Plain Java with the clock passed in,
 * tested on the host. Thread-safe.
 */
public final class Backoff {
    /** The first wait after a failure. */
    public static final long FIRST_MS = 2_000;
    /** The longest wait. */
    public static final long MAX_MS = 60_000;
    private static final int MAX_KEYS = 512;

    private final long mFirstMs;
    private final long mMaxMs;
    // Key -> {failures, retry at}. In order of the last failure, oldest first.
    private final Map<String, long[]> mFailed = new LinkedHashMap<>(16, 0.75f, true); // guarded by this
    // Keys being worked on now: not started a second time.
    private final Map<String, Boolean> mRunning = new LinkedHashMap<>(); // guarded by this

    public Backoff() {
        this(FIRST_MS, MAX_MS);
    }

    public Backoff(long firstMs, long maxMs) {
        mFirstMs = Math.max(0, firstMs);
        mMaxMs = Math.max(mFirstMs, maxMs);
    }

    /** True when {@code key} may be tried at {@code nowMs}: never failed, or its wait is over, and not running. */
    public synchronized boolean mayTry(String key, long nowMs) {
        if (mRunning.containsKey(key)) {
            return false;
        }
        final long[] failed = mFailed.get(key);
        return failed == null || nowMs >= failed[1];
    }

    /** Like {@link #mayTry}, and when it may, marks it running until {@link #succeeded} or {@link #failed}. */
    public synchronized boolean start(String key, long nowMs) {
        if (!mayTry(key, nowMs)) {
            return false;
        }
        if (mRunning.size() >= MAX_KEYS) {
            // Something never reported back: forget the oldest rather than block it for good.
            mRunning.remove(mRunning.keySet().iterator().next());
        }
        mRunning.put(key, Boolean.TRUE);
        return true;
    }

    /** {@code key} worked: tried at once next time. */
    public synchronized void succeeded(String key) {
        mRunning.remove(key);
        mFailed.remove(key);
    }

    /**
     * {@code key} failed at {@code nowMs}: the next try waits, longer after each failure. Returns that wait, so the
     * caller can schedule the next try (nothing here schedules it).
     */
    public synchronized long failed(String key, long nowMs) {
        mRunning.remove(key);
        long[] failed = mFailed.get(key);
        if (failed == null) {
            if (mFailed.size() >= MAX_KEYS) {
                mFailed.remove(mFailed.keySet().iterator().next());
            }
            failed = new long[2];
            mFailed.put(key, failed);
        }
        failed[0]++;
        long wait = mFirstMs;
        for (long i = 1; i < failed[0] && wait < mMaxMs; i++) {
            wait *= 2;
        }
        failed[1] = nowMs + Math.min(mMaxMs, wait);
        return failed[1] - nowMs;
    }

    /** How often {@code key} failed in a row so far. */
    public synchronized int failures(String key) {
        final long[] failed = mFailed.get(key);
        return failed == null ? 0 : (int) Math.min(Integer.MAX_VALUE, failed[0]);
    }

    /** Forgets every failure: everything may be tried at once (the decode service came up). */
    public synchronized void reset() {
        mFailed.clear();
    }
}
