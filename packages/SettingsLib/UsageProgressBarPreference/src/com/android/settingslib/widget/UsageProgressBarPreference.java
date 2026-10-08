/*
 * Copyright (C) 2021 The Android Open Source Project
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
import android.os.Build;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.method.LinkMovementMethod;
import android.text.style.TextAppearanceSpan;
import android.util.AttributeSet;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.Preference;
import androidx.preference.PreferenceViewHolder;

import com.android.settingslib.widget.preference.usage.R;

import com.google.android.material.progressindicator.LinearProgressIndicator;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Progress bar preference with a usage summary and a total summary.
 *
 * <p>This preference shows number in usage summary with enlarged font size.
 */
public class UsageProgressBarPreference extends Preference implements GroupSectionDividerMixin {

    private static final int NUMBER_TEXT_APPEARANCE =
            com.android.settingslib.widget.theme.R.style.TextAppearance_SettingsLib_DisplayLarge_Emphasized;
    private final Pattern mNumberPattern = Pattern.compile("[\\d]*[\\٫.,]?[\\d]+");

    private CharSequence mUsageSummary;
    private CharSequence mTotalSummary;
    private CharSequence mBottomSummary;
    private CharSequence mBottomSummaryContentDescription;
    private ImageView mCustomImageView;
    private int mPercent = -1;

    /**
     * Perform inflation from XML and apply a class-specific base style.
     *
     * @param context The {@link Context} this is associated with, through which it can access the
     *     current theme, resources, {@link SharedPreferences}, etc.
     * @param attrs The attributes of the XML tag that is inflating the preference
     * @param defStyle An attribute in the current theme that contains a reference to a style
     *     resource that supplies default values for the view. Can be 0 to not look for defaults.
     */
    public UsageProgressBarPreference(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        int resId = SettingsThemeHelper.isExpressiveTheme(context)
                ? R.layout.preference_usage_progress_bar_expressive
                : R.layout.preference_usage_progress_bar;
        setLayoutResource(resId);
    }

