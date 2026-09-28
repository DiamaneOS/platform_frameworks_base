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

package com.android.systemui.tally.volume

import android.content.res.Resources
import android.view.View
import androidx.dynamicanimation.animation.SpringAnimation
import com.android.systemui.res.R
import de.diamaneos.tally.R as TallyR
import kotlin.math.sqrt

/**
 * How the Tally volume panel comes and goes (the prototype's `vol.x`): it slides in from its edge
 * over a short way on the stone spring, with a tap's impulse, and fades as it moves, rather than
 * crossing the whole window at full opacity. The panel's timeout and what shows or hides it stay
 * stock.
 */
class TallyVolumePanelMotion(resources: Resources) {
    private val travel = resources.getDimension(R.dimen.tally_volume_panel_travel)
    val stiffness = resources.getFloat(TallyR.dimen.tally_spring_stone_stiffness)
    val dampingRatio = resources.getFloat(TallyR.dimen.tally_spring_stone_damping_ratio)

    /** A move from rest starts at the tap impulse (0.6 · √k · distance; the way is 0 to 1). */
    private val impulse =
        resources.getFloat(TallyR.dimen.tally_motion_tap_impulse) * sqrt(stiffness)

    /** Places the panel [view] at [fraction] of the way in: 0 hidden, 1 shown. */
    fun apply(view: View, fraction: Float, fromLeft: Boolean) {
        view.alpha = fraction.coerceIn(0f, 1f)
        view.translationX = (1f - fraction) * if (fromLeft) -travel else travel
    }

    /**
     * Gives [animation] the impulse towards shown (or hidden) before it starts, unless it is
     * already moving: a moving panel keeps its own speed.
     */
    fun kick(animation: SpringAnimation, towardsShown: Boolean) {
        if (!animation.isRunning) {
            animation.setStartVelocity(if (towardsShown) impulse else -impulse)
        }
    }
}
