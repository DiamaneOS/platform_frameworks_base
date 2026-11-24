/* Copyright (C) 2026 DiamaneOS */
package android.graphics;

/** Gain maps for a whole image assembled from the VM's stored base regions. */
final class GateGainmaps {
    // Whole reply and copy, gain-map reply, copy and inverse transform, before any allocation.
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
        try {
            final Gainmap map = whole.getGainmap();
            if (map == null) {
                target.setGainmap(null);
                return;
            }
            Bitmap contents = map.getGainmapContents();
            if ((long) contents.getWidth() * contents.getHeight() > 2L * 1024 * 1024
                    || contents.getWidth() > whole.getWidth() || contents.getHeight() > whole.getHeight()) {
                throw DecodeGate.failed(true, "gain map exceeds the checked producer limit");
            }
            if (stored && (decoded.rotation != 0 || decoded.mirrored)) {
                final Matrix forward = new Matrix();
                if (decoded.mirrored) forward.postScale(-1, 1);
                forward.postRotate(decoded.rotation);
                final Matrix inverse = new Matrix();
                if (!forward.invert(inverse)) throw DecodeGate.failed(true, "invalid gain-map transform");
                contents = GatePixels.transform(contents, 0, 0, contents.getWidth(), contents.getHeight(),
                        inverse, false);
            }
            target.setGainmap(new Gainmap(map, contents));
        } finally {
            DecodeMemory.recycle(whole, target);
        }
    }

    private GateGainmaps() {
    }
}
