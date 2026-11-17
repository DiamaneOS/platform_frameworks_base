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

package de.diamaneos.decode.client;

/**
 * Checks everything the decode service sends back before the system uses it. The VM behind the service runs code
 * that a crafted file may have taken over, so every value here is hostile until checked. Plain Java with no Android
 * classes, so it is tested off the device too.
 */
public final class StripeValidator {
    // The same limits as de.diamaneos.sanitizer.decode.IDecodeService.
    public static final int MAX_DIMENSION = 65535;
    public static final int MAX_PIXELS = 8388608;
    public static final int MAX_STRIPE_BYTES = 262144;
    public static final int MAX_TEXT_CHARS = 256;
    public static final int MAX_NUMBER_CHARS = 32;
    public static final int MAX_ID_CHARS = 128;
    public static final int MAX_FORMAT = 10;
    public static final int FORMAT_PNG = 2;
    public static final int FORMAT_GIF = 3;
    public static final int FORMAT_WEBP = 4;
    public static final int FORMAT_HEIF = 6;
    public static final int FORMAT_AVIF = 7;

    private StripeValidator() {
    }

    /** Thrown for any value outside the contract. The caller treats the file as undecodable. */
    public static final class BadReplyException extends Exception {
        public BadReplyException(String message) {
            super(message);
        }
    }

    /**
     * Checks a thumbnail header against the box the caller asked for and returns the number of stripes. The pixels are
     * not yet turned: for a rotation of 90 or 270 the box is checked the other way round.
     */
    public static int checkHeader(int width, int height, int stripeRows, int rotation, int maxWidth, int maxHeight)
            throws BadReplyException {
        if (maxWidth < 1 || maxHeight < 1) {
            throw new BadReplyException("bad box " + maxWidth + "x" + maxHeight);
        }
        if (rotation != 0 && rotation != 90 && rotation != 180 && rotation != 270) {
            throw new BadReplyException("bad rotation " + rotation);
        }
        if (width < 1 || height < 1 || width > MAX_DIMENSION || height > MAX_DIMENSION) {
            throw new BadReplyException("bad size " + width + "x" + height);
        }
        final boolean turned = rotation == 90 || rotation == 270;
        if (width > (turned ? maxHeight : maxWidth) || height > (turned ? maxWidth : maxHeight)) {
            throw new BadReplyException("bigger than asked: " + width + "x" + height);
        }
        if ((long) width * height > MAX_PIXELS) {
            throw new BadReplyException("too many pixels: " + width + "x" + height);
        }
        if (stripeRows < 1 || stripeRows > height || (long) stripeRows * width * 4 > MAX_STRIPE_BYTES) {
            throw new BadReplyException("bad stripe rows " + stripeRows);
        }
        return (height + stripeRows - 1) / stripeRows;
    }

    /** The exact length stripe {@code index} must have. */
    public static int stripeLength(int width, int height, int stripeRows, int index) throws BadReplyException {
        if (index < 0) {
            throw new BadReplyException("bad stripe index " + index);
        }
        final long first = (long) index * stripeRows;
        if (first >= height) {
            throw new BadReplyException("stripe index past the end " + index);
        }
        final long rows = Math.min(stripeRows, height - first);
        return (int) (rows * width * 4);
    }

    /**
     * Copies stripe {@code index} into {@code frame} (width * height * 4 bytes of premultiplied RGBA) after checking its
     * exact length. Colour channels above alpha are clamped, so the pixels are valid premultiplied values whatever the
     * VM sent.
     */
    public static void putStripe(byte[] frame, int width, int height, int stripeRows, int index, byte[] stripe)
            throws BadReplyException {
        if (frame == null || frame.length != width * height * 4) {
            throw new BadReplyException("bad frame");
        }
        final int expected = stripeLength(width, height, stripeRows, index);
        if (stripe == null || stripe.length != expected) {
            throw new BadReplyException("stripe " + index + " has " + (stripe == null ? -1 : stripe.length)
                    + " bytes, expected " + expected);
        }
        final int offset = index * stripeRows * width * 4;
        for (int i = 0; i < expected; i += 4) {
            final int a = stripe[i + 3] & 0xff;
            frame[offset + i] = (byte) Math.min(stripe[i] & 0xff, a);
            frame[offset + i + 1] = (byte) Math.min(stripe[i + 1] & 0xff, a);
            frame[offset + i + 2] = (byte) Math.min(stripe[i + 2] & 0xff, a);
            frame[offset + i + 3] = (byte) a;
        }
    }

    /** Checks a probe result. */
    public static void checkInfo(int format, int width, int height) throws BadReplyException {
        checkInfo(format, width, height, false);
    }

