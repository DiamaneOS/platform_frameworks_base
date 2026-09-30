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

package com.android.systemui.tally.shade

import android.content.Context
import android.content.res.Configuration
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.View.MeasureSpec
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.res.R
import com.android.systemui.statusbar.notification.collection.render.SectionHeaderNodeControllerImpl
import com.android.systemui.statusbar.notification.headsup.HeadsUpAnimator
import com.android.systemui.statusbar.notification.stack.SectionHeaderView
import com.android.systemui.statusbar.notification.stack.StackScrollAlgorithm
import com.android.systemui.statusbar.notification.stack.StackScrollAlgorithm.SectionProvider
import com.android.systemui.tally.TallyShell
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * The space a section header takes in the shade, against the prototype (.nsec and .sec-h in
 * app.css): from the last card of one section to the header's words 16 dp (the section's 4 dp
 * margin and the header's 12 dp), the words on the section type's 16 sp line, and 8 dp from the
 * words to the first card: 40 dp card to card at the default text size. Stock's header is 48 dp
 * with its section gap before it. Cards within a section stay 8 dp apart, the Live section's too.
 */
@SmallTest
@RunWith(AndroidJUnit4::class)
class TallySectionSpacingTest : SysuiTestCase() {

    private val card = mock<View>()
    private val nextCard = mock<View>()
    private val sections = mock<SectionProvider>()

