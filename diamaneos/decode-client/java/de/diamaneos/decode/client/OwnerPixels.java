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
 * cannot fill SystemUI's memory. Plain Java, tested on the host. Thread-safe.
 */
public final class OwnerPixels {
    /** Pixels one app may hold at once (64 MB in ARGB), four notifications at their full cap. */
    public static final long MAX_PIXELS = 4 * ImageBudget.MAX_PIXELS;
    private static final int MAX_OWNERS = 256;

    private final long mMax;
    // Per app: pixels per live holder. Holders are weak, so a forgotten one never keeps memory charged for long.
    private final Map<String, WeakHashMap<Object, long[]>> mHeld = new HashMap<>(); // guarded by this

    public OwnerPixels() {
        this(MAX_PIXELS);
    }

    public OwnerPixels(long maxPixels) {
        mMax = Math.max(0, maxPixels);
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
        if (sum(holders) + pixels > mMax) {
            return false;
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
