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
 * The image metadata MediaProvider stores, as a fixed record. No free-form blobs: every string is limited in length
 * and characters, and the caller rejects the whole record when a field breaks its rule. Absent fields are -1, NaN
 * or empty.
 */
parcelable MetadataRecord {
    /** IDecodeService.STATUS_*. The other fields are only meaningful with STATUS_OK. */
    int status;

    int format;
    int width;
    int height;

    /** 0, 90, 180, 270, or -1. */
    int orientation;

    /**
     * DateTimeOriginal in UTC milliseconds, -1 when absent (and then offsetKnown is false). Otherwise from 1900-01-01
     * to 9999-12-31 with a day of slack, and at most a day after the current time. offsetKnown when
     * OffsetTimeOriginal was present.
     */
    long dateTakenMillis;
    boolean offsetKnown;

    /** GPS position in degrees, NaN when absent. Latitude -90 to 90, longitude -180 to 180. */
    double latitude;
    double longitude;

    /**
     * Printable text, at most MAX_TEXT_CHARS: no control characters (C0, DEL, C1), no line or paragraph separators,
     * no bidi controls (U+061C, U+200E, U+200F, U+202A to U+202E, U+2066 to U+2069), no zero-width characters
     * (U+180E, U+200B to U+200D, U+2060, U+FEFF), no unpaired surrogates.
     */
    String description;

    /** EXIF numbers as text: at most MAX_NUMBER_CHARS of 0-9 . / and space. */
    String exposureTime;
    String fNumber;
    String iso;
    String sceneCaptureType;

    /** XMP identifiers: at most MAX_ID_CHARS of A-Z a-z 0-9 : . - _ */
    String documentId;
    String instanceId;
    String originalDocumentId;

    /** MediaStore special format: none, GIF, motion photo, animated WebP. */
    int specialFormat;
}
