/* Copyright (C) 2026 DiamaneOS */
package android.graphics;

/** Render checked regions into one reserved hardware buffer without a full software image. */
final class GateLargeHardware {
    private static final int TILE_PIXELS = 4 << 20;

    static boolean supportsColor(ColorSpace color) {
        return color == null || (!color.equals(ColorSpace.get(ColorSpace.Named.EXTENDED_SRGB))
                && !color.equals(ColorSpace.get(ColorSpace.Named.LINEAR_EXTENDED_SRGB)));
    }

    static long peakBytes(long hardwareBytes, int width, int height, boolean hdr) {
        final long pixels = (long) width * height;
        final long regions = 2 * Math.min(pixels, DecodeGate.MAX_VM_PIXELS) * 4;
        final long drawing = Math.min(pixels, TILE_PIXELS) * 4;
        final long gain = hdr ? GateGainmaps.extraBytes(width, height) + (16L << 20) : 0;
        return hardwareBytes + Math.max(regions + drawing, gain);
    }

    static Bitmap decode(DecodeGate.Op op, DecodeGate.Bytes bytes, int storedWidth, int storedHeight,
            int sample, int width, int height, int rotation, boolean mirrored, ColorSpace targetColor,
            boolean hdr, boolean storedGainmap, boolean required) throws DecodeGate.Failure {
        final long allocation;
        try { allocation = GateWritablePixels.allocationBytes(width, height); }
        catch (DecodeGate.Failure unavailable) {
            if (required) throw unavailable;
            return null;
        }
        op.checkFit(bytes.reserved() + peakBytes(allocation, width, height, hdr));
        GateWritablePixels writable;
        try { writable = GateWritablePixels.create(width, height, allocation); }
        catch (DecodeGate.Failure unavailable) {
            if (required) throw unavailable;
            return null;
        }
        Bitmap probe = null;
        Bitmap result = null;
        boolean complete = false;
        try (GateWritablePixels target = writable) {
            probe = DecodeGate.region(op, bytes, 0, 0, Math.min(sample, storedWidth), storedHeight, sample);
            final int sampledHeight = probe.getHeight();
            final ColorSpace sourceColor = probe.getColorSpace();
            final boolean alpha = probe.hasAlpha();
            DecodeMemory.recycle(probe, null);
            probe = DecodeGate.region(op, bytes, 0, 0, storedWidth, Math.min(sample, storedHeight), sample);
            final int sampledWidth = probe.getWidth();
            if (!java.util.Objects.equals(sourceColor, probe.getColorSpace())) {
                throw DecodeGate.failed(false, "inconsistent large image color space");
            }
            DecodeMemory.recycle(probe, null);
            probe = null;
            final Matrix transform = new Matrix();
            if (mirrored) transform.postScale(-1, 1);
            transform.postRotate(rotation);
            final boolean turned = rotation == 90 || rotation == 270;
            transform.postScale((float) width / (turned ? sampledHeight : sampledWidth),
                    (float) height / (turned ? sampledWidth : sampledHeight));
            final RectF bounds = new RectF(0, 0, sampledWidth, sampledHeight);
            transform.mapRect(bounds);
            transform.postTranslate(-bounds.left, -bounds.top);
            final Matrix inverse = new Matrix();
            if (!transform.invert(inverse)) throw DecodeGate.failed(true, "invalid hardware image transform");
            final ColorSpace outputColor = targetColor != null ? targetColor : sourceColor;
            final int columns = Math.min(width, 2048);
            final int rows = Math.max(1, TILE_PIXELS / columns);
            for (int top = 0; top < height; top += rows) {
                for (int left = 0; left < width; left += columns) {
                    render(op, bytes, target, new Rect(left, top, Math.min(width, left + columns),
                            Math.min(height, top + rows)), transform, inverse, sampledWidth, sampledHeight,
                            storedWidth, storedHeight, sample, sourceColor, outputColor, alpha);
                }
            }
            result = target.finish(outputColor, alpha, Bitmap.getDefaultDensity());
            if (hdr) {
                GateGainmaps.attach(op, bytes, result, storedGainmap);
                hardwareGainmap(result);
            }
            complete = true;
            return result;
        } finally {
            DecodeMemory.recycle(probe, null);
            if (!complete) DecodeMemory.recycle(result, null);
        }
    }

