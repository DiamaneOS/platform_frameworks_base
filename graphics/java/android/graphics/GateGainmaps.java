/* Copyright (C) 2026 DiamaneOS */
package android.graphics;

/** Gain maps for a whole image assembled from the VM's stored base regions. */
final class GateGainmaps {
    // The initial whole metadata reply and copy, before its independent gain-pixel stage.
    private static final int MAX_BOX = (int) Math.sqrt(DecodeGate.MAX_VM_PIXELS);

    private static int box(int width, int height) {
        return Math.max(1, Math.min(MAX_BOX, Math.max(width, height)));
    }

    static long extraBytes(int width, int height) {
        final long pixels = (long) box(width, height) * box(width, height);
        return 2 * pixels * 4 + 4 * Math.min(pixels, 2L * 1024 * 1024) * 4;
    }

    static void attach(DecodeGate.Op op, DecodeGate.Bytes bytes, Bitmap target, boolean stored)
            throws DecodeGate.Failure {
        final int box = box(target.getWidth(), target.getHeight());
        final DecodeGate.Decoded decoded = DecodeGate.decode(op, bytes, box, box);
        final Bitmap whole = decoded.bitmap;
        Bitmap contents = null;
        boolean complete = false;
        try {
            final Gainmap map = whole.getGainmap();
            if (map == null) {
                target.setGainmap(null);
                complete = true;
                return;
            }
            final Bitmap metadataPixels = map.getGainmapContents();
            if ((long) metadataPixels.getWidth() * metadataPixels.getHeight() > 2L * 1024 * 1024
                    || metadataPixels.getWidth() > whole.getWidth()
                    || metadataPixels.getHeight() > whole.getHeight()) {
                throw DecodeGate.failed(true, "gain map exceeds the checked producer limit");
            }
            final boolean turned = !stored && (decoded.rotation == 90 || decoded.rotation == 270);
            final int baseWidth = turned ? target.getHeight() : target.getWidth();
            final int baseHeight = turned ? target.getWidth() : target.getHeight();
            final long gainPixels = Math.min((long) baseWidth * baseHeight, DecodeGate.MAX_VM_PIXELS);
            op.checkFit(bytes.reserved() + DecodeMemory.bitmapBytes(target)
                    + 2 * DecodeMemory.bitmapBytes(whole) + 3 * gainPixels * 4 + (4L << 20));
            contents = DecodeGate.gainmap(op, bytes, baseWidth, baseHeight);
            if (!stored && (decoded.rotation != 0 || decoded.mirrored)) {
                final Matrix forward = new Matrix();
                if (decoded.mirrored) forward.postScale(-1, 1);
                forward.postRotate(decoded.rotation);
                final Bitmap upright = GatePixels.transform(contents, 0, 0,
                        contents.getWidth(), contents.getHeight(), forward, false);
                DecodeMemory.recycle(contents, upright);
                contents = upright;
            }
            target.setGainmap(new Gainmap(map, contents));
            complete = true;
        } finally {
            if (!complete) DecodeMemory.recycle(contents, null);
            DecodeMemory.recycle(whole, target);
        }
    }

    private GateGainmaps() {
    }
}
