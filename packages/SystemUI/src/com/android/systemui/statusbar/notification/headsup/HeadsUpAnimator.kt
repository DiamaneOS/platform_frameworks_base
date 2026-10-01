/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.systemui.statusbar.notification.headsup

import android.content.Context
import com.android.internal.policy.SystemBarUtils
import com.android.systemui.res.R
import com.android.systemui.statusbar.notification.stack.AnimationProperties
import com.android.systemui.statusbar.ui.SystemBarUtilsProxy
import com.android.systemui.tally.TallyShell
import de.diamaneos.tally.R as TallyR

/**
 * A class shared between [StackScrollAlgorithm] and [StackStateAnimator] to ensure all heads up
 * animations use the same animation values.
 *
 * @param systemBarUtilsProxy optional utility class to provide the status bar height. Typically
 *   null in production code and non-null in tests.
 */
class HeadsUpAnimator(context: Context, private val systemBarUtilsProxy: SystemBarUtilsProxy?) {
    var headsUpAppearHeightBottom: Int = 0
    var stackTopMargin: Int = 0

    private var headsUpAppearStartAboveScreen = context.fetchHeadsUpAppearStartAboveScreen()
    private var statusBarHeight = fetchStatusBarHeight(context)

    // Tally: how a heads-up slides (isTallySlide), read with the other resources.
    private var tallySlideMargin = context.fetchTallySlideMargin()
    private var tallySpringStiffness = context.fetchFloat(TallyR.dimen.tally_spring_stone_stiffness)
    private var tallySpringDampingRatio =
        context.fetchFloat(TallyR.dimen.tally_spring_stone_damping_ratio)
    private var tallyImpulse = context.fetchFloat(TallyR.dimen.tally_motion_tap_impulse)

    /**
     * Returns the Y translation for a heads-up notification animation.
     *
     * For an appear animation, the returned Y translation should be the starting value of the
     * animation. For a disappear animation, the returned Y translation should be the ending value
     * of the animation.
     */
    fun getHeadsUpYTranslation(isHeadsUpFromBottom: Boolean, hasStatusBarChip: Boolean): Int {
        if (isHeadsUpFromBottom) {
            // start from or end at the bottom of the screen
            return headsUpAppearHeightBottom + headsUpAppearStartAboveScreen
        }

        if (hasStatusBarChip) {
            // If this notification is also represented by a chip in the status bar, we don't want
            // any HUN transitions to obscure that chip.
            return statusBarHeight - stackTopMargin
        }

        // start from or end at the top of the screen
        return -stackTopMargin - headsUpAppearStartAboveScreen
    }

    /**
     * Tally: whether a heads-up appears and goes away as Tally's does, sliding in whole from above
     * the screen and back out above it on the stone spring, neither revealed nor faded: at the top
     * of the screen, when its notification has no status bar chip. One that comes from the bottom,
     * or whose chip it must not cover, moves as stock's.
     */
    fun isTallySlide(isHeadsUpFromBottom: Boolean, hasStatusBarChip: Boolean): Boolean =
        TallyShell.isEnabled && !isHeadsUpFromBottom && !hasStatusBarChip

    /**
     * Tally: the Y translation a sliding heads-up [height] tall starts from or ends at, with the
     * whole card and its shadow above the top of the screen.
     */
    fun getTallySlideYTranslation(height: Int): Int = -stackTopMargin - height - tallySlideMargin

    /**
     * Tally: moves the views of the animation [properties] describes on the stone spring, each
     * starting with a tap's push unless it is already moving, as the prototype's heads-up does.
     */
    fun applyTallySpring(properties: AnimationProperties) {
        properties.setCustomSpring(tallySpringStiffness, tallySpringDampingRatio, tallyImpulse)
    }

    /** Should be invoked when resource values may have changed. */
    fun updateResources(context: Context) {
        headsUpAppearStartAboveScreen = context.fetchHeadsUpAppearStartAboveScreen()
        statusBarHeight = fetchStatusBarHeight(context)
        tallySlideMargin = context.fetchTallySlideMargin()
        tallySpringStiffness = context.fetchFloat(TallyR.dimen.tally_spring_stone_stiffness)
        tallySpringDampingRatio = context.fetchFloat(TallyR.dimen.tally_spring_stone_damping_ratio)
        tallyImpulse = context.fetchFloat(TallyR.dimen.tally_motion_tap_impulse)
    }

    private fun Context.fetchHeadsUpAppearStartAboveScreen(): Int {
        return this.resources.getDimensionPixelSize(R.dimen.heads_up_appear_y_above_screen)
    }

    // Tally: the gap between the status bar and a heads-up, here between the screen's top and a
    // heads-up about to slide in, which keeps its shadow out of view too.
    private fun Context.fetchTallySlideMargin(): Int =
        resources.getDimensionPixelSize(R.dimen.heads_up_status_bar_padding)

    private fun Context.fetchFloat(id: Int): Float = resources.getFloat(id)

    private fun fetchStatusBarHeight(context: Context): Int {
        return systemBarUtilsProxy?.getStatusBarHeight()
            ?: SystemBarUtils.getStatusBarHeight(context)
    }
}
