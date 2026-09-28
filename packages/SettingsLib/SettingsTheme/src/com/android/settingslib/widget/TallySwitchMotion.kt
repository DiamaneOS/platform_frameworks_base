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

package com.android.settingslib.widget

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The Tally switch's motion, as the prototype's `T.tswitch`:
 * - The thumb moves on the pebble spring. A move from rest starts with the tap impulse (0.6 × √k ×
 *   distance), so the first frame already moves; a thumb caught in flight keeps its speed.
 * - The lamp wipes in from the switch's start on the fill spring, and out towards it, never past
 *   its ends.
 * - The first frame of a move advances 1/120 s, as the prototype's first frame does.
 * - The springs follow the animator duration scale. At 0 (Remove animations, the prototype's
 *   reduced motion) the thumb jumps and the lamp's wipe runs linearly over the reduced-motion fade
 *   (120 ms) in real time. A spring still moving when animations go off ends at once.
 *
 * Times are nanoseconds of one clock (the frame clock); the thumb is in dp from its start and the
 * wipe from 0 (dark) to 1 (lit). Nothing is allocated after construction.
 */
internal class TallySwitchMotion {
    /** The thumb's distance from its start, in dp. */
    val thumb: Float
        get() = thumbSpring.value

    /** How far the lamp has wiped in, from 0 to 1. */
    val wipe: Float
        get() = if (fading) fadeValue else wipeSpring.value

    /** Whether the thumb or the lamp is moving. */
    val moving: Boolean
        get() = thumbSpring.moving || wipeSpring.moving || fading

    /** Where the thumb is going: on or off. */
    var checked = false
        private set

    /** Where the lamp is going: lit or dark. */
    var lit = false
        private set

    private val thumbSpring = Spring(THUMB_REST_DELTA, THUMB_REST_VELOCITY)
    private val wipeSpring = Spring(WIPE_REST_DELTA, WIPE_REST_VELOCITY)
    private var travel = 0f
    private var thumbStiffness = 1f
    private var thumbDampingRatio = 1f
    private var wipeStiffness = 1f
    private var wipeDampingRatio = 1f
    private var tapImpulse = 0f
    private var fadeNanos = 1L

    private var fading = false
    private var fadeFrom = 0f
    private var fadeValue = 0f
    private var fadeStartNanos = UNSET

    fun setSpec(spec: TallySwitchSpec) {
        travel = spec.travelDp
        thumbStiffness = spec.thumbStiffness
        thumbDampingRatio = spec.thumbDampingRatio
        wipeStiffness = spec.wipeStiffness
        wipeDampingRatio = spec.wipeDampingRatio
        tapImpulse = spec.tapImpulse
        fadeNanos = maxOf(1L, spec.reducedMotionFadeMillis * NANOS_PER_MILLI)
        // A thumb at rest stays at its end when the travel changes (another density).
        if (thumbSpring.moving) thumbSpring.retarget(thumbTarget())
        else thumbSpring.snap(thumbTarget())
    }

    /** Shows [checked] and [lit] at once, with nothing moving. */
    fun jumpTo(checked: Boolean, lit: Boolean) {
        this.checked = checked
        this.lit = lit
        fading = false
        thumbSpring.snap(thumbTarget())
        wipeSpring.snap(if (lit) 1f else 0f)
    }

    /** Ends every move where it is going. */
    fun jumpToTargets() = jumpTo(checked, lit)

    /**
     * Moves to [checked] and [lit]. With [durationScale] 0 the thumb jumps and the lamp fades over
     * the reduced-motion time.
     */
    fun animateTo(checked: Boolean, lit: Boolean, durationScale: Float) {
        if (checked != this.checked) {
            this.checked = checked
            if (durationScale <= 0f) {
                thumbSpring.snap(thumbTarget())
            } else {
                // The prototype's impulse: from rest the move starts at 0.6 × √k × distance; a
                // thumb already moving keeps its own velocity.
                val velocity =
                    if (thumbSpring.moving) thumbSpring.velocity
                    else tapImpulse * sqrt(thumbStiffness) * (thumbTarget() - thumbSpring.value)
                thumbSpring.animateTo(thumbTarget(), velocity)
            }
        }
        if (lit != this.lit) {
            this.lit = lit
            val target = if (lit) 1f else 0f
            if (durationScale <= 0f) {
                startFade(target)
            } else {
                if (fading) {
                    // Animations came back on during a fade: go on from where the fade is.
                    fading = false
                    wipeSpring.snap(fadeValue)
                }
                wipeSpring.animateTo(target, wipeSpring.velocity)
            }
        }
    }

    /** Holds the thumb at [dp] from its start, where a finger drags it. */
    fun holdThumb(dp: Float) {
        thumbSpring.snap(dp)
        thumbSpring.retarget(thumbTarget())
    }

    /** Lets a held thumb go: it moves from where it is to where it is going, as from a tap. */
    fun releaseThumb(durationScale: Float) {
        if (thumbSpring.moving || thumbSpring.value == thumbTarget()) return
        if (durationScale <= 0f) {
            thumbSpring.snap(thumbTarget())
        } else {
            val velocity = tapImpulse * sqrt(thumbStiffness) * (thumbTarget() - thumbSpring.value)
            thumbSpring.animateTo(thumbTarget(), velocity)
        }
    }

