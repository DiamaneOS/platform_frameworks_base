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

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Time each app may keep image work busy across its notifications: a bucket per app that holds at most
 * {@link #BURST_MS} and refills at {@link #REFILL_PER_SECOND_MS} per second. Work first {@link #reserve}s its time,
 * then {@link #settle}s what it really took: a task that ran longer than it reserved leaves the app in debt, down to
 * {@link #DEBT_FLOOR_MS}, and the app gets no time until it has waited the debt out. An app that posts or updates
 * notifications with new images faster than that gets empty images, so it cannot keep the image threads busy for
 * other apps. The same class holds a shared bucket for everyone (a system-wide limit). Plain Java with the clock
 * passed in, tested on the host. Thread-safe.
 */
public final class OwnerQuota {
    /** The most time one app may spend at once (two notifications at their full budget). */
    public static final long BURST_MS = 4_000;
    /** Time given back per second: one app keeps at most a fifth of an image thread busy over time. */
    public static final long REFILL_PER_SECOND_MS = 200;
    /** The deepest debt: work that overran its time is paid back by waiting, up to this much. */
    public static final long DEBT_FLOOR_MS = -10_000;
    private static final int MAX_OWNERS = 256;

    private final long mBurstMs;
    private final long mRefillPerSecondMs;
    private final long mFloorMs;
    private final Map<String, long[]> mBuckets = new HashMap<>(); // owner -> {left ms, last refill ms}

    public OwnerQuota() {
        this(BURST_MS, REFILL_PER_SECOND_MS);
    }

    public OwnerQuota(long burstMs, long refillPerSecondMs) {
        this(burstMs, refillPerSecondMs, DEBT_FLOOR_MS);
    }

    public OwnerQuota(long burstMs, long refillPerSecondMs, long floorMs) {
        mBurstMs = Math.max(0, burstMs);
        mRefillPerSecondMs = Math.max(0, refillPerSecondMs);
        mFloorMs = Math.min(0, floorMs);
    }

    /** The time {@code owner} may still spend at {@code nowMs} (a monotonic clock), 0 while in debt. */
    public synchronized long available(String owner, long nowMs) {
        return Math.max(0, refill(key(owner), nowMs)[0]);
    }

    /** The bucket itself, negative while in debt. For tests. */
    public synchronized long balance(String owner, long nowMs) {
        return refill(key(owner), nowMs)[0];
    }

    /**
     * Takes up to {@code wantMs} off {@code owner}'s bucket before the work starts and returns what was taken (0 while
     * the app is in debt). Pass the result to {@link #settle} when the work is done.
     */
    public synchronized long reserve(String owner, long wantMs, long nowMs) {
        final long[] bucket = refill(key(owner), nowMs);
        final long granted = Math.max(0, Math.min(Math.max(0, wantMs), bucket[0]));
        bucket[0] -= granted;
        return granted;
    }

    /**
     * The work that reserved {@code reservedMs} took {@code usedMs}: the difference is given back, or taken as debt
     * down to the floor.
     */
    public synchronized void settle(String owner, long reservedMs, long usedMs, long nowMs) {
        final long[] bucket = refill(key(owner), nowMs);
        bucket[0] = clamp(bucket[0] + Math.max(0, reservedMs) - Math.max(0, usedMs));
    }

    /** Takes {@code ms} off {@code owner}'s bucket after the fact, down to the floor. */
    public synchronized void spend(String owner, long ms, long nowMs) {
        settle(owner, 0, ms, nowMs);
    }

    private long clamp(long left) {
        return Math.max(mFloorMs, Math.min(mBurstMs, left));
    }

    private long[] refill(String owner, long nowMs) {
        long[] bucket = mBuckets.get(owner);
        if (bucket == null) {
            if (mBuckets.size() >= MAX_OWNERS) {
                prune(nowMs);
            }
            bucket = new long[] {mBurstMs, nowMs};
            mBuckets.put(owner, bucket);
            return bucket;
        }
        final long elapsed = Math.max(0, nowMs - bucket[1]);
        if (bucket[0] >= mBurstMs || mRefillPerSecondMs == 0) {
            bucket[1] = nowMs;
            return bucket;
        }
        final long gained = elapsed * mRefillPerSecondMs / 1000;
        if (gained > 0) {
            bucket[0] = Math.min(mBurstMs, bucket[0] + gained);
            // Only the time that bought whole milliseconds is used up: frequent calls never lose the refill.
            bucket[1] = bucket[0] >= mBurstMs ? nowMs : bucket[1] + gained * 1000 / mRefillPerSecondMs;
        }
        return bucket;
    }

    // Full buckets carry no state: dropped when the map grows.
    private void prune(long nowMs) {
        for (Iterator<Map.Entry<String, long[]>> it = mBuckets.entrySet().iterator(); it.hasNext(); ) {
            final long[] bucket = it.next().getValue();
            final long elapsed = Math.max(0, nowMs - bucket[1]);
            if (bucket[0] + elapsed * mRefillPerSecondMs / 1000 >= mBurstMs) {
                it.remove();
            }
        }
    }

    private static String key(String owner) {
        return owner == null ? "" : owner;
    }
}
