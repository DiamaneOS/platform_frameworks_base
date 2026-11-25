/* Copyright (C) 2026 DiamaneOS */
package android.graphics;

/**
 * The bounded scalar colour descriptions in the decode wire contract.
 * @hide
 */
public final class GateColorSpaces {
    static boolean isValid(int value) {
        return value >= 0 && value <= 7;
    }

    static boolean isValid(int value, float[] description) {
        return value == 8 ? isColorDescription(description)
                : isValid(value) && (description == null || description.length == 0);
    }

    static boolean isColorDescription(float[] p) {
        if (p == null || p.length != 16) return false;
        for (float value : p) if (!Float.isFinite(value) || Math.abs(value) > 65536.0f) return false;
        final double det = (double)p[0] * ((double)p[4]*p[8] - (double)p[7]*p[5])
                - (double)p[3] * ((double)p[1]*p[8] - (double)p[7]*p[2])
                + (double)p[6] * ((double)p[1]*p[5] - (double)p[4]*p[2]);
        if (!Double.isFinite(det) || Math.abs(det) < 1e-12) return false;
        for (int i=0; i<9; i+=3) if (Math.abs((double)p[i]+p[i+1]+p[i+2]) < 1e-12) return false;
        final double a=p[9], c=p[11], d=p[12], g=p[15];
        return d >= 0 && d <= 1.00000011920928955078125 && a >= 0 && c >= 0 && g >= 0
                && !(d == 0 && (a == 0 || g == 0)) && !(d >= 1 && c == 0)
                && !((a == 0 || g == 0) && c == 0) && p[9]*p[12]+p[10] >= 0;
    }

    public static ColorSpace of(int value, float[] description) {
        if (!isValid(value, description)) throw new IllegalArgumentException("unchecked colour description");
        if (value != 8) return of(value);
        final float[] xyz = java.util.Arrays.copyOf(description, 9);
        final ColorSpace.Rgb.TransferParameters transfer = new ColorSpace.Rgb.TransferParameters(
                description[9], description[10], description[11], description[12], description[13], description[14], description[15]);
        final ColorSpace matched = ColorSpace.match(xyz, transfer);
        return matched != null ? matched : new ColorSpace.Rgb("Unknown", xyz, transfer, true);
    }

    /**
     * The colour space to make new pixels in that keep a gated bitmap's own colours. For a custom profile
     * Bitmap.getColorSpace gives a space whose native profile is adapted to D50 from its white point, so it is made
     * again on the bitmap's own D50 matrix as {@link #of} makes it. Any other space is the bitmap's as it is.
     */
    static ColorSpace nativeOf(Bitmap bitmap) {
        final ColorSpace space = bitmap.getColorSpace();
        if (!(space instanceof ColorSpace.Rgb) || space.getId() != ColorSpace.MIN_ID) return space;
        final ColorSpace.Rgb rgb = (ColorSpace.Rgb) space;
        final ColorSpace.Rgb.TransferParameters transfer = rgb.getTransferParameters();
        if (transfer == null) return space;
        return new ColorSpace.Rgb(rgb.getName(), rgb.getTransform(), transfer, true);
    }

    static float[] checkedNativeTransform(float[] adapted, float[] sourceD50) {
        for (float value : adapted) {
            if (!Float.isFinite(value)) {
                throw new IllegalArgumentException("colour transform is not finite");
            }
        }
        return sourceD50;
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
