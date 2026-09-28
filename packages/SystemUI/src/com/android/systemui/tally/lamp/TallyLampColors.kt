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

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import androidx.annotation.ColorInt
import de.diamaneos.tally.R as TallyR

/**
 * The colours of a Tally lamp, one for each part it draws.
 *
 * [theme] is the lamp on the shell's own surfaces: the off ring in the outline colour, the
 * requested dashes in the accent, a lit lamp in the lamp colour in both themes (in light theme
 * edged with the lamp outline, which also draws the live lamp's ring of light), the failed ring in
 * the error colour and the unavailable ring in the muted colour. [plain] draws every part in one
 * colour and without the edge, for a lamp on a coloured field: [onLitField], [onInverse] (toasts)
 * or a sensor chip. [sensor] is a sensor lamp in the sensor colour.
 */
data class TallyLampColors(
    /** The off ring. */
    @ColorInt val off: Int,
    /** The requested lamp's dashes. */
    @ColorInt val requested: Int,
    /** A lit lamp's disc (on and live). */
    @ColorInt val lit: Int,
    /** The lit disc's edge, or [Color.TRANSPARENT] for none. */
    @ColorInt val litEdge: Int,
    /** The live lamp's ring of light. */
    @ColorInt val halo: Int,
    /** The failed ring. */
    @ColorInt val failed: Int,
    /** The unavailable ring. */
    @ColorInt val unavailable: Int,
    /**
     * A sensor lamp (camera, microphone or location in use). Tally's privacy rule: a sensor lamp
     * appears with no spring delay and only its disappearance may animate, so lamps with these
     * colours appear at once unless the caller says otherwise.
     */
    val sensor: Boolean = false,
) {
    /** Whether a lit disc has an edge. */
    val hasLitEdge: Boolean
        get() = Color.alpha(litEdge) != 0

    companion object {
        /**
         * The theme's lamp, light or dark by the night mode of [context]'s configuration. Pass a
         * context in the theme of the surface under the lamp, for example the lock screen's
         * wallpaper theme.
         */
        @JvmStatic
        fun theme(context: Context): TallyLampColors {
            val night =
                (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                    Configuration.UI_MODE_NIGHT_YES
            val lamp = context.getColor(TallyR.color.tally_lamp)
            // In light theme the lamp outline edges the disc and draws the ring of light, so a lit
            // lamp reads at 3:1 on light surfaces; in dark theme the lamp stands out by itself.
            val outline = context.getColor(TallyR.color.tally_lamp_outline)
            return TallyLampColors(
                off = context.getColor(TallyR.color.tally_outline),
                requested = context.getColor(TallyR.color.tally_accent),
                lit = lamp,
                litEdge = if (night) Color.TRANSPARENT else outline,
                halo = if (night) lamp else outline,
                failed = context.getColor(TallyR.color.tally_error),
                unavailable = context.getColor(TallyR.color.tally_ink_muted),
            )
        }

        /** Every part in [color], without an edge. */
        @JvmStatic
        @JvmOverloads
        fun plain(@ColorInt color: Int, sensor: Boolean = false): TallyLampColors =
            TallyLampColors(
                off = color,
                requested = color,
                lit = color,
                litEdge = Color.TRANSPARENT,
                halo = color,
                failed = color,
                unavailable = color,
                sensor = sensor,
            )

        /**
         * A sensor lamp on a surface the shell owns (the lock strip, a tally, the privacy sheet):
         * every part in the theme's sensor colour, appearing at once. Status bar indicators take
         * the sensor colour for the area under them instead: `plain(color, sensor = true)`.
         */
        @JvmStatic
        fun sensor(context: Context): TallyLampColors =
            plain(context.getColor(TallyR.color.tally_sensor), sensor = true)

        /** A lamp on a lit (lamp-coloured) field or tile: every part in the colour on the lamp. */
        @JvmStatic
        fun onLitField(context: Context): TallyLampColors =
            plain(context.getColor(TallyR.color.tally_on_lamp))

        /** A lamp on a toast or the Undo bar: every part in the colour on the inverse surface. */
        @JvmStatic
        fun onInverse(context: Context): TallyLampColors =
            plain(context.getColor(TallyR.color.tally_on_inverse))
    }
}
