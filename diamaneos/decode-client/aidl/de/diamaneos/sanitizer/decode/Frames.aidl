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
 * A decoded animation held by the service: every frame as it is shown, composed onto the whole canvas,
 * all of the same size. The pixels come in stripes through readStripe(handle, index) as one tall image: frame f is
 * rows [f * height, (f + 1) * height). Stripe i holds rows [i * stripeRows, min(frameCount * height, (i + 1) *
 * stripeRows)), each row width * 4 bytes of premultiplied RGBA. The caller checks every stripe as for a Thumbnail.
 */
parcelable Frames {
    /** IDecodeService.STATUS_*. The other fields are only meaningful with STATUS_OK. */
    int status;

    /** Every frame's size: within the requested box, frameCount * width * height * 4 within MAX_FRAMES_BYTES. */
    int width;
    int height;

    /** 1 to IDecodeService.MAX_FRAMES. */
    int frameCount;

    /** How often the animation plays, 0: forever. At most 65535. */
    int loopCount;

    /** Each frame's time in milliseconds, frameCount values of 0 to IDecodeService.MAX_FRAME_MILLIS. */
    int[] durations;

    /** IDecodeService.COLOR_SPACE_*, as for a Thumbnail. */
    int colorSpace;

    /** Rows per stripe of the tall image, so that stripeRows * width * 4 is at most IDecodeService.MAX_STRIPE_BYTES. */
    int stripeRows;

    /** For readStripe and release. Valid until release or IDecodeService.HANDLE_TIMEOUT_MILLIS. */
    long handle;
    /** Exactly 16 checked XYZ D50 and transfer values for COLOR_SPACE_CUSTOM, empty for named tags. */
    float[] colorDescription;

    /**
     * A window (DecodeRequest.frameWindow): its first frame, the requested one, and the animation's frame count,
     * frameCount being the window's. Both 0 for every frame.
     */
    int firstFrame = 0;
    int totalFrames = 0;

    /** A band (DecodeRequest.frameBand): the whole frame's height, height being the band's. 0 otherwise. */
    int frameHeight = 0;
}
