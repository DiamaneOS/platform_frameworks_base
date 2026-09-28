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

import android.content.res.Resources
import org.diamaneos.tally.R as TallyR

/**
 * The animated lamp's tokens (the prototype's `T.Lamp` and `T.lampGeo`), read once from the Tally
 * token library. Lengths are in pixels, angles in degrees, times in milliseconds.
 */
internal class TallyLampSpec(
    /** Lamps smaller than this (12 dp) take the small ring, halo, gap and inset. */
    val smallBelow: Float,
    val ringWidth: Float,
    val ringWidthSmall: Float,
    val haloWidth: Float,
    val haloWidthSmall: Float,
    val haloGap: Float,
    val haloGapSmall: Float,
    /** How much smaller the live disc is than the on disc, on each side. */
    val discInset: Float,
    val discInsetSmall: Float,
    /** The lit disc's edge (light theme). */
    val litEdgeWidth: Float,
    val requestedDashDegrees: Float,
    val requestedGapDegrees: Float,
    val failedGapDegrees: Float,
    val failedStartDegrees: Float,
    /** Where a lamp that lights starts its fill, as a fraction of its radius. */
    val igniteFraction: Float,
    /** The fill spring, which moves a lamp's disc and its ring of light. */
    val fillStiffness: Float,
    val fillDampingRatio: Float,
    val requestedTurnMillis: Long,
    /** When a requested lamp is still and its words say "Still trying…". */
    val requestedStillMillis: Long,
    /** Turning lamps redraw at most this often, even on a faster display. */
    val maxFramesPerSecond: Int,
) {
    /** The requested dashes' whole turns before they are still: three at 1.6 s within 5 s. */
    val requestedTurns: Int
        get() = (requestedStillMillis / requestedTurnMillis).toInt()

    /** The time the requested dashes turn. */
    val requestedTurningMillis: Long
        get() = requestedTurns * requestedTurnMillis

    /** The shortest time between two frames of a lamp that only turns. */
    val turnFrameMillis: Long
        get() = 1000L / maxFramesPerSecond

    companion object {
        fun from(resources: Resources): TallyLampSpec =
            TallyLampSpec(
                smallBelow = resources.getDimension(TallyR.dimen.tally_lamp_size),
                ringWidth = resources.getDimension(TallyR.dimen.tally_lamp_ring_width),
                ringWidthSmall = resources.getDimension(TallyR.dimen.tally_lamp_ring_width_small),
                haloWidth = resources.getDimension(TallyR.dimen.tally_lamp_halo_width),
                haloWidthSmall = resources.getDimension(TallyR.dimen.tally_lamp_halo_width_small),
                haloGap = resources.getDimension(TallyR.dimen.tally_lamp_halo_gap),
                haloGapSmall = resources.getDimension(TallyR.dimen.tally_lamp_halo_gap_small),
                discInset = resources.getDimension(TallyR.dimen.tally_lamp_disc_inset),
                discInsetSmall = resources.getDimension(TallyR.dimen.tally_lamp_disc_inset_small),
                litEdgeWidth = resources.getDimension(TallyR.dimen.tally_stroke_lit_edge),
                requestedDashDegrees =
                    resources
                        .getInteger(TallyR.integer.tally_lamp_requested_dash_degrees)
                        .toFloat(),
                requestedGapDegrees =
                    resources.getInteger(TallyR.integer.tally_lamp_requested_gap_degrees).toFloat(),
                failedGapDegrees =
                    resources.getInteger(TallyR.integer.tally_lamp_failed_gap_degrees).toFloat(),
                failedStartDegrees =
                    resources.getInteger(TallyR.integer.tally_lamp_failed_start_degrees).toFloat(),
                igniteFraction = resources.getFloat(TallyR.dimen.tally_lamp_ignite_fraction),
                fillStiffness = resources.getFloat(TallyR.dimen.tally_spring_fill_stiffness),
                fillDampingRatio = resources.getFloat(TallyR.dimen.tally_spring_fill_damping_ratio),
                requestedTurnMillis =
                    resources.getInteger(TallyR.integer.tally_lamp_requested_turn_ms).toLong(),
                requestedStillMillis =
                    resources.getInteger(TallyR.integer.tally_lamp_requested_still_ms).toLong(),
                maxFramesPerSecond = resources.getInteger(TallyR.integer.tally_lamp_max_fps),
            )
    }
}
