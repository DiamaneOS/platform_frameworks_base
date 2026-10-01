/*
 * Copyright (C) 2026 The Android Open Source Project
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

package com.android.internal.widget;

import android.content.Context;
import android.text.Layout;
import android.text.TextPaint;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.RemoteViews;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.internal.R;

import java.util.ArrayList;

/**
 * A custom {@link FrameLayout} designed specifically for displaying single metric
 * {@link Notification.MetricStyle}
 *
 * it measures the single metric value (which has maximum width) and adjust end margins of
 * label accordingly to render  correctly.
 *
 * The value is never cut. Where it does not fit its share of the width, or leaves the label or
 * the header too little room for their words, its text steps down, at most to
 * {@link #MIN_VALUE_TEXT_SCALE} of its size and never below its size at the default text size.
 * Where that is not enough (large text, a large display size, a long label), the value takes its
 * own line under the label at its full size, and the label and the header get the whole width.
 * @hide
 */
@RemoteViews.RemoteView
public class SingleMetricNotificationFrameLayout extends FrameLayout {

    /**
     * The smallest the value steps down to stay beside the label: about the 28 sp of a metric
     * among several against the 36 sp of a single one. Above the default text size, the value
     * also keeps at least its size at the default.
     */
    private static final float MIN_VALUE_TEXT_SCALE = 0.8f;

    /** The smallest a value too long even for its own line steps down to, before it is cut. */
    private static final float MIN_OWN_LINE_TEXT_SCALE = 0.5f;

    @NonNull
    private View mMetricValueContainer;

    @NonNull
    private View mMetricLabelContainer;

    @Nullable
    private TextView mMetricLabel;

    private float mValueContainerMaxFraction;

    /** The value's text views and their own text sizes, which a step down scales. */
    private final ArrayList<TextView> mValueViews = new ArrayList<>();
    private float[] mValueTextSizes = new float[0];
    private float mValueTextScale = 1f;
    private final TextPaint mMeasurePaint = new TextPaint();

    /** The label container's end margin from the template, kept while the value has a line. */
    private int mLabelContainerMarginEnd;

    /** Whether the value is on its own line, under the label. */
    private boolean mValueOnOwnLine;

    /** The least room between the label's or the header's words and the value beside them. */
    private int mWordsGap;

    public SingleMetricNotificationFrameLayout(
            @NonNull Context context) {
        super(context);
        init();
    }

