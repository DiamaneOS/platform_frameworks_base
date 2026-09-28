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

import kotlin.math.ceil

/**
 * Where a lamp of one size draws its parts, in pixels from its centre, as the prototype's
 * `T.lampGeo`: the ring inside the lamp's size, the live lamp's disc a little inside the on disc,
 * and the ring of light clear of it, reaching just past the lamp's size.
 */
internal class TallyLampGeometry {
    /** The lamp's diameter. */
    var size = 0f
        private set

    /** The ring's width (off, requested, failed and unavailable). */
    var ringWidth = 0f
        private set

    /** The radius of the ring's centre line. */
    var ringRadius = 0f
        private set

    /** The live lamp's disc radius. */
    var liveDiscRadius = 0f
        private set

    /** The width of the live lamp's ring of light. */
    var haloWidth = 0f
        private set

    /** The radius of the centre line of the live lamp's ring of light. */
    var haloRadius = 0f
        private set

    /** The width of the lit disc's edge. */
    var litEdgeWidth = 0f
        private set

    /** The on disc's radius. */
    val discRadius: Float
        get() = size / 2f

    /** The fill a live lamp settles at: its disc radius as a fraction of the on disc's. */
    val liveFill: Float
        get() = if (size > 0f) liveDiscRadius / discRadius else 0f

    /** How far the ring of light reaches past the lamp's size on each side. */
    val reach: Float
        get() = haloRadius + haloWidth / 2f - discRadius

    /** [reach] rounded up to whole pixels, so that a box around the lamp keeps it on the grid. */
    val reachPx: Int
        get() = ceil(reach - ROUNDING_SLACK).toInt()

    fun set(spec: TallyLampSpec, sizePx: Float) {
        size = sizePx
        // Half a pixel of slack: a 12 dp lamp rounded down to whole pixels is still not small.
        val small = sizePx < spec.smallBelow - 0.5f
        ringWidth = if (small) spec.ringWidthSmall else spec.ringWidth
        haloWidth = if (small) spec.haloWidthSmall else spec.haloWidth
        val gap = if (small) spec.haloGapSmall else spec.haloGap
        val inset = if (small) spec.discInsetSmall else spec.discInset
        ringRadius = sizePx / 2f - ringWidth / 2f
        liveDiscRadius = sizePx / 2f - inset
        haloRadius = liveDiscRadius + gap + haloWidth / 2f
        litEdgeWidth = spec.litEdgeWidth
    }

    private companion object {
        const val ROUNDING_SLACK = 0.01f
    }
}
