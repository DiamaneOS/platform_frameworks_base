/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.settingslib.widget;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.progressindicator.LinearProgressIndicator;

/** Tally's drawing of {@link UsageProgressBarPreference}'s bar. */
final class TallyUsageMeter {

    /**
     * Tally: the bar as the prototype's meters, as SettingsLib's slider draws them, in place of an
     * accent pill with a stop dot: an r8 track on the high surface, the level in ink at 16 % over
     * it, and the value as a lamp tick across the meter. A level is a value, neither a state nor
     * an action, so it takes no accent colour. The colours are the Tally roles SettingsTheme
     * exports from API 34 (res/values-v34/tally_switch.xml).
     */
    static void applyTallyMeter(@NonNull LinearProgressIndicator indicator, int percent) {
        final Context context = indicator.getContext();
        final Resources res = context.getResources();
        final int surface = context.getColor(
                com.android.settingslib.widget.theme.R.color.settingslib_tally_surface_high);
        final int ink = context.getColor(
                com.android.settingslib.widget.theme.R.color.settingslib_tally_ink);
        final int radius = res.getDimensionPixelSize(
                com.android.settingslib.widget.theme.R.dimen.settingslib_tally_usage_meter_radius);
        indicator.setTrackColor(surface);
        indicator.setIndicatorColor(over(withAlpha(ink, 0.16f), surface));
        indicator.setTrackCornerRadius(radius);
        indicator.setTrackInnerCornerRadius(0);
        indicator.setTrackStopIndicatorSize(0);
        indicator.setIndicatorTrackGapSize(0);

        final Drawable foreground = indicator.getForeground();
        TallyMeterTick tick =
                foreground instanceof TallyMeterTick ? (TallyMeterTick) foreground : null;
        if (tick == null) {
            tick = new TallyMeterTick(
                    context.getColor(
                            com.android.settingslib.widget.theme.R.color.settingslib_tally_lamp),
                    context.getColor(com.android.settingslib.widget.theme.R.color
                            .settingslib_tally_lamp_outline),
                    res.getDimensionPixelSize(com.android.settingslib.widget.theme.R.dimen
                            .settingslib_tally_usage_meter_tick_width),
                    res.getDimension(com.android.settingslib.widget.theme.R.dimen
                            .settingslib_tally_usage_meter_tick_edge),
                    radius);
            indicator.setForeground(tick);
        }
        tick.setFraction(percent < 0 ? -1f : Math.min(percent, 100) / 100f);
    }

    private static int withAlpha(int color, float alpha) {
        return (Math.round(alpha * 255f) << 24) | (color & 0x00ffffff);
    }

    /** {@code top} drawn over the opaque {@code bottom}. */
    private static int over(int top, int bottom) {
        final float a = Color.alpha(top) / 255f;
        return Color.rgb(
                Math.round(Color.red(top) * a + Color.red(bottom) * (1f - a)),
                Math.round(Color.green(top) * a + Color.green(bottom) * (1f - a)),
                Math.round(Color.blue(top) * a + Color.blue(bottom) * (1f - a)));
    }

    /**
     * Tally: the value's lamp tick across a meter, centred on the end of the level and kept inside
     * the meter's rounded corners, with the lamp outline as its edge (the slider's thumb). None
     * while the bar is indeterminate. Mirrored right to left.
     */
    private static final class TallyMeterTick extends Drawable {
        private final Paint mFill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mEdge = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int mWidth;
        private final float mEdgeWidth;
        private final float mRadius;
        private final Path mClip = new Path();
        private final RectF mRect = new RectF();
        private float mFraction = -1f;

        TallyMeterTick(int lamp, int lampOutline, int width, float edgeWidth, float radius) {
            mFill.setColor(lamp);
            mEdge.setColor(lampOutline);
            mEdge.setStyle(Paint.Style.STROKE);
            mEdge.setStrokeWidth(edgeWidth);
            mWidth = width;
            mEdgeWidth = edgeWidth;
            mRadius = radius;
        }

        void setFraction(float fraction) {
            if (fraction != mFraction) {
                mFraction = fraction;
                invalidateSelf();
            }
        }

        @Override
        protected void onBoundsChange(@NonNull Rect bounds) {
            mClip.reset();
            mClip.addRoundRect(new RectF(bounds), mRadius, mRadius, Path.Direction.CW);
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            final Rect b = getBounds();
            if (mFraction < 0f || b.isEmpty()) return;
            final float span = b.width();
            float start = mFraction * span - mWidth / 2f;
            start = Math.max(0f, Math.min(start, span - mWidth));
            final float left = getLayoutDirection() == View.LAYOUT_DIRECTION_RTL
                    ? b.right - start - mWidth
                    : b.left + start;
            mRect.set(left, b.top, left + mWidth, b.bottom);
            canvas.save();
            canvas.clipPath(mClip);
            canvas.drawRect(mRect, mFill);
            mRect.inset(mEdgeWidth / 2f, mEdgeWidth / 2f);
            canvas.drawRect(mRect, mEdge);
            canvas.restore();
        }

        @Override
        public boolean onLayoutDirectionChanged(int layoutDirection) {
            invalidateSelf();
            return true;
        }

        @Override
        public void setAlpha(int alpha) {
            mFill.setAlpha(alpha);
            mEdge.setAlpha(alpha);
            invalidateSelf();
        }

        @Override
        public void setColorFilter(@Nullable ColorFilter colorFilter) {
            mFill.setColorFilter(colorFilter);
            mEdge.setColorFilter(colorFilter);
            invalidateSelf();
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
