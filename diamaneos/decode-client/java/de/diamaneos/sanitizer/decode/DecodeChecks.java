/*
 * Copyright (C) 2026 DiamaneOS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package de.diamaneos.sanitizer.decode;

/**
 * The callers' checks of what the decode service returns, one copy for every caller (MediaProvider, SystemUI, the
 * system server) and for the service's own tests, in the library de.diamaneos.sanitizer.decode-client. Each method
 * returns null when the value meets every rule of the contract (IDecodeService and its parcelables), else what it
 * broke. A caller treats a broken answer like STATUS_INVALID: a placeholder, no value stored.
 */
public final class DecodeChecks {
    private DecodeChecks() {}

    private static boolean status(int s) {
        return s == IDecodeService.STATUS_OK || s == IDecodeService.STATUS_UNAVAILABLE
                || s == IDecodeService.STATUS_UNSUPPORTED || s == IDecodeService.STATUS_INVALID
                || s == IDecodeService.STATUS_NO_ROOM;
    }

    private static boolean format(int f) {
        return f >= IDecodeService.FORMAT_JPEG && f <= IDecodeService.FORMAT_WBMP;
    }

    private static boolean size(int w, int h, int maxW, int maxH) {
        return w >= 1 && h >= 1 && w <= maxW && h <= maxH && w <= IDecodeService.MAX_DIMENSION
                && h <= IDecodeService.MAX_DIMENSION;
    }

    /** Only GIF, WebP, PNG (APNG), HEIF and AVIF can be animated. */
    public static boolean mayBeAnimated(int format) {
        return format == IDecodeService.FORMAT_GIF || format == IDecodeService.FORMAT_WEBP
                || format == IDecodeService.FORMAT_PNG || format == IDecodeService.FORMAT_HEIF
                || format == IDecodeService.FORMAT_AVIF;
    }

    public static String check(ImageInfo i) {
        if (i == null || !status(i.status)) return "status";
        if (i.sourceError != 0 && !(i.status == IDecodeService.STATUS_INVALID && i.sourceError == 2)) {
            return "sourceError";
        }
        if (i.status != IDecodeService.STATUS_OK) return null;
        if (!format(i.format)) return "format";
        if (!size(i.width, i.height, IDecodeService.MAX_DIMENSION, IDecodeService.MAX_DIMENSION)) return "size";
        if (!isColorSpace(i.colorSpace, i.colorDescription)) return "colorSpace";
        if (i.animated && !mayBeAnimated(i.format)) return "animated";
        if (!isBitDepth(i.bitDepth)) return "bitDepth";
        return null;
    }

    /** maxWidth x maxHeight is the box as shown: turned by 90 or 270 degrees, the stored image fits it turned. */
    public static String check(Thumbnail t, int maxWidth, int maxHeight) {
        if (t == null || !status(t.status)) return "status";
        if (t.sourceError != 0 && t.sourceError != 2 && t.sourceError != 3) return "sourceError";
        if (t.status != IDecodeService.STATUS_OK) return t.sourceError == 0 ? null : "sourceError";
        final int pixelBytes = bytesPerPixel(t.pixelFormat);
        if (pixelBytes == 0) return "pixelFormat";
        if ((t.pixelFormat & 2) != 0 && (t.rotation != 0 || t.mirrored
                || t.gainmapWidth != 0 || t.gainmapHeight != 0)) return "straight geometry";
        boolean turned = t.rotation == 90 || t.rotation == 270;
        if (!turned && t.rotation != 0 && t.rotation != 180) return "rotation";
        if (!size(t.width, t.height, turned ? maxHeight : maxWidth, turned ? maxWidth : maxHeight)) return "size";
        if ((long) t.width * t.height > (pixelBytes == 8 ? IDecodeService.MAX_HIGH_PRECISION_PIXELS : IDecodeService.MAX_PIXELS)) return "pixels";
        if (t.stripeRows < 1 || t.stripeRows > t.height
                || (long) t.stripeRows * t.width * pixelBytes > IDecodeService.MAX_STRIPE_BYTES) {
            return "stripeRows";
        }
        if (t.handle == 0) return "handle";
        if (!isColorSpace(t.colorSpace, t.colorDescription)) {
            return "colorSpace";
        }
        if (t.gainmapWidth == 0 && t.gainmapHeight == 0) {
            return t.gainmapParams == null || t.gainmapParams.length == 0 ? null : "gainmapParams";
        }
        if (t.gainmapWidth < 1 || t.gainmapHeight < 1 || t.gainmapWidth > t.width || t.gainmapHeight > t.height) {
            return "gainmap size";
        }
        if (t.gainmapStripeRows < 1 || t.gainmapStripeRows > t.gainmapHeight
                || (long) t.gainmapStripeRows * t.gainmapWidth * 4 > IDecodeService.MAX_STRIPE_BYTES) {
            return "gainmapStripeRows";
        }
        return gainmapParams(t.gainmapParams) ? null : "gainmapParams";
    }

