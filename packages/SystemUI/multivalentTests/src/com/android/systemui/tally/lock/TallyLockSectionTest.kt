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

import android.view.LayoutInflater
import android.view.View
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.keyguard.smartspace.LockscreenSmartspaceGeneralPlugin
import com.android.systemui.keyguard.ui.viewmodel.KeyguardClockViewModel
import com.android.systemui.plugins.BcSmartspaceDataPlugin
import com.android.systemui.plugins.keyguard.ui.clocks.ClockConfig
import com.android.systemui.plugins.keyguard.ui.clocks.ClockController
import com.android.systemui.plugins.keyguard.ui.clocks.ClockViewIds
import com.android.systemui.shared.R as sharedR
import com.android.systemui.shared.clocks.tally.TallyClocks.TALLY_CLOCK_ID
import com.android.systemui.statusbar.lockscreen.LockscreenSmartspaceController
import com.android.systemui.tally.TallyShell
import com.android.systemui.tally.lock.TallyLockSection.Smartspace
import com.google.common.truth.Truth.assertThat
import java.util.Optional
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock

/**
 * The lock section beside the lock screen's smartspace: with GrapheneOS's built-in smartspace it
 * adds its views as with none and hides that smartspace's line, whose rows it shows itself, so the
 * date shows once; with a smartspace plugin it adds nothing and leaves the plugin's smartspace as
 * it is.
 */
@SmallTest
@RunWith(AndroidJUnit4::class)
class TallyLockSectionTest : SysuiTestCase() {
    private val currentClock = MutableStateFlow<ClockController?>(null)
    private val largeClockVisible = MutableStateFlow(false)
    private lateinit var root: ConstraintLayout
    private lateinit var smartspaceLine: View

    @Before
    fun setUp() {
        // The default blueprint uses the section only while Tally is on; tests take the build's
        // flag.
        assumeTrue(TallyShell.isEnabled)
        smartspaceLine = View(context).apply { id = sharedR.id.bc_smartspace_view }
        root = ConstraintLayout(context).apply { addView(smartspaceLine) }
    }

    @Test
    fun smartspace_toldApartByItsPlugin() {
        val builtIn = LockscreenSmartspaceGeneralPlugin(LayoutInflater.from(context))

        assertThat(Smartspace.of(false, Optional.empty())).isEqualTo(Smartspace.NONE)
        assertThat(Smartspace.of(true, Optional.of(builtIn))).isEqualTo(Smartspace.BUILT_IN)
        assertThat(Smartspace.of(true, Optional.of(mock<BcSmartspaceDataPlugin>())))
            .isEqualTo(Smartspace.PLUGIN)
    }

    @Test
    fun builtInSmartspace_addsTheDateLineAndBothStrips() {
        section(builtInPlugin()).addViews(root)

        assertThat(root.childCount).isEqualTo(4)
        assertThat(views<TallyLockDateView>()).hasSize(1)
        assertThat(views<TallyLampStripView>()).hasSize(1)
        assertThat(views<TallyAodStripView>()).hasSize(1)
    }

    @Test
    fun builtInSmartspace_tallyClock_hidesTheSmartspaceLineAndPutsTheStripUnderTheClock() {
        currentClock.value = clock(TALLY_CLOCK_ID)
        val section = section(builtInPlugin())
        section.addViews(root)

        val constraints = constraints(section)

        assertThat(constraints.getVisibility(sharedR.id.bc_smartspace_view)).isEqualTo(View.GONE)
        // The Tally clock carries the date: the date line stays away.
        assertThat(constraints.getVisibility(view<TallyLockDateView>().id)).isEqualTo(View.GONE)
        assertThat(constraints.getConstraint(view<TallyLampStripView>().id).layout.topToBottom)
            .isEqualTo(ClockViewIds.LOCKSCREEN_CLOCK_VIEW_SMALL)
    }

    @Test
    fun builtInSmartspace_largeTallyClock_putsTheStripUnderTheLargeFace() {
        currentClock.value = clock(TALLY_CLOCK_ID)
        largeClockVisible.value = true
        val section = section(builtInPlugin())
        section.addViews(root)

        val constraints = constraints(section)

        assertThat(constraints.getVisibility(sharedR.id.bc_smartspace_view)).isEqualTo(View.GONE)
        assertThat(constraints.getConstraint(view<TallyLampStripView>().id).layout.topToBottom)
            .isEqualTo(ClockViewIds.LOCKSCREEN_CLOCK_VIEW_LARGE)
    }

