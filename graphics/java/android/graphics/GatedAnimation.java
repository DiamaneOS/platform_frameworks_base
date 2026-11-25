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
import android.content.res.Resources;
import android.graphics.drawable.AnimatedImageDrawable;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.ArrayList;

/**
 * DiamaneOS: AnimatedImageDrawable playback of checked VM frames, without a native image decoder.
 * Every native entry point of the parent is implemented using the retained pixels. The parent's
 * final mirror getter reads only the Java flag of its empty state. Frames come a window at a time
 * as playback reaches them ({@link GateFrameWindows}): a frame not here yet keeps the last one shown.
 * @hide
 */
public final class GatedAnimation extends AnimatedImageDrawable {
    private static final int SHORT_FRAME_MS = 10;
    private static final int SHORT_FRAME_SHOWN_MS = 100;

    private GateFrameWindows mWindows;
    private Bitmap mShown;
    private int mShownDuration;
    private boolean mWaiting; // the next frame's window is being fetched
    private volatile Handler mDrawHandler;
    private int mWidth;
    private int mHeight;
    private Rect mCrop;
    private final Paint mPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Rect mSource = new Rect();
    private final RectF mTarget = new RectF();
    private final ArrayList<AnimationCallback> mCallbacks = new ArrayList<>();
    private Handler mCallbackHandler;
    private Drawable mCallbackDrawable = this;
    private Callback mXmlCallback;
    private int mFrame;
    private long mPlayed;
    private long mNextAt;
    private boolean mStarting;
    private boolean mRunning;
    private int mRepeatCount;

    private final Runnable mNext = () -> {
        if (!mRunning) return;
        if (mFrame == mWindows.total - 1 && mRepeatCount >= 0 && mPlayed >= mRepeatCount) {
            finish();
        } else {
            invalidateSelf();
        }
    };

    GatedAnimation(Bitmap[] frames, int width, int height, @Nullable Rect crop, int[] durations, int loops) {
        this(held(frames, durations, loops), width, height, crop);
    }

    GatedAnimation(GateFrameWindows source, int width, int height, @Nullable Rect crop) {
        mWidth = width;
        mHeight = height;
        mCrop = crop == null ? null : new Rect(crop);
        useSource(source);
    }

    private static GateFrameWindows held(Bitmap[] frames, int[] durations, int loops) {
        if (frames.length == 0 || frames.length != durations.length) {
            throw new IllegalArgumentException("animation frames and durations differ");
        }
        return GateFrameWindows.held(frames, durations.clone(), loops);
    }

    private void useSource(GateFrameWindows source) {
        mWindows = source;
        mRepeatCount = source.loops == 0 ? REPEAT_INFINITE : source.loops - 1;
        mShown = source.frame(0);
        mShownDuration = source.duration(0);
        mFrame = 0;
        mWaiting = false;
        final java.lang.ref.WeakReference<GatedAnimation> self = new java.lang.ref.WeakReference<>(this);
        source.setArrived(() -> {
            final GatedAnimation animation = self.get();
            final Handler handler = animation != null ? animation.mDrawHandler : null;
            if (handler != null) handler.post(animation::windowArrived);
        });
    }

    // On the drawing thread: a frame the playback waits for may be here now.
    private void windowArrived() {
        if (mWaiting && mRunning) invalidateSelf();
    }

    int frameCount() { return mWindows.total; }
    int frameDuration(int frame) { return mWindows.duration(frame); }
    Bitmap frameBitmap(int frame) { return mWindows.frame(frame); }

    /** Decodes another XML source through the VM when this drawable already holds VM frames. */
    public static GatedAnimation decodeXml(java.io.InputStream stream, Resources resources, int density)
            throws IOException {
        final ImageDecoder.Source source = ImageDecoder.createSource(resources, stream, density);
        try (java.io.InputStream input = stream;
                GatedDecodes.Header header = GatedDecodes.header(input, source)) {
            if (!header.animated) throw new IllegalArgumentException("image is not animated");
            final int targetDensity = resources.getDisplayMetrics().densityDpi;
            final int width = Bitmap.scaleFromDensity(header.width, density, targetDensity);
            final int height = Bitmap.scaleFromDensity(header.height, density, targetDensity);
            return (GatedAnimation) GatedDecodes.animated(header, width, height, null, null, source, null);
        }
    }

    /** Routes invalidation and callback identity through an XML drawable that retained these frames. */
    public void attachTo(AnimatedImageDrawable drawable) {
        mCallbackDrawable = drawable;
        mXmlCallback = new Callback() {
            @Override public void invalidateDrawable(Drawable ignored) { drawable.invalidateSelf(); }
            @Override public void scheduleDrawable(Drawable ignored, Runnable what, long when) {
                drawable.scheduleSelf(what, when);
            }
            @Override public void unscheduleDrawable(Drawable ignored, Runnable what) {
                drawable.unscheduleSelf(what);
            }
        };
        setCallback(mXmlCallback);
    }

