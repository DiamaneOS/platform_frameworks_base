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

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Visible consumers with images left out, and the bounded retry turns of each consumer. Ordinary outage demand
 * responds to the service recovery epoch. Capacity demand responds only to its captured lane versions and binding
 * epoch, so an unrelated success or health recovery cannot select it. Keys are weak and demand ends when its
 * consumer gets its images or disappears. Plain Java, tested on the host. Thread-safe.
 */
public final class RetryTurns<K> {
    private final int mMax;
    private static final class Demand {
        int turns;
        int wakes;
        int capacitySeen;
        java.util.function.IntSupplier capacity;

        Demand(int wakes, java.util.function.IntSupplier capacity, int observed) {
            this.wakes = wakes;
            this.capacity = capacity;
            capacitySeen = observed;
        }
    }
    private final Map<K, Demand> mWaiting = new WeakHashMap<>(); // guarded by this

    public RetryTurns(int max) {
        mMax = max;
    }

    /** {@code key} left images out (again), with the service's wakes now: it waits for a recovery run. */
    public synchronized void leftOut(K key, int wakes) {
        leftOut(key, wakes, null);
    }

    /** Captures only the lanes whose capacity left this consumer waiting. */
    public synchronized void leftOut(K key, int wakes, java.util.function.IntSupplier capacity) {
        leftOut(key, wakes, capacity, capacity != null ? capacity.getAsInt() : 0);
    }

    /** Keeps events that arrived between a worker's failure and its UI completion. */
    public synchronized void leftOut(K key, int wakes, java.util.function.IntSupplier capacity, int observed) {
        final Demand state = mWaiting.get(key);
        if (state == null) {
            mWaiting.put(key, new Demand(wakes, capacity, observed));
        } else {
            state.capacitySeen = observed;
            state.capacity = capacity;
        }
    }

    /** {@code key} got its images (or is gone): it waits no longer. */
    public synchronized void done(K key) {
        mWaiting.remove(key);
    }

    /**
     * A recovery run with the service's wakes now: the keys to ask again, each counting a turn. Keys out of turns
     * stay waiting for the next genuine change.
     */
    public synchronized List<K> due(int wakes) {
        final List<K> due = new ArrayList<>();
        for (Iterator<Map.Entry<K, Demand>> it = mWaiting.entrySet().iterator(); it.hasNext(); ) {
            final Map.Entry<K, Demand> e = it.next();
            final Demand state = e.getValue();
            final int capacity = state.capacity != null ? state.capacity.getAsInt() : 0;
            final boolean changed = state.capacity != null ? capacity != state.capacitySeen : state.wakes != wakes;
            if (state.capacity != null && !changed) continue;
            if (changed) {
                state.turns = 0;
                state.wakes = wakes;
                state.capacitySeen = capacity;
            }
            if (state.turns < mMax) {
                state.turns++;
                due.add(e.getKey());
            }
        }
        return due;
    }

    /** Turns {@code key} took since the last genuine change, or -1 when it is not waiting. */
    public synchronized int turns(K key) {
        final Demand state = mWaiting.get(key);
        return state == null ? -1 : state.turns;
    }
}
