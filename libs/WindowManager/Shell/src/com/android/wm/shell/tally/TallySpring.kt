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

package com.android.wm.shell.tally

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * A Tally spring: Android's SpringForce with a damping ratio of 1, so it never overshoots, solved
 * in closed form. A value leaves `from` at `velocity` towards `to`; with ω = √stiffness, the
 * distance d0 = from − to and b = velocity + ω·d0:
 * ```
 * x(t) = to + (d0 + b·t)·e^(−ω·t)        v(t) = (velocity − ω·b·t)·e^(−ω·t)
 * ```
 *
 * This is SpringForce's own critically damped solution. Times are seconds at an animation scale of
 * 1: the callers run it under a ValueAnimator or an Animation, whose durations the platform scales,
 * so 0.5x and Remove animations behave as they do for stock's animations.
 */
class TallySpring(stiffness: Float) {
    /** The natural frequency, √stiffness, in radians per second. */
    val omega: Double = sqrt(stiffness.toDouble())

    /** Where the value is [t] seconds after it left [from] at [velocity] towards [to]. */
    fun value(from: Float, to: Float, velocity: Float, t: Float): Float {
        val d0 = (from - to).toDouble()
        val b = velocity + omega * d0
        return (to + (d0 + b * t) * exp(-omega * t)).toFloat()
    }

    /** The value's velocity [t] seconds after it left [from] at [velocity] towards [to]. */
    fun velocity(from: Float, to: Float, velocity: Float, t: Float): Float {
        val d0 = (from - to).toDouble()
        val b = velocity + omega * d0
        return ((velocity - omega * b * t) * exp(-omega * t)).toFloat()
    }

    /**
     * The start velocity of a move a tap starts (the prototype's impulse): [impulse] · √k · the
     * distance, so the first 120 Hz frame visibly moves; with a damping ratio of 1 it cannot
     * overshoot.
     */
    fun impulse(from: Float, to: Float, impulse: Float): Float =
        (impulse * omega * (to - from)).toFloat()

    /**
     * How long, in whole milliseconds, the value takes to come within [tolerance] of [to] for good.
     * It scans in steps of a millisecond for up to [SETTLE_MAX_MS]; it runs once when an animation
     * starts, never per frame.
     */
    fun settleMillis(from: Float, to: Float, velocity: Float, tolerance: Float): Long {
        var last = 0L
        for (ms in 0..SETTLE_MAX_MS) {
            if (abs(value(from, to, velocity, ms / 1000f) - to) > tolerance) last = ms + 1L
        }
        return last
    }

    private companion object {
        const val SETTLE_MAX_MS = 2000
    }
}
