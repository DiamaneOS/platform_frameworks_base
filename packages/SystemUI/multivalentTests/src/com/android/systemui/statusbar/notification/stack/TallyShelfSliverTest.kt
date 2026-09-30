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

package com.android.systemui.statusbar.notification.stack

import android.util.TypedValue
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.res.R
import com.android.systemui.statusbar.notification.headsup.HeadsUpAnimator
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow
import com.android.systemui.statusbar.notification.row.ExpandableView
import com.android.systemui.tally.TallyShell
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * Where a Tally card meets the shelf: a card goes into the shelf when less than its top margin and
 * the upper half of its icon (36 dp) would show above it, so the shade never shows a sliver of a
 * card; with that much or more it shows, clipped by the shelf as stock's.
 */
@SmallTest
@RunWith(AndroidJUnit4::class)
class TallyShelfSliverTest : SysuiTestCase() {

    private val shelfStart = 2000f

    private fun algorithm() =
        StackScrollAlgorithm(context, FrameLayout(context), mock<HeadsUpAnimator>())

    private fun dp(value: Float) =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value,
            context.resources.displayMetrics,
        )

    private fun minVisible() =
        context.resources.getDimensionPixelSize(R.dimen.tally_notification_shelf_min_visible)

    private fun state(y: Float) = ExpandableViewState().apply { setYTranslation(y, "test") }

    @Test
    fun minimum_isTheTopMarginAndHalfTheIcon() {
        assertThat(minVisible().toFloat()).isWithin(1f).of(dp(36f))
    }

    @Test
    fun cardShowingLessThanTheMinimum_goesIntoTheShelf() {
        if (!TallyShell.isEnabled) return
        for (visible in floatArrayOf(1f, dp(12f), minVisible() - 1f)) {
            val state = state(shelfStart - visible)
            algorithm().updateViewWithShelf(mock<ExpandableNotificationRow>(), state, shelfStart)
            assertThat(state.yTranslation).isEqualTo(shelfStart)
            assertThat(state.hidden).isTrue()
            assertThat(state.inShelf).isTrue()
        }
    }

    @Test
    fun cardShowingTheMinimumOrMore_staysAboveTheShelf() {
        for (visible in floatArrayOf(minVisible().toFloat(), dp(72f))) {
            val state = state(shelfStart - visible)
            algorithm().updateViewWithShelf(mock<ExpandableNotificationRow>(), state, shelfStart)
            assertThat(state.yTranslation).isEqualTo(shelfStart - visible)
            assertThat(state.hidden).isFalse()
            assertThat(state.inShelf).isFalse()
        }
    }

    @Test
    fun expandingCard_staysWhereItIs() {
        val row = mock<ExpandableNotificationRow>()
        whenever(row.isExpandAnimationRunning).thenReturn(true)
        val state = state(shelfStart - dp(12f))
        algorithm().updateViewWithShelf(row, state, shelfStart)
        assertThat(state.yTranslation).isEqualTo(shelfStart - dp(12f))
        assertThat(state.hidden).isFalse()
    }

    @Test
    fun otherViews_keepStocksRule() {
        // Section headers and the footer are not cards: only what reaches the shelf goes into it.
        val state = state(shelfStart - dp(12f))
        algorithm().updateViewWithShelf(mock<ExpandableView>(), state, shelfStart)
        assertThat(state.yTranslation).isEqualTo(shelfStart - dp(12f))
        assertThat(state.hidden).isFalse()
    }
}
