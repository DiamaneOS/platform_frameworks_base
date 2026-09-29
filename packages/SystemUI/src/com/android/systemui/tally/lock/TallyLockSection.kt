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

import android.content.Context
import android.util.TypedValue
import android.view.View
import androidx.constraintlayout.widget.Barrier
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor
import com.android.systemui.keyguard.shared.model.KeyguardSection
import com.android.systemui.keyguard.smartspace.LockscreenSmartspaceGeneralPlugin
import com.android.systemui.keyguard.ui.viewmodel.AodBurnInViewModel
import com.android.systemui.keyguard.ui.viewmodel.KeyguardClockViewModel
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.plugins.BcSmartspaceDataPlugin
import com.android.systemui.plugins.keyguard.ui.clocks.ClockViewIds
import com.android.systemui.res.R
import com.android.systemui.shade.ShadeDisplayAware
import com.android.systemui.shared.R as sharedR
import com.android.systemui.shared.clocks.tally.TallyClocks.TALLY_CLOCK_ID
import com.android.systemui.statusbar.lockscreen.LockscreenSmartspaceController
import com.android.systemui.statusbar.policy.ConfigurationController
import com.android.systemui.tally.TallyShell
import com.android.systemui.util.kotlin.DisposableHandles
import java.util.Optional
import javax.inject.Inject
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * The Tally lock screen's lamp strip, under the clock. The default blueprint uses this section in
 * place of KeyguardSliceViewSection while Tally is on: the Tally clock shows the date, and the
 * strip shows the next alarm and Do Not Disturb, which the slice showed. Under any other clock,
 * which has no date of its own, a date line takes the slice's place and the strip follows it. On
 * the always-on display the strip gives way to the always-on strip, which shows what the slice
 * showed there besides the date. Like the slice section, it defines smart_space_barrier_bottom, so
 * the notifications start below.
 *
 * Beside GrapheneOS's built-in smartspace (LockscreenSmartspaceGeneralPlugin), which takes the
 * slice's place in GrapheneOS and shows the slice's rows (the date, the next alarm within 12 hours,
 * Do Not Disturb and, while dozing, the playing media), it adds its views all the same and hides
 * that smartspace's line, whose every row it shows itself, so nothing shows twice. With a
 * smartspace plugin, which draws its own date and places the notifications itself, it adds nothing,
 * as the slice section does not either.
 */
