/* Copyright (C) 2026 DiamaneOS */
package android.graphics;

/** Settings validated before raw VM pixels are made into a framework result. */
final class GateImageSettings {
    static void validateColorSpace(ColorSpace colorSpace) {
        if (colorSpace == null) return;
        if (!(colorSpace instanceof ColorSpace.Rgb)) {
            throw new IllegalArgumentException("The destination color space must use the RGB color model");
        }
        if (!colorSpace.equals(ColorSpace.get(ColorSpace.Named.BT2020_HLG))
                && !colorSpace.equals(ColorSpace.get(ColorSpace.Named.BT2020_PQ))
                && ((ColorSpace.Rgb) colorSpace).getTransferParameters() == null) {
            throw new IllegalArgumentException("The destination color space must use an ICC parametric transfer function");
        }
    }

    static Bitmap.Config config(Bitmap bitmap, ColorSpace target, boolean conserveMemory,
            boolean hardware, boolean postProcessed) {
        if (bitmap.getConfig() == Bitmap.Config.ALPHA_8) return Bitmap.Config.ALPHA_8;
        if (conserveMemory) {
            // As stock, low RAM never keeps F16. RGB_565 is chosen by GatedDecodes.lowRam565.
            if (bitmap.getConfig() == Bitmap.Config.RGBA_F16) return Bitmap.Config.ARGB_8888;
        } else if (target != null && (target.equals(ColorSpace.get(ColorSpace.Named.EXTENDED_SRGB))
                        || target.equals(ColorSpace.get(ColorSpace.Named.LINEAR_EXTENDED_SRGB)))) {
            return Bitmap.Config.RGBA_F16;
        }
        return bitmap.getConfig();
    }

    /** Consumes working pixels while converting their checked raw colours, before allocator finalization. */
    static Bitmap convert(Bitmap source, ColorSpace target, Bitmap.Config config) throws DecodeGate.Failure {
        if (config == Bitmap.Config.ALPHA_8) return source;
        if (target == null) target = source.getColorSpace();
        if (config == source.getConfig() && java.util.Objects.equals(target, source.getColorSpace())) return source;
        Bitmap converted = null;
        boolean complete = false;
        try {
            converted = GatePixels.blank(source.getWidth(), source.getHeight(), config,
                    source.hasAlpha(), target);
            final Canvas canvas = new Canvas(converted);
            try {
                final Paint paint = new Paint();
                paint.setBlendMode(BlendMode.SRC);
                canvas.drawBitmap(source, 0, 0, paint);
            } finally {
                GatePixels.releaseCanvas(canvas);
            }
            converted.setDensity(source.getDensity());
            converted.setGainmap(source.getGainmap());
            DecodeMemory.recycle(source, converted);
            complete = true;
            return converted;
        } finally {
            if (!complete) {
                DecodeMemory.recycle(converted, source);
                DecodeMemory.recycle(source, null);
            }
        }
    }

    private GateImageSettings() { }
}