    /**
     * Gain map parameters: finite, ratios from 2^-16 to 2^16 with min at most max, gamma above 0 up to 16, epsilons 0
     * to 1, display ratios from 1 to 2^16 with SDR at most HDR.
     */
    public static boolean gainmapParams(float[] p) {
        if (p == null || p.length != IDecodeService.GAINMAP_PARAMS) return false;
        for (float v : p) {
            if (!Float.isFinite(v)) return false;
        }
        for (int c = 0; c < 3; c++) {
            float lo = p[c], hi = p[3 + c], gamma = p[6 + c], es = p[9 + c], eh = p[12 + c];
            if (!(lo >= 1f / 65536 && lo <= hi && hi <= 65536)) return false;
            if (!(gamma > 0 && gamma <= 16)) return false;
            if (!(es >= 0 && es <= 1 && eh >= 0 && eh <= 1)) return false;
        }
        return p[15] >= 1 && p[15] <= p[16] && p[16] <= 65536;
    }

    /** Gain map stripe index of t: exactly its rows, every alpha 255. */
    public static String checkGainmap(Thumbnail t, int index, byte[] stripe) {
        long first = (long) index * t.gainmapStripeRows;
        if (t.gainmapHeight == 0 || index < 0 || first >= t.gainmapHeight) return "index";
        long rows = Math.min(t.gainmapStripeRows, t.gainmapHeight - first);
        if (stripe == null || stripe.length != rows * t.gainmapWidth * 4) return "stripe length";
        for (int i = 3; i < stripe.length; i += 4) {
            if (stripe[i] != (byte) 255) return "gainmap alpha";
        }
        return null;
    }

