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

import java.util.HashMap;
import java.util.Map;

/**
 * What one notification may spend on its images: time waiting for the VM, and for the images of its layouts a number
 * of images and a number of pixels. An app cannot starve others with a layout of hundreds of images: anything over the
 * caps becomes empty without asking the VM. Plain Java, tested on the host. Thread-safe.
 */
public final class ImageBudget {
    /** Time all images of one notification may wait for the VM together. */
    public static final long TIME_MS = 2_000;
    /** Different images in the layouts of one notification (custom layouts and their app resources). */
    public static final int MAX_IMAGES = 24;
    /** Decoded pixels of those images together (16 MB in ARGB). */
    public static final long MAX_PIXELS = 4_000_000;

    /** The answer to {@link #admit}. */
    public enum Admission {
        /** A new image within the cap: make it, then {@link #charge} its pixels. */
        NEW,
        /** This image was admitted before: already charged. */
        SEEN,
        /** Over a cap: show it empty, do not ask the VM. */
        REFUSED,
    }

    private final int mMaxImages;
    private final long mMaxPixels;
    private long mRemainingMs; // guarded by this
    private long mPixels; // guarded by this
    private int mRefused; // guarded by this
    private int mVmCalls; // guarded by this
    // Images by key: true when admitted and charged or waiting for its charge, false when refused.
    private final Map<String, Boolean> mImages = new HashMap<>(); // guarded by this

    public ImageBudget() {
        this(TIME_MS, MAX_IMAGES, MAX_PIXELS);
    }

    public ImageBudget(long timeMs, int maxImages, long maxPixels) {
        mRemainingMs = Math.max(0, timeMs);
        mMaxImages = Math.max(0, maxImages);
        mMaxPixels = Math.max(0, maxPixels);
    }

    /** Time left for waiting on the VM, 0 when spent. */
    public synchronized long remainingMs() {
        return mRemainingMs;
    }

    /** Takes {@code ms} of waiting off the time left. */
    public synchronized void spent(long ms) {
        mRemainingMs = Math.max(0, mRemainingMs - Math.max(0, ms));
    }

    /** Counts one call to the VM, for logs and tests. */
    public synchronized void countVmCall() {
        mVmCalls++;
    }

    /** Whether the layout image {@code key} may be made. The same key is admitted once and then SEEN. */
    public synchronized Admission admit(String key) {
        final Boolean known = mImages.get(key);
        if (known != null) {
            return known ? Admission.SEEN : Admission.REFUSED;
        }
        if (mImages.size() >= mMaxImages) {
            mRefused++;
            return Admission.REFUSED;
        }
        mImages.put(key, Boolean.TRUE);
        return Admission.NEW;
    }

    /**
     * Charges the pixels of a NEW image. False when they do not fit any more: the image is then refused, also when
     * it comes again.
     */
    public synchronized boolean charge(String key, long pixels) {
        if (pixels < 0 || mPixels + pixels > mMaxPixels) {
            mImages.put(key, Boolean.FALSE);
            mRefused++;
            return false;
        }
        mPixels += pixels;
        return true;
    }

    /** Images admitted so far (including those later refused for their pixels). */
    public synchronized int images() {
        return mImages.size();
    }

    /** Images made empty because a cap was reached. */
    public synchronized int refused() {
        return mRefused;
    }

    /** Pixels charged so far. */
    public synchronized long pixels() {
        return mPixels;
    }

    /** Calls to the VM so far. */
    public synchronized int vmCalls() {
        return mVmCalls;
    }
}
