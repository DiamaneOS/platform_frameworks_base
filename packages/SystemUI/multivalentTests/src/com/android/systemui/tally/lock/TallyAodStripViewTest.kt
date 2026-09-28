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

package com.android.systemui.tally.lock

import android.view.View
import android.view.View.MeasureSpec
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

/** The always-on strip never shows before its binder has a dozing state, and survives no items. */
@SmallTest
@RunWith(AndroidJUnit4::class)
class TallyAodStripViewTest : SysuiTestCase() {

    @Test
    fun newStrip_isHidden() {
        val strip = TallyAodStripView(context)

        assertThat(strip.visibility).isEqualTo(View.INVISIBLE)
        assertThat(strip.alpha).isEqualTo(0f)
    }

    @Test
    fun noItems_measuresAndLaysOut() {
        val strip = TallyAodStripView(context)
        strip.setItems(emptyList())

        strip.measure(
            MeasureSpec.makeMeasureSpec(1080, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
        )
        strip.layout(0, 0, strip.measuredWidth, strip.measuredHeight)

        assertThat(strip.measuredWidth).isEqualTo(1080)
    }
}