    public SingleMetricNotificationFrameLayout(@NonNull Context context,
            @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public SingleMetricNotificationFrameLayout(@NonNull Context context,
            @Nullable AttributeSet attrs,
            int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    public SingleMetricNotificationFrameLayout(@NonNull Context context,
            @Nullable AttributeSet attrs,
            int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        init();
    }

    private void init() {
        mValueContainerMaxFraction = getMetricValueMaxFraction();
        mWordsGap = getResources().getDimensionPixelSize(R.dimen.notification_2025_margin) / 2;
    }

    protected float getMetricValueMaxFraction() {
        return getContext().getResources().getFraction(
                R.fraction.notification_single_metric_value_max_fraction, 1, 1
        );
    }

    protected View getMetricValueContainer() {
        return requireViewById(R.id.metric_value_container);
    }

    protected View getMetricLabelContainer() {
        return requireViewById(R.id.metric_label_0);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mMetricValueContainer = getMetricValueContainer();
        mMetricLabelContainer = getMetricLabelContainer();
        mMetricLabel = mMetricLabelContainer.findViewById(R.id.metric_label_0);
        if (mMetricValueContainer instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                if (group.getChildAt(i) instanceof TextView value) {
                    mValueViews.add(value);
                }
            }
        } else if (mMetricValueContainer instanceof TextView value) {
            mValueViews.add(value);
        }
        mValueTextSizes = new float[mValueViews.size()];
        for (int i = 0; i < mValueViews.size(); i++) {
            mValueTextSizes[i] = mValueViews.get(i).getTextSize();
        }
        final MarginLayoutParams layoutParams =
                (MarginLayoutParams) mMetricLabelContainer.getLayoutParams();
        mLabelContainerMarginEnd = layoutParams != null ? layoutParams.getMarginEnd() : 0;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        measureArranged(widthMeasureSpec, heightMeasureSpec);

        // Where the value goes, from what this measure found: beside the label at its full size
        // (the template's layout), beside it a step smaller, or on its own line under it.
        final TextView value = getVisibleValue();
        boolean ownLine = false;
        float scale = 1f;
        if (value != null) {
            final float textWidth = getFullSizeTextWidth(value);
            final int overhead = getValueOverhead(value);
            final float besideScale = textWidth > 0
                    ? (getMaxBesideWidth(widthMeasureSpec) - overhead - 1) / textWidth : 1f;
            if (besideScale < 1f) {
                scale = (float) Math.floor(besideScale * 100f) / 100f;
                if (scale < getMinValueTextScale(value)) {
                    ownLine = true;
                    final int lineWidth = MeasureSpec.getSize(widthMeasureSpec) - getPaddingLeft()
                            - getPaddingRight() - getLabelTextStart() - overhead - 1;
                    scale = Math.max(MIN_OWN_LINE_TEXT_SCALE, Math.min(1f, lineWidth / textWidth));
                }
            }
        }
        if (ownLine != mValueOnOwnLine || scale != mValueTextScale) {
            mValueOnOwnLine = ownLine;
            setValueTextScale(scale);
            measureArranged(widthMeasureSpec, heightMeasureSpec);
        }
    }

    /** Measures with the value where it is now: beside the label or on its own line. */
    private void measureArranged(int widthMeasureSpec, int heightMeasureSpec) {
        if (!mValueOnOwnLine) {
            measureChildWithMargins(mMetricValueContainer,
                    widthMeasureSpec, 0,
                    heightMeasureSpec, 0);

            final int toplineMarginEnd = getLabelContainerMarginEnd();

            adjustEndMarginBeforeOnMeasure(toplineMarginEnd);
            setLabelContainerMarginEnd(toplineMarginEnd);

            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            return;
        }

        adjustEndMarginBeforeOnMeasure(0);
        setLabelContainerMarginEnd(mLabelContainerMarginEnd);
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        final int bottom = getValueTopOnOwnLine() + mMetricValueContainer.getMeasuredHeight()
                + getLabelContainerBottomMargin() + getPaddingBottom();
        if (bottom > getMeasuredHeight()) {
            setMeasuredDimension(getMeasuredWidthAndState(),
                    resolveSizeAndState(bottom, heightMeasureSpec, 0));
        }
    }

    /**
     * The widest the value (with its padding and margins) may be beside the label: its share of
     * the width, and what leaves the label and the header room for their words, where moving the
     * value to its own line would give them that room.
     */
    private int getMaxBesideWidth(int widthMeasureSpec) {
        int maxWidth = (int) (MeasureSpec.getSize(widthMeasureSpec) * mValueContainerMaxFraction)
                - getPaddingLeft() - getPaddingRight();
        if (mMetricLabel != null && mMetricLabel.getVisibility() != GONE) {
            final int labelWidth = (int) Math.ceil(getDesiredTextWidth(mMetricLabel));
            // The label's room with no end margin for the value, from this measure.
            final int labelRoom = getTextAvailableWidth(mMetricLabel)
                    + getLabelContainerCurrentMarginEnd() - mLabelContainerMarginEnd;
            if (labelWidth <= labelRoom) {
                maxWidth = Math.min(maxWidth,
                        labelRoom + mLabelContainerMarginEnd - labelWidth - mWordsGap);
            }
        }
        final int headerMaxWidth = getMaxValueWidthForHeader();
        if (headerMaxWidth != Integer.MAX_VALUE) {
            maxWidth = Math.min(maxWidth, headerMaxWidth - mWordsGap);
        }
        return maxWidth;
    }

    /**
     * The widest the value may be at the end of the header line and leave the header's words
     * whole, where they fit the line without it; from the last measure.
     */
    protected int getMaxValueWidthForHeader() {
        return Integer.MAX_VALUE;
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        if (!mValueOnOwnLine || mMetricValueContainer.getVisibility() == GONE) {
            return;
        }
        // Under the label, its text lined up with the label's.
        final View value = mMetricValueContainer;
        final int width = value.getMeasuredWidth();
        final int textStart = getLabelTextStart();
        final int valueLeft;
        if (isLayoutRtl()) {
            valueLeft = right - left - getPaddingRight() - textStart + value.getPaddingRight()
                    - width;
        } else {
            valueLeft = getPaddingLeft() + textStart - value.getPaddingLeft();
        }
        final int valueTop = getValueTopOnOwnLine();
        value.layout(valueLeft, valueTop, valueLeft + width,
                valueTop + value.getMeasuredHeight());
    }

    protected void adjustEndMarginBeforeOnMeasure(int marginEnd) {}

    @Nullable
    private TextView getVisibleValue() {
        for (int i = 0; i < mValueViews.size(); i++) {
            final TextView value = mValueViews.get(i);
            if (value.getVisibility() != GONE) {
                return value;
            }
        }
        return null;
    }

    /** The value's text width at its full size. */
    private float getFullSizeTextWidth(TextView value) {
        final CharSequence content = value.getText();
        if (content == null) {
            return 0f;
        }
        mMeasurePaint.set(value.getPaint());
        mMeasurePaint.setTextSize(mValueTextSizes[mValueViews.indexOf(value)]);
        return Layout.getDesiredWidth(content, mMeasurePaint);
    }

    /**
     * How far the value may step down beside the label: to {@link #MIN_VALUE_TEXT_SCALE} of its
     * size, and with the text size above the default, not below its size at the default text
     * size, so that larger text never makes the value smaller.
     */
    private float getMinValueTextScale(TextView value) {
        final DisplayMetrics metrics = getResources().getDisplayMetrics();
        if (getResources().getConfiguration().fontScale <= 1f) {
            return MIN_VALUE_TEXT_SCALE;
        }
        final float fullSize = mValueTextSizes[mValueViews.indexOf(value)];
        final float defaultSize = TypedValue.deriveDimension(TypedValue.COMPLEX_UNIT_SP, fullSize,
                metrics) * metrics.density;
        return Math.max(MIN_VALUE_TEXT_SCALE, defaultSize / fullSize);
    }

    /** What the value needs beside its text: its paddings and its container's, and margins. */
    private int getValueOverhead(TextView value) {
        final MarginLayoutParams layoutParams =
                (MarginLayoutParams) mMetricValueContainer.getLayoutParams();
        final int margins = layoutParams != null
                ? layoutParams.getMarginStart() + layoutParams.getMarginEnd() : 0;
        final int containerPadding = mMetricValueContainer == value
                ? 0 : mMetricValueContainer.getMeasuredWidth() - value.getMeasuredWidth();
        return containerPadding + value.getCompoundPaddingLeft() + value.getCompoundPaddingRight()
                + margins;
    }

    private void setValueTextScale(float scale) {
        mValueTextScale = scale;
        for (int i = 0; i < mValueViews.size(); i++) {
            final TextView value = mValueViews.get(i);
            final float size = mValueTextSizes[i] * scale;
            if (value.getTextSize() != size) {
                value.setTextSize(TypedValue.COMPLEX_UNIT_PX, size);
            }
        }
    }

    private int getLabelContainerCurrentMarginEnd() {
        final MarginLayoutParams layoutParams =
                (MarginLayoutParams) mMetricLabelContainer.getLayoutParams();
        return layoutParams != null ? layoutParams.getMarginEnd() : 0;
    }

    private void setLabelContainerMarginEnd(int marginEnd) {
        final MarginLayoutParams layoutParams =
                (MarginLayoutParams) mMetricLabelContainer.getLayoutParams();
        if (layoutParams != null && layoutParams.getMarginEnd() != marginEnd) {
            layoutParams.setMarginEnd(marginEnd);
            mMetricLabelContainer.setLayoutParams(layoutParams);
        }
    }

    private int getLabelContainerBottomMargin() {
        final MarginLayoutParams layoutParams =
                (MarginLayoutParams) mMetricLabelContainer.getLayoutParams();
        return layoutParams != null ? layoutParams.bottomMargin : 0;
    }

    /** Where the value's line starts, under the label's (last measure). */
    private int getValueTopOnOwnLine() {
        final MarginLayoutParams layoutParams =
                (MarginLayoutParams) mMetricLabelContainer.getLayoutParams();
        final int topMargin = layoutParams != null ? layoutParams.topMargin : 0;
        return getPaddingTop() + topMargin + mMetricLabelContainer.getMeasuredHeight();
    }

    /** How far in from this view's start the label's text starts. */
    private int getLabelTextStart() {
        final MarginLayoutParams layoutParams =
                (MarginLayoutParams) mMetricLabelContainer.getLayoutParams();
        int start = layoutParams != null ? layoutParams.getMarginStart() : 0;
        if (mMetricLabel != null && mMetricLabel != mMetricLabelContainer) {
            start += mMetricLabelContainer.getPaddingStart();
        }
        if (mMetricLabel != null) {
            start += isLayoutRtl() ? mMetricLabel.getCompoundPaddingRight()
                    : mMetricLabel.getCompoundPaddingLeft();
        }
        return start;
    }

    private static float getDesiredTextWidth(TextView text) {
        final CharSequence content = text.getText();
        return content == null ? 0f : Layout.getDesiredWidth(content, text.getPaint());
    }

    private static int getTextAvailableWidth(TextView text) {
        return text.getMeasuredWidth() - text.getCompoundPaddingLeft()
                - text.getCompoundPaddingRight();
    }

    /**
     * NOTE: mMetricValueContainer needs to be measured before this method.
     */
    private int getLabelContainerMarginEnd() {
        final MarginLayoutParams layoutParams =
                (MarginLayoutParams) mMetricValueContainer.getLayoutParams();

        final int horizontalMargins;
        if (layoutParams != null) {
            horizontalMargins = layoutParams.getMarginStart() + layoutParams.getMarginEnd();
        } else {
            horizontalMargins = 0;
        }

        return mMetricValueContainer.getMeasuredWidth() + horizontalMargins;
    }

    @Override
    protected void measureChildWithMargins(View child, int parentWidthMeasureSpec, int widthUsed,
            int parentHeightMeasureSpec, int heightUsed) {
        if (child.getId() == R.id.metric_value_container) {
            final int availableWidth = MeasureSpec.getSize(parentWidthMeasureSpec);
            // Beside the label, the value has its share of the width; on its own line, the
            // label's.
            final int maxAllowedWidth = mValueOnOwnLine
                    ? availableWidth - getLabelTextStart()
                    : (int) (availableWidth * mValueContainerMaxFraction);
            final int restrictedWidthSpec = MeasureSpec.makeMeasureSpec(maxAllowedWidth,
                    MeasureSpec.AT_MOST);
            super.measureChildWithMargins(
                    child,
                    restrictedWidthSpec,
                    widthUsed,
                    parentHeightMeasureSpec,
                    heightUsed
            );
        } else {
            super.measureChildWithMargins(child, parentWidthMeasureSpec, widthUsed,
                    parentHeightMeasureSpec, heightUsed);
        }
    }
}
