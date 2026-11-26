/* Copyright (C) 2026 DiamaneOS */
package android.graphics;

/**
 * Gain maps at stock's size: Android's codec decodes the gain image at the base's target scale (hwui ImageDecoder
 * extractGainmap, BitmapFactory decodeGainmap), a greyscale one as ALPHA_8. Here the VM decodes the gain image alone:
 * whole when the target fits one reply, else in checked regions assembled as large bases are.
 */
final class GateGainmaps {
    // A small whole-image reply carries the gain map's parameters, its pixels are dropped at once.
    private static final int PREVIEW_BOX = 64;

    /** Bytes of the parameter preview, held before the gain stage checks its own size ({@link #fill}). */
    static long extraBytes(int width, int height) {
        return 4L * PREVIEW_BOX * PREVIEW_BOX * 4;
    }

    /**
     * Attaches the image's gain map to {@code target}, {@code storedWidth} x {@code storedHeight} as the file stores
     * it: in stored orientation when {@code stored} (BitmapFactory), else upright as the target is.
     */
    static void attach(DecodeGate.Op op, DecodeGate.Bytes bytes, Bitmap target, boolean stored,
            int storedWidth, int storedHeight) throws DecodeGate.Failure {
        final DecodeGate.Decoded preview = largeJpeg(bytes) ? DecodeGate.gainPreview(op, bytes)
                : DecodeGate.decode(op, bytes, PREVIEW_BOX, PREVIEW_BOX);
        final Gainmap params;
        try {
            final Gainmap map = preview.bitmap.getGainmap();
            params = map == null ? null : parameters(map);
        } finally {
            DecodeMemory.recycle(preview.bitmap, null);
        }
        if (params == null) {
            target.setGainmap(null);
            return;
        }
        fill(op, bytes, target, params, stored ? 0 : preview.rotation, !stored && preview.mirrored,
                storedWidth, storedHeight, stored);
    }

    /**
     * Replaces the gain map a whole-image reply attached to the upright {@code target} with the gain image at
     * stock's size. A target without one keeps none.
     */
    static void replace(DecodeGate.Op op, DecodeGate.Bytes bytes, Bitmap target, int rotation, boolean mirrored,
            int storedWidth, int storedHeight) throws DecodeGate.Failure {
        final Gainmap map = target.getGainmap();
        if (map == null) {
            if (largeJpeg(bytes)) attach(op, bytes, target, false, storedWidth, storedHeight);
            return;
        }
        final Bitmap embedded = map.getGainmapContents();
        final Gainmap params = parameters(map);
        target.setGainmap(null);
        DecodeMemory.recycle(embedded, null);
        fill(op, bytes, target, params, rotation, mirrored, storedWidth, storedHeight, false);
    }

    private static boolean largeJpeg(DecodeGate.Bytes bytes) {
        return bytes.reserved() > 32L * 1024 * 1024 && "image/jpeg".equals(DecodeGate.mimeType(bytes.head()));
    }

    // The parameters of {@code map} on a one-pixel placeholder, so its pixels can go before the gain stage.
    private static Gainmap parameters(Gainmap map) {
        return new Gainmap(map, Bitmap.createBitmap(1, 1, Bitmap.Config.ALPHA_8));
    }

    private static void fill(DecodeGate.Op op, DecodeGate.Bytes bytes, Bitmap target, Gainmap params, int rotation,
            boolean mirrored, int storedWidth, int storedHeight, boolean bitmapFactory) throws DecodeGate.Failure {
        final Bitmap placeholder = params.getGainmapContents();
        Bitmap contents = null;
        boolean complete = false;
        try {
            final boolean turned = rotation == 90 || rotation == 270;
            final int baseWidth = turned ? target.getHeight() : target.getWidth();
            final int baseHeight = turned ? target.getWidth() : target.getHeight();
            contents = pixels(op, bytes, DecodeMemory.bitmapBytes(target), baseWidth, baseHeight,
                    storedWidth, storedHeight, rotation, mirrored, bitmapFactory);
            target.setGainmap(new Gainmap(params, contents));
            complete = true;
        } finally {
            if (!complete) DecodeMemory.recycle(contents, null);
            placeholder.recycle();
        }
    }

