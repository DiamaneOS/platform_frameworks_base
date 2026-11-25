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

import de.diamaneos.sanitizer.decode.AppImageFiles;
import de.diamaneos.sanitizer.decode.AppImagesRequest;
import de.diamaneos.sanitizer.decode.DecodeRequest;
import de.diamaneos.sanitizer.decode.Frames;
import de.diamaneos.sanitizer.decode.ImageInfo;
import de.diamaneos.sanitizer.decode.Thumbnail;

/**
 * Decodes untrusted media inside a protected VM. Callers hold de.diamaneos.sanitizer.permission.DECODE (signature or
 * privileged, allowlisted) and the service also checks the calling package against its own list.
 *
 * Fail closed: STATUS_UNAVAILABLE means the VM could not do the work in time (not running, refused, restarting, queue
 * full). STATUS_NO_ROOM means the service had no memory for this request while other large images held it. Either
 * way the caller shows a placeholder or stores no value and asks again: later, or shortly after STATUS_NO_ROOM. It
 * never decodes the file itself. There is no switch that turns this off.
 */
interface IDecodeService {
    const int STATUS_OK = 0;
    /** The VM is not available right now. Ask again later, never decode in the caller. */
    const int STATUS_UNAVAILABLE = 1;
    /** Not a format the VM decodes. Treat as undecodable (placeholder), do not retry. */
    const int STATUS_UNSUPPORTED = 2;
    /** Broken or hostile file, or a limit was hit. Treat as undecodable, do not retry. */
    const int STATUS_INVALID = 3;
    /**
     * No memory for this request now: other large images hold the service's budget. The VM works and nothing is known
     * about the file. Ask again shortly, never decode in the caller.
     */
    const int STATUS_NO_ROOM = 4;

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

    /** Checked colour tags for ImageInfo, Thumbnail and Frames. */
    const int COLOR_SPACE_SRGB = 0;
    const int COLOR_SPACE_DISPLAY_P3 = 1;
    const int COLOR_SPACE_ADOBE_RGB = 2;
    const int COLOR_SPACE_PRO_PHOTO_RGB = 3;
    const int COLOR_SPACE_BT2020 = 4;
    const int COLOR_SPACE_BT2020_HLG = 5;
    const int COLOR_SPACE_BT2020_PQ = 6;
    const int COLOR_SPACE_LINEAR_SRGB = 7;
    const int COLOR_SPACE_CUSTOM = 8;

    /**
     * Thumbnails: a longer input is refused (STATUS_INVALID). A regular file the service can read without
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
    /** Explicit raw pixel storage, with an unchanged 32 MiB base-pixel budget. */
    const int PIXEL_FORMAT_RGBA_8888 = 0;
    const int PIXEL_FORMAT_RGBA_F16 = 1;
    const int PIXEL_FORMAT_STRAIGHT_RGBA_8888 = 2;
    const int PIXEL_FORMAT_STRAIGHT_RGBA_F16 = 3;
    const int MAX_HIGH_PRECISION_PIXELS = 4194304;
    const int MAX_STRIPE_BYTES = 262144;
    const int HANDLE_TIMEOUT_MILLIS = 30000;

    /** Frames: bounded object storage, frame count, delays and total pixel payload. */
    const int FRAME_OBJECT_BYTES = 4096;
    const int MAX_FRAME_OBJECT_BYTES = 33554432;
    const int MAX_FRAMES = 8192;
    const int MAX_FRAME_MILLIS = 16777215;
    const int MAX_FRAMES_BYTES = 33554432;
    /** A window's animation (DecodeRequest.frameWindow): at most this many frames, 4 bytes of timeline each. */
    const int MAX_TIMELINE_FRAMES = 1048576;

    /** decodeRegion: the largest sample size. */
    const int MAX_REGION_SAMPLE = 64;

    /** Thumbnail.gainmapParams: this many values. */
    const int GAINMAP_PARAMS = 17;

    /** AppImageFiles: at most this many files for one app. */
    const int MAX_APP_IMAGE_FILES = 256;

    /** DecodeRequest.ownerUser when the caller names no user (UserHandle.USER_NULL). */
    const int NO_USER = -10000;

    /** Header only: format, size, animation, from at most MAX_PROBE_BYTES. No pixels are decoded. */
    ImageInfo probe(in DecodeRequest request);

    /**
     * Decodes and scales the image so that, once turned by Thumbnail.rotation, it fits in maxWidth x maxHeight with
     * its aspect ratio kept. The first frame of an animation.
     */
    Thumbnail decodeThumbnail(in DecodeRequest request, int maxWidth, int maxHeight);

    /**
     * Stripe index of a decoded thumbnail, or of decoded frames (one tall image). Exactly the documented length, or an
     * empty array when the handle is gone.
     */
    byte[] readStripe(long handle, int index);

    /** Frees a thumbnail's or frames' pixels in the service. */
    void release(long handle);

    /**
     * A supported animation, every frame fitting maxWidth x maxHeight with its aspect ratio kept, scaled down
     * further when all frames would exceed MAX_FRAMES_BYTES, and at most MAX_FRAMES of them. STATUS_UNSUPPORTED for
     * another format. With DecodeRequest.frameWindow only that window, every frame at the size it has in any window
     * of the animation. Last, so callers built before it keep their transaction numbers.
     */
    Frames decodeFrames(in DecodeRequest request, int maxWidth, int maxHeight);

    /**
     * Stripe index of a thumbnail's gain map (Thumbnail.gainmapStripeRows rows of gainmapWidth * 4 bytes). Exactly
     * that length, or an empty array when the handle is gone or has no gain map.
     */
    byte[] readGainmapStripe(long handle, int index);

    /**
     * The image files an app's splash screens and launcher icons name, for the clean icon cache. Resolved here (the
     * app's resource table and XML drawables, attribute references only, nothing inflated or decoded), so the system
     * server parses none of the app's XML. Only the system server may call it. Last, so callers built before it keep
     * their transaction numbers.
     */
    AppImageFiles listAppImageFiles(in AppImagesRequest request);

    /**
     * A region of the stored image, never turned or mirrored (as Android's region decoder reads it): left, top,
     * width and height in its pixels, decoded at full detail and scaled down by sampleSize (1 to MAX_REGION_SAMPLE) to
     * at most ceil(width / sampleSize) x ceil(height / sampleSize), which must be at most MAX_PIXELS. A Thumbnail
     * with rotation 0 and no gain map. STATUS_INVALID for a region outside the image. Last, so callers built before it
     * keep their transaction numbers.
     */
    Thumbnail decodeRegion(in DecodeRequest request, int left, int top, int width, int height, int sampleSize);

    /**
     * A hint: per-user content of the caller's user follows soon (Files or the photo picker opened a view of images).
     * The service may start the VM for that user now, before the first thumbnail. The user is decided as for a
     * decode (DecodeRequest.ownerUser), the request carries no file. Nothing is decoded or answered. Last, so callers
     * built before it keep their transaction numbers.
     */
    oneway void prepare(in DecodeRequest request);
}
