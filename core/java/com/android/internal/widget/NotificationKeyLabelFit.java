/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.internal.widget;

import android.annotation.Nullable;
import android.graphics.Typeface;
import android.icu.text.BreakIterator;
import android.text.Layout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.View.MeasureSpec;
import android.widget.TextView;

/**
 * Tally: fits the label of an {@link EmphasizedNotificationButton} to its key before it is cut.
 * A label wider than its key is set in the condensed family, then wraps to two lines between
 * words.
 */
final class NotificationKeyLabelFit {
    /**
     * Tally: the family a label that does not fit its key is set in, the condensed family token
     * (Sofia Sans Semi Condensed). Where it is not installed this is the default font, and a
     * label that does not fit goes on to two lines.
     */
    private static final String CONDENSED_FAMILY = "sofia-sans-semi-condensed";

    /** The label fits the key as it is. */
    private static final int FIT_WHOLE = 0;
    /** The label fits the key on one line in the condensed family. */
    private static final int FIT_CONDENSED = 1;
    /** The label takes two lines in the condensed family. The rest ends in an ellipsis. */
    private static final int FIT_TWO_LINES = 2;

    private int mLabelFit = FIT_WHOLE;
    /** The label's own typeface, kept while the label is condensed. */
    @Nullable
    private Typeface mLabelTypeface;
    private final TextPaint mFitPaint = new TextPaint();

    private final TextView mView;

    NotificationKeyLabelFit(TextView view) {
        mView = view;
    }

    /** Chooses the label's fit for the width the key is measured with. */
    void onMeasure(int widthMeasureSpec) {
        if (MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED) {
            setLabelFit(chooseLabelFit(MeasureSpec.getSize(widthMeasureSpec)));
        }
    }

    private int chooseLabelFit(int width) {
        final CharSequence text = mView.getText();
        final int room = width - mView.getCompoundPaddingLeft() - mView.getCompoundPaddingRight();
        if (TextUtils.isEmpty(text) || room <= 0) {
            return FIT_WHOLE;
        }
        mFitPaint.set(mView.getPaint());
        mFitPaint.setTypeface(labelTypeface());
        if (Layout.getDesiredWidth(text, mFitPaint) <= room) {
            return FIT_WHOLE;
        }
        mFitPaint.setTypeface(condensedTypeface());
        if (Layout.getDesiredWidth(text, mFitPaint) <= room) {
            return FIT_CONDENSED;
        }
        // Wrap only where every word fits a line, so no word is broken inside.
        return widestWord(text, mFitPaint) <= room ? FIT_TWO_LINES : FIT_CONDENSED;
    }

    private static float widestWord(CharSequence text, TextPaint paint) {
        final BreakIterator breaks = BreakIterator.getLineInstance();
        breaks.setText(text.toString());
        float widest = 0;
        int start = breaks.first();
        for (int end = breaks.next(); end != BreakIterator.DONE; start = end, end = breaks.next()) {
            int trimmedEnd = end;
            while (trimmedEnd > start && Character.isWhitespace(text.charAt(trimmedEnd - 1))) {
                trimmedEnd--;
            }
            widest = Math.max(widest, Layout.getDesiredWidth(text, start, trimmedEnd, paint));
        }
        return widest;
    }

    private void setLabelFit(int fit) {
        if (fit == mLabelFit) {
            return;
        }
        if (mLabelFit == FIT_WHOLE) {
            mLabelTypeface = mView.getTypeface();
        }
        final Typeface typeface = fit == FIT_WHOLE ? mLabelTypeface : condensedTypeface();
        if (fit == FIT_TWO_LINES) {
            mView.setSingleLine(false);
            mView.setMaxLines(2);
        } else if (mLabelFit == FIT_TWO_LINES) {
            mView.setSingleLine(true);
        }
        mLabelFit = fit;
        mView.setTypeface(typeface);
    }

    /** The label's own typeface, whether or not it is condensed now. */
    @Nullable
    private Typeface labelTypeface() {
        return mLabelFit == FIT_WHOLE ? mView.getTypeface() : mLabelTypeface;
    }

    /** The condensed family at the label's own weight and slant. */
    private Typeface condensedTypeface() {
        final Typeface label = labelTypeface();
        final int weight = label != null ? label.getWeight() : 400;
        final boolean italic = label != null && label.isItalic();
        return Typeface.create(Typeface.create(CONDENSED_FAMILY, Typeface.NORMAL), weight, italic);
    }
}
