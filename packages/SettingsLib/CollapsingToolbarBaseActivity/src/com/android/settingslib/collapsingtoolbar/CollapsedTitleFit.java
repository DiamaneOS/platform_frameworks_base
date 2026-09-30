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

package com.android.settingslib.collapsingtoolbar;

import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;

import androidx.annotation.Nullable;

import com.google.android.material.appbar.CollapsingToolbarLayout;

/**
 * Tally: fits a page's collapsed title to its bar. A title too wide for the bar at the collapsed
 * size is set smaller, just enough to fit, down to the size of a row's title; only a title that
 * does not fit even then still ends in an ellipsis. Pages open with the title collapsed, so stock
 * cut every long title ("Additional settings in the a…"), where the Tally prototype's page title
 * scales down as it docks.
 */
public final class CollapsedTitleFit implements ViewTreeObserver.OnPreDrawListener {

    /** The smallest collapsed title: the size of a row's title (Tally's item type). */
    private static final float MIN_TEXT_SIZE_DP = 16f;

    private final CollapsingToolbarLayout mLayout;
    /** The collapsed title's own size, from its text appearance. */
    private final float mTextSize;
    private final float mMinTextSize;
    private final TextPaint mPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);

    @Nullable private CharSequence mFittedTitle;
    private int mFittedWidth = -1;
    @Nullable private Typeface mFittedTypeface;

    /** Keeps the layout's collapsed title fitted to its bar, from its next frame on. */
    public static void install(@Nullable CollapsingToolbarLayout layout) {
        if (layout == null) {
            return;
        }
        layout.getViewTreeObserver().addOnPreDrawListener(new CollapsedTitleFit(layout));
    }

    private CollapsedTitleFit(CollapsingToolbarLayout layout) {
        mLayout = layout;
        mTextSize = layout.getCollapsedTitleTextSize();
        mMinTextSize = Math.min(mTextSize, TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,
                MIN_TEXT_SIZE_DP, layout.getResources().getDisplayMetrics()));
    }

    @Override
    public boolean onPreDraw() {
        // Runs before every frame, so it only measures when the title, the bar's width or the
        // typeface has changed.
        final CharSequence title = mLayout.getTitle();
        final int width = availableWidth();
        final Typeface typeface = mLayout.getCollapsedTitleTypeface();
        if (width == mFittedWidth && typeface == mFittedTypeface
                && TextUtils.equals(title, mFittedTitle)) {
            return true;
        }
        mFittedTitle = title;
        mFittedWidth = width;
        mFittedTypeface = typeface;
        if (TextUtils.isEmpty(title) || width <= 0) {
            return true;
        }
        mPaint.setTypeface(typeface);
        float size = mTextSize;
        final float natural = measure(title, size);
        if (natural > width) {
            size = Math.max(mMinTextSize, (float) Math.floor(mTextSize * width / natural));
            while (size > mMinTextSize && measure(title, size) > width) {
                size = Math.max(mMinTextSize, size - 1);
            }
        }
        if (size != mLayout.getCollapsedTitleTextSize()) {
            mLayout.setCollapsedTitleTextSize(size);
        }
        return true;
    }

    private float measure(CharSequence text, float size) {
        mPaint.setTextSize(size);
        return mPaint.measureText(text, 0, text.length());
    }

    /**
     * The width the collapsed title gets: that of the view the layout puts in its toolbar to mark
     * the title's place (between the Up key and the actions), less the toolbar's title margins,
     * as the layout measures it. 0 before the first layout.
     */
    private int availableWidth() {
        final View toolbar = toolbar();
        if (toolbar == null) {
            return 0;
        }
        final ViewGroup group = (ViewGroup) toolbar;
        for (int i = 0; i < group.getChildCount(); i++) {
            final View child = group.getChildAt(i);
            // The title's place is a plain View (CollapsingToolbarLayout's dummy view).
            if (child.getClass() == View.class) {
                return child.getWidth() - titleMargins(toolbar);
            }
        }
        return 0;
    }

    @Nullable
    private View toolbar() {
        for (int i = 0; i < mLayout.getChildCount(); i++) {
            final View child = mLayout.getChildAt(i);
            if (child instanceof android.widget.Toolbar
                    || child instanceof androidx.appcompat.widget.Toolbar) {
                return child;
            }
        }
        return null;
    }

    private static int titleMargins(View toolbar) {
        if (toolbar instanceof android.widget.Toolbar) {
            final android.widget.Toolbar t = (android.widget.Toolbar) toolbar;
            return t.getTitleMarginStart() + t.getTitleMarginEnd();
        }
        final androidx.appcompat.widget.Toolbar t = (androidx.appcompat.widget.Toolbar) toolbar;
        return t.getTitleMarginStart() + t.getTitleMarginEnd();
    }
}
