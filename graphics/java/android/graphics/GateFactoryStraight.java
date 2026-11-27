/* Copyright (C) 2026 DiamaneOS */
package android.graphics;

/** BitmapFactory's wide straight pixels, converted before its legacy density transform. */
final class GateFactoryStraight {
    static Bitmap decode(GatedDecodes.Header header, int sample, int sampledWidth, int sampledHeight,
            int width, int height, ColorSpace target, ColorSpace originalColor, Bitmap.Config sourceConfig,
            BitmapFactory.Options options)
            throws DecodeGate.Failure {
        Bitmap result = null;
        Bitmap pixels = null;
        try {
            final Bitmap reused = options.inBitmap;
            if (reused != null && (reused.isRecycled() || !reused.isMutable()
                    || reused.getConfig() == Bitmap.Config.HARDWARE)) return null;
            final Bitmap.Config config = options.inPreferredConfig == Bitmap.Config.RGBA_F16 ? Bitmap.Config.RGBA_F16 : sourceConfig;
            final ColorSpace color = target != null ? target : config == Bitmap.Config.RGBA_F16
                    ? header.colorSpace : originalColor;
            header.packedAvif = config == Bitmap.Config.RGBA_1010102 && header.bitDepth == 10 && "image/avif".equals(header.mimeType);
            pixels = GateStraightPixels.decode(header, sampledWidth, sampledHeight, sample, null, color,
                    false, ImageDecoder.ALLOCATOR_SOFTWARE, false);
            pixels.setHasAlpha(header.hasAlpha);
            if (pixels.getConfig() != config) pixels = transformed(pixels, sampledWidth, sampledHeight, config, color, true);
            if (width != sampledWidth || height != sampledHeight) pixels = transformed(pixels, width, height, config, color, true);
            if (reused != null) {
                if (reused.isRecycled()) return null;
                reused.setGainmap(null);
                try { reused.reconfigure(width, height, config); }
                catch (IllegalArgumentException | IllegalStateException e) { return null; }
                reused.setHasAlpha(header.hasAlpha);
                reused.setPremultiplied(false);
                if (!Bitmap.writeStraightPixels(pixels, reused)) return null;
                return result = reused;
            }
            if (options.inPreferredConfig == Bitmap.Config.HARDWARE) result = GatePixels.preferredHardware(pixels);
            if (result != null) return result;
            if (options.inMutable) pixels = transformed(pixels, width, height, config, color, false);
            return result = pixels;
        } finally { DecodeMemory.recycle(pixels, result); }
    }

    private static Bitmap transformed(Bitmap source, int width, int height, Bitmap.Config config, ColorSpace color,
            boolean freeze) throws DecodeGate.Failure {
        Bitmap output = null;
        boolean complete = false;
        try {
            output = GatePixels.blank(width, height, config, source.hasAlpha(), config == Bitmap.Config.ALPHA_8 ? null : color);
            output.setPremultiplied(false);
            if (!Bitmap.writeStraightPixels(source, output)) throw DecodeGate.notThisWay("straight pixel transform unavailable");
            if (freeze) output.freezeForDecodeGate();
            complete = true;
            return output;
        } finally {
            if (!complete) DecodeMemory.recycle(output, null);
            DecodeMemory.recycle(source, null);
        }
    }

    private GateFactoryStraight() { }
}
