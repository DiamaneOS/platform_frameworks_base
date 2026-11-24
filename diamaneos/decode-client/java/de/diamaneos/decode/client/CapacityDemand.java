/* Copyright (C) 2026 DiamaneOS */
package de.diamaneos.decode.client;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.IntSupplier;

/** Missing inputs with capacity evidence captured before a worker hands off its result. */
public final class CapacityDemand<K> {
    private static final class State {
        final IntSupplier capacity;
        int seen;

        State(IntSupplier capacity, int seen) {
            this.capacity = capacity;
            this.seen = seen;
        }
    }

    private final Map<K, State> mMissing = new HashMap<>();
    // This supplier keeps only the input states, never a view or its callbacks.
    private final IntSupplier mWakes = this::poll;
    private int mVersion;

    public synchronized void missing(K key, IntSupplier capacity, int observed) {
        mMissing.put(key, new State(capacity, observed));
    }

    public synchronized void remove(K key) {
        mMissing.remove(key);
    }

    public synchronized void retain(Set<K> wanted) {
        mMissing.keySet().retainAll(wanted);
    }

    public synchronized boolean isEmpty() {
        return mMissing.isEmpty();
    }

    /** Ordinary missing inputs still need ordinary recovery, with per-input capacity filtering at reload. */
    public synchronized IntSupplier capacityWakes() {
        if (mMissing.isEmpty()) return null;
        for (State state : mMissing.values()) {
            if (state.capacity == null) return null;
        }
        return mWakes;
    }

    /** The version already delivered, without consuming a worker-to-main event. */
    public synchronized int observed() {
        return mVersion;
    }

    private synchronized int poll() {
        boolean changed = false;
        for (State state : mMissing.values()) {
            if (state.capacity == null) continue;
            final int current = state.capacity.getAsInt();
            if (current != state.seen) {
                state.seen = current;
                changed = true;
            }
        }
        if (changed) mVersion++;
        return mVersion;
    }
}