    private fun dp(value: Float, c: Context = context) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, c.resources.displayMetrics)

    private fun sp(value: Float, c: Context) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, c.resources.displayMetrics)

    private fun px(id: Int) = context.resources.getDimensionPixelSize(id).toFloat()

    /** The header as the shade inflates it, laid out 360 dp wide at [fontScale]. */
    private fun header(
        fontScale: Float = 1f,
        clearAll: Boolean = false,
        rtl: Boolean = false,
    ): Pair<SectionHeaderView, Context> {
        val c =
            context.createConfigurationContext(
                Configuration(context.resources.configuration).apply { this.fontScale = fontScale }
            )
        val controller =
            SectionHeaderNodeControllerImpl(
                "silent header",
                LayoutInflater.from(c),
                R.string.notification_section_header_gentle,
                mock(),
                "",
            )
        val parent = FrameLayout(c)
        controller.reinflateView(parent)
        controller.setClearSectionEnabled(clearAll)
        val view = controller.headerView!!
        if (rtl) view.layoutDirection = View.LAYOUT_DIRECTION_RTL
        // Measured as the stack measures its children: its width, and any height it asks for.
        val width = dp(360f, c).toInt()
        view.measure(
            ViewGroup.getChildMeasureSpec(
                MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                0,
                view.layoutParams.width,
            ),
            ViewGroup.getChildMeasureSpec(
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
                0,
                view.layoutParams.height,
            ),
        )
        view.layout(0, 0, width, view.measuredHeight)
        return view to c
    }

    private fun SectionHeaderView.label() = requireViewById<TextView>(R.id.header_label)

    /**
     * The words' line: the section type's 16 sp, unless the font's own height (from its ascent to
     * its descent) is more, which Sofia Sans's (13.2 dp at 11 sp) never is.
     */
    private fun line(label: TextView, c: Context): Float {
        val font = label.paint.fontMetricsInt.let { it.descent - it.ascent }.toFloat()
        return maxOf(sp(16f, c), font)
    }

    private fun SectionHeaderView.clearAll() = requireViewById<ImageView>(R.id.btn_clear_all)

    /** Where [child] is within [root], from its top. */
    private fun top(child: View, root: View): Int {
        var y = 0
        var v: View = child
        while (v !== root) {
            y += v.top
            v = v.parent as View
        }
        return y
    }

    private fun left(child: View, root: View): Int {
        var x = 0
        var v: View = child
        while (v !== root) {
            x += v.left
            v = v.parent as View
        }
        return x
    }

    @Test
    fun header_isTwelveDpAndTheWordsLine() {
        for (scale in floatArrayOf(1f, 1.5f, 2f)) {
            val (view, c) = header(scale)
            val label = view.label()
            if (TallyShell.isEnabled) {
                val line = line(label, c)
                assertThat(label.minHeight.toFloat()).isWithin(1f).of(sp(16f, c))
                assertThat(top(label, view).toFloat()).isWithin(1f).of(dp(12f, c))
                assertThat(label.height.toFloat()).isWithin(1f).of(line)
                assertThat(view.height.toFloat()).isWithin(1f).of(dp(12f, c) + line)
            } else {
                assertThat(view.height.toFloat()).isWithin(1f).of(dp(48f, c))
            }
        }
    }

    @Test
    fun header_wordsSitFourDpInFromTheCards() {
        val (view, _) = header()
        val label = view.label()
        if (!TallyShell.isEnabled) return
        assertThat((left(label, view) + label.paddingStart).toFloat()).isWithin(1f).of(dp(4f))
        val (rtl, _) = header(rtl = true)
        val rtlLabel = rtl.label()
        assertThat(
                (rtl.width - left(rtlLabel, rtl) - rtlLabel.width + rtlLabel.paddingStart).toFloat()
            )
            .isWithin(1f)
            .of(dp(4f))
    }

    @Test
    fun clearAll_takesTheHeadersHeight_crossOnTheWordsLine() {
        for (scale in floatArrayOf(1f, 2f)) {
            val (view, c) = header(scale, clearAll = true)
            val x = view.clearAll()
            val label = view.label()
            assertThat(x.visibility).isEqualTo(View.VISIBLE)
            if (!TallyShell.isEnabled) {
                assertThat(view.height.toFloat()).isWithin(1f).of(dp(48f, c))
                continue
            }
            // The key doesn't make the header taller; it spans all of it, 48 dp wide.
            assertThat(view.height.toFloat()).isWithin(1f).of(dp(12f, c) + line(label, c))
            assertThat(x.height).isEqualTo(view.height)
            assertThat(x.width.toFloat()).isWithin(1f).of(dp(48f, c))
            // Its cross is centred on the words' line, and its end 4 dp in, as stock's.
            val crossCentre = top(x, view) + x.paddingTop + (x.height - x.paddingTop) / 2f
            val lineCentre = top(label, view) + label.height / 2f
            assertThat(crossCentre).isWithin(1f).of(lineCentre)
            assertThat((view.width - left(x, view) - x.width).toFloat()).isWithin(1f).of(dp(4f, c))
        }
    }

    private fun algorithm() =
        StackScrollAlgorithm(context, FrameLayout(context), mock<HeadsUpAnimator>())

    @Test
    fun cardToCard_acrossAHeader_isFortyDp() {
        val (view, _) = header()
        // A header begins its section (NotificationSectionsManager.beginsSection).
        whenever(sections.beginsSection(view, card)).thenReturn(true)
        val gap =
            algorithm().getGapHeightForChild(sections, 1, view, card, 0f, /* onKeyguard= */ false)
        val between = px(R.dimen.notification_divider_height)
        val total = between + gap + view.height + between
        if (TallyShell.isEnabled) {
            val label = view.label()
            assertThat(between).isWithin(1f).of(dp(8f))
            // 4 dp margin + 12 dp above the words, the words' line, 8 dp to the next card
            assertThat(between + gap + top(label, view)).isWithin(1f).of(dp(16f))
            assertThat(total).isWithin(1f).of(dp(16f) + line(label, context) + dp(8f))
            assertThat(between + gap + top(label, view) + sp(16f, context) + between)
                .isWithin(1f)
                .of(dp(40f))
        } else {
            assertThat(gap).isEqualTo(px(R.dimen.notification_section_divider_height))
        }
    }

    @Test
    fun liveCards_areEightDpApart_inTheShade() {
        whenever(sections.isGroupingDisabled(nextCard)).thenReturn(true)
        val gap = algorithm().getGapHeightForChild(sections, 2, nextCard, card, 0f, false)
        if (TallyShell.isEnabled) {
            assertThat(gap).isEqualTo(0f)
        } else {
            assertThat(gap).isEqualTo(px(R.dimen.grouping_disabled_section_gap_height))
        }
        // The lock screen keeps stock's gap.
        assertThat(algorithm().getGapHeightForChild(sections, 2, nextCard, card, 0f, true))
            .isEqualTo(px(R.dimen.grouping_disabled_section_gap_height))
    }

    @Test
    fun conversations_areOneSection_inTheShade() {
        whenever(sections.beginsSection(nextCard, card)).thenReturn(true)
        whenever(sections.continuesShadeSection(nextCard, card)).thenReturn(true)
        val gap = algorithm().getGapHeightForChild(sections, 2, nextCard, card, 0f, false)
        assertThat(gap)
            .isEqualTo(
                if (TallyShell.isEnabled) 0f else px(R.dimen.notification_section_divider_height)
            )
        // The lock screen keeps stock's gap between them.
        assertThat(algorithm().getGapHeightForChild(sections, 2, nextCard, card, 0f, true))
            .isEqualTo(px(R.dimen.notification_section_divider_height_lockscreen))
    }

    @Test
    fun aSectionWithoutHeader_keepsStocksGap() {
        whenever(sections.beginsSection(nextCard, card)).thenReturn(true)
        assertThat(algorithm().getGapHeightForChild(sections, 2, nextCard, card, 0f, false))
            .isEqualTo(px(R.dimen.notification_section_divider_height))
        assertThat(algorithm().getGapHeightForChild(sections, 2, nextCard, card, 0f, true))
            .isEqualTo(px(R.dimen.notification_section_divider_height_lockscreen))
    }

    @Test
    fun headerGap_movesFromTheLockScreensAsStocksDoes() {
        val (view, _) = header()
        whenever(sections.beginsSection(view, card)).thenReturn(true)
        val algorithm = algorithm()
        val shade = algorithm.getGapHeightForChild(sections, 1, view, card, 0f, false)
        val lock = algorithm.getGapHeightForChild(sections, 1, view, card, 0f, true)
        val half = algorithm.getGapHeightForChild(sections, 1, view, card, 0.5f, true)
        assertThat(lock).isEqualTo(px(R.dimen.notification_section_divider_height_lockscreen))
        assertThat(half).isWithin(0.01f).of((lock + shade) / 2)
    }
}
