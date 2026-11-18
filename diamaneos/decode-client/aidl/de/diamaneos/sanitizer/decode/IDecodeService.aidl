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

import de.diamaneos.sanitizer.decode.DecodeRequest;
import de.diamaneos.sanitizer.decode.ImageInfo;
import de.diamaneos.sanitizer.decode.MetadataRecord;
import de.diamaneos.sanitizer.decode.Thumbnail;

/**
 * Decodes untrusted media inside a protected VM. Callers hold de.diamaneos.sanitizer.permission.DECODE (signature or
 * privileged, allowlisted) and the service also checks the calling package against its own list.
 *
 * Fail closed: STATUS_UNAVAILABLE means the VM could not do the work in time (not running, refused, restarting, queue
 * full). The caller shows a placeholder or stores no value and asks again later. It never decodes the file itself.
 * There is no switch that turns this off.
 */
interface IDecodeService {
    const int STATUS_OK = 0;
    /** The VM is not available right now. Ask again later, never decode in the caller. */
    const int STATUS_UNAVAILABLE = 1;
    /** Not a format the VM decodes. Treat as undecodable (placeholder), do not retry. */
    const int STATUS_UNSUPPORTED = 2;
    /** Broken or hostile file, or a limit was hit. Treat as undecodable, do not retry. */
    const int STATUS_INVALID = 3;

    const int PRIORITY_USER_WAITING = 0;
    const int PRIORITY_BACKGROUND = 1;

    const int FORMAT_UNKNOWN = 0;
    const int FORMAT_JPEG = 1;
    const int FORMAT_PNG = 2;
    const int FORMAT_GIF = 3;
    const int FORMAT_WEBP = 4;
    const int FORMAT_BMP = 5;
    const int FORMAT_HEIF = 6;
    const int FORMAT_AVIF = 7;
    const int FORMAT_DNG = 8;
    const int FORMAT_ICO = 9;
    const int FORMAT_WBMP = 10;

    /**
     * Thumbnails and metadata: a longer input is refused (STATUS_INVALID). A regular file the service can read without
     * stalling (not on FUSE) longer than MAX_BUFFERED_BYTES is streamed to the VM, never held whole, and is decoded
     * when it is a JPEG or PNG. Any other format above MAX_BUFFERED_BYTES is refused.
     */
    const int MAX_INPUT_BYTES = 268435456;
    /** Inputs the service holds in memory while they are decoded: a pipe, a file on FUSE, or any non-JPEG/PNG file. */
    const int MAX_BUFFERED_BYTES = 33554432;
    /** A probe reads at most this much from the start of the input, so it works for inputs of any size. */
    const int MAX_PROBE_BYTES = 1048576;
    const int MAX_DIMENSION = 65535;
    const int MAX_PIXELS = 8388608;
    const int MAX_STRIPE_BYTES = 262144;
    const int MAX_TEXT_CHARS = 256;
    const int MAX_NUMBER_CHARS = 32;
    const int MAX_ID_CHARS = 128;
    const int HANDLE_TIMEOUT_MILLIS = 30000;

    /** Header only: format, size, animation, from at most MAX_PROBE_BYTES. No pixels are decoded. */
    ImageInfo probe(in DecodeRequest request);

    /**
     * Decodes and scales the image so that, once turned by Thumbnail.rotation, it fits in maxWidth x maxHeight with
     * its aspect ratio kept. The first frame of an animation.
     */
    Thumbnail decodeThumbnail(in DecodeRequest request, int maxWidth, int maxHeight);

    /** Stripe index of a decoded thumbnail. Exactly the documented length, or an empty array when the handle is gone. */
    byte[] readStripe(long handle, int index);

    /** Frees a thumbnail's pixels in the service. */
    void release(long handle);

    /** The fixed metadata record for an image. */
    MetadataRecord readMetadata(in DecodeRequest request);
}
