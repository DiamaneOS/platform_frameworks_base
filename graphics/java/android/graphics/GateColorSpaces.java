/* Copyright (C) 2026 DiamaneOS */
package android.graphics;

/** The bounded named colour tags in the decode wire contract. */
final class GateColorSpaces {
    static boolean isValid(int value) {
        return value >= 0 && value <= 7;
    }

    static boolean isBitDepth(int depth) {
        return depth == 1 || depth == 2 || depth == 4 || depth == 8 || depth == 10 || depth == 12 || depth == 16;
    }

    static ColorSpace of(int value) {
        final ColorSpace.Named named;
        switch (value) {
            case 0: named = ColorSpace.Named.SRGB; break;
            case 1: named = ColorSpace.Named.DISPLAY_P3; break;
            case 2: named = ColorSpace.Named.ADOBE_RGB; break;
            case 3: named = ColorSpace.Named.PRO_PHOTO_RGB; break;
            case 4: named = ColorSpace.Named.BT2020; break;
            case 5: named = ColorSpace.Named.BT2020_HLG; break;
            case 6: named = ColorSpace.Named.BT2020_PQ; break;
            case 7: named = ColorSpace.Named.LINEAR_SRGB; break;
            default: throw new IllegalArgumentException("unchecked colour space");
        }
        return ColorSpace.get(named);
    }

    private GateColorSpaces() { }
}