    /** Moves everything to [nowNanos]. */
    fun advance(nowNanos: Long, durationScale: Float) {
        if (durationScale <= 0f) {
            thumbSpring.snap(thumbTarget())
            if (wipeSpring.moving) startFade(wipeSpring.target, from = wipeSpring.value)
        } else {
            thumbSpring.step(nowNanos, thumbStiffness, thumbDampingRatio, durationScale)
            wipeSpring.step(nowNanos, wipeStiffness, wipeDampingRatio, durationScale)
        }
        if (fading) {
            if (fadeStartNanos == UNSET) fadeStartNanos = nowNanos
            val t = (nowNanos - fadeStartNanos).coerceAtLeast(0L).toFloat() / fadeNanos
            val target = if (lit) 1f else 0f
            if (t >= 1f) {
                fading = false
                wipeSpring.snap(target)
            } else {
                fadeValue = fadeFrom + (target - fadeFrom) * t
            }
        }
    }

    private fun startFade(target: Float, from: Float = wipe) {
        wipeSpring.snap(from)
        wipeSpring.retarget(target)
        if (abs(target - from) <= FADE_MIN) {
            fading = false
            wipeSpring.snap(target)
            return
        }
        fading = true
        fadeFrom = from
        fadeValue = from
        fadeStartNanos = UNSET
    }

    private fun thumbTarget() = if (checked) travel else 0f

    /**
     * A spring on one value, Android's SpringForce solved exactly for each frame, resting as the
     * prototype's values do: within the rest delta and slower than the rest velocity.
     */
    private class Spring(private val restDelta: Float, private val restVelocity: Float) {
        var value = 0f
            private set

        var target = 0f
            private set

        /** Units per second. */
        var velocity = 0f
            private set

        var moving = false
            private set

        private var lastNanos = UNSET

        fun snap(to: Float) {
            value = to
            target = to
            velocity = 0f
            moving = false
        }

        /** Changes the target without moving (the value stays where it is). */
        fun retarget(to: Float) {
            target = to
            moving = moving && value != to
        }

        fun animateTo(to: Float, startVelocity: Float) {
            target = to
            velocity = startVelocity
            if (!moving) lastNanos = UNSET
            moving = value != to || startVelocity != 0f
        }

        fun step(nowNanos: Long, stiffness: Float, dampingRatio: Float, durationScale: Float) {
            if (!moving) return
            val elapsed = if (lastNanos == UNSET) FIRST_FRAME_NANOS else nowNanos - lastNanos
            lastNanos = nowNanos
            if (elapsed <= 0L) return
            val t = elapsed / NANOS_PER_SECOND / durationScale
            val x0 = (value - target).toDouble()
            val v0 = velocity.toDouble()
            val w = sqrt(stiffness.toDouble())
            val z = dampingRatio.toDouble()
            val x: Double
            val v: Double
            if (abs(z - 1.0) < CRITICAL_SLACK) {
                val b = v0 + w * x0
                val e = exp(-w * t)
                x = (x0 + b * t) * e
                v = (v0 - w * b * t) * e
            } else if (z > 1.0) {
                val root = w * sqrt(z * z - 1.0)
                val gammaPlus = -z * w + root
                val gammaMinus = -z * w - root
                val b = (gammaMinus * x0 - v0) / (gammaMinus - gammaPlus)
                val a = x0 - b
                val ea = exp(gammaMinus * t)
                val eb = exp(gammaPlus * t)
                x = a * ea + b * eb
                v = a * gammaMinus * ea + b * gammaPlus * eb
            } else {
                val wd = w * sqrt(1.0 - z * z)
                val sinCoefficient = (v0 + z * w * x0) / wd
                val e = exp(-z * w * t)
                val c = cos(wd * t)
                val s = sin(wd * t)
                x = e * (x0 * c + sinCoefficient * s)
                v = -z * w * x + e * wd * (sinCoefficient * c - x0 * s)
            }
            if (abs(x) < restDelta && abs(v) < restVelocity) {
                snap(target)
            } else {
                value = (target + x).toFloat()
                velocity = v.toFloat()
            }
        }
    }

    private companion object {
        const val UNSET = Long.MIN_VALUE
        const val NANOS_PER_MILLI = 1_000_000L
        const val NANOS_PER_SECOND = 1e9
        /** The prototype's first frame after a change: one 120 Hz frame. */
        const val FIRST_FRAME_NANOS = 1_000_000_000L / 120
        /** The prototype's rest thresholds (`T.val`'s defaults): dp and dp/s for the thumb. */
        const val THUMB_REST_DELTA = 0.01f
        const val THUMB_REST_VELOCITY = 0.5f
        /** ... and fractions of the switch for the wipe. */
        const val WIPE_REST_DELTA = 0.01f
        const val WIPE_REST_VELOCITY = 0.5f
        /** The prototype's reduced motion jumps a change smaller than this instead of fading. */
        const val FADE_MIN = 1e-4f
        const val CRITICAL_SLACK = 1e-4
    }
}
