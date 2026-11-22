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

    private GateImageSettings() { }
}