    /**
     * The android.graphics.Gainmap of a checked thumbnail, its pixels read and checked (checkGainmap), or null
     * without one or before Android 14.
     */
    @android.annotation.TargetApi(34)
    public static android.graphics.Gainmap gainmapOf(Thumbnail t, byte[] pixels) {
        if (t.gainmapWidth == 0 || android.os.Build.VERSION.SDK_INT < 34) return null;
        android.graphics.Bitmap b = android.graphics.Bitmap.createBitmap(t.gainmapWidth, t.gainmapHeight,
                android.graphics.Bitmap.Config.ARGB_8888);
        b.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(pixels));
        android.graphics.Gainmap g = new android.graphics.Gainmap(b);
        float[] p = t.gainmapParams;
        g.setRatioMin(p[0], p[1], p[2]);
        g.setRatioMax(p[3], p[4], p[5]);
        g.setGamma(p[6], p[7], p[8]);
        g.setEpsilonSdr(p[9], p[10], p[11]);
        g.setEpsilonHdr(p[12], p[13], p[14]);
        g.setMinDisplayRatioForHdrTransition(p[15]);
        g.setDisplayRatioForFullHdr(p[16]);
        return g;
    }

    /** The rules of decoded frames for a box of maxWidth x maxHeight. Null when they hold, else what broke. */
    public static String check(Frames f, int maxWidth, int maxHeight) {
        if (f == null || !status(f.status)) return "status";
        if (f.status != IDecodeService.STATUS_OK) return null;
        if (!size(f.width, f.height, maxWidth, maxHeight)) return "size";
        if (f.frameCount < 1 || f.frameCount > IDecodeService.MAX_FRAMES) return "frameCount";
        if ((long) f.frameCount * f.width * f.height * 4 > IDecodeService.MAX_FRAMES_BYTES) return "bytes";
        if (f.loopCount < 0 || f.loopCount > 65535) return "loopCount";
        if (f.durations == null || f.durations.length != f.frameCount) return "durations";
        for (int d : f.durations) {
            if (d < 0 || d > IDecodeService.MAX_FRAME_MILLIS) return "duration";
        }
        if (!isColorSpace(f.colorSpace, f.colorDescription)) {
            return "colorSpace";
        }
        long rows = (long) f.frameCount * f.height;
        if (f.stripeRows < 1 || f.stripeRows > rows
                || (long) f.stripeRows * f.width * 4 > IDecodeService.MAX_STRIPE_BYTES) {
            return "stripeRows";
        }
        if (f.handle == 0) return "handle";
        return null;
    }

    /** Stripe index of f's tall image: exactly its rows, premultiplied (no colour channel above alpha). */
    public static String check(Frames f, int index, byte[] stripe) {
        long rows = (long) f.frameCount * f.height;
        long first = (long) index * f.stripeRows;
        if (index < 0 || first >= rows) return "index";
        long n = Math.min(f.stripeRows, rows - first);
        if (stripe == null || stripe.length != n * f.width * 4) return "stripe length";
        for (int i = 0; i < stripe.length; i += 4) {
            int a = stripe[i + 3] & 0xff;
            if ((stripe[i] & 0xff) > a || (stripe[i + 1] & 0xff) > a || (stripe[i + 2] & 0xff) > a) {
                return "colour above alpha";
            }
        }
        return null;
    }

    /** The android.graphics.ColorSpace checked frames are built in. */
    public static android.graphics.ColorSpace colorSpaceOf(Frames f) {
        return colorSpaceOf(f.colorSpace, f.colorDescription);
    }

    /** The android.graphics.ColorSpace a checked thumbnail's Bitmap is built in. */
    public static android.graphics.ColorSpace colorSpaceOf(Thumbnail t) {
        return colorSpaceOf(t.colorSpace, t.colorDescription);
    }

    /** Only the complete named colour tag set is accepted. */
    public static boolean isColorSpace(int value) {
        return value >= IDecodeService.COLOR_SPACE_SRGB && value <= IDecodeService.COLOR_SPACE_LINEAR_SRGB;
    }

    /** A custom tag requires exactly the checked matrix and transfer values. */
    public static boolean isColorSpace(int value, float[] description) {
        return value == IDecodeService.COLOR_SPACE_CUSTOM ? isColorDescription(description)
                : isColorSpace(value) && (description == null || description.length == 0);
    }

    public static boolean isColorDescription(float[] p) {
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

    /** Build only from checked scalar values produced inside the VM. */
    public static android.graphics.ColorSpace colorSpaceOf(int value, float[] description) {
        if (!isColorSpace(value, description)) throw new IllegalArgumentException("unchecked colour description");
        if (value != IDecodeService.COLOR_SPACE_CUSTOM) return colorSpaceOf(value);
        final float[] xyz = java.util.Arrays.copyOf(description, 9);
        final android.graphics.ColorSpace.Rgb.TransferParameters transfer = new android.graphics.ColorSpace.Rgb.TransferParameters(
                description[9], description[10], description[11], description[12], description[13], description[14], description[15]);
        final android.graphics.ColorSpace matched = android.graphics.ColorSpace.match(xyz, transfer);
        return matched != null ? matched : new android.graphics.ColorSpace.Rgb("Unknown", xyz, transfer);
    }

    /** Build a colour space only after checking its scalar tag. */
    public static android.graphics.ColorSpace colorSpaceOf(int value) {
        final android.graphics.ColorSpace.Named named;
        switch (value) {
            case IDecodeService.COLOR_SPACE_SRGB: named = android.graphics.ColorSpace.Named.SRGB; break;
            case IDecodeService.COLOR_SPACE_DISPLAY_P3: named = android.graphics.ColorSpace.Named.DISPLAY_P3; break;
            case IDecodeService.COLOR_SPACE_ADOBE_RGB: named = android.graphics.ColorSpace.Named.ADOBE_RGB; break;
            case IDecodeService.COLOR_SPACE_PRO_PHOTO_RGB: named = android.graphics.ColorSpace.Named.PRO_PHOTO_RGB; break;
            case IDecodeService.COLOR_SPACE_BT2020: named = android.graphics.ColorSpace.Named.BT2020; break;
            case IDecodeService.COLOR_SPACE_BT2020_HLG: named = android.graphics.ColorSpace.Named.BT2020_HLG; break;
            case IDecodeService.COLOR_SPACE_BT2020_PQ: named = android.graphics.ColorSpace.Named.BT2020_PQ; break;
            case IDecodeService.COLOR_SPACE_LINEAR_SRGB: named = android.graphics.ColorSpace.Named.LINEAR_SRGB; break;
            default: throw new IllegalArgumentException("unchecked colour space");
        }
        return android.graphics.ColorSpace.get(named);
    }

    /** Bytes per pixel only for the complete explicit storage format set, zero for an unknown value. */
    public static int bytesPerPixel(int format) {
        if (format == IDecodeService.PIXEL_FORMAT_RGBA_8888
                || format == IDecodeService.PIXEL_FORMAT_STRAIGHT_RGBA_8888) return 4;
        if (format == IDecodeService.PIXEL_FORMAT_RGBA_F16
                || format == IDecodeService.PIXEL_FORMAT_STRAIGHT_RGBA_F16) return 8;
        return 0;
    }

    /** Source depths that the image contract can represent. */
    public static boolean isBitDepth(int depth) {
        return depth == 1 || depth == 2 || depth == 4 || depth == 8 || depth == 10 || depth == 12 || depth == 16;
    }

    private static int halfAt(byte[] bytes, int offset) {
        return (bytes[offset] & 255) | ((bytes[offset + 1] & 255) << 8);
    }

    /** Stripe index of t: exactly its rows, premultiplied (no colour channel above alpha). */
    public static String check(Thumbnail t, int index, byte[] stripe) {
        final int pixelBytes = bytesPerPixel(t.pixelFormat);
        if (pixelBytes == 0) return "pixelFormat";
        long first = (long) index * t.stripeRows;
        if (index < 0 || first >= t.height) return "index";
        long rows = Math.min(t.stripeRows, t.height - first);
        if (stripe == null || stripe.length != rows * t.width * pixelBytes) return "stripe length";
        if (pixelBytes == 8) {
            for (int i = 0; i < stripe.length; i += 8) {
                final int alpha = halfAt(stripe, i + 6);
                // Positive half values sort like their bits, 0x3c00 is one and excludes every NaN and infinity.
                if (alpha > 0x3c00) return "half alpha";
                for (int channel = 0; channel < 3; channel++) {
                    final int value = halfAt(stripe, i + channel * 2);
                    if (value > ((t.pixelFormat & 2) != 0 ? 0x3c00 : alpha)) return "half colour above alpha";
                }
            }
        } else if ((t.pixelFormat & 2) == 0) {
            for (int i = 0; i < stripe.length; i += 4) {
                int alpha = stripe[i + 3] & 255;
                if ((stripe[i] & 255) > alpha || (stripe[i + 1] & 255) > alpha || (stripe[i + 2] & 255) > alpha) {
                    return "colour above alpha";
                }
            }
        }
        return null;
    }
}