    /** Retains a registration on the XML wrapper as well as its current child. */
    public @Nullable ArrayList<AnimationCallback> registerXmlCallback(
            @Nullable ArrayList<AnimationCallback> callbacks, @Nullable AnimationCallback callback) {
        if (callback == null) return callbacks;
        if (callbacks == null) callbacks = new ArrayList<>();
        if (callbacks.contains(callback)) return callbacks;
        registerAnimationCallback(callback);
        try {
            callbacks.add(callback);
        } catch (RuntimeException | Error failure) {
            unregisterAnimationCallback(callback);
            throw failure;
        }
        return callbacks;
    }

    /** Transfers live XML registrations with their existing callback handler. */
    public void replaceXmlCallbacks(@Nullable GatedAnimation previous,
            @Nullable ArrayList<AnimationCallback> callbacks, @Nullable Handler xmlHandler) {
        final Handler handler;
        if (previous != null) {
            synchronized (previous) {
                handler = previous.mCallbackHandler;
            }
            previous.detachXml();
        } else {
            handler = xmlHandler;
        }
        synchronized (this) {
            mCallbacks.clear();
            if (callbacks != null && !callbacks.isEmpty()) {
                mCallbackHandler = handler != null ? handler : new Handler(Looper.getMainLooper());
                mCallbacks.addAll(callbacks);
            }
        }
    }

    /** Returns the registration route retained by an XML wrapper. */
    public synchronized Handler xmlCallbackHandler() { return mCallbackHandler; }

    /** Queues a hardware end on the live XML registration's looper without retaining its wrapper. */
    public static Runnable nativeXmlCallback(AnimatedImageDrawable drawable, @Nullable Handler handler,
            ArrayList<AnimationCallback> callbacks,
            java.util.function.BiConsumer<AnimatedImageDrawable, ArrayList<AnimationCallback>> deliver) {
        final Handler route = handler != null ? handler : new Handler(Looper.getMainLooper());
        final java.lang.ref.WeakReference<AnimatedImageDrawable> self = new java.lang.ref.WeakReference<>(drawable);
        final java.lang.ref.WeakReference<ArrayList<AnimationCallback>> registration =
                new java.lang.ref.WeakReference<>(callbacks);
        return () -> route.post(() -> {
            final AnimatedImageDrawable current = self.get();
            final ArrayList<AnimationCallback> registered = registration.get();
            if (current != null && registered != null) deliver.accept(current, registered);
        });
    }

    /** Releases the outgoing child's frame schedule and XML callbacks. */
    public void detachXml() {
        unscheduleSelf(mNext);
        setCallback(null);
        synchronized (this) {
            mCallbacks.clear();
        }
        mRunning = false;
        mStarting = false;
        mCallbackDrawable = this;
        mXmlCallback = null;
    }

    private int duration() {
        final int duration = mShownDuration;
        return duration <= SHORT_FRAME_MS ? SHORT_FRAME_SHOWN_MS : duration;
    }

    // Shows a frame that is here: its window becomes the shown one and the next window is asked for.
    private void show(int frame, Bitmap bitmap) {
        mFrame = frame;
        mShown = bitmap;
        mShownDuration = mWindows.duration(frame);
        mWaiting = false;
        mWindows.showing(frame);
    }

    @Override public void draw(@NonNull Canvas canvas) {
        final Looper looper = Looper.myLooper();
        if (looper != null && (mDrawHandler == null || mDrawHandler.getLooper() != looper)) {
            mDrawHandler = new Handler(looper);
        }
        if (mRunning) {
            final long now = SystemClock.uptimeMillis();
            if (mStarting) {
                final Bitmap first = mWindows.frame(0);
                if (first != null) {
                    mStarting = false;
                    show(0, first);
                    notifyCallbacks(true);
                    mNextAt = now + duration();
                } else {
                    mWaiting = true;
                }
            } else if (mWaiting || now >= mNextAt) {
                final int count = mWindows.total;
                if (mFrame == count - 1 && mRepeatCount >= 0 && mPlayed >= mRepeatCount) {
                    finish();
                } else {
                    final int next = mFrame + 1 == count ? 0 : mFrame + 1;
                    final Bitmap bitmap = mWindows.frame(next);
                    if (bitmap != null) {
                        if (next == 0) mPlayed++;
                        show(next, bitmap);
                        mNextAt = now + duration();
                    } else {
                        // Not decoded yet (or refused): the last frame stays until its window is here.
                        mWaiting = true;
                    }
                }
            }
            if (mRunning && !mWaiting) {
                unscheduleSelf(mNext);
                scheduleSelf(mNext, mNextAt);
            }
        }
        final Bitmap frame = mShown;
        if (mCrop != null) {
            final float sx = (float) frame.getWidth() / mWidth;
            final float sy = (float) frame.getHeight() / mHeight;
            mSource.set((int) Math.floor(mCrop.left * sx), (int) Math.floor(mCrop.top * sy),
                    (int) Math.ceil(mCrop.right * sx), (int) Math.ceil(mCrop.bottom * sy));
        } else {
            mSource.set(0, 0, frame.getWidth(), frame.getHeight());
        }
        mTarget.set(getBounds());
        final int save = canvas.save();
        if (isAutoMirrored() && getLayoutDirection() == View.LAYOUT_DIRECTION_RTL) {
            canvas.translate(mTarget.left + mTarget.right, 0);
            canvas.scale(-1, 1);
        }
        canvas.drawBitmap(frame, mSource, mTarget, mPaint);
        canvas.restoreToCount(save);
    }