    private static void hardwareGainmap(Bitmap base) throws DecodeGate.Failure {
        if (!base.hasGainmap()) return;
        final Gainmap gain = base.getGainmap();
        final Bitmap source = gain.getGainmapContents();
        if (source.getConfig() == Bitmap.Config.HARDWARE) return;
        final Bitmap hardware = GatePixels.copy(source, Bitmap.Config.HARDWARE, false);
        boolean linked = false;
        try {
            base.setGainmap(new Gainmap(gain, hardware));
            linked = true;
        } finally {
            if (!linked) DecodeMemory.recycle(hardware, null);
        }
        DecodeMemory.recycle(source, hardware);
        if (Bitmap.hardwareAllocationBytes(base) <= 0) throw DecodeGate.notNow("hardware gain map is unavailable");
    }

    private static void render(DecodeGate.Op op, DecodeGate.Bytes bytes, GateWritablePixels target,
            Rect destination, Matrix transform, Matrix inverse, int sampledWidth, int sampledHeight,
            int storedWidth, int storedHeight, int sample, ColorSpace sourceColor, ColorSpace outputColor,
            boolean alpha) throws DecodeGate.Failure {
        final RectF requested = new RectF(destination);
        inverse.mapRect(requested);
        final int x0 = Math.max(0, (int) Math.floor(requested.left) - 2);
        final int y0 = Math.max(0, (int) Math.floor(requested.top) - 2);
        final int x1 = Math.min(sampledWidth, (int) Math.ceil(requested.right) + 2);
        final int y1 = Math.min(sampledHeight, (int) Math.ceil(requested.bottom) + 2);
        final int left = x0 * sample, top = y0 * sample;
        final int askWidth = Math.min(storedWidth, x1 * sample) - left;
        final int askHeight = Math.min(storedHeight, y1 * sample) - top;
        if (askWidth < 1 || askHeight < 1) throw DecodeGate.failed(true, "empty hardware source region");
        if ((long) GatedDecodes.roundedUp(askWidth, sample) * GatedDecodes.roundedUp(askHeight, sample)
                > DecodeGate.MAX_VM_PIXELS) {
            if (destination.width() == 1 && destination.height() == 1) {
                throw DecodeGate.failed(true, "hardware source region exceeds its limit");
            }
            final Rect first = new Rect(destination), second = new Rect(destination);
            if (destination.width() >= destination.height()) {
                first.right = second.left = destination.left + destination.width() / 2;
            } else {
                first.bottom = second.top = destination.top + destination.height() / 2;
            }
            render(op, bytes, target, first, transform, inverse, sampledWidth, sampledHeight,
                    storedWidth, storedHeight, sample, sourceColor, outputColor, alpha);
            render(op, bytes, target, second, transform, inverse, sampledWidth, sampledHeight,
                    storedWidth, storedHeight, sample, sourceColor, outputColor, alpha);
            return;
        }
        Bitmap region = null, drawing = null;
        Canvas canvas = null;
        try {
            region = DecodeGate.region(op, bytes, left, top, askWidth, askHeight, sample);
            if (region.getWidth() < x1 - x0 || region.getHeight() < y1 - y0
                    || !java.util.Objects.equals(sourceColor, region.getColorSpace())) {
                throw DecodeGate.failed(false, "inconsistent hardware source region");
            }
            drawing = GatePixels.blank(destination.width(), destination.height(), Bitmap.Config.ARGB_8888,
                    alpha, outputColor);
            canvas = new Canvas(drawing);
            canvas.translate(-destination.left, -destination.top);
            canvas.concat(transform);
            final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
            paint.setBlendMode(BlendMode.SRC);
            canvas.drawBitmap(region, x0, y0, paint);
            canvas.setBitmap(null);
            canvas = null;
            target.write(drawing, destination.left, destination.top);
        } finally {
            if (canvas != null) canvas.setBitmap(null);
            DecodeMemory.recycle(region, null);
            DecodeMemory.recycle(drawing, null);
        }
    }

    private GateLargeHardware() { }
}
