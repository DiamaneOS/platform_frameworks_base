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
        return checkHeader(width, height, stripeRows, rotation, maxWidth, maxHeight, 0);
    }

    public static int checkHeader(int width, int height, int stripeRows, int rotation, int maxWidth, int maxHeight,
            int pixelFormat) throws BadReplyException {
        final int bytes = pixelFormat == 0 ? 4 : pixelFormat == 1 ? 8 : 0;
        if (bytes == 0) throw new BadReplyException("pixel format");
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
        if ((long) width * height > (pixelFormat == 1 ? MAX_PIXELS / 2 : MAX_PIXELS)) {
            throw new BadReplyException("too many pixels: " + width + "x" + height);
        }
        if (stripeRows < 1 || stripeRows > height || (long) stripeRows * width * bytes > MAX_STRIPE_BYTES) {
            throw new BadReplyException("bad stripe rows " + stripeRows);
        }
        return (height + stripeRows - 1) / stripeRows;
    }

    /** The exact length stripe {@code index} must have. */
    public static int stripeLength(int width, int height, int stripeRows, int index) throws BadReplyException {
        return stripeLength(width, height, stripeRows, index, 0);
    }

    public static int stripeLength(int width, int height, int stripeRows, int index, int pixelFormat)
            throws BadReplyException {
        final int bytes = pixelFormat == 0 ? 4 : pixelFormat == 1 ? 8 : 0;
        if (bytes == 0) throw new BadReplyException("pixel format");
        if (index < 0) {
            throw new BadReplyException("bad stripe index " + index);
        }
        final long first = (long) index * stripeRows;
        if (first >= height) {
            throw new BadReplyException("stripe index past the end " + index);
        }
        final long rows = Math.min(stripeRows, height - first);
        return (int) (rows * width * bytes);
    }

    /**
     * Checks stripe {@code index} of a width x height image of premultiplied RGBA: its exact length and that no colour
     * channel is above alpha. Returns its length. A stripe that breaks either is refused, as the contract's own check
     * (DecodeChecks) refuses it: the whole image is then a placeholder.
     */
    public static int checkStripe(int width, int height, int stripeRows, int index, byte[] stripe)
            throws BadReplyException {
        return checkStripe(width, height, stripeRows, index, stripe, 0);
    }

    public static int checkStripe(int width, int height, int stripeRows, int index, byte[] stripe, int pixelFormat)
            throws BadReplyException {
        final int expected = stripeLength(width, height, stripeRows, index, pixelFormat);
        if (stripe == null || stripe.length != expected) {
            throw new BadReplyException("stripe " + index + " has " + (stripe == null ? -1 : stripe.length)
                    + " bytes, expected " + expected);
        }
        if (pixelFormat == 1) {
            for (int at = 0; at < expected; at += 8) {
                final int alpha = halfWord(stripe, at + 6);
                if (alpha > 0x3c00) throw new BadReplyException("wide alpha");
                for (int channel = 0; channel < 3; channel++) {
                    if (halfWord(stripe, at + channel * 2) > alpha) throw new BadReplyException("wide colour");
                }
            }
            return expected;
        }
        for (int i = 0; i < expected; i += 4) {
            final int a = stripe[i + 3] & 0xff;
            if ((stripe[i] & 0xff) > a || (stripe[i + 1] & 0xff) > a || (stripe[i + 2] & 0xff) > a) {
                throw new BadReplyException("stripe " + index + ": colour above alpha at byte " + i);
            }
        }
        return expected;
    }

    private static int halfWord(byte[] stripe, int at) {
        return (stripe[at] & 255) | ((stripe[at + 1] & 255) << 8);
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
}
