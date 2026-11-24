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

/** What the VM found in a file's header. Every field is range-checked by the service and again by the caller. */
parcelable ImageInfo {
    /** IDecodeService.STATUS_*. The other fields are only meaningful with STATUS_OK. */
    int status;

    /** IDecodeService.FORMAT_*. */
    int format;

    /** Full image size, 1 to IDecodeService.MAX_DIMENSION each. */
    int width;
    int height;

    /** True for an image with more than one frame. Only GIF, WebP, PNG (APNG), HEIF and AVIF can be animated. */
    boolean animated;
}
