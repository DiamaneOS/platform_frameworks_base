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

package com.android.systemui.tally.lamp

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A lamp's motion, as the prototype's `T.Lamp`, shared by the drawable and the Compose lamp.
 * - The disc's fill (its radius as a fraction of the on disc's) and the live lamp's ring of light
 *   move on the fill spring. A lamp that lights ignites: its fill starts from the ignite fraction,
 *   not from nothing. A change caught mid-flight goes on from where the lamp is, at its speed.
 *   Effects never overshoot, so the target stops a spring that would pass it.
 * - The ring changes form and colour at once; the painter hides it while the lamp is lit.
 * - The requested dashes turn once per turn time for as many whole turns as fit before the lamp is
 *   still (three), then rest where they started.
 * - A lamp that appears with [setState]'s `instantAppear` (sensor lamps, the privacy rule) is lit
 *   at once; only its disappearance moves.
 *
 * Times are uptime milliseconds, the frame clock of `AnimationUtils.currentAnimationTimeMillis`.
 * The springs follow the animator duration scale; at 0 (animations off) every change is at once and
 * the dashes do not turn. The turn keeps real time, as it counts towards "Still trying…". Nothing
 * is allocated after construction.
 */
internal class TallyLampMotion {
    /** The state shown, null before the first. */
    var state: TallyLampState? = null
        private set

    /** The disc's radius as a fraction of the on disc's; 0 draws no disc. */
    val fill: Float
        get() = fillSpring.value

    /** The live lamp's ring of light, from 0 (none) to 1. */
    val halo: Float
        get() = haloSpring.value

    /** The requested dashes' turn, in degrees clockwise. */
    var rotation = 0f
        private set

    /** Whether the disc or the ring of light is moving. */
    val springsMoving: Boolean
        get() = fillSpring.moving || haloSpring.moving

    /** When the current (or last) request started. */
    var requestedStartMillis = 0L
        private set

    /** When the current request is still: its words say "Still trying…" from then (5 s in). */
    val requestedStillAtMillis: Long
        get() = requestedStartMillis + stillMillis

    private val fillSpring = Spring()
    private val haloSpring = Spring()
    private var liveFill = 1f

    private var igniteFraction = 0f
    private var stiffness = 1f
    private var dampingRatio = 1f
    private var turnMillis = 1L
    private var turningMillis = 0L
    private var stillMillis = 0L
    private var turnFrameMillis = 0L

    fun setSpec(spec: TallyLampSpec) {
        igniteFraction = spec.igniteFraction
        stiffness = spec.fillStiffness
        dampingRatio = spec.fillDampingRatio
        turnMillis = spec.requestedTurnMillis
        turningMillis = spec.requestedTurningMillis
        stillMillis = spec.requestedStillMillis
        turnFrameMillis = spec.turnFrameMillis
    }

    /** Sets the fill a live lamp settles at (it depends on the lamp's size) and jumps to it. */
    fun setLiveFill(value: Float) {
        liveFill = value
        state?.let {
            fillSpring.snap(fillTarget(it))
            haloSpring.snap(haloTarget(it))
        }
    }

    /**
     * Shows [newState] from [nowMillis]. It moves there when [animate] (the lamp is on screen) and
     * the duration scale is not 0, and jumps otherwise: the first state, a lamp not yet drawn, and
     * a lit state when [instantAppear]. [requestedSinceMillis] is when the request started (uptime
     * milliseconds), or [TallyLampState.SINCE_FIRST_SHOWN].
     */
    fun setState(
        newState: TallyLampState,
        nowMillis: Long,
        durationScale: Float,
        animate: Boolean,
        instantAppear: Boolean,
        requestedSinceMillis: Long,
    ) {
        advance(nowMillis, durationScale)
        val previous = state
        if (newState == TallyLampState.REQUESTED) {
            requestedStartMillis =
                when {
                    requestedSinceMillis != TallyLampState.SINCE_FIRST_SHOWN ->
                        minOf(requestedSinceMillis, nowMillis)
                    previous == TallyLampState.REQUESTED -> requestedStartMillis
                    else -> nowMillis
                }
        }
        if (newState != previous) {
            state = newState
            val fillTarget = fillTarget(newState)
            val haloTarget = haloTarget(newState)
            if (
                previous == null ||
                    !animate ||
                    durationScale <= 0f ||
                    (instantAppear && newState.isLit)
            ) {
                fillSpring.snap(fillTarget)
                haloSpring.snap(haloTarget)
            } else {
                // A lamp that lights ignites from the ring; one caught while going out goes on
                // from where it is.
                if (fillTarget > 0f && fillSpring.value < IGNITE_BELOW) {
                    fillSpring.snap(igniteFraction)
                }
                fillSpring.animateTo(fillTarget, nowMillis)
                haloSpring.animateTo(haloTarget, nowMillis)
            }
        }
        rotation = rotationAt(nowMillis, durationScale)
    }