@SysUISingleton
class TallyLockSection
@Inject
constructor(
    @ShadeDisplayAware private val context: Context,
    @Application private val appContext: Context,
    private val keyguardClockViewModel: KeyguardClockViewModel,
    private val viewModel: TallyLampStripViewModel,
    private val keyguardInteractor: KeyguardInteractor,
    @Main private val configurationController: ConfigurationController,
    smartspaceController: LockscreenSmartspaceController,
    smartspacePlugin: Optional<BcSmartspaceDataPlugin>,
    private val aodBurnInViewModel: AodBurnInViewModel,
) : KeyguardSection() {
    private val smartspace = Smartspace.of(smartspaceController.isEnabled, smartspacePlugin)
    private var stripView: TallyLampStripView? = null
    private var dateView: TallyLockDateView? = null
    private var aodStripView: TallyAodStripView? = null
    private val handles = DisposableHandles()

    override fun addViews(constraintLayout: ConstraintLayout) {
        if (TallyShell.isUnexpectedlyInLegacyMode() || smartspace == Smartspace.PLUGIN) return
        val date = TallyLockDateView(context).apply { id = DATE_ID }
        val strip = TallyLampStripView(context).apply { id = STRIP_ID }
        val aodStrip = TallyAodStripView(context).apply { id = AOD_STRIP_ID }
        dateView = date
        stripView = strip
        aodStripView = aodStrip
        constraintLayout.addView(date)
        constraintLayout.addView(strip)
        constraintLayout.addView(aodStrip)
    }

    override fun bindData(constraintLayout: ConstraintLayout) {
        val strip = stripView ?: return
        val date = dateView ?: return
        val aodStrip = aodStripView ?: return
        handles.dispose()
        handles +=
            TallyLampStripViewBinder.bind(
                strip,
                viewModel,
                keyguardInteractor,
                configurationController,
                appContext,
            )
        handles +=
            TallyLockDateViewBinder.bind(
                date,
                viewModel,
                keyguardInteractor,
                aodBurnInViewModel,
                configurationController,
                appContext,
            )
        handles +=
            TallyAodStripViewBinder.bind(
                aodStrip,
                viewModel,
                keyguardInteractor,
                aodBurnInViewModel,
                configurationController,
            )
        // The date line and the strip's place depend on the clock: SystemUI re-applies only the
        // clock's own constraints when the clock changes, so this section re-applies its own.
        handles +=
            date.repeatWhenAttached {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    keyguardClockViewModel.currentClock
                        .map { isTallyClock() }
                        .distinctUntilChanged()
                        .collect { reapplyConstraints(constraintLayout) }
                }
            }
    }

    override fun applyConstraints(constraintSet: ConstraintSet) {
        if (stripView == null) return
        val tallyClock = isTallyClock()
        val side = px(SIDE_MARGIN_DP)
        constraintSet.apply {
            // Where the stock date line was: under the small clock's place, which SystemUI keeps
            // while the large clock shows. Hidden under the Tally clock, which has its own date.
            constrainWidth(DATE_ID, ConstraintSet.MATCH_CONSTRAINT)
            constrainHeight(DATE_ID, ConstraintSet.WRAP_CONTENT)
            connect(
                DATE_ID,
                ConstraintSet.START,
                ConstraintSet.PARENT_ID,
                ConstraintSet.START,
                side,
            )
            connect(DATE_ID, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END, side)
            connect(
                DATE_ID,
                ConstraintSet.TOP,
                ClockViewIds.LOCKSCREEN_CLOCK_VIEW_SMALL,
                ConstraintSet.BOTTOM,
            )
            setVisibility(DATE_ID, if (tallyClock) View.GONE else View.VISIBLE)

            constrainWidth(STRIP_ID, ConstraintSet.MATCH_CONSTRAINT)
            constrainHeight(STRIP_ID, ConstraintSet.WRAP_CONTENT)
            connect(
                STRIP_ID,
                ConstraintSet.START,
                ConstraintSet.PARENT_ID,
                ConstraintSet.START,
                side,
            )
            connect(STRIP_ID, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END, side)
            if (tallyClock) {
                // Under whichever face shows; both faces of the Tally clock sit in one place.
                val clockId =
                    if (keyguardClockViewModel.isLargeClockVisible.value) {
                        ClockViewIds.LOCKSCREEN_CLOCK_VIEW_LARGE
                    } else {
                        ClockViewIds.LOCKSCREEN_CLOCK_VIEW_SMALL
                    }
                connect(
                    STRIP_ID,
                    ConstraintSet.TOP,
                    clockId,
                    ConstraintSet.BOTTOM,
                    px(CLOCK_GAP_DP),
                )
            } else {
                connect(STRIP_ID, ConstraintSet.TOP, DATE_ID, ConstraintSet.BOTTOM, px(DATE_GAP_DP))
            }
            // The always-on strip takes the lamp strip's place.
            constrainWidth(AOD_STRIP_ID, ConstraintSet.MATCH_CONSTRAINT)
            constrainHeight(AOD_STRIP_ID, ConstraintSet.WRAP_CONTENT)
            connect(AOD_STRIP_ID, ConstraintSet.START, STRIP_ID, ConstraintSet.START)
            connect(AOD_STRIP_ID, ConstraintSet.END, STRIP_ID, ConstraintSet.END)
            connect(AOD_STRIP_ID, ConstraintSet.TOP, STRIP_ID, ConstraintSet.TOP)
            createBarrier(
                R.id.smart_space_barrier_bottom,
                Barrier.BOTTOM,
                0,
                STRIP_ID,
                AOD_STRIP_ID,
                DATE_ID,
            )
            if (smartspace == Smartspace.BUILT_IN) {
                // GrapheneOS's built-in smartspace line shows only what these views show: its date
                // is the Tally clock's or the date line's, its alarm and Do Not Disturb are the
                // strip's, and its media while dozing is the always-on strip's. It goes under any
                // clock, so nothing shows twice; the barrier above, which replaces its section's,
                // leaves it out.
                setVisibility(sharedR.id.bc_smartspace_view, View.GONE)
            }
        }
    }

    override fun removeViews(constraintLayout: ConstraintLayout) {
        handles.dispose()
        stripView?.let { constraintLayout.removeView(it) }
        dateView?.let { constraintLayout.removeView(it) }
        aodStripView?.let { constraintLayout.removeView(it) }
        stripView = null
        dateView = null
        aodStripView = null
    }

    private fun isTallyClock(): Boolean =
        keyguardClockViewModel.currentClock.value?.config?.id == TALLY_CLOCK_ID

    private fun reapplyConstraints(root: ConstraintLayout) {
        val constraints = ConstraintSet().apply { clone(root) }
        applyConstraints(constraints)
        constraints.applyTo(root)
    }

    private fun px(dp: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, context.resources.displayMetrics)
            .roundToInt()

    /** The lock screen's smartspace, which decides what this section adds beside it. */
    internal enum class Smartspace {
        /** No smartspace: the section adds its views, in the slice's place. */
        NONE,

        /** GrapheneOS's built-in smartspace: the section adds its views and hides its line. */
        BUILT_IN,

        /** A smartspace plugin, which draws its own date: the section adds nothing. */
        PLUGIN;

        companion object {
            /** From LockscreenSmartspaceController.isEnabled and the smartspace data plugin. */
            fun of(enabled: Boolean, plugin: Optional<BcSmartspaceDataPlugin>): Smartspace =
                when {
                    !enabled -> NONE
                    plugin.orElse(null) is LockscreenSmartspaceGeneralPlugin -> BUILT_IN
                    else -> PLUGIN
                }
        }
    }

    private companion object {
        val STRIP_ID = View.generateViewId()

        val DATE_ID = View.generateViewId()

        val AOD_STRIP_ID = View.generateViewId()

        /** The prototype's lock layout: 24 dp from the sides, 36 dp under the clock's box. */
        const val SIDE_MARGIN_DP = 24f
        const val CLOCK_GAP_DP = 36f

        /** Between the date line and the strip, whose 40 dp row centres its items. */
        const val DATE_GAP_DP = 4f
    }
}
