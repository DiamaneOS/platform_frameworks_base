/* Copyright (C) 2026 DiamaneOS */
package android.graphics;

/**
 * DiamaneOS: the bands of a whole plain JPEG at sample 1 from one row stream ({@link DecodeGate#METHOD_JPEG_ROWS}),
 * asked in GateLargePixels' order and overlap. A first band that fails ends the stream before any pixel came, and the
 * caller takes regions instead. A close before the last band ends the stream in the provider.
 */
final class GateRowBands implements AutoCloseable {
    private static final int COLUMNS = 2048; // a drawing's width and pixels at most, as GateLargeHardware's tiles
    private static final int DRAWING_PIXELS = 4 << 20;
    /** The hardware route's bands: half the VM's pixels, so the hardware buffer, a band twice and a drawing fit. */
    static final int HARDWARE_BAND_PIXELS = DecodeGate.MAX_VM_PIXELS / 2;
    private final DecodeGate.Op mOp;
    private final DecodeGate.Bytes mBytes;
    private final int mWidth, mHeight;
    private long mStream;
    private int mBottom; // the last band's bottom so far
    private boolean mDone; // the caller's last band came: the provider ended the stream

    /** A stream for a whole plain JPEG at sample 1 of the caller's own bytes, else null: regions. */
    static GateRowBands of(DecodeGate.Op op, DecodeGate.Bytes bytes, boolean plainJpeg, int sample, int width,
            int height) {
        return eligible(op, plainJpeg, sample) ? new GateRowBands(op, bytes, width, height) : null;
    }

    static boolean eligible(DecodeGate.Op op, boolean plainJpeg, int sample) {
        return plainJpeg && sample == 1 && op.owner.source == null;
    }

    private GateRowBands(DecodeGate.Op op, DecodeGate.Bytes bytes, int width, int height) {
        mOp = op;
        mBytes = bytes;
        mWidth = width;
        mHeight = height;
        mStream = DecodeGate.rowStream();
    }

    /** Rows top to bottom ({@code last}: the caller's last band), or null when the first band failed. */
    Bitmap band(int top, int bottom, boolean last) throws DecodeGate.Failure {
        try {
            final Bitmap band = DecodeGate.rows(mOp, mBytes, mStream, mWidth, mHeight, top, bottom, last);
            mBottom = bottom;
            mDone = last;
            return band;
        } catch (DecodeGate.Failure e) {
            if (mBottom != 0) throw e;
            close();
            return null;
        }
    }

    /**
     * Every band at the stored size into {@code target} through drawings of at most COLUMNS columns and
     * DRAWING_PIXELS pixels, as GateLargeHardware writes its regions: the finished hardware bitmap, or null when the
     * first band failed.
     */
    Bitmap hardware(GateWritablePixels target, ColorSpace targetColor) throws DecodeGate.Failure {
        final int mostRows = Math.max(1, HARDWARE_BAND_PIXELS / mWidth - 2);
        final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        paint.setBlendMode(BlendMode.SRC);
        ColorSpace output = null;
        boolean alpha = false;
        Bitmap band = null, drawing = null;
        try {
            for (int made = 0; made < mHeight; ) {
                final int rows = Math.min(mostRows, mHeight - made), before = made == 0 ? 0 : 1;
                band = band(made - before, Math.min(mHeight, made + rows + 1), made + rows == mHeight);
                if (band == null) return null;
                if (output == null) {
                    output = targetColor != null ? targetColor : band.getColorSpace();
                    alpha = band.hasAlpha();
                }
                final int chunk = Math.max(1, DRAWING_PIXELS / Math.min(COLUMNS, mWidth));
                for (int y = 0; y < rows; y += chunk) {
                    for (int left = 0; left < mWidth; left += COLUMNS) {
                        drawing = GatePixels.blank(Math.min(COLUMNS, mWidth - left), Math.min(chunk, rows - y),
                                Bitmap.Config.ARGB_8888, alpha, output);
                        final Canvas canvas = new Canvas(drawing);
                        canvas.drawBitmap(band, -left, -before - y, paint);
                        canvas.setBitmap(null);
                        target.write(drawing, left, made + y);
                        DecodeMemory.recycle(drawing, null);
                        drawing = null;
                    }
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
        if (mStream != 0 && !mDone) DecodeGate.closeRows(mOp, mBytes, mStream, mWidth, mHeight);
        mStream = 0;
    }
}
