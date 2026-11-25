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

/**
 * DiamaneOS: the memory images of the protected VM may take in this process at once (the decode gate's input, its
 * working bitmaps and copies, the decode client's replies with their gain maps, requests the system server's provider
 * holds for others). Every one of them reserves the most it will hold before it allocates and gives it back when done,
 * so however many come together they never hold more than {@link #MAX_BYTES}. A reservation that does not fit now is
 * refused at once and the image is left out for now (asked for again later), one that could never fit is refused for
 * good. Thread-safe.
 *
 * @hide
 */
public final class DecodeMemory {
    /** All reservations of this process together, at most. */
    public static final long MAX_BYTES = 320L << 20;
    static final int FRAME_OBJECT_BYTES = 4096;
    static final long MAX_FRAME_OBJECT_BYTES = 32L << 20;
    static final int MAX_FRAMES = (int) (MAX_FRAME_OBJECT_BYTES / FRAME_OBJECT_BYTES);

    private static final Object sLock = new Object();
    private static long sHeld; // guarded by sLock
    private static long sMax = MAX_BYTES; // guarded by sLock
    private static final java.lang.ref.ReferenceQueue<Object> sCollected = new java.lang.ref.ReferenceQueue<>();
    private static final java.util.ArrayList<Held> sPixels = new java.util.ArrayList<>();

    private static boolean sCollector;

    private static synchronized void collector() {
        if (sCollector) return;
        final Thread thread = new Thread(() -> {
            for (;;) {
                try {
                    final Held held = (Held) sCollected.remove();
                    synchronized (sLock) { sPixels.remove(held); }
                    held.memory.close();
                } catch (InterruptedException ignored) {
                    // This process-lifetime thread continues to release collected storage.
                }
            }
        }, "DecodeMemoryCollected");
        thread.setDaemon(true);
        thread.start();
        sCollector = true;
    }

    private static final class Held extends java.lang.ref.PhantomReference<Object> {
        final Reservation memory = new Reservation(0);
        final java.lang.ref.WeakReference<Object> identity;

        Held(Object bitmap) {
            super(bitmap, sCollected);
            identity = new java.lang.ref.WeakReference<>(bitmap);
        }
    }

    private static void collectedLocked() {
        Held held;
        while ((held = (Held) sCollected.poll()) != null) {
            sPixels.remove(held);
            held.memory.close();
        }
    }

    private static boolean keptLocked(Object bitmap) {
        for (int i = 0; i < sPixels.size(); i++) {
            if (sPixels.get(i).identity.get() == bitmap) return true;
        }
        return false;
    }

    /** The immutable copy held by an icon request across retries. */
    public static void keepBytes(byte[] bytes, Reservation memory) {
        keepStorage(bytes, bytes.length, memory);
    }

    // XML frame replacement shares the array, so its metadata follows every drawable that retains it.
    static void keepFrames(Bitmap[] frames, Reservation memory) {
        if (frames.length < 1 || frames.length > MAX_FRAMES) {
            throw new IllegalArgumentException("frame object count");
        }
        keepStorage(frames, (long) frames.length * FRAME_OBJECT_BYTES, memory);
    }

    // Reply bundles share this array even after their original pixels are recycled.
    static void keepDurations(int[] durations, Reservation memory) {
        if (durations.length < 1 || durations.length > MAX_FRAMES) {
            throw new IllegalArgumentException("frame duration count");
        }
        keepStorage(durations, (long) durations.length * Integer.BYTES, memory);
    }

    private static void keepStorage(Object bytes, long length, Reservation memory) {
        collector();
        final Held held = new Held(bytes);
        synchronized (sLock) {
            if (keptLocked(bytes)) return;
            if (memory.mBytes < length) throw new IllegalStateException("bytes exceed reservation");
            try {
                sPixels.add(held);
            } catch (RuntimeException | Error e) {
                sPixels.remove(held);
                throw e;
            }
            held.memory.mBytes = length;
            memory.mBytes -= length;
        }
    }

