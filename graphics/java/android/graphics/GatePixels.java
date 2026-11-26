/* Copyright (C) 2026 DiamaneOS */
package android.graphics;

/** Allocations of gate working pixels transfer their reservation to the created storage. */
final class GatePixels {
    private interface Make { Bitmap run(); }

    static DecodeMemory.Reservation scratch(long bytes) throws DecodeGate.Failure {
        try {
            return DecodeMemory.reserve(bytes);
        } catch (DecodeMemory.NoRoomException e) {
            throw DecodeGate.noRoom(e);
        }
    }

    private static Bitmap allocate(long bytes, Make make) throws DecodeMemory.NoRoomException {
        try (DecodeMemory.Reservation memory = DecodeMemory.reserve(bytes)) {
            final Bitmap bitmap = make.run();
            if (bitmap == null) return null;
            try {
                DecodeMemory.keep(bitmap, memory);
                return bitmap;
            } catch (RuntimeException | Error e) {
                DecodeMemory.recycle(bitmap, null);
                throw e;
            }
        }
    }

    private static Bitmap make(long bytes, Make make) throws DecodeGate.Failure {
        try {
            final Bitmap bitmap = allocate(bytes, make);
            if (bitmap == null) throw DecodeGate.notNow("working pixels unavailable");
            return bitmap;
        } catch (DecodeMemory.NoRoomException e) {
            throw DecodeGate.noRoom(e);
        } catch (OutOfMemoryError e) {
            throw DecodeGate.notNow("no memory for working pixels");
        } catch (IllegalStateException e) {
            throw DecodeGate.notThisWay("allocated pixel storage exceeds the checked descriptor");
        }
    }

    /** Preferred and default allocators may keep the software result when hardware cannot be admitted. */
    static Bitmap preferredHardware(Bitmap source) {
        try {
            if (source.getConfig() == Bitmap.Config.HARDWARE) return null;
            final long bytes = Bitmap.hardwareAllocationBytes(source);
            return bytes > 0 ? allocate(bytes, () -> hardwareCopy(source)) : null;
        } catch (DecodeMemory.NoRoomException | OutOfMemoryError | IllegalStateException e) {
            return null;
        }
    }

    private static Bitmap hardwareCopy(Bitmap source) {
        final Bitmap copy = source.copy(Bitmap.Config.HARDWARE, false);
        if (copy == null) return null;
        boolean verified = false;
        try {
            // Native counts may be estimates when allocator metadata failed during creation.
            if (copy.hasGainmap() != source.hasGainmap() || Bitmap.hardwareAllocationBytes(copy) <= 0) return null;
            verified = true;
            return copy;
        } finally {
            if (!verified) DecodeMemory.recycle(copy, null);
        }
    }

    private static int bpp(Bitmap.Config config) {
        return config == Bitmap.Config.RGBA_F16 ? 8 : config == Bitmap.Config.ALPHA_8 ? 1
                : config == Bitmap.Config.RGB_565 ? 2 : 4;
    }

    private static long gainBytes(Bitmap source, int width, int height) {
        if (!source.hasGainmap()) return 0;
        final Bitmap gain = source.getGainmap().getGainmapContents();
        // Bitmap.transformGainmap uses these same float ratios and rounded dimensions.
        final int w = Math.round(width * (gain.getWidth() / (float) source.getWidth()));
        final int h = Math.round(height * (gain.getHeight() / (float) source.getHeight()));
        return (long) w * h * bpp(gain.getConfig());
    }

    static Bitmap blank(int width, int height, Bitmap.Config config, boolean alpha, ColorSpace color)
            throws DecodeGate.Failure {
        return make((long) width * height * bpp(config),
                () -> Bitmap.createBitmap(width, height, config, alpha, color));
    }

    static Bitmap copy(Bitmap source, Bitmap.Config config, boolean mutable) throws DecodeGate.Failure {
        if (config == Bitmap.Config.HARDWARE) {
            if (mutable) throw DecodeGate.notThisWay("hardware pixels cannot be mutable");
            if (source.getConfig() == Bitmap.Config.HARDWARE) throw DecodeGate.notThisWay("a hardware source needs no upload");
            final long bytes = Bitmap.hardwareAllocationBytes(source);
            if (bytes <= 0) throw DecodeGate.notNow("hardware storage is not available");
            return make(bytes, () -> hardwareCopy(source));
        }
        final long base = Math.max(source.getAllocationByteCount(),
                (long) source.getWidth() * source.getHeight() * bpp(config));
        final Bitmap gain = source.hasGainmap() ? source.getGainmap().getGainmapContents() : null;
        final long gainBytes = gain == null ? 0 : Math.max(gain.getAllocationByteCount(),
                (long) gain.getWidth() * gain.getHeight() * bpp(config));
        return make(base + gainBytes, () -> source.copy(config, mutable));
    }

    /** A software copy in another configuration that keeps the source's native colour space (Bitmap.copy). */
    static Bitmap sameColour(Bitmap source, Bitmap.Config config) throws DecodeGate.Failure {
        return make((long) source.getWidth() * source.getHeight() * bpp(config), () -> source.copy(config, true));
    }

    static Bitmap shared(Bitmap source) throws DecodeGate.Failure {
        return make(DecodeMemory.bitmapBytes(source), source::asShared);
    }

    static Bitmap crop(Bitmap source, int x, int y, int width, int height) throws DecodeGate.Failure {
        return transform(source, x, y, width, height, null, false);
    }

    static Bitmap scale(Bitmap source, int width, int height) throws DecodeGate.Failure {
        final Matrix matrix = new Matrix();
        matrix.setScale(width / (float) source.getWidth(), height / (float) source.getHeight());
        return transform(source, 0, 0, source.getWidth(), source.getHeight(), matrix, true);
    }

    static Bitmap transform(Bitmap source, int x, int y, int width, int height, Matrix matrix, boolean filter)
            throws DecodeGate.Failure {
        final RectF bounds = new RectF(0, 0, width, height);
        if (matrix != null) matrix.mapRect(bounds);
        final int w = Math.round(bounds.width());
        final int h = Math.round(bounds.height());
        // All gate transforms use software inputs and rectilinear matrices.
        final long bytes = (long) w * h * bpp(source.getConfig()) + gainBytes(source, w, h);
        return make(bytes, () -> Bitmap.createBitmap(source, x, y, width, height, matrix, filter));
    }

    static void releaseCanvas(Canvas canvas) {
        try {
            canvas.setBitmap(null);
        } finally {
            canvas.release();
        }
    }

    private GatePixels() { }
}
