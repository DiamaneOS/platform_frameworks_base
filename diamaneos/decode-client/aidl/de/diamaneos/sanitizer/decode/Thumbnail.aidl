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
 * holds rows [i * stripeRows, min(height, (i + 1) * stripeRows)), each row width * 4 bytes of premultiplied RGBA.
 * The caller checks every stripe's exact length and that no colour channel exceeds alpha.
 */
parcelable Thumbnail {
    /** IDecodeService.STATUS_*. The other fields are only meaningful with STATUS_OK. */
    int status;

    /** Decoded size: within the requested box, width * height at most IDecodeService.MAX_PIXELS. */
    int width;
    int height;

    /** Rows per stripe, so that stripeRows * width * 4 is at most IDecodeService.MAX_STRIPE_BYTES. */
    int stripeRows;

    /** Orientation from the file, applied by the caller: 0, 90, 180 or 270. */
    int rotation;

    /** For readStripe and release. Valid until release or IDecodeService.HANDLE_TIMEOUT_MILLIS. */
    long handle;
}