    /**
     * Perform inflation from XML and apply a class-specific base style.
     *
     * @param context The {@link Context} this is associated with, through which it can access the
     *     current theme, resources, {@link SharedPreferences}, etc.
     * @param attrs The attributes of the XML tag that is inflating the preference
     */
    public UsageProgressBarPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        int resId = SettingsThemeHelper.isExpressiveTheme(context)
                ? R.layout.preference_usage_progress_bar_expressive
                : R.layout.preference_usage_progress_bar;
        setLayoutResource(resId);
    }

    /**
     * Constructor to create a preference.
     *
     * @param context The Context this is associated with.
     */
    public UsageProgressBarPreference(Context context) {
        this(context, null);
    }

    /** Set usage summary, number in the summary will show with enlarged font size. */
    public void setUsageSummary(CharSequence usageSummary) {
        if (TextUtils.equals(mUsageSummary, usageSummary)) {
            return;
        }
        mUsageSummary = usageSummary;
        notifyChanged();
    }

    /** Set total summary. */
    public void setTotalSummary(CharSequence totalSummary) {
        if (TextUtils.equals(mTotalSummary, totalSummary)) {
            return;
        }
        mTotalSummary = totalSummary;
        notifyChanged();
    }

    /** Set bottom summary. */
    public void setBottomSummary(CharSequence bottomSummary) {
        if (TextUtils.equals(mBottomSummary, bottomSummary)) {
            return;
        }
        mBottomSummary = bottomSummary;
        notifyChanged();
    }

    /** Set content description for the bottom summary. */
    public void setBottomSummaryContentDescription(CharSequence contentDescription) {
        if (!TextUtils.equals(mBottomSummaryContentDescription, contentDescription)) {
            mBottomSummaryContentDescription = contentDescription;
            notifyChanged();
        }
    }

    /** Set percentage of the progress bar. */
    public void setPercent(long usage, long total) {
        if (usage > total) {
            return;
        }
        if (total == 0L) {
            if (mPercent != 0) {
                mPercent = 0;
                notifyChanged();
            }
            return;
        }
        final int percent = (int) (usage / (double) total * 100);
        if (mPercent == percent) {
            return;
        }
        mPercent = percent;
        notifyChanged();
    }

    /** Set custom ImageView to the right side of total summary. */
    public <T extends ImageView> void setCustomContent(T imageView) {
        if (imageView == mCustomImageView) {
            return;
        }
        mCustomImageView = imageView;
        notifyChanged();
    }

    /**
     * Binds the created View to the data for this preference.
     *
     * <p>This is a good place to grab references to custom Views in the layout and set properties
     * on them.
     *
     * <p>Make sure to call through to the superclass's implementation.
     *
     * @param holder The ViewHolder that provides references to the views to fill in. These views
     *     will be recycled, so you should not hold a reference to them after this method returns.
     */
    @Override
    public void onBindViewHolder(PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);

        holder.setDividerAllowedAbove(false);
        holder.setDividerAllowedBelow(false);

        final TextView usageSummary = (TextView) holder.findViewById(R.id.usage_summary);
        usageSummary.setText(enlargeFontOfNumber(mUsageSummary));

        final TextView totalSummary = (TextView) holder.findViewById(R.id.total_summary);
        if (mTotalSummary != null) {
            totalSummary.setText(mTotalSummary);
        }

        final TextView bottomSummary = (TextView) holder.findViewById(R.id.bottom_summary);
        if (TextUtils.isEmpty(mBottomSummary)) {
            bottomSummary.setVisibility(View.GONE);
        } else {
            bottomSummary.setVisibility(View.VISIBLE);
            bottomSummary.setMovementMethod(LinkMovementMethod.getInstance());
            bottomSummary.setText(mBottomSummary);
            if (!TextUtils.isEmpty(mBottomSummaryContentDescription)) {
                bottomSummary.setContentDescription(mBottomSummaryContentDescription);
            }
        }

        final ProgressBar progressBar = (ProgressBar) holder.findViewById(android.R.id.progress);
        if (mPercent < 0) {
            progressBar.setIndeterminate(true);
        } else {
            progressBar.setIndeterminate(false);
            progressBar.setProgress(mPercent);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
                && progressBar instanceof LinearProgressIndicator
                && SettingsThemeHelper.isExpressiveTheme(getContext())) {
            applyTallyMeter((LinearProgressIndicator) progressBar, mPercent);
        }

        final FrameLayout customLayout = (FrameLayout) holder.findViewById(R.id.custom_content);
        if (mCustomImageView == null) {
            customLayout.removeAllViews();
            customLayout.setVisibility(View.GONE);
        } else {
            customLayout.removeAllViews();
            customLayout.addView(mCustomImageView);
            customLayout.setVisibility(View.VISIBLE);
        }
    }

    private CharSequence enlargeFontOfNumber(CharSequence summary) {
        if (TextUtils.isEmpty(summary)) {
            return "";
        }

        final Matcher matcher = mNumberPattern.matcher(summary);
        if (matcher.find()) {
            final SpannableString spannableSummary = new SpannableString(summary);
            spannableSummary.setSpan(
                    new TextAppearanceSpan(getContext(), NUMBER_TEXT_APPEARANCE),
                    matcher.start(),
                    matcher.end(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            return spannableSummary;
        }
        return summary;
    }

    /**
     * Tally: the bar as the prototype's meters, as SettingsLib's slider draws them, in place of an
     * accent pill with a stop dot: an r8 track on the high surface, the level in ink at 16 % over
     * it, and the value as a lamp tick across the meter. A level is a value, neither a state nor
     * an action, so it takes no accent colour. The colours are the Tally roles SettingsTheme
     * exports from API 34 (res/values-v34/tally_switch.xml).
     */
    private static void applyTallyMeter(@NonNull LinearProgressIndicator indicator, int percent) {
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