    /** Pixel storage of the base and its attached gain map. */
    public static long bitmapBytes(Bitmap bitmap) {
        final Gainmap gain = bitmap.hasGainmap() ? bitmap.getGainmap() : null;
        return (long) bitmap.getAllocationByteCount()
                + (gain != null ? gain.getGainmapContents().getAllocationByteCount() : 0);
    }

    /** Moves the live pixels out of a working reservation, leaving scratch storage with the operation. */
    public static void keep(Bitmap bitmap, Reservation memory) {
        collector();
        final Bitmap gain = bitmap.hasGainmap() ? bitmap.getGainmap().getGainmapContents() : null;
        final Held baseHeld = new Held(bitmap);
        final Held gainHeld = gain != null && gain != bitmap ? new Held(gain) : null;
        synchronized (sLock) {
            collectedLocked();
            final long baseBytes = keptLocked(bitmap) ? 0 : bitmap.getAllocationByteCount();
            final long gainBytes = gainHeld == null || keptLocked(gain) ? 0 : gain.getAllocationByteCount();
            if (memory.mBytes < baseBytes + gainBytes) throw new IllegalStateException("pixels exceed reservation");
            try {
                if (baseBytes > 0) sPixels.add(baseHeld);
                if (gainBytes > 0) sPixels.add(gainHeld);
            } catch (RuntimeException | Error e) {
                sPixels.remove(baseHeld);
                sPixels.remove(gainHeld);
                throw e;
            }
            baseHeld.memory.mBytes = baseBytes;
            if (gainHeld != null) gainHeld.memory.mBytes = gainBytes;
            memory.mBytes -= baseBytes + gainBytes;
        }
    }

    /** Keeps a newly received bitmap through cache, refill and consumer lifetimes. */
    public static void keep(Bitmap bitmap) throws NoRoomException {
        synchronized (sLock) {
            if (keptLocked(bitmap) && (!bitmap.hasGainmap()
                    || keptLocked(bitmap.getGainmap().getGainmapContents()))) return;
        }
        try (Reservation memory = reserve(bitmapBytes(bitmap))) {
            keep(bitmap, memory);
        }
    }

    /** Recycles exclusive working pixels, including a gain map not shared with the result. */
    public static void recycle(Bitmap bitmap, Bitmap result) {
        if (bitmap == null || bitmap == result || bitmap.isRecycled()) return;
        final Bitmap gain;
        final Bitmap keptGain;
        try {
            gain = bitmap.hasGainmap() ? bitmap.getGainmap().getGainmapContents() : null;
            keptGain = result != null && result.hasGainmap() ? result.getGainmap().getGainmapContents() : null;
        } finally {
            bitmap.recycle();
        }
        if (gain != null && gain != keptGain && gain != result) gain.recycle();
    }

    /** Bitmap's recycle hook releases only that bitmap's storage, independently of an extracted gain map. */
    static void recycled(Bitmap bitmap) {
        synchronized (sLock) {
            for (int i = sPixels.size() - 1; i >= 0; i--) {
                final Held held = sPixels.get(i);
                if (held.identity.get() == bitmap) {
                    sPixels.remove(i);
                    held.memory.close();
                }
            }
        }
    }

    private DecodeMemory() {
    }

    /** A reservation, given back by {@link #close} (once, however often it is called). */
    public static final class Reservation implements AutoCloseable {
        private long mBytes; // guarded by sLock

        private Reservation(long bytes) {
            mBytes = bytes;
        }

        /** The bytes held. */
        public long bytes() {
            synchronized (sLock) {
                return mBytes;
            }
        }

        /** Takes {@code more} bytes in addition: false, and nothing taken, when they do not fit now. */
        public boolean grow(long more) {
            if (more <= 0) {
                return true;
            }
            synchronized (sLock) {
                collectedLocked();
                if (more > sMax - sHeld) {
                    return false;
                }
                sHeld += more;
                mBytes += more;
                return true;
            }
        }

