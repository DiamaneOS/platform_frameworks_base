/*
 * Copyright (C) 2026 The DiamaneOS Project
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

package com.android.wm.shell.startingsurface;

import static android.os.Trace.TRACE_TAG_WINDOW_MANAGER;

import android.animation.Animator;
import android.animation.ValueAnimator;
import android.annotation.NonNull;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.SystemClock;
import android.os.Trace;
import android.view.SurfaceView;
import android.view.View;
import android.window.SplashScreenView;

import com.android.launcher3.icons.BaseIconFactory;
import com.android.wm.shell.shared.animation.Interpolators;
import com.android.wm.shell.tally.TallyPageMotion;
import com.android.wm.shell.tally.TallySpring;

import de.diamaneos.tally.R;

import java.io.Closeable;

/**
 * DiamaneOS Tally's splash screen, as the Tally prototype's cold start (apps.js: {@code Win} and
 * {@code T.launch}): the app's icon as an 88 dp keycap centred on the window's background, with
 * its LED, a 14 dp lamp in the keycap's top right corner that lights 30 ms after the splash first
 * shows; once the app has drawn, the splash fades out on the fill spring.
 *
 * <p>The keycap is the app's adaptive icon cut by the system icon mask ({@code config_icon_mask},
 * the keycap on DiamaneOS) with the key's relief (a 1 dp highlight along its top edge and a skirt
 * along its bottom, both following the shape, as Launcher's keys); a legacy icon is first wrapped
 * into an adaptive icon, as Launcher does. The LED is drawn with the token library's static lamp
 * glyphs (off, then on) in the key's LED housing: the animated lamp lives in SystemUI.
 */
final class TallySplashScreen {
    /** The splash keycap's size (the prototype's {@code SPLASH_KC}). */
    static final float KEYCAP_DP = 88f;
    /** The lamp's housing around the lamp (the prototype's {@code .splash-lamp} padding). */
    private static final float HOUSING_DP = 2f;
    /** When the LED lights after the splash first shows, at an animator scale of 1. */
    private static final long LIGHT_DELAY_MS = 30;
    /** The off lamp's ring on the housing (the prototype's {@code .splash-lamp .l-ring}). */
    private static final int OFF_RING_COLOR = 0xB3FFFFFF;

    private TallySplashScreen() {}

    /** The keycap's size in pixels. */
    static int keycapSize(@NonNull Context shellContext) {
        return Math.round(KEYCAP_DP * shellContext.getResources().getDisplayMetrics().density);
    }

    /**
     * The density to load the app's icon at, so that its layers, which reach half a keycap past
     * each side of the key, have a pixel for each pixel of the keycap.
     */
    static int iconDpi(int densityDpi, int keycapPx, int defaultIconPx) {
        return (int) (0.5f + densityDpi * 1.5f * keycapPx / defaultIconPx);
    }

    /**
     * The keycap for {@code icon}, drawn once on {@code worker} ahead of the first frame, as stock
     * draws its splash icons. {@code shellContext} gives the token library's resources.
     */
    @NonNull
    static Drawable makeKeycap(@NonNull Context shellContext, @NonNull Drawable icon, int iconDpi,
            int sizePx, @NonNull Handler worker) {
        final AdaptiveIconDrawable adaptive;
        if (icon instanceof AdaptiveIconDrawable a) {
            adaptive = a;
        } else {
            try (BaseIconFactory factory = new BaseIconFactory(shellContext, iconDpi, sizePx)) {
                adaptive = factory.wrapToAdaptiveIcon(icon);
            }
        }
        return new Keycap(shellContext.getResources(), adaptive, sizePx, worker);
    }

    /**
     * Fades {@code view} out on the {@code fill} spring, from rest, over the time the fade takes;
     * {@code listener} gets the animator's callbacks (the jank monitor and the finish). A
     * ValueAnimator runs it, so the animator duration scale applies to it as to stock's exit.
     */
    static void startExitAnimation(@NonNull TallySpring fill, @NonNull SplashScreenView view,
            @NonNull Animator.AnimatorListener listener) {
        final long millis = fill.settleMillis(1f, 0f, 0f, TallyPageMotion.SETTLE_ALPHA);
        // An icon drawn in its own surface (an app's animated icon) takes only its own alpha.
        final View iconView = view.getIconView();
        final View iconSurface = iconView instanceof SurfaceView ? iconView : null;
        if (iconSurface == null) {
            // Draw the splash once and fade that, rather than drawing it again every frame.
            view.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        }
        final ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(millis);
        animator.setInterpolator(Interpolators.LINEAR);
        animator.addUpdateListener(a -> {
            final float fraction = a.getAnimatedFraction();
            final float alpha = fraction >= 1f ? 0f
                    : fill.value(1f, 0f, 0f, fraction * millis / 1000f);
            view.setAlpha(alpha);
            if (iconSurface != null) {
                iconSurface.setAlpha(alpha);
            }
        });
        animator.addListener(listener);
        animator.start();
    }

