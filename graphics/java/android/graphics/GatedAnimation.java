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

package android.graphics;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.graphics.drawable.Animatable2;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;

import java.util.ArrayList;

/**
 * DiamaneOS: the animated drawable an ImageDecoder of the protected VM gives for a GIF or WebP animation (see
 * {@link GatedDecodes#animated}): the VM's frames, a bitmap each (each within the GPU's texture size, as a decoded
 * image is), drawn scaled to the animation's size and shown in turn with their times, played {@code loops} times
 * (0: forever) as AnimatedImageDrawable plays them. It starts when {@link #start} is called and stops when hidden.
 * Memory: the frames only (at most the decode service's frame byte limit). It is not an AnimatedImageDrawable: code
 * that starts only those shows its first frame.
 *
 * @hide
 */
public final class GatedAnimation extends Drawable implements Animatable2 {
    // A frame time of 10 ms or less is shown for 100 ms, as browsers and Android's decoder show it.
    private static final int SHORT_FRAME_MS = 10;
    private static final int SHORT_FRAME_SHOWN_MS = 100;

    private final Bitmap[] mFrames;
    private final int mCount;
    private final int mWidth;
    private final int mHeight;
    private final Rect mCrop;
    private final int[] mDurations;
    private final Paint mPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Rect mSource = new Rect();
    private final RectF mTarget = new RectF();
    private final ArrayList<AnimationCallback> mCallbacks = new ArrayList<>();
    private int mFrame;
    private int mPlayed;
    private boolean mRunning;
    private final int mRepeatCount;

    private final Runnable mNext = new Runnable() {
        @Override
        public void run() {
            if (!mRunning) {
                return;
            }
            mFrame++;
            if (mFrame >= mCount) {
                mFrame = 0;
                mPlayed++;
                if (mRepeatCount >= 0 && mPlayed > mRepeatCount) {
                    mFrame = mCount - 1;
                    stop();
                    invalidateSelf();
                    return;
                }
            }
            invalidateSelf();
            scheduleSelf(this, SystemClock.uptimeMillis() + duration(mFrame));
        }
    };

    // {@code frames} all of one size, shown at {@code width} x {@code height}, {@code crop} in those terms.
    GatedAnimation(Bitmap[] frames, int width, int height, @Nullable Rect crop, int[] durations, int loops) {
        mFrames = frames;
        mCount = frames.length;
        mWidth = width;
        mHeight = height;
        mCrop = crop;
        mDurations = durations;
        // As AnimatedImageDrawable: the file's loop count, REPEAT_INFINITE for one that plays forever.
        mRepeatCount = loops == 0 ? -1 : loops - 1;
    }

    private int duration(int frame) {
        final int d = mDurations[frame];
        return d <= SHORT_FRAME_MS ? SHORT_FRAME_SHOWN_MS : d;
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        final Bitmap frame = mFrames[mFrame];
        if (mCrop != null) {
            // The crop is in the animation's size: the frame's part of it.
            final float sx = (float) frame.getWidth() / mWidth;
            final float sy = (float) frame.getHeight() / mHeight;
            mSource.set((int) Math.floor(mCrop.left * sx), (int) Math.floor(mCrop.top * sy),
                    (int) Math.ceil(mCrop.right * sx), (int) Math.ceil(mCrop.bottom * sy));
        } else {
            mSource.set(0, 0, frame.getWidth(), frame.getHeight());
        }
        mTarget.set(getBounds());
        canvas.drawBitmap(frame, mSource, mTarget, mPaint);
    }

    @Override
    public int getIntrinsicWidth() {
        return mCrop != null ? mCrop.width() : mWidth;
    }

    @Override
    public int getIntrinsicHeight() {
        return mCrop != null ? mCrop.height() : mHeight;
    }

    @Override
    public void setAlpha(int alpha) {
        mPaint.setAlpha(alpha);
        invalidateSelf();
    }

    @Override
    public int getAlpha() {
        return mPaint.getAlpha();
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {
        mPaint.setColorFilter(colorFilter);
        invalidateSelf();
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }

    @Override
    public boolean setVisible(boolean visible, boolean restart) {
        final boolean changed = super.setVisible(visible, restart);
        if (!visible) {
            stop();
        } else if (restart && changed) {
            mFrame = 0;
            mPlayed = 0;
        }
        return changed;
    }

    @Override
    public void start() {
        if (mRunning || mCount < 2) {
            return;
        }
        mRunning = true;
        mPlayed = 0;
        for (AnimationCallback c : new ArrayList<>(mCallbacks)) {
            c.onAnimationStart(this);
        }
        scheduleSelf(mNext, SystemClock.uptimeMillis() + duration(mFrame));
    }

    @Override
    public void stop() {
        if (!mRunning) {
            return;
        }
        mRunning = false;
        unscheduleSelf(mNext);
        for (AnimationCallback c : new ArrayList<>(mCallbacks)) {
            c.onAnimationEnd(this);
        }
    }

    @Override
    public boolean isRunning() {
        return mRunning;
    }

    @Override
    public void registerAnimationCallback(@NonNull AnimationCallback callback) {
        if (!mCallbacks.contains(callback)) {
            mCallbacks.add(callback);
        }
    }

    @Override
    public boolean unregisterAnimationCallback(@NonNull AnimationCallback callback) {
        return mCallbacks.remove(callback);
    }

    @Override
    public void clearAnimationCallbacks() {
        mCallbacks.clear();
    }
}
