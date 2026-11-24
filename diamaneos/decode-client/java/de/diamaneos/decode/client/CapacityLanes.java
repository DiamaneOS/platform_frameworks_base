/* Copyright (C) 2026 DiamaneOS */
package de.diamaneos.decode.client;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.lang.ref.WeakReference;
import java.util.function.IntSupplier;

/** Retry evidence for a failed user lane, published after overlapping image tasks have ended. */
final class CapacityLanes {
    private static final long[] WAITS = {1000, 2000, 4000, 8000, 16000, 30000};
    private final LinkedHashMap<String, WeakReference<Lane>> lanes = new LinkedHashMap<>();
    private int tasks;
    private boolean pending;
    private boolean deliveryPending;

    static final class Lane {
        long bytes;
        long due;
        int round;
        volatile int version;
        boolean pending;
        boolean blocked;
    }

    synchronized Lane refused(String key, long bytes, long now) {
        final WeakReference<Lane> prior = lanes.get(key);
        Lane lane = prior != null ? prior.get() : null;
        if (lane == null) {
            lane = new Lane();
            lanes.values().removeIf(ref -> ref.get() == null);
            lanes.put(key, new WeakReference<>(lane));
        }
        lane.bytes = lane.blocked ? Math.max(lane.bytes, bytes) : bytes;
        if (!lane.blocked) lane.round = 0;
        lane.blocked = true;
        if (lane.due == 0) lane.due = now + WAITS[Math.min(lane.round++, WAITS.length - 1)];
        return lane;
    }

    synchronized void taskStarted() {
        tasks++;
    }

    synchronized boolean taskEnded() {
        if (tasks <= 0) throw new IllegalStateException("no image task to end");
        tasks--;
        if (tasks != 0) return false;
        boolean changed = deliveryPending;
        deliveryPending = false;
        pending = false;
        for (WeakReference<Lane> ref : lanes.values()) {
            final Lane lane = ref.get();
            if (lane == null) continue;
            if (lane.pending) {
                lane.pending = false;
                lane.version++;
                changed = true;
            }
        }
        return changed;
    }

    synchronized boolean mayPublish() {
        if (tasks == 0) return true;
        deliveryPending = true;
        return false;
    }

    private boolean changed(Lane lane) {
        if (tasks != 0) {
            lane.pending = true;
            pending = true;
            return false;
        }
        lane.version++;
        return true;
    }

    synchronized boolean interested(String key) {
        final WeakReference<Lane> ref = lanes.get(key);
        final Lane lane = ref != null ? ref.get() : null;
        return lane != null && lane.blocked;
    }

    synchronized boolean completed(String key, long bytes) {
        final WeakReference<Lane> ref = lanes.get(key);
        final Lane lane = ref != null ? ref.get() : null;
        if (lane == null || !lane.blocked || bytes < lane.bytes) return false;
        lane.blocked = false;
        lane.due = 0;
        return changed(lane);
    }

    synchronized boolean tick(long now) {
        boolean changed = false;
        for (WeakReference<Lane> ref : lanes.values()) {
            final Lane lane = ref.get();
            if (lane == null) continue;
            if (lane.due != 0 && lane.due <= now) {
                lane.due = 0;
                changed |= changed(lane);
            }
        }
        return changed;
    }

    synchronized long delay(long now) {
        long next = Long.MAX_VALUE;
        final java.util.Iterator<WeakReference<Lane>> entries = lanes.values().iterator();
        while (entries.hasNext()) {
            final Lane lane = entries.next().get();
            if (lane == null) entries.remove();
            else if (lane.due != 0) next = Math.min(next, lane.due);
        }
        return next == Long.MAX_VALUE ? -1 : Math.max(1, next - now);
    }

    static IntSupplier snapshot(List<Lane> failed) {
        return snapshot(failed, () -> 0);
    }

    static IntSupplier snapshot(List<Lane> failed, IntSupplier bindings) {
        if (failed.isEmpty()) return null;
        final List<Lane> copy = new ArrayList<>(failed);
        return () -> {
            int version = bindings.getAsInt();
            for (Lane lane : copy) version += lane.version;
            return version;
        };
    }
}
