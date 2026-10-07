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

package com.android.internal.widget;

import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;

import static com.google.common.truth.Truth.assertThat;

import android.content.Context;
import android.graphics.Typeface;
import android.text.Layout;
import android.util.TypedValue;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View.MeasureSpec;
import android.widget.LinearLayout;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.internal.R;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Tally: a notification action key fits its label before it cuts it (condensed face, then two
 * lines between words), and the action row grows with a two-line key.
 */
@RunWith(AndroidJUnit4.class)
@SmallTest
public class EmphasizedNotificationButtonFitTest {

    private static final String LABEL = "Add 1 min";

    private Context mContext;

    @Before
    public void setUp() {
        mContext = new ContextThemeWrapper(
                InstrumentationRegistry.getInstrumentation().getTargetContext(),
                android.R.style.Theme_DeviceDefault);
    }

    @Test
    public void wideKey_keepsTheLabelAsItIs() {
        final EmphasizedNotificationButton key = newKey(LABEL);
        final Typeface typeface = key.getTypeface();

        measure(key, widthForLabel(key, 1.2f));

        assertThat(key.getLineCount()).isEqualTo(1);
        assertNotCut(key);
        assertThat(key.getTypeface()).isSameInstanceAs(typeface);
    }

    @Test
    public void narrowKey_wrapsTheLabelWholeOnTwoLines_andGoesBackWhenWide() {
        final EmphasizedNotificationButton key = newKey(LABEL);
        final Typeface typeface = key.getTypeface();
        final int narrow = widthForLabel(key, 0.7f);
        final int wide = widthForLabel(key, 1.2f);

        measure(key, narrow);

        assertThat(key.getLineCount()).isEqualTo(2);
        assertNotCut(key);
        assertThat(key.getMeasuredHeight()).isAtLeast(dp(48));

        measure(key, wide);

        assertThat(key.getLineCount()).isEqualTo(1);
        assertNotCut(key);
        assertThat(key.getTypeface()).isSameInstanceAs(typeface);
    }

    @Test
    public void singleLongWord_isNotBrokenInside() {
        final EmphasizedNotificationButton key = newKey("Supercalifragilistic");

        measure(key, widthForLabel(key, 0.6f));

        assertThat(key.getLineCount()).isEqualTo(1);
        assertThat(key.getLayout().getEllipsisCount(0)).isGreaterThan(0);
    }

    @Test
    public void evenlyDividedRow_growsWithATwoLineKey_andKeepsKeysOneHeight() {
        final NotificationActionListLayout row = new NotificationActionListLayout(mContext, null);
        row.setEvenlyDividedMode(true);
        final EmphasizedNotificationButton pause = newKey("Pause");
        final EmphasizedNotificationButton add = newKey(LABEL);
        row.addView(pause, new LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT));
        row.addView(add, new LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT));
        // Each key gets half the row: room for 70 % of the long label.
        final int rowWidth = 2 * widthForLabel(add, 0.7f);

        row.measure(MeasureSpec.makeMeasureSpec(rowWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(dp(400), MeasureSpec.AT_MOST));

        assertThat(add.getLineCount()).isEqualTo(2);
        assertNotCut(add);
        assertThat(pause.getMeasuredHeight()).isEqualTo(add.getMeasuredHeight());
        assertThat(row.getMeasuredHeight()).isAtLeast(add.getMeasuredHeight());
    }

    private EmphasizedNotificationButton newKey(String label) {
        final EmphasizedNotificationButton key = (EmphasizedNotificationButton) LayoutInflater
                .from(mContext).inflate(R.layout.notification_material_action_emphasized, null);
        key.setText(label);
        return key;
    }

    /**
     * A key width that leaves {@code share} of the label's width, in the key's present face, for
     * the label.
     */
    private static int widthForLabel(EmphasizedNotificationButton key, float share) {
        final float label = Layout.getDesiredWidth(key.getText(), key.getPaint());
        return (int) (label * share) + key.getCompoundPaddingLeft()
                + key.getCompoundPaddingRight();
    }

    private void measure(EmphasizedNotificationButton key, int width) {
        key.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
    }

    private static void assertNotCut(EmphasizedNotificationButton key) {
        final Layout layout = key.getLayout();
        for (int line = 0; line < layout.getLineCount(); line++) {
            assertThat(layout.getEllipsisCount(line)).isEqualTo(0);
        }
    }

    private int dp(int value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                mContext.getResources().getDisplayMetrics()));
    }
}
