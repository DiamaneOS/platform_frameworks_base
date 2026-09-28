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

package com.android.systemui.tally.privacy

import android.animation.TimeInterpolator
import android.content.res.Resources
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt
import org.diamaneos.tally.R as TallyR

/**
 * A privacy indicator going out on the prototype's fill spring (`tally_spring_fill_*`), as the
 * prototype's lens ring fades: its opacity goes from 1 to 0 from rest, as Android's SpringForce
 * solves it, and comes to rest at the prototype's thresholds without passing 0. Only the
 * disappearance moves: an indicator appears at once.
 *
 * Give [durationMillis] and [interpolator] to a `ValueAnimator` or a `ViewPropertyAnimator`. They
 * follow the animator duration scale like any animator, so at 0 (Remove animations) the fade ends
 * at once. Nothing is allocated while it runs.
 */
class TallyFillSpringFade(stiffness: Float, dampingRatio: Float) {
    private val w = sqrt(stiffness.toDouble())
    private val z = dampingRatio.toDouble()
    private val critical = abs(z - 1.0) < CRITICAL_SLACK

    // Over-damped: the two decay rates and their weights. Under-damped: the damped frequency.
    private val root = if (z > 1.0) w * sqrt(z * z - 1.0) else 0.0
    private val gammaPlus = -z * w + root
    private val gammaMinus = -z * w - root
    private val weightPlus = if (z > 1.0) gammaMinus / (gammaMinus - gammaPlus) else 0.0
    private val weightMinus = 1.0 - weightPlus
    private val wd = if (z < 1.0) w * sqrt(1.0 - z * z) else 0.0
    private val sinCoefficient = if (z < 1.0) z * w / wd else 0.0

    /** How long the spring takes to come to rest, at an animator duration scale of 1. */
    val durationMillis: Long = restMillis()

    /** The part of the fade done at each fraction of [durationMillis]. */
    val interpolator = TimeInterpolator { fraction ->
        if (fraction >= 1f) {
            1f
        } else {
            val t = fraction * durationMillis / 1000.0
            (1.0 - position(t)).coerceIn(0.0, 1.0).toFloat()
        }
    }

    /** The spring's value [t] seconds after it left 1, at rest, for 0. */
    private fun position(t: Double): Double =
        when {
            critical -> (1.0 + w * t) * exp(-w * t)
            z > 1.0 -> weightMinus * exp(gammaMinus * t) + weightPlus * exp(gammaPlus * t)
            else -> exp(-z * w * t) * (cos(wd * t) + sinCoefficient * sin(wd * t))
        }

    /** The spring's velocity [t] seconds in, in values per second. */
    private fun velocity(t: Double): Double =
        when {
            critical -> -w * w * t * exp(-w * t)
            z > 1.0 ->
                weightMinus * gammaMinus * exp(gammaMinus * t) +
                    weightPlus * gammaPlus * exp(gammaPlus * t)
            else -> -(w * w / wd) * exp(-z * w * t) * sin(wd * t)
        }

    /** The first millisecond at which the spring rests or reaches 0, where an effect stops. */
    private fun restMillis(): Long {
        for (ms in 1..MAX_MILLIS) {
            val t = ms / 1000.0
            val x = position(t)
            if (x <= 0.0 || (abs(x) < REST_DELTA && abs(velocity(t)) < REST_VELOCITY)) return ms
        }
        return MAX_MILLIS
    }

    companion object {
        /** The prototype's rest thresholds for an effect's value (its motion values' defaults). */
        private const val REST_DELTA = 0.01
        private const val REST_VELOCITY = 0.5
        private const val CRITICAL_SLACK = 1e-4
        private const val MAX_MILLIS = 2000L

        /** The fade on the fill spring of the Tally tokens. */
        @JvmStatic
        fun from(resources: Resources): TallyFillSpringFade =
            TallyFillSpringFade(
                resources.getFloat(TallyR.dimen.tally_spring_fill_stiffness),
                resources.getFloat(TallyR.dimen.tally_spring_fill_damping_ratio),
            )
    }
}