    /**
     * The splash keycap: the key (icon, relief and LED housing) drawn once into a bitmap on the
     * splash worker thread, and the LED's lamp glyph over it, off until 30 ms after the key first
     * shows (at once with Remove animations).
     */
    private static final class Keycap extends Drawable implements Closeable {
        private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG
                | Paint.FILTER_BITMAP_FLAG);
        private final int mSize;
        private final Drawable mLampOff;
        private final Drawable mLampOn;
        private final float mLedX;
        private final float mLedY;
        private final float mLampBox;
        private final long mLightDelayMs;
        private final Runnable mLight = this::light;
        private Bitmap mBitmap;
        private boolean mLit;
        private boolean mLightScheduled;

        Keycap(@NonNull Resources res, @NonNull AdaptiveIconDrawable icon, int size,
                @NonNull Handler worker) {
            mSize = size;
            final float density = res.getDisplayMetrics().density;
            final float lamp = res.getDimension(R.dimen.tally_lamp_size_large);
            final float housing = lamp / 2f + HOUSING_DP * density;
            final float inset = res.getFraction(R.fraction.tally_keycap_led_inset, 1, 1);
            // The LED's housing sits in the key's top right corner, 8 % in from both edges.
            mLedX = size * (1f - inset) - housing;
            mLedY = size * inset + housing;
            mLampBox = res.getDimension(R.dimen.tally_lamp_box_14);
            mLampOff = res.getDrawable(R.drawable.tally_lamp_off_14, null).mutate();
            mLampOff.setTint(OFF_RING_COLOR);
            mLampOn = res.getDrawable(R.drawable.tally_lamp_on_14, null).mutate();
            mLightDelayMs = (long) (LIGHT_DELAY_MS * ValueAnimator.getDurationScale());
            mLit = mLightDelayMs <= 0;
            final int housingColor = res.getColor(R.color.tally_on_lamp, null);
            final int highlightColor = res.getColor(R.color.tally_keycap_highlight, null);
            final int shadeColor = res.getColor(R.color.tally_keycap_shade, null);
            final float highlight = res.getDimension(R.dimen.tally_keycap_highlight_height);
            final float skirt = size * res.getFraction(R.fraction.tally_keycap_skirt, 1, 1);
            worker.post(() -> preDraw(icon, housing, housingColor, highlight, highlightColor,
                    skirt, shadeColor));
        }

        private void preDraw(AdaptiveIconDrawable icon, float housing, int housingColor,
                float highlight, int highlightColor, float skirt, int shadeColor) {
            Trace.traceBegin(TRACE_TAG_WINDOW_MANAGER, "TallySplashScreen#preDraw");
            final Bitmap bitmap = Bitmap.createBitmap(mSize, mSize, Bitmap.Config.ARGB_8888);
            final Canvas canvas = new Canvas(bitmap);
            icon.setBounds(0, 0, mSize, mSize);
            icon.draw(canvas);
            // The key's relief, as Launcher's keys: bands of the key the key moved by the
            // highlight's height (down) or the skirt's (up) does not cover.
            final Path key = icon.getIconMask();
            final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            final int count = canvas.save();
            canvas.clipPath(key);
            paint.setColor(highlightColor);
            drawEdge(canvas, key, highlight, paint);
            paint.setColor(shadeColor);
            drawEdge(canvas, key, -skirt, paint);
            canvas.restoreToCount(count);
            paint.setColor(housingColor);
            canvas.drawCircle(mLedX, mLedY, housing, paint);
            synchronized (mPaint) {
                mBitmap = bitmap;
            }
            Trace.traceEnd(TRACE_TAG_WINDOW_MANAGER);
        }

        private void drawEdge(Canvas canvas, Path key, float dy, Paint paint) {
            final int count = canvas.save();
            canvas.translate(0f, dy);
            canvas.clipOutPath(key);
            canvas.translate(0f, -dy);
            canvas.drawRect(0f, 0f, mSize, mSize, paint);
            canvas.restoreToCount(count);
        }

        private void light() {
            mLit = true;
            invalidateSelf();
        }

        @Override
        protected void onBoundsChange(Rect bounds) {
            final int left = Math.round(bounds.left + mLedX - mLampBox / 2f);
            final int top = Math.round(bounds.top + mLedY - mLampBox / 2f);
            final int box = Math.round(mLampBox);
            mLampOff.setBounds(left, top, left + box, top + box);
            mLampOn.setBounds(left, top, left + box, top + box);
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            synchronized (mPaint) {
                if (mBitmap == null) {
                    // Not drawn yet on the worker thread: wait for it, as stock's icons do.
                    invalidateSelf();
                    return;
                }
                canvas.drawBitmap(mBitmap, getBounds().left, getBounds().top, mPaint);
            }
            if (!mLit && !mLightScheduled) {
                mLightScheduled = true;
                scheduleSelf(mLight, SystemClock.uptimeMillis() + mLightDelayMs);
            }
            (mLit ? mLampOn : mLampOff).draw(canvas);
        }

        @Override
        public int getIntrinsicWidth() {
            return mSize;
        }

        @Override
        public int getIntrinsicHeight() {
            return mSize;
        }

        @Override
        public void setAlpha(int alpha) {
            mPaint.setAlpha(alpha);
            mLampOff.setAlpha(alpha);
            mLampOn.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }

        @Override
        public void close() {
            unscheduleSelf(mLight);
            synchronized (mPaint) {
                if (mBitmap != null) {
                    mBitmap.recycle();
                    mBitmap = null;
                }
            }
        }
    }
}
