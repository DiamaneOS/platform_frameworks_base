/* Copyright (C) 2026 DiamaneOS */
package de.diamaneos.decode.client;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Coalesced lane-capacity notifications, always outside the lane lock. */
final class ImageCapacity {
    private static final AtomicLong sVersion = new AtomicLong();
    private static final AtomicBoolean sPending = new AtomicBoolean();
    private static final CopyOnWriteArrayList<Runnable> sListeners = new CopyOnWriteArrayList<>();
    private static final ScheduledExecutorService sWorker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "WidgetImageCapacity");
        thread.setDaemon(true);
        return thread;
    });

    static long version() {
        return sVersion.get();
    }

    static void listen(Runnable listener) {
        sListeners.add(listener);
    }

    static void changed() {
        sVersion.incrementAndGet();
        schedule();
    }

    static void wakeSince(long observed) {
        if (sVersion.get() != observed) {
            schedule();
        }
    }

    private static void schedule() {
        if (sPending.compareAndSet(false, true)) {
            sWorker.schedule(() -> {
                long before = sVersion.get();
                try {
                    for (Runnable listener : sListeners) {
                        try {
                            listener.run();
                        } catch (RuntimeException ignored) {
                            // A failed host does not suppress another host's retry.
                        }
                    }
                } finally {
                    sPending.set(false);
                    wakeSince(before);
                }
            }, 25, TimeUnit.MILLISECONDS);
        }
    }

    private ImageCapacity() {
    }
}