        @Override
        public void close() {
            final boolean released;
            synchronized (sLock) {
                released = mBytes > 0;
                sHeld -= mBytes;
                mBytes = 0;
            }
            if (released) {
                try { GateRefill.capacityAvailable(); } catch (RuntimeException | Error ignored) { }
            }
        }
    }

    /** Thrown when a reservation is refused: for now ({@link #later} true) or for good. */
    public static final class NoRoomException extends Exception {
        /** True: it fits once others give back what they hold. False: it could never fit. */
        public final boolean later;
        public final long bytes;

        NoRoomException(boolean later, String message, long bytes) {
            super(message);
            this.later = later;
            this.bytes = bytes;
        }
    }

    /** Rejects a complete operation that could never fit, without charging its pixels twice. */
    static void checkFit(long bytes) throws NoRoomException {
        synchronized (sLock) {
            if (bytes > sMax) throw new NoRoomException(false,
                    "the complete image needs " + bytes + " bytes, more than " + sMax, bytes);
        }
    }

    /**
     * Reserves {@code bytes} (at least 0), or throws when they do not fit now or could never fit. Before it refuses
     * for lack of room now, pixels nothing references any more (a drawable an app dropped) are collected, as the
     * runtime collects for native allocations, so a dropped image never blocks the next decode.
     */
    public static Reservation reserve(long bytes) throws NoRoomException {
        try {
            return reserveNow(bytes);
        } catch (NoRoomException e) {
            if (!e.later || !reclaimUnreachable(Math.max(0, bytes))) throw e;
            return reserveNow(bytes);
        }
    }

    static final long RECLAIM_WAIT_MS = 200;
    private static long sLastReclaim = -RECLAIM_WAIT_MS; // guarded by sLock

    // One collection (at most one started per RECLAIM_WAIT_MS), then up to RECLAIM_WAIT_MS for its released charges.
    // Never on the main thread, which keeps its own short waits.
    private static boolean reclaimUnreachable(long n) {
        if (android.os.Looper.getMainLooper() != null && android.os.Looper.getMainLooper().isCurrentThread()) {
            return false;
        }
        final long now = android.os.SystemClock.uptimeMillis();
        synchronized (sLock) {
            if (sPixels.isEmpty()) return false;
            if (now - sLastReclaim >= RECLAIM_WAIT_MS) {
                sLastReclaim = now;
            } else {
                collectedLocked();
                return n <= sMax - sHeld;
            }
        }
        Runtime.getRuntime().gc();
        final long end = now + RECLAIM_WAIT_MS;
        while (true) {
            synchronized (sLock) {
                collectedLocked();
                if (n <= sMax - sHeld) return true;
            }
            if (android.os.SystemClock.uptimeMillis() >= end) return false;
            android.os.SystemClock.sleep(10);
        }
    }

    private static Reservation reserveNow(long bytes) throws NoRoomException {
        final long n = Math.max(0, bytes);
        synchronized (sLock) {
            collectedLocked();
            if (n > sMax) {
                throw new NoRoomException(false, "an image needs " + n + " bytes, more than the "
                        + sMax + " this process takes", n);
            }
            if (n > sMax - sHeld) {
                throw new NoRoomException(true, "no room for an image now: " + n + " bytes needed, "
                        + sHeld + " of " + sMax + " held", n);
            }
            final Reservation memory = new Reservation(n);
            sHeld += n;
            return memory;
        }
    }

    static long available() {
        synchronized (sLock) { return Math.max(0, sMax - sHeld); }
    }

    /** The bytes held now. */
    public static long held() {
        synchronized (sLock) {
            collectedLocked();
            return sHeld;
        }
    }

    /** Tests on debuggable builds: the limit ({@code 0}: back to {@link #MAX_BYTES}). */
    public static void setMaxForTest(long max) {
        if (!android.os.Build.IS_DEBUGGABLE) {
            throw new SecurityException("debuggable builds only");
        }
        synchronized (sLock) {
            sMax = max > 0 ? max : MAX_BYTES;
        }
    }
}
