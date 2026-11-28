/* Copyright (C) 2026 DiamaneOS */
package android.graphics;

/**
 * DiamaneOS: the bands of a whole plain JPEG at sample 1 from one row stream ({@link DecodeGate#METHOD_JPEG_ROWS}),
 * asked in GateLargePixels' order and overlap. A first band that fails ends the stream before any pixel came, and the
 * caller takes regions instead. A close before the last band ends the stream in the provider.
 */
final class GateRowBands implements AutoCloseable {
    private static final int COLUMNS = 2048; // a drawing's width, as GateLargeHardware's tiles
    private final DecodeGate.Op mOp;
    private final DecodeGate.Bytes mBytes;
    private final int mWidth, mHeight;
    private long mStream;
    private int mBottom; // the last band's bottom so far

    /** A stream for a whole plain JPEG at sample 1 of the caller's own bytes, else null: regions. */
    static GateRowBands of(DecodeGate.Op op, DecodeGate.Bytes bytes, boolean plainJpeg, int sample, int width,
            int height) {
        return plainJpeg && sample == 1 && op.owner.source == null ? new GateRowBands(op, bytes, width, height) : null;
    }

    private GateRowBands(DecodeGate.Op op, DecodeGate.Bytes bytes, int width, int height) {
        mOp = op;
        mBytes = bytes;
        mWidth = width;
        mHeight = height;
        mStream = DecodeGate.rowStream();
    }

    /** Rows top to bottom, or null when the first band failed: the stream ended, nothing came. */
    Bitmap band(int top, int bottom) throws DecodeGate.Failure {
        try {
            final Bitmap band = DecodeGate.rows(mOp, mBytes, mStream, mWidth, mHeight, top, bottom);
            mBottom = bottom;
            return band;
        } catch (DecodeGate.Failure e) {
            if (mBottom != 0) throw e;
            close();
            return null;
        }
    }

    /**
     * Every band at the stored size into {@code target} through drawings of at most COLUMNS columns, as
     * GateLargeHardware writes its regions: the finished hardware bitmap, or null when the first band failed.
     */
    Bitmap hardware(GateWritablePixels target, ColorSpace targetColor) throws DecodeGate.Failure {
        final int mostRows = Math.max(1, DecodeGate.MAX_VM_PIXELS / mWidth - 2);
        final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        paint.setBlendMode(BlendMode.SRC);
        ColorSpace output = null;
        boolean alpha = false;
        Bitmap band = null, drawing = null;
        try {
            for (int made = 0; made < mHeight; ) {
                final int rows = Math.min(mostRows, mHeight - made), before = made == 0 ? 0 : 1;
                band = band(made - before, Math.min(mHeight, made + rows + 1));
                if (band == null) return null;
                if (output == null) {
                    output = targetColor != null ? targetColor : band.getColorSpace();
                    alpha = band.hasAlpha();
                }
                for (int left = 0; left < mWidth; left += COLUMNS) {
                    drawing = GatePixels.blank(Math.min(COLUMNS, mWidth - left), rows, Bitmap.Config.ARGB_8888, alpha,
                            output);
                    final Canvas canvas = new Canvas(drawing);
                    canvas.drawBitmap(band, -left, -before, paint);
                    canvas.setBitmap(null);
                    target.write(drawing, left, made);
                    DecodeMemory.recycle(drawing, null);
                    drawing = null;
                }
                DecodeMemory.recycle(band, null);
                band = null;
                made += rows;
            }
            return target.finish(output, alpha, Bitmap.getDefaultDensity());
        } finally {
            DecodeMemory.recycle(band, null);
            DecodeMemory.recycle(drawing, null);
        }
    }

    @Override
    public void close() {
        if (mStream != 0 && mBottom != mHeight) DecodeGate.closeRows(mOp, mBytes, mStream, mWidth, mHeight);
        mStream = 0;
    }
}
