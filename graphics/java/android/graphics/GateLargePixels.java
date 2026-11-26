/* Copyright (C) 2026 DiamaneOS */
package android.graphics;

/** Assemble checked VM regions directly into the final upright pixel storage. */
final class GateLargePixels {
    static long peakBytes(int width, int height, int finalBytesPerPixel, boolean copyArgb, boolean hdr) {
        final long target = (long) width * height;
        final long base = target * 4;
        final long stripes = 2 * Math.min(target, DecodeGate.MAX_VM_PIXELS) * 4;
        final long gain = hdr ? GateGainmaps.extraBytes(width, height) : 0;
        final long finishing = copyArgb || finalBytesPerPixel != 4
                ? target * Math.max(1, finalBytesPerPixel) : 0;
        final long retainedGainCopies = hdr && finishing > 0 ? 2L * (2L << 20) * 4 : 0;
        return base + Math.max(Math.max(stripes, gain), finishing + retainedGainCopies);
    }

    static Bitmap decode(DecodeGate.Op op, DecodeGate.Bytes bytes, int left, int top,
            int storedWidth, int storedHeight, int sample, int width, int height,
            int rotation, boolean mirrored, ColorSpace targetColor) throws DecodeGate.Failure {
        return decode(op, bytes, left, top, storedWidth, storedHeight, sample, width, height, rotation, mirrored,
                targetColor, false);
    }

    /** As above, from regions of the gain image when {@code gain}: opaque RGBA or ALPHA_8, as its replies are. */
    static Bitmap decode(DecodeGate.Op op, DecodeGate.Bytes bytes, int left, int top,
            int storedWidth, int storedHeight, int sample, int width, int height,
            int rotation, boolean mirrored, ColorSpace targetColor, boolean gain) throws DecodeGate.Failure {
        Bitmap tile = null;
        Bitmap result = null;
        Canvas canvas = null;
        boolean complete = false;
        try {
            // Unit sampling has the checked header's height. Other samples use the codec's exact size.
            final int sampledHeight;
            if (sample == 1) sampledHeight = storedHeight;
            else {
                tile = region(op, bytes, left, top, Math.min(sample, storedWidth), storedHeight, sample, gain);
                sampledHeight = tile.getHeight();
                DecodeMemory.recycle(tile, null);
                tile = null;
            }
            final int upperWidth = GatedDecodes.roundedUp(storedWidth, sample);
            // Two neighbouring rows let the same bilinear filter cross each stripe boundary.
            final int mostRows = Math.max(1, DecodeGate.MAX_VM_PIXELS / upperWidth - 2);
            final Paint paint = new Paint();
            paint.setBlendMode(BlendMode.SRC);
            paint.setFilterBitmap(true);
            int made = 0;
            int sampledWidth = 0;
            ColorSpace sourceColor = null;
            while (made < sampledHeight) {
                final int rows = Math.min(mostRows, sampledHeight - made);
                final int before = made == 0 ? 0 : 1;
                final int askTop = (made - before) * sample;
                final int askBottom = Math.min(storedHeight, (made + rows + 1) * sample);
                tile = region(op, bytes, left, top + askTop, storedWidth, askBottom - askTop, sample, gain);
                if (result == null) {
                    sampledWidth = tile.getWidth();
                    sourceColor = tile.getColorSpace();
                    final boolean grey = gain && tile.getConfig() == Bitmap.Config.ALPHA_8;
                    result = GatePixels.blank(width, height, grey ? Bitmap.Config.ALPHA_8
                            : Bitmap.Config.ARGB_8888, grey || (!gain && tile.hasAlpha()),
                            grey ? null : targetColor != null ? targetColor : GateColorSpaces.nativeOf(tile));
                    final boolean turned = rotation == 90 || rotation == 270;
                    final Matrix matrix = new Matrix();
                    if (mirrored) matrix.postScale(-1, 1);
                    matrix.postRotate(rotation);
                    matrix.postScale((float) width / (turned ? sampledHeight : sampledWidth),
                            (float) height / (turned ? sampledWidth : sampledHeight));
                    final RectF bounds = new RectF(0, 0, sampledWidth, sampledHeight);
                    matrix.mapRect(bounds);
                    matrix.postTranslate(-bounds.left, -bounds.top);
                    canvas = new Canvas(result);
                    canvas.concat(matrix);
                }
                if (tile.getWidth() != sampledWidth || tile.getHeight() < before + rows
                        || !java.util.Objects.equals(sourceColor, tile.getColorSpace())
                        || (gain && tile.getConfig() != result.getConfig())) {
                    throw DecodeGate.failed(false, "inconsistent large image stripe");
                }
                final int saved = canvas.save();
                try {
                    canvas.clipRect(0, made, sampledWidth, made + rows);
                    canvas.drawBitmap(tile, 0, made - before, paint);
                } finally {
                    canvas.restoreToCount(saved);
                }
                DecodeMemory.recycle(tile, null);
                tile = null;
                made += rows;
            }
            complete = true;
            return result;
        } finally {
            if (canvas != null) canvas.setBitmap(null);
            DecodeMemory.recycle(tile, null);
            if (!complete) DecodeMemory.recycle(result, null);
        }
    }

    private static Bitmap region(DecodeGate.Op op, DecodeGate.Bytes bytes, int left, int top, int width, int height,
            int sample, boolean gain) throws DecodeGate.Failure {
        return gain ? DecodeGate.gainRegion(op, bytes, left, top, width, height, sample)
                : DecodeGate.region(op, bytes, left, top, width, height, sample);
    }

    private GateLargePixels() { }
}