    @Override public int getIntrinsicWidth() { return mCrop != null ? mCrop.width() : mWidth; }
    @Override public int getIntrinsicHeight() { return mCrop != null ? mCrop.height() : mHeight; }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    @Override public void setAlpha(int alpha) {
        if (alpha < 0 || alpha > 255) throw new IllegalArgumentException("Alpha must be between 0 and 255");
        mPaint.setAlpha(alpha);
        invalidateSelf();
    }
    @Override public int getAlpha() { return mPaint.getAlpha(); }
    @Override public void setColorFilter(@Nullable ColorFilter colorFilter) {
        mPaint.setColorFilter(colorFilter);
        invalidateSelf();
    }
    @Override public ColorFilter getColorFilter() { return mPaint.getColorFilter(); }
    @Override public void setFilterBitmap(boolean filterBitmap) {
        mPaint.setFilterBitmap(filterBitmap);
        invalidateSelf();
    }
    @Override public boolean isFilterBitmap() { return mPaint.isFilterBitmap(); }
    @Override public void setAutoMirrored(boolean mirrored) {
        // The empty parent's final getter retains this Java flag and never calls native code.
        super.setAutoMirrored(mirrored);
        invalidateSelf();
    }
    @Override public boolean onLayoutDirectionChanged(int layoutDirection) {
        if (!isAutoMirrored()) return false;
        invalidateSelf();
        return true;
    }
    @Override protected void onBoundsChange(Rect bounds) { invalidateSelf(); }
    @Override public Drawable mutate() { return this; }
    @Override public ConstantState getConstantState() { return null; }
    @Override public void inflate(Resources resources, XmlPullParser parser, AttributeSet attrs,
            Resources.Theme theme) throws XmlPullParserException, IOException {
        super.inflate(resources, parser, attrs, theme);
    }

    /** Retains an independently decoded XML source without taking its native state. */
    public void replaceFrames(GatedAnimation animation) {
        stop();
        mWidth = animation.mWidth;
        mHeight = animation.mHeight;
        mCrop = animation.mCrop;
        useSource(animation.mWindows);
        mPlayed = 0;
        invalidateSelf();
    }

    @Override public int getRepeatCount() { return mRepeatCount; }
    @Override public void setRepeatCount(int repeatCount) {
        if (repeatCount < REPEAT_INFINITE) throw new IllegalArgumentException("invalid repeat count");
        mRepeatCount = repeatCount;
    }
    @Override @Deprecated public void setLoopCount(int loopCount) { setRepeatCount(loopCount); }
    @Override @Deprecated public int getLoopCount(int loopCount) { return getRepeatCount(); }

    @Override public boolean setVisible(boolean visible, boolean restart) {
        return super.setVisible(visible, restart);
    }
    @Override public void start() {
        if (mRunning) return;
        mFrame = 0;
        mPlayed = 0;
        mStarting = true;
        mRunning = true;
        invalidateSelf();
    }
    private void finish() {
        if (!mRunning) return;
        mRunning = false;
        mStarting = false;
        unscheduleSelf(mNext);
        notifyCallbacks(false);
    }
    @Override public void stop() { finish(); }
    @Override public boolean isRunning() { return mRunning; }

    @Override public synchronized void registerAnimationCallback(@NonNull AnimationCallback callback) {
        if (callback == null) return;
        if (mCallbackHandler == null) {
            final Looper looper = Looper.myLooper();
            if (looper == null) throw new IllegalStateException("animation callbacks require a Looper");
            mCallbackHandler = new Handler(looper);
        }
        if (!mCallbacks.contains(callback)) mCallbacks.add(callback);
    }
    @Override public synchronized boolean unregisterAnimationCallback(@NonNull AnimationCallback callback) {
        return callback != null && mCallbacks.remove(callback);
    }
    @Override public synchronized void clearAnimationCallbacks() { mCallbacks.clear(); }
    private synchronized void notifyCallbacks(boolean started) {
        if (mCallbacks.isEmpty()) return;
        mCallbackHandler.post(() -> {
            final AnimationCallback[] callbacks;
            synchronized (GatedAnimation.this) {
                callbacks = mCallbacks.toArray(new AnimationCallback[0]);
            }
            for (AnimationCallback callback : callbacks) {
                if (started) callback.onAnimationStart(mCallbackDrawable);
                else callback.onAnimationEnd(mCallbackDrawable);
            }
        });
    }
}
