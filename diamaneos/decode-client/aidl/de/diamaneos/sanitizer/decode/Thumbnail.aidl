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
 * A decoded, scaled image held by the service. The pixels come in stripes through readStripe(handle, index): stripe i
 * holds rows [i * stripeRows, min(height, (i + 1) * stripeRows)). RGBA8888 uses four bytes per pixel.
 * RGBAF16 uses eight bytes, four little-endian half values in [0, 1]. Explicit straight requests retain RGB above alpha.
 * The caller checks every stripe's exact length and that no colour channel exceeds alpha.
 */
parcelable Thumbnail {
    /** IDecodeService.STATUS_*. The other fields are only meaningful with STATUS_OK. */
    int status;

    /** Decoded size: within the requested box, width * height at most MAX_PIXELS, or MAX_HIGH_PRECISION_PIXELS for F16. */
    int width;
    int height;

    /** Rows per stripe, so that stripeRows * width * bytesPerPixel(pixelFormat) is at most IDecodeService.MAX_STRIPE_BYTES. */
    int stripeRows;

    /** Orientation from the file, applied by the caller: 0, 90, 180 or 270. */
    int rotation;

    /** For readStripe and release. Valid until release or IDecodeService.HANDLE_TIMEOUT_MILLIS. */
    long handle;

    /**
     * The checked pixels' colour space, IDecodeService.COLOR_SPACE_*. A custom scalar tag requires the complete
     * colorDescription. The caller builds its Bitmap in that checked space.
     */
    int colorSpace;

    /**
     * Ultra HDR: the gain map's size, both 0 when there is none. At most the image's size, read through
     * IDecodeService.readGainmapStripe in stripes of gainmapStripeRows rows of gainmapWidth * 4 bytes of RGBA, alpha
     * 255. The caller attaches it to the Bitmap as an android.graphics.Gainmap, turned with the image.
     */
    int gainmapWidth;
    int gainmapHeight;
    int gainmapStripeRows;

    /**
     * The gain map's parameters as android.graphics.Gainmap takes them: ratio min (3), ratio max (3), gamma (3),
     * epsilon SDR (3), epsilon HDR (3), then the display ratio of SDR and of full HDR. IDecodeService.GAINMAP_PARAMS
     * values, or empty without a gain map.
     */
    float[] gainmapParams;

    /**
     * True when the file's orientation mirrors the image (EXIF 2, 4, 5 and 7, HEIF imir): the pixels are mirrored
     * left to right already, and only the rotation is left to the caller. Last, so a caller built before it reads
     * false.
     */
    boolean mirrored;

    /** IDecodeService.PIXEL_FORMAT_*, checked before any stripe is allocated or copied. */
    int pixelFormat = 0;

    /** Zero for complete data, 2 for incomplete source data, 3 for malformed source data. */
    int sourceError = 0;
    /** Exactly 16 checked XYZ D50 and transfer values for COLOR_SPACE_CUSTOM, empty for named tags. */
    float[] colorDescription;
}
