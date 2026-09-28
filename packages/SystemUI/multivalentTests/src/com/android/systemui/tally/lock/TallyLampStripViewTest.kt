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
import com.android.systemui.res.R
import com.android.systemui.tally.lamp.TallyLampState
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

/** The lamp strip measures and lays out in every state it can be in, the empty ones included. */
@SmallTest
@RunWith(AndroidJUnit4::class)
class TallyLampStripViewTest : SysuiTestCase() {

    @Test
    fun noItems_measuresAndLaysOutWithNoHeight() {
        val strip = TallyLampStripView(context)

        measureAndLayout(strip, MeasureSpec.makeMeasureSpec(WIDTH, MeasureSpec.EXACTLY))

        assertThat(strip.measuredHeight).isEqualTo(0)
    }

    @Test
    fun noItems_unboundedWidth_measuresAndLaysOutWithNoSize() {
        val strip = TallyLampStripView(context)

        measureAndLayout(strip, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))

        assertThat(strip.measuredWidth).isEqualTo(0)
        assertThat(strip.measuredHeight).isEqualTo(0)
    }

    @Test
    fun itemsThatAllFoldAway_measuresAndLaysOutWithNoHeight() {
        val strip = TallyLampStripView(context)
        strip.setItems(
            listOf(
                offItem(TallyStripItem.Kind.WIFI),
                offItem(TallyStripItem.Kind.BLUETOOTH),
                offItem(TallyStripItem.Kind.CALM),
            )
        )

        // Too narrow for any item: the items that are off fold away, and nothing is left.
        measureAndLayout(strip, MeasureSpec.makeMeasureSpec(1, MeasureSpec.EXACTLY))

        assertThat(strip.measuredHeight).isEqualTo(0)
    }

    @Test
    fun oneItem_measuresAndLaysOutOneRow() {
        val strip = TallyLampStripView(context)
        strip.setItems(
            listOf(
                TallyStripItem(
                    TallyStripItem.Kind.BATTERY,
                    lamp = null,
                    listOf(TallyWords(R.string.accessibility_battery_level, 64)),
                    batteryLevel = 64,
                )
            )
        )

        measureAndLayout(strip, MeasureSpec.makeMeasureSpec(WIDTH, MeasureSpec.EXACTLY))

        assertThat(strip.measuredHeight).isGreaterThan(0)
        val item =
            (0 until strip.childCount)
                .map { strip.getChildAt(it) }
                .single { it.visibility == View.VISIBLE }
        assertThat(item.width).isGreaterThan(0)
        assertThat(item.bottom).isAtMost(strip.measuredHeight)
    }

    private fun offItem(kind: TallyStripItem.Kind) =
        TallyStripItem(kind, TallyLampState.OFF, listOf(TallyWords(R.string.dnd_is_off)))

    private fun measureAndLayout(strip: TallyLampStripView, widthSpec: Int) {
        strip.measure(widthSpec, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        strip.layout(0, 0, strip.measuredWidth, strip.measuredHeight)
    }

    private companion object {
        const val WIDTH = 1080
    }
}
