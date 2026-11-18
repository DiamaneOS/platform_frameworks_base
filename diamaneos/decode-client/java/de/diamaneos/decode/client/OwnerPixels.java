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
import java.util.WeakHashMap;

/**
 * Decoded pixels each app holds in SystemUI across all its notifications at once: every live budget (one per shown
 * round of a notification's images) adds what it charged. A budget that is replaced gives its pixels back
 * ({@link ImageBudget#release}), and one that is no longer reachable (a removed notification) drops out by itself.
 * An app with many notifications full of large images gets empty images once it holds {@link #MAX_PIXELS}, so it
 * cannot fill SystemUI's memory. All apps together hold at most {@link #MAX_TOTAL_PIXELS}: past that an app still
 * gets its fair share (the total split over the apps that hold pixels), so several apps cannot fill it together and
 * none is shut out. Plain Java, tested on the host. Thread-safe.
 */
public final class OwnerPixels {
    /** Pixels one app may hold at once (64 MB in ARGB), four notifications at their full cap. */
    public static final long MAX_PIXELS = 4 * ImageBudget.MAX_PIXELS;
    /** Pixels all apps together may hold at once (128 MB in ARGB). */
    public static final long MAX_TOTAL_PIXELS = 2 * MAX_PIXELS;
    private static final int MAX_OWNERS = 256;

    private final long mMax;
    private final long mMaxTotal;
    // Per app: pixels per live holder. Holders are weak, so a forgotten one never keeps memory charged for long.
    private final Map<String, WeakHashMap<Object, long[]>> mHeld = new HashMap<>(); // guarded by this

    public OwnerPixels() {
        this(MAX_PIXELS, MAX_TOTAL_PIXELS);
    }

    public OwnerPixels(long maxPixels) {
        this(maxPixels, Long.MAX_VALUE);
    }

    /** {@code maxPixels} per app, {@code maxTotalPixels} for all apps together (each keeps a fair share of it). */
    public OwnerPixels(long maxPixels, long maxTotalPixels) {
        mMax = Math.max(0, maxPixels);
        mMaxTotal = Math.max(0, maxTotalPixels);
    }

    /**
     * Adds {@code pixels} (negative to give back) held by {@code holder} for {@code owner}. False, and nothing added,
     * when the app would hold more than the cap.
     */
    public synchronized boolean add(String owner, Object holder, long pixels) {
        final String key = owner == null ? "" : owner;
        WeakHashMap<Object, long[]> holders = mHeld.get(key);
        if (pixels <= 0) {
            final long[] held = holders == null ? null : holders.get(holder);
            if (held != null) {
                held[0] = Math.max(0, held[0] + pixels);
            }
            return true;
        }
        if (holders == null) {
            if (mHeld.size() >= MAX_OWNERS) {
                prune();
            }
            holders = new WeakHashMap<>();
            mHeld.put(key, holders);
        }
        final long own = sum(holders);
        if (own + pixels > mMax) {
            return false;
        }
        if (mMaxTotal != Long.MAX_VALUE) {
            long total = 0;
            int holding = 0;
            for (Map.Entry<String, WeakHashMap<Object, long[]>> e : mHeld.entrySet()) {
                final long held = e.getKey().equals(key) ? own : sum(e.getValue());
                total += held;
                if (held > 0 || e.getKey().equals(key)) {
                    holding++;
                }
            }
            // Past the total, only within the app's fair share of it.
            if (total + pixels > mMaxTotal && own + pixels > mMaxTotal / Math.max(1, holding)) {
                return false;
            }
        }
        holders.computeIfAbsent(holder, k -> new long[1])[0] += pixels;
        return true;
    }

    /** Everything {@code holder} held for {@code owner} is given back. */
    public synchronized void release(String owner, Object holder) {
        final WeakHashMap<Object, long[]> holders = mHeld.get(owner == null ? "" : owner);
        if (holders != null) {
            holders.remove(holder);
        }
    }

    /** The pixels all apps hold now. */
    public synchronized long total() {
        long total = 0;
        for (WeakHashMap<Object, long[]> holders : mHeld.values()) {
            total += sum(holders);
        }
        return total;
    }

    /** The pixels {@code owner} holds now. */
    public synchronized long held(String owner) {
        final WeakHashMap<Object, long[]> holders = mHeld.get(owner == null ? "" : owner);
        return holders == null ? 0 : sum(holders);
    }

    private static long sum(WeakHashMap<Object, long[]> holders) {
        long total = 0;
        for (long[] held : holders.values()) {
            total += held[0];
        }
        return total;
    }

    // Apps that hold nothing carry no state: dropped when the map grows.
    private void prune() {
        for (Iterator<WeakHashMap<Object, long[]>> it = mHeld.values().iterator(); it.hasNext(); ) {
            if (sum(it.next()) == 0) {
                it.remove();
            }
        }
    }
}
