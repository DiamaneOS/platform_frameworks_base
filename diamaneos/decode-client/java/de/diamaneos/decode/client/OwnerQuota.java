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
 * Time each app may make SystemUI wait for the VM across its notifications: a bucket per app that holds at most
 * {@link #BURST_MS} and refills at {@link #REFILL_PER_SECOND_MS} per second. An app that posts or updates
 * notifications with new images faster than that gets empty images until its bucket refills, so it cannot keep the
 * image threads busy for other apps. Plain Java with the clock passed in, tested on the host. Thread-safe.
 */
public final class OwnerQuota {
    /** The most waiting one app may spend at once (two notifications at their full budget). */
    public static final long BURST_MS = 4_000;
    /** Waiting given back per second: one app keeps at most a fifth of an image thread busy over time. */
    public static final long REFILL_PER_SECOND_MS = 200;
    private static final int MAX_OWNERS = 256;

    private final long mBurstMs;
    private final long mRefillPerSecondMs;
    private final Map<String, long[]> mBuckets = new HashMap<>(); // owner -> {left ms, last uptime ms}

    public OwnerQuota() {
        this(BURST_MS, REFILL_PER_SECOND_MS);
    }

    public OwnerQuota(long burstMs, long refillPerSecondMs) {
        mBurstMs = Math.max(0, burstMs);
        mRefillPerSecondMs = Math.max(0, refillPerSecondMs);
    }

    /** The waiting {@code owner} may still spend at {@code nowMs} (an uptime clock). */
    public synchronized long available(String owner, long nowMs) {
        return refill(key(owner), nowMs)[0];
    }

    /** Takes {@code ms} of waiting off {@code owner}'s bucket. */
    public synchronized void spend(String owner, long ms, long nowMs) {
        final long[] bucket = refill(key(owner), nowMs);
        bucket[0] = Math.max(0, bucket[0] - Math.max(0, ms));
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
        bucket[0] = Math.min(mBurstMs, bucket[0] + elapsed * mRefillPerSecondMs / 1000);
        bucket[1] = nowMs;
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