    /** Checks a probe result, including the formats that can be animated (contract service-api 0a8413f). */
    public static void checkInfo(int format, int width, int height, boolean animated) throws BadReplyException {
        if (format < 0 || format > MAX_FORMAT) {
            throw new BadReplyException("bad format " + format);
        }
        if (width < 1 || height < 1 || width > MAX_DIMENSION || height > MAX_DIMENSION) {
            throw new BadReplyException("bad size " + width + "x" + height);
        }
        if (animated && format != FORMAT_GIF && format != FORMAT_WEBP && format != FORMAT_PNG
                && format != FORMAT_HEIF && format != FORMAT_AVIF) {
            throw new BadReplyException("animated format " + format);
        }
    }

    // The service's limits for a date taken: 1900-01-01 to 9999-12-31, with a day of slack for the offset.
    public static final long MIN_DATE_MILLIS = -2208988800000L - 86400000L;
    public static final long MAX_DATE_MILLIS = 253402300799999L + 86400000L;

    /** A date taken in UTC milliseconds, or -1 for none, at most a day after {@code nowMillis}. */
    public static long checkDate(long millis, long nowMillis) throws BadReplyException {
        if (millis != -1 && (millis < MIN_DATE_MILLIS || millis > MAX_DATE_MILLIS
                || millis > nowMillis + 86400000L)) {
            throw new BadReplyException("bad date " + millis);
        }
        return millis;
    }

    /** {@link #checkDate(long, long)} against the current time. */
    public static long checkDate(long millis) throws BadReplyException {
        return checkDate(millis, System.currentTimeMillis());
    }

    /**
     * Free text: at most {@code max} characters, no control characters (C0, DEL, C1), no line or paragraph separators,
     * no bidi controls (including U+061C), no zero-width characters, no unpaired surrogates. Null and empty stay
     * empty.
     */
    public static String checkText(String text, int max) throws BadReplyException {
        if (text == null || text.isEmpty()) {
            return "";
        }
        if (text.length() > max) {
            throw new BadReplyException("text too long: " + text.length());
        }
        for (int i = 0; i < text.length(); i++) {
            final char c = text.charAt(i);
            if (c < 0x20 || (c >= 0x7f && c <= 0x9f) || (c >= 0x200b && c <= 0x200f)
                    || c == 0x2028 || c == 0x2029 || c == 0x061c || c == 0x180e || c == 0x2060
                    || (c >= 0x202a && c <= 0x202e) || (c >= 0x2066 && c <= 0x2069) || c == 0xfeff) {
                throw new BadReplyException("control character in text at " + i);
            }
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= text.length() || !Character.isLowSurrogate(text.charAt(i + 1))) {
                    throw new BadReplyException("unpaired surrogate at " + i);
                }
                i++;
            } else if (Character.isLowSurrogate(c)) {
                throw new BadReplyException("unpaired surrogate at " + i);
            }
        }
        return text;
    }

    /** An EXIF number as text: digits, '.', '/' and space only. */
    public static String checkNumber(String text) throws BadReplyException {
        return checkCharset(text, MAX_NUMBER_CHARS, "0123456789./ ");
    }

    /** An XMP identifier: letters, digits, ':', '.', '-', '_'. */
    public static String checkId(String text) throws BadReplyException {
        return checkCharset(text, MAX_ID_CHARS,
                "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789:.-_");
    }

    private static String checkCharset(String text, int max, String allowed) throws BadReplyException {
        if (text == null || text.isEmpty()) {
            return "";
        }
        if (text.length() > max) {
            throw new BadReplyException("value too long: " + text.length());
        }
        for (int i = 0; i < text.length(); i++) {
            if (allowed.indexOf(text.charAt(i)) < 0) {
                throw new BadReplyException("bad character at " + i);
            }
        }
        return text;
    }

    /** Orientation: 0, 90, 180, 270, or -1 for none. */
    public static int checkOrientation(int orientation) throws BadReplyException {
        if (orientation != -1 && orientation != 0 && orientation != 90 && orientation != 180 && orientation != 270) {
            throw new BadReplyException("bad orientation " + orientation);
        }
        return orientation;
    }

    /** A position in degrees, or NaN for none. Both NaN or both in range. */
    public static void checkLocation(double latitude, double longitude) throws BadReplyException {
        final boolean none = Double.isNaN(latitude) && Double.isNaN(longitude);
        final boolean valid = latitude >= -90 && latitude <= 90 && longitude >= -180 && longitude <= 180;
        if (!none && !valid) {
            throw new BadReplyException("bad location");
        }
    }
}