    @Test
    fun builtInSmartspace_otherClock_showsOneDateLineWithTheStripUnderIt() {
        currentClock.value = clock("DEFAULT")
        val section = section(builtInPlugin())
        section.notificationsShown = true
        section.addViews(root)

        val constraints = constraints(section)

        assertThat(constraints.getVisibility(sharedR.id.bc_smartspace_view)).isEqualTo(View.GONE)
        val date = view<TallyLockDateView>()
        assertThat(constraints.getVisibility(date.id)).isEqualTo(View.VISIBLE)
        assertThat(constraints.getConstraint(view<TallyLampStripView>().id).layout.topToBottom)
            .isEqualTo(date.id)
    }

    @Test
    fun otherClock_lockScreenHidesNotifications_hidesTheDateLine() {
        // As GrapheneOS's smartspace line, whose date the date line stands in for.
        currentClock.value = clock("DEFAULT")
        val section = section(builtInPlugin())
        section.notificationsShown = false
        section.addViews(root)

        val constraints = constraints(section)

        val date = view<TallyLockDateView>()
        assertThat(constraints.getVisibility(date.id)).isEqualTo(View.GONE)
        assertThat(constraints.getVisibility(sharedR.id.bc_smartspace_view)).isEqualTo(View.GONE)
        // The strip takes the date line's place under the clock.
        assertThat(constraints.getConstraint(view<TallyLampStripView>().id).layout.topToBottom)
            .isEqualTo(date.id)
    }

    @Test
    fun otherClock_notificationsNotReadYet_keepsTheDateLineHidden() {
        currentClock.value = clock("DEFAULT")
        val section = section(builtInPlugin())
        section.addViews(root)

        val constraints = constraints(section)

        assertThat(constraints.getVisibility(view<TallyLockDateView>().id)).isEqualTo(View.GONE)
    }

    @Test
    fun tallyClock_notificationsShown_dateStaysInTheClockOnly() {
        currentClock.value = clock(TALLY_CLOCK_ID)
        val section = section(builtInPlugin())
        section.notificationsShown = true
        section.addViews(root)

        val constraints = constraints(section)

        assertThat(constraints.getVisibility(view<TallyLockDateView>().id)).isEqualTo(View.GONE)
    }

    @Test
    fun builtInSmartspace_noClockYet_appliesWithoutFailing() {
        val section = section(builtInPlugin())
        section.addViews(root)

        val constraints = constraints(section)

        assertThat(constraints.getVisibility(sharedR.id.bc_smartspace_view)).isEqualTo(View.GONE)
    }

    @Test
    fun smartspacePlugin_addsNothingAndLeavesItsSmartspaceAlone() {
        currentClock.value = clock(TALLY_CLOCK_ID)
        val section = section(mock<BcSmartspaceDataPlugin>())
        section.addViews(root)

        val constraints = constraints(section)

        assertThat(root.childCount).isEqualTo(1)
        assertThat(constraints.getVisibility(sharedR.id.bc_smartspace_view)).isEqualTo(View.VISIBLE)
    }

    @Test
    fun noSmartspace_addsTheViewsAndTouchesNoSmartspace() {
        root.removeView(smartspaceLine)
        val section = section(plugin = null)
        section.addViews(root)

        val constraints = constraints(section)

        assertThat(root.childCount).isEqualTo(3)
        assertThat(constraints.knownIds.toList()).doesNotContain(sharedR.id.bc_smartspace_view)
    }

    @Test
    fun removeViews_takesAwayOnlyTheSectionsViews() {
        val section = section(builtInPlugin())
        section.addViews(root)

        section.removeViews(root)

        assertThat(root.childCount).isEqualTo(1)
        assertThat(root.getChildAt(0)).isSameInstanceAs(smartspaceLine)
    }

    private fun section(plugin: BcSmartspaceDataPlugin?) =
        TallyLockSection(
            context,
            mock<TallyLockInk>(),
            mock<KeyguardClockViewModel> {
                on { currentClock } doReturn currentClock
                on { isLargeClockVisible } doReturn largeClockVisible
            },
            mock(),
            mock(),
            mock(),
            mock<LockscreenSmartspaceController> { on { isEnabled } doReturn (plugin != null) },
            Optional.ofNullable(plugin),
            mock(),
        )

    private fun builtInPlugin() = LockscreenSmartspaceGeneralPlugin(LayoutInflater.from(context))

    private fun clock(id: String): ClockController = mock {
        on { config } doReturn ClockConfig(id, id, id)
    }

    private fun constraints(section: TallyLockSection) =
        ConstraintSet().apply {
            clone(root)
            section.applyConstraints(this)
        }

    private inline fun <reified T : View> views(): List<T> =
        (0 until root.childCount).map { root.getChildAt(it) }.filterIsInstance<T>()

    private inline fun <reified T : View> view(): T = views<T>().single()
}
