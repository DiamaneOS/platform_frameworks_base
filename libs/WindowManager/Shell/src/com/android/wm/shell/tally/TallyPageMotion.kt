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
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * DiamaneOS Tally's page motion, as the Tally prototype moves its pages (design repository,
 * `design/prototypes/tally/apps.js`: `T.pushPage`, `T.popPage`, `paintPage`, `wm.backMove` and
 * `wm.backEnd`). Pure maths in pixels and in seconds at an animation scale of 1, shared by the page
 * transitions and predictive Back, and checked on the JVM against the prototype's springs.
 * - Opening a page: it enters from [OPEN_OFFSET_DP] to the side (in the reading direction) on the
 *   slab spring with a tap impulse and fades in on the fill spring.
 * - Closing a page: it scales to [CLOSE_SCALE] about its centre on slab with a tap impulse and
 *   fades out on fill.
 * - The page under either stays where it is. A page's corners follow its scale, from 0 at full size
 *   to [RADIUS_DP] at [CLOSE_SCALE], in the page's own unscaled units (the prototype rounds the
 *   page before its transform scales it).
 * - Back: the page scales 1 → [CLOSE_SCALE] as the gesture's progress k goes 0 → 1 (the prototype's
 *   progress is the finger's travel over [BACK_DISTANCE_DP]), moves [BACK_SHIFT_DP]·k the way the
 *   finger travels and follows the finger's vertical travel by [BACK_FOLLOW], at most
 *   [BACK_FOLLOW_MAX_DP], times k. Commit closes the page from where it is (the shift and the
 *   follow stay); cancel takes k back to 0 on slab.
 *
 * @param slabStiffness the slab spring, for full-screen surfaces (`tally_spring_slab_stiffness`)
 * @param fillStiffness the fill spring, for effects (`tally_spring_fill_stiffness`)
 * @param tapImpulse the tap impulse (`tally_motion_tap_impulse`)
 * @param density pixels per dp
 */
class TallyPageMotion(
    slabStiffness: Float,
    fillStiffness: Float,
    private val tapImpulse: Float,
    val density: Float,
) {
    val slab = TallySpring(slabStiffness)
    val fill = TallySpring(fillStiffness)

    private val openOffsetPx = OPEN_OFFSET_DP * density
    private val radiusPx = RADIUS_DP * density
    private val backDistancePx = BACK_DISTANCE_DP * density
    private val shiftPx = BACK_SHIFT_DP * density
    private val followMaxPx = BACK_FOLLOW_MAX_DP * density
    private val settlePx = SETTLE_DP * density
    private val openVelocity = slab.impulse(openOffsetPx, 0f, tapImpulse)

    /** How long an opening page moves, in milliseconds at an animation scale of 1. */
    val openMillis: Long =
        max(
            slab.settleMillis(openOffsetPx, 0f, openVelocity, settlePx),
            fill.settleMillis(0f, 1f, 0f, SETTLE_ALPHA),
        )

    /** How long a closing page takes to fade out (it is gone then), in milliseconds. */
    val closeMillis: Long = fill.settleMillis(1f, 0f, 0f, SETTLE_ALPHA)

    /** An opening page's offset towards the end of the reading direction, [t] seconds in. */
    fun openOffset(t: Float): Float = slab.value(openOffsetPx, 0f, openVelocity, t)

    /** An opening page's opacity, [t] seconds in. */
    fun openAlpha(t: Float): Float = fill.value(0f, 1f, 0f, t)

    /**
     * A closing page's scale [t] seconds after it left the scale [from]: 1 when a tap closes it,
     * the gesture's scale when Back commits. The slab spring starts with a tap impulse, as the
     * prototype's `T.popPage` does in both cases.
     */
    fun closeScale(from: Float, t: Float): Float =
        slab.value(from, CLOSE_SCALE, slab.impulse(from, CLOSE_SCALE, tapImpulse), t)

    /** A closing page's opacity, [t] seconds in. */
    fun closeAlpha(t: Float): Float = fill.value(1f, 0f, 0f, t)

    /** A page's corner radius at [scale], in pixels of the page's own unscaled units. */
    fun cornerRadius(scale: Float): Float =
        radiusPx * ((1f - scale) / (1f - CLOSE_SCALE)).coerceIn(0f, 1f)

    /**
     * The Back gesture's progress k, 0 to 1, for the platform's [progress]. An edge swipe's
     * platform progress is the finger's travel over [distancePx] (Android's BackTouchTracker: the
     * display's width on a phone), and k is the same travel over [BACK_DISTANCE_DP], as the
     * prototype's. The progress of a Back button (no edge) is taken as it is.
     */
    fun backProgress(progress: Float, fromEdge: Boolean, distancePx: Float): Float =
        if (fromEdge) (progress * distancePx / backDistancePx).coerceIn(0f, 1f)
        else progress.coerceIn(0f, 1f)

    /** The page's scale at progress [k]. */
    fun backScale(k: Float): Float = 1f - (1f - CLOSE_SCALE) * k

    /**
     * The page's sideways shift at progress [k], in pixels. [direction] is +1 for a swipe from the
     * left edge (the finger travels right), −1 for one from the right edge and 0 for a button.
     */
    fun backShift(k: Float, direction: Int): Float = direction * shiftPx * k

    /** The page's vertical shift at progress [k], with the finger [dy] pixels below its start. */
    fun backFollow(k: Float, dy: Float): Float =
        (dy * BACK_FOLLOW).coerceIn(-followMaxPx, followMaxPx) * k

    /** The progress [t] seconds into a cancel that started at progress [from]. */
    fun cancelProgress(from: Float, t: Float): Float = slab.value(from, 0f, 0f, t)

    /**
     * How long a cancel from progress [from] takes to settle, in milliseconds, for a [width] ×
     * [height] page with the given [direction] and finger travel [dy] (see [backShift] and
     * [backFollow]): until no point of the page is more than [SETTLE_DP] from where it rests.
     */
    fun cancelMillis(from: Float, width: Float, height: Float, direction: Int, dy: Float): Long {
        val travel =
            (1f - CLOSE_SCALE) * hypot(width, height) / 2f +
                abs(direction) * shiftPx +
                min(abs(dy * BACK_FOLLOW), followMaxPx)
        return slab.settleMillis(from, 0f, 0f, settlePx / max(travel, settlePx))
    }

    companion object {
        /** Where an opening page starts, towards the end of the reading direction. */
        const val OPEN_OFFSET_DP = 16f
        /** The scale a closing page goes to, and the scale of a page at full Back progress. */
        const val CLOSE_SCALE = 0.9f
        /** A page's corner radius at [CLOSE_SCALE]. */
        const val RADIUS_DP = 20f
        /** The finger's travel that makes a full Back progress. */
        const val BACK_DISTANCE_DP = 160f
        /** How far the page moves with the finger at full Back progress. */
        const val BACK_SHIFT_DP = 8f
        /** How much of the finger's vertical travel the page follows... */
        const val BACK_FOLLOW = 0.25f
        /** ...and at most how far. */
        const val BACK_FOLLOW_MAX_DP = 40f
        /** A move has settled this close to its end: a third of a pixel at 480 dpi. */
        const val SETTLE_DP = 0.1f
        /** A fade has settled within one 8-bit step of its end. */
        const val SETTLE_ALPHA = 1f / 255f
    }
}
