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
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor
import com.android.systemui.keyguard.shared.model.KeyguardSection
import com.android.systemui.keyguard.ui.viewmodel.KeyguardClockViewModel
import com.android.systemui.plugins.keyguard.ui.clocks.ClockViewIds
import com.android.systemui.res.R
import com.android.systemui.shade.ShadeDisplayAware
import com.android.systemui.statusbar.lockscreen.LockscreenSmartspaceController
import com.android.systemui.statusbar.policy.ConfigurationController
import com.android.systemui.tally.TallyShell
import javax.inject.Inject
import kotlin.math.roundToInt
import kotlinx.coroutines.DisposableHandle

/**
 * The Tally lock screen's lamp strip, under the clock. The default blueprint uses this section in
 * place of KeyguardSliceViewSection while Tally is on: the Tally clock shows the date, and the
 * strip shows the next alarm and Do Not Disturb, which the slice showed. Like the slice section, it
 * defines smart_space_barrier_bottom, so the notifications start below the strip.
 *
 * With a smartspace plugin, which draws its own date and places the notifications itself, it adds
 * nothing, as the slice section does not either.
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
    private val smartspaceController: LockscreenSmartspaceController,
) : KeyguardSection() {
    private var stripView: TallyLampStripView? = null
    private var disposableHandle: DisposableHandle? = null

    override fun addViews(constraintLayout: ConstraintLayout) {
        if (TallyShell.isUnexpectedlyInLegacyMode() || smartspaceController.isEnabled) return
        val view = TallyLampStripView(context).apply { id = STRIP_ID }
        stripView = view
        constraintLayout.addView(view)
    }

    override fun bindData(constraintLayout: ConstraintLayout) {
        val view = stripView ?: return
        disposableHandle?.dispose()
        disposableHandle =
            TallyLampStripViewBinder.bind(
                view,
                viewModel,
                keyguardInteractor,
                configurationController,
                appContext,
            )
    }

    override fun applyConstraints(constraintSet: ConstraintSet) {
        if (stripView == null) return
        // Under whichever clock face shows; both faces of the Tally clock sit in the same place.
        val clockId =
            if (keyguardClockViewModel.isLargeClockVisible.value) {
                ClockViewIds.LOCKSCREEN_CLOCK_VIEW_LARGE
            } else {
                ClockViewIds.LOCKSCREEN_CLOCK_VIEW_SMALL
            }
        val side = px(SIDE_MARGIN_DP)
        constraintSet.apply {
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
            connect(STRIP_ID, ConstraintSet.TOP, clockId, ConstraintSet.BOTTOM, px(CLOCK_GAP_DP))
            createBarrier(R.id.smart_space_barrier_bottom, Barrier.BOTTOM, 0, STRIP_ID)
        }
    }

    override fun removeViews(constraintLayout: ConstraintLayout) {
        disposableHandle?.dispose()
        disposableHandle = null
        stripView?.let { constraintLayout.removeView(it) }
        stripView = null
    }

    private fun px(dp: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, context.resources.displayMetrics)
            .roundToInt()

    private companion object {
        val STRIP_ID = View.generateViewId()

        /** The prototype's lock layout: 24 dp from the sides, 36 dp under the clock's box. */
        const val SIDE_MARGIN_DP = 24f
        const val CLOCK_GAP_DP = 36f
    }
}