    // The gain image at the scale of a base of baseWidth x baseHeight (stored orientation), turned as asked.
    private static Bitmap pixels(DecodeGate.Op op, DecodeGate.Bytes bytes, long targetBytes, int baseWidth,
            int baseHeight, int storedWidth, int storedHeight, int rotation, boolean mirrored, boolean bitmapFactory)
            throws DecodeGate.Failure {
        final boolean turned = rotation == 90 || rotation == 270;
        if (bitmapFactory && (long) baseWidth * baseHeight <= DecodeGate.MAX_VM_PIXELS) {
            // The VM keeps the gain image within the stored base, so at this scale it fits one reply.
            final long most = (long) baseWidth * baseHeight * 4;
            op.checkFit(bytes.reserved() + targetBytes + (rotation != 0 || mirrored ? 2 : 1) * most);
            Bitmap contents = DecodeGate.gainmap(op, bytes, baseWidth, baseHeight);
            if (rotation != 0 || mirrored) {
                final Matrix forward = new Matrix();
                if (mirrored) forward.postScale(-1, 1);
                forward.postRotate(rotation);
                final Bitmap upright = GatePixels.transform(contents, 0, 0,
                        contents.getWidth(), contents.getHeight(), forward, false);
                DecodeMemory.recycle(contents, upright);
                contents = upright;
            }
            return contents;
        }
        // The gain image's stored size: a one-row and a one-column region, which the VM clips to it.
        final int gainWidth = edge(op, bytes, storedWidth, true);
        final int gainHeight = edge(op, bytes, storedHeight, false);
        if (gainWidth > storedWidth || gainHeight > storedHeight) {
            throw DecodeGate.failed(false, "gain image larger than its base");
        }
        // DiamaneOS: ImageDecoder truncates its float scale, BitmapFactory rounds its fine scale.
        // ImageDecoder uses checked regions at every size, so the whole gain reply's rounding cannot add a pixel.
        final int scaledWidth = bitmapFactory
                ? (int) ((double) gainWidth * baseWidth / storedWidth + 0.5)
                : (int) (gainWidth * ((float) baseWidth / storedWidth));
        final int scaledHeight = bitmapFactory
                ? (int) ((double) gainHeight * baseHeight / storedHeight + 0.5)
                : (int) (gainHeight * ((float) baseHeight / storedHeight));
        // Stock ImageDecoder keeps both original gain dimensions if setTargetSize rejects either zero dimension.
        final boolean original = !bitmapFactory && (scaledWidth == 0 || scaledHeight == 0);
        final int width = original ? gainWidth : Math.max(1, scaledWidth);
        final int height = original ? gainHeight : Math.max(1, scaledHeight);
        final int sample = Math.max(1, Math.min(DecodeGate.MAX_REGION_SAMPLE,
                Math.min(gainWidth / width, gainHeight / height)));
        op.checkFit(bytes.reserved() + targetBytes + (long) width * height * 4
                + 2L * Math.min((long) width * height, DecodeGate.MAX_VM_PIXELS) * 4);
        return GateLargePixels.decode(op, bytes, 0, 0, gainWidth, gainHeight, sample,
                turned ? height : width, turned ? width : height, rotation, mirrored, null, true);
    }

    private static int edge(DecodeGate.Op op, DecodeGate.Bytes bytes, int stored, boolean row)
            throws DecodeGate.Failure {
        final Bitmap line = row ? DecodeGate.gainRegion(op, bytes, 0, 0, stored, 1, 1)
                : DecodeGate.gainRegion(op, bytes, 0, 0, 1, stored, 1);
        try {
            if ((row ? line.getHeight() : line.getWidth()) != 1) {
                throw DecodeGate.failed(false, "bad gain image edge");
            }
            return row ? line.getWidth() : line.getHeight();
        } finally {
            DecodeMemory.recycle(line, null);
        }
    }

    private GateGainmaps() {
    }
}