    /** Moves everything to [nowMillis]. */
    fun advance(nowMillis: Long, durationScale: Float) {
        if (durationScale <= 0f) {
            jumpToTargets()
        } else {
            fillSpring.step(nowMillis, stiffness, dampingRatio, durationScale)
            haloSpring.step(nowMillis, stiffness, dampingRatio, durationScale)
        }
        rotation = rotationAt(nowMillis, durationScale)
    }

    /** Ends the springs where they are going. The dashes keep turning. */
    fun jumpToTargets() {
        fillSpring.snap(fillSpring.target)
        haloSpring.snap(haloSpring.target)
    }

    /** Whether the requested dashes turn at [nowMillis]. */
    fun isTurning(nowMillis: Long, durationScale: Float): Boolean {
        if (state != TallyLampState.REQUESTED || durationScale <= 0f) return false
        val elapsed = nowMillis - requestedStartMillis
        return elapsed >= 0L && elapsed < turningMillis
    }

    /** Whether the lamp needs another frame after one drawn at [nowMillis]. */
    fun needsFrame(nowMillis: Long, durationScale: Float): Boolean =
        springsMoving || isTurning(nowMillis, durationScale)

    /**
     * Whether a frame at [nowMillis] should be drawn, the last one having been drawn at
     * [lastDrawMillis]: every frame while a spring moves, at most the lamp frame rate while the
     * dashes only turn.
     */
    fun shouldRedraw(nowMillis: Long, lastDrawMillis: Long): Boolean =
        springsMoving || nowMillis - lastDrawMillis >= turnFrameMillis

    private fun rotationAt(nowMillis: Long, durationScale: Float): Float {
        if (!isTurning(nowMillis, durationScale)) return 0f
        return (nowMillis - requestedStartMillis) % turnMillis * 360f / turnMillis
    }

    private fun fillTarget(state: TallyLampState): Float =
        when (state) {
            TallyLampState.ON -> 1f
            TallyLampState.LIVE -> liveFill
            else -> 0f
        }

    private fun haloTarget(state: TallyLampState): Float =
        if (state == TallyLampState.LIVE) 1f else 0f

    /**
     * A spring on one value (Android's SpringForce, solved exactly for each frame), which rests as
     * the prototype's lamp values do and never passes its target.
     */
    private class Spring {
        var value = 0f
            private set

        var target = 0f
            private set

        var moving = false
            private set

        /** Units per second. */
        private var velocity = 0f
        private var lastMillis = 0L

        fun snap(to: Float) {
            value = to
            target = to
            velocity = 0f
            moving = false
        }

        fun animateTo(to: Float, nowMillis: Long) {
            target = to
            lastMillis = nowMillis
            moving = value != to || velocity != 0f
        }

        fun step(nowMillis: Long, stiffness: Float, dampingRatio: Float, durationScale: Float) {
            if (!moving || nowMillis <= lastMillis) return
            val t = (nowMillis - lastMillis) / (1000.0 * durationScale)
            lastMillis = nowMillis
            val x0 = (value - target).toDouble()
            val v0 = velocity.toDouble()
            val w = sqrt(stiffness.toDouble())
            val z = dampingRatio.toDouble()
            var x: Double
            var v: Double
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
            if (x0 * x < 0.0) {
                // Past the target: an effect stops there.
                x = 0.0
                v = 0.0
            }
            if (abs(x) < REST_DELTA && abs(v) < REST_VELOCITY) {
                snap(target)
            } else {
                value = (target + x).toFloat()
                velocity = v.toFloat()
            }
        }
    }

    private companion object {
        /** A lamp below this fill lights by igniting (the prototype's `T.Lamp.set`). */
        const val IGNITE_BELOW = 0.05f
        /** The prototype's rest thresholds for a lamp's fill and ring of light. */
        const val REST_DELTA = 0.002
        const val REST_VELOCITY = 0.02
        const val CRITICAL_SLACK = 1e-4
    }
}
