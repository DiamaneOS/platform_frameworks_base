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
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * What one piece of image work may spend: wall-clock time, and for the images of the app's layouts a number of images
 * and a number of pixels. The time runs from when the budget is made, whatever the work does with it (waiting for
 * the VM, inflating XML, walking a layout): every loop of the work asks {@link #remainingMs} and stops at 0. An app
 * cannot starve others with a layout of hundreds of images: anything over the caps becomes empty without asking the
 * VM. Pixels can also count against a pool per app ({@link OwnerPixels}), so many notifications of one app together
 * stay under a cap. Plain Java with the clock passed in, tested on the host. Thread-safe.
 */
public final class ImageBudget {
    /** Wall-clock time the image work of one notification may take. */
    public static final long TIME_MS = 2_000;
    /** Different images in the layouts of one notification (custom layouts and their app resources). */
    public static final int MAX_IMAGES = 24;
    /** Decoded pixels of those images together (16 MB in ARGB). */
    public static final long MAX_PIXELS = 4_000_000;

    /** A monotonic clock in milliseconds. */
    public static final LongSupplier CLOCK = () -> System.nanoTime() / 1_000_000;

    /** The answer to {@link #admit}. */
    public enum Admission {
        /** A new image within the cap: {@link #charge} the pixels it can have, then make it. */
        NEW,
        /** This image was admitted before: already charged. */
        SEEN,
        /** Over a cap: show it empty, do not ask the VM. */
        REFUSED,
    }

    private final int mMaxImages;
    private final long mMaxPixels;
    private final long mGrantedMs;
    private final long mStart;
    private final LongSupplier mClock;
    private final OwnerPixels mPool;
    private final String mOwner;
    private long mPixels; // guarded by this
    private int mRefused; // guarded by this
    private int mVmCalls; // guarded by this
    private boolean mReleased; // guarded by this
    // Images by key: true when admitted and charged or waiting for its charge, false when refused.
    private final Map<String, Boolean> mImages = new HashMap<>(); // guarded by this
    // Images that could not be made in this budget: not asked for again.
    private final Set<String> mFailed = new HashSet<>(); // guarded by this

    public ImageBudget() {
        this(TIME_MS, MAX_IMAGES, MAX_PIXELS);
    }

    public ImageBudget(long timeMs, int maxImages, long maxPixels) {
        this(timeMs, maxImages, maxPixels, CLOCK, null, null);
    }

    /**
     * {@code timeMs} from now on {@code clock}. {@code pool}: the pixels of every live budget of {@code owner}
     * together, or null for none.
     */
    public ImageBudget(long timeMs, int maxImages, long maxPixels, LongSupplier clock, OwnerPixels pool,
            String owner) {
        mGrantedMs = Math.max(0, timeMs);
        mMaxImages = Math.max(0, maxImages);
        mMaxPixels = Math.max(0, maxPixels);
        mClock = clock != null ? clock : CLOCK;
        mStart = mClock.getAsLong();
        mPool = pool;
        mOwner = owner == null ? "" : owner;
    }

    /** Time left, 0 when the time is up. It falls with the clock, not only while waiting for the VM. */
    public long remainingMs() {
        return Math.max(0, mStart + mGrantedMs - mClock.getAsLong());
    }

    /** True when the time is up. */
    public boolean expired() {
        return remainingMs() <= 0;
    }

    /** The time this budget was given (what was reserved of the app's quota). */
    public long grantedMs() {
        return mGrantedMs;
    }

    /** Time since the budget was made. */
    public long elapsedMs() {
        return Math.max(0, mClock.getAsLong() - mStart);
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
     * Charges pixels of a NEW image, before it is decoded (the most it can have) and again if it came back bigger.
     * False when they do not fit any more, in this budget or in the app's pool: the image is then refused, also when
     * it comes again, and is not kept.
     */
    public synchronized boolean charge(String key, long pixels) {
        if (mReleased || pixels < 0 || mPixels + pixels > mMaxPixels
                || (mPool != null && !mPool.add(mOwner, this, pixels))) {
            mImages.put(key, Boolean.FALSE);
            mRefused++;
            return false;
        }
        mPixels += pixels;
        return true;
    }

    /**
     * Charges the pixels of an image of the standard fields (an icon, a person, a message sender) to the app's pool
     * only, once it is made: they do not count against this notification's layout caps. False when the app holds too
     * many already: the image is then not used.
     */
    public synchronized boolean chargePool(long pixels) {
        if (mReleased || pixels < 0) {
            return false;
        }
        return mPool == null || mPool.add(mOwner, this, pixels);
    }

    /** Gives back pixels charged for an image that came back smaller, or not at all. */
    public synchronized void refund(long pixels) {
        final long back = Math.min(mPixels, Math.max(0, pixels));
        mPixels -= back;
        if (mPool != null && back > 0) {
            mPool.add(mOwner, this, -back);
        }
    }

    /**
     * The images of this budget are no longer shown (a new round replaced them): their pixels leave the app's pool.
     * Nothing more can be charged.
     */
    public synchronized void release() {
        if (!mReleased && mPool != null) {
            mPool.release(mOwner, this);
        }
        mReleased = true;
    }

    /** Marks an image that could not be made: it is not asked for again in this budget. */
    public synchronized void failed(String key) {
        mFailed.add(key);
    }

    /** True for an image that could not be made earlier in this budget. */
    public synchronized boolean hasFailed(String key) {
        return mFailed.contains(key);
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
