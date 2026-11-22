/* Copyright (C) 2026 DiamaneOS */
package android.graphics;

import android.hardware.HardwareBuffer;

/** Exclusively held hardware pixels retain a checked reservation through their first publication. */
final class GateWritablePixels implements AutoCloseable {
    private HardwareBuffer buffer;
    private DecodeMemory.Reservation memory;
    private Bitmap made;
    private boolean published;

    static long allocationBytes(int width, int height) throws DecodeGate.Failure {
        final long bytes = Bitmap.writableAllocationBytes(width, height);
        if (bytes <= 0) throw DecodeGate.notNow("writable hardware storage is unavailable");
        return bytes;
    }

    static GateWritablePixels create(int width, int height, long bytes) throws DecodeGate.Failure {
        final GateWritablePixels pixels = new GateWritablePixels();
        boolean complete = false;
        try {
            pixels.memory = DecodeMemory.reserve(bytes);
            pixels.buffer = HardwareBuffer.create(width, height, HardwareBuffer.RGBA_8888, 1,
                    HardwareBuffer.USAGE_CPU_WRITE_OFTEN | HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE);
            final long actual = Bitmap.writableAllocationBytes(pixels.buffer);
            if (actual <= 0 || actual > pixels.memory.bytes()) {
                throw DecodeGate.notNow("hardware storage exceeds the checked descriptor");
            }
            complete = true;
            return pixels;
        } catch (DecodeMemory.NoRoomException error) {
            throw DecodeGate.noRoom(error);
        } catch (OutOfMemoryError | IllegalArgumentException error) {
            throw DecodeGate.notNow("writable hardware storage is unavailable");
        } finally {
            if (!complete) pixels.close();
        }
    }

    void write(Bitmap stripe, int left, int top) throws DecodeGate.Failure {
        if (buffer == null || published || !Bitmap.writeHardwarePixels(buffer, stripe, left, top, memory.bytes())) {
            throw DecodeGate.notNow("hardware stripe could not be delivered");
        }
    }

    Bitmap finish(ColorSpace color, boolean alpha, int density) throws DecodeGate.Failure {
        if (buffer == null || made != null || published) throw new IllegalStateException("hardware storage is not private");
        made = Bitmap.wrapHardwareBuffer(buffer, color);
        if (made == null) throw DecodeGate.notNow("hardware bitmap is unavailable");
        made.setHasAlpha(alpha);
        made.setDensity(density);
        final long actual = Bitmap.hardwareAllocationBytes(made);
        if (actual <= 0 || actual > memory.bytes()) throw DecodeGate.notNow("hardware bitmap exceeds its reservation");
        DecodeMemory.keep(made, memory);
        final Bitmap result = made;
        published = true;
        made = null;
        return result;
    }

    @Override public void close() {
        try {
            DecodeMemory.recycle(made, null);
            made = null;
        } finally {
            try {
                if (buffer != null) buffer.close();
                buffer = null;
            } finally {
                if (memory != null) memory.close();
                memory = null;
            }
        }
    }

    private GateWritablePixels() { }
}
