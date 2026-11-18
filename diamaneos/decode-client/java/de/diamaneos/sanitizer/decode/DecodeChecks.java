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

    private static final long DAY = 86400000L;
    private static final long MIN_DATE = -2208988800000L - DAY; // 1900-01-01, a day of slack
    private static final long MAX_DATE = 253402300799999L + DAY; // 9999-12-31, a day of slack

    private static boolean status(int s) {
        return s == IDecodeService.STATUS_OK || s == IDecodeService.STATUS_UNAVAILABLE
                || s == IDecodeService.STATUS_UNSUPPORTED || s == IDecodeService.STATUS_INVALID;
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
        if (i.status != IDecodeService.STATUS_OK) return null;
        if (!format(i.format)) return "format";
        if (!size(i.width, i.height, IDecodeService.MAX_DIMENSION, IDecodeService.MAX_DIMENSION)) return "size";
        if (i.animated && !mayBeAnimated(i.format)) return "animated";
        return null;
    }

    /** maxWidth x maxHeight is the box as shown: turned by 90 or 270 degrees, the stored image fits it turned. */
    public static String check(Thumbnail t, int maxWidth, int maxHeight) {
        if (t == null || !status(t.status)) return "status";
        if (t.status != IDecodeService.STATUS_OK) return null;
        boolean turned = t.rotation == 90 || t.rotation == 270;
        if (!turned && t.rotation != 0 && t.rotation != 180) return "rotation";
        if (!size(t.width, t.height, turned ? maxHeight : maxWidth, turned ? maxWidth : maxHeight)) return "size";
        if ((long) t.width * t.height > IDecodeService.MAX_PIXELS) return "pixels";
        if (t.stripeRows < 1 || t.stripeRows > t.height
                || (long) t.stripeRows * t.width * 4 > IDecodeService.MAX_STRIPE_BYTES) {
            return "stripeRows";
        }
        if (t.handle == 0) return "handle";
        return null;
    }

    /** Stripe index of t: exactly its rows, premultiplied (no colour channel above alpha). */
    public static String check(Thumbnail t, int index, byte[] stripe) {
        long first = (long) index * t.stripeRows;
        if (index < 0 || first >= t.height) return "index";
        long rows = Math.min(t.stripeRows, t.height - first);
        if (stripe == null || stripe.length != rows * t.width * 4) return "stripe length";
        for (int i = 0; i < stripe.length; i += 4) {
            int a = stripe[i + 3] & 0xff;
            if ((stripe[i] & 0xff) > a || (stripe[i + 1] & 0xff) > a || (stripe[i + 2] & 0xff) > a) {
                return "colour above alpha";
            }
        }
        return null;
    }

    /**
     * A date taken in the future (more than a day ahead of this phone's clock) is not believed: the date and its
     * offset are dropped and the rest of the record stays. A camera with a wrong clock, or this phone's own clock
     * behind, gives such dates, so it is not a reason to refuse the record. Call before check.
     */
    public static void dropFutureDate(MetadataRecord m, long nowMillis) {
        if (m != null && m.dateTakenMillis != -1 && m.dateTakenMillis > nowMillis + DAY) {
            m.dateTakenMillis = -1;
            m.offsetKnown = false;
        }
    }

    /** The record's rules. A date in the future is not checked here (dropFutureDate), only its range. */
    public static String check(MetadataRecord m, long nowMillis) {
        if (m == null || !status(m.status)) return "status";
        if (m.status != IDecodeService.STATUS_OK) return null;
        if (!format(m.format)) return "format";
        if (!size(m.width, m.height, IDecodeService.MAX_DIMENSION, IDecodeService.MAX_DIMENSION)) return "size";
        if (m.orientation != -1 && m.orientation != 0 && m.orientation != 90 && m.orientation != 180
                && m.orientation != 270) {
            return "orientation";
        }
        if (m.dateTakenMillis == -1) {
            if (m.offsetKnown) return "offset without date";
        } else if (m.dateTakenMillis < MIN_DATE || m.dateTakenMillis > MAX_DATE) {
            return "date";
        }
        boolean noLat = Double.isNaN(m.latitude), noLon = Double.isNaN(m.longitude);
        if (noLat != noLon) return "position half absent";
        if (!noLat && (m.latitude < -90 || m.latitude > 90 || m.longitude < -180 || m.longitude > 180)) {
            return "position";
        }
        if (!text(m.description, IDecodeService.MAX_TEXT_CHARS)) return "description";
        for (String n : new String[] {m.exposureTime, m.fNumber, m.iso, m.sceneCaptureType}) {
            if (!number(n)) return "number";
        }
        for (String id : new String[] {m.documentId, m.instanceId, m.originalDocumentId}) {
            if (!id(id)) return "identifier";
        }
        // GIF is always a GIF, an animated WebP is one, a motion photo is marked in its XMP (JPEG, PNG, WebP, HEIF,
        // AVIF): the same rule as the service's own check (GuestLink).
        boolean special = m.specialFormat == 0
                || (m.specialFormat == 1 && m.format == IDecodeService.FORMAT_GIF)
                || (m.specialFormat == 2 && (m.format == IDecodeService.FORMAT_JPEG
                        || m.format == IDecodeService.FORMAT_PNG || m.format == IDecodeService.FORMAT_WEBP
                        || m.format == IDecodeService.FORMAT_HEIF || m.format == IDecodeService.FORMAT_AVIF))
                || (m.specialFormat == 3 && m.format == IDecodeService.FORMAT_WEBP);
        if (!special) return "special format";
        return null;
    }

    /**
     * Printable text: no control characters (C0, DEL, C1), no line or paragraph separators, no bidi controls, no
     * zero-width characters, no unpaired surrogates, at most max chars.
     */
    public static boolean text(String s, int max) {
        if (s == null || s.length() > max) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 || (c >= 0x7f && c <= 0x9f)) return false;
            if (c == 0x2028 || c == 0x2029) return false;
            if (c == 0x061c || c == 0x200e || c == 0x200f || (c >= 0x202a && c <= 0x202e)
                    || (c >= 0x2066 && c <= 0x2069)) {
                return false;
            }
            if (c == 0x180e || (c >= 0x200b && c <= 0x200d) || c == 0x2060 || c == 0xfeff) return false;
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= s.length() || !Character.isLowSurrogate(s.charAt(i + 1))) return false;
                i++;
            } else if (Character.isLowSurrogate(c)) {
                return false;
            }
        }
        return true;
    }

    public static boolean number(String s) {
        if (s == null || s.length() > IDecodeService.MAX_NUMBER_CHARS) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!((c >= '0' && c <= '9') || c == '.' || c == '/' || c == ' ')) return false;
        }
        return true;
    }

    public static boolean id(String s) {
        if (s == null || s.length() > IDecodeService.MAX_ID_CHARS) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == ':' || c == '.'
                    || c == '-' || c == '_')) {
                return false;
            }
        }
        return true;
    }
}
