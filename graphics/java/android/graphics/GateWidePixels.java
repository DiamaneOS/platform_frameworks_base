/* Copyright (C) 2026 DiamaneOS */
package android.graphics;

/** Assemble bounded wide regions directly into the final float bitmap. */
final class GateWidePixels {
    private static final int MAX_PIXELS = DecodeGate.MAX_VM_PIXELS / 2;
    private static final int MAX_WIDTH = 262144 / 8;

    static long peakBytes(int width, int height, boolean finishingCopy) {
        final long image = (long) width * height * 8;
        return image + Math.max(2L * MAX_PIXELS * 8, finishingCopy ? image : 0);
    }

    static ColorSpace outputColor(ColorSpace source) {
        if (ColorSpace.get(ColorSpace.Named.SRGB).equals(source)) {
            return ColorSpace.get(ColorSpace.Named.EXTENDED_SRGB);
        }
        if (ColorSpace.get(ColorSpace.Named.LINEAR_SRGB).equals(source)) {
            return ColorSpace.get(ColorSpace.Named.LINEAR_EXTENDED_SRGB);
        }
        return source;
    }

    static Bitmap decode(DecodeGate.Op op, DecodeGate.Bytes bytes, int storedWidth, int storedHeight,
            int sample, int width, int height, int rotation, boolean mirrored, String mimeType,
            ColorSpace targetColor) throws DecodeGate.Failure {
        final int sampledWidth = "image/png".equals(mimeType)
                ? GatedDecodes.roundedDown(storedWidth, sample) : GatedDecodes.roundedUp(storedWidth, sample);
        final int sampledHeight = "image/png".equals(mimeType)
                ? GatedDecodes.roundedDown(storedHeight, sample) : GatedDecodes.roundedUp(storedHeight, sample);
        final int columns = Math.min(sampledWidth, MAX_WIDTH - 2);
        final int rows = Math.max(1, MAX_PIXELS / Math.min(MAX_WIDTH, columns + 2) - 2);
        Bitmap tile = null;
        Bitmap result = null;
        Canvas canvas = null;
        boolean complete = false;
        ColorSpace sourceColor = null;
        final Paint paint = new Paint();
        paint.setBlendMode(BlendMode.SRC);
        paint.setFilterBitmap(true);
        try {
            for (int y = 0; y < sampledHeight; y += rows) {
                final int tall = Math.min(rows, sampledHeight - y);
                final int y0 = Math.max(0, y - 1);
                final int y1 = Math.min(sampledHeight, y + tall + 1);
                for (int x = 0; x < sampledWidth; x += columns) {
                    final int wide = Math.min(columns, sampledWidth - x);
                    final int x0 = Math.max(0, x - 1);
                    final int x1 = Math.min(sampledWidth, x + wide + 1);
                    final int sourceX = Math.min(x0 * sample, storedWidth - 1);
                    final int sourceY = Math.min(y0 * sample, storedHeight - 1);
                    final int sourceRight = Math.min(x1 * sample, storedWidth);
                    final int sourceBottom = Math.min(y1 * sample, storedHeight);
                    tile = DecodeGate.region(op, bytes, sourceX, sourceY, sourceRight - sourceX,
                            sourceBottom - sourceY, sample, 1);
                    if (tile.getWidth() != x1 - x0 || tile.getHeight() != y1 - y0) {
                        throw DecodeGate.failed(false, "inconsistent wide region geometry");
                    }
                    if (result == null) {
                        sourceColor = tile.getColorSpace();
                        result = GatePixels.blank(width, height, Bitmap.Config.RGBA_F16, tile.hasAlpha(),
                                targetColor != null ? targetColor : sourceColor);
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
                    if (!java.util.Objects.equals(sourceColor, tile.getColorSpace())) {
                        throw DecodeGate.failed(false, "inconsistent wide region colour");
                    }
                    final int saved = canvas.save();
                    try {
                        canvas.clipRect(x, y, x + wide, y + tall);
                        canvas.drawBitmap(tile, x0, y0, paint);
                    } finally {
                        canvas.restoreToCount(saved);
                    }
                    DecodeMemory.recycle(tile, null);
                    tile = null;
                }
            }
            complete = true;
            return result;
        } finally {
            if (canvas != null) canvas.setBitmap(null);
            DecodeMemory.recycle(tile, null);
            if (!complete) DecodeMemory.recycle(result, null);
        }
    }

    private GateWidePixels() { }
}
