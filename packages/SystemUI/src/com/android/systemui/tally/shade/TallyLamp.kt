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

package com.android.systemui.tally.shade

import android.content.Context
import android.graphics.drawable.Drawable
import androidx.annotation.DimenRes
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.unit.Dp
import kotlin.math.roundToInt
import org.diamaneos.tally.R as TallyR

/** What a Tally lamp shows. Lamps only ever show real state; the words next to them say why. */
enum class TallyLampForm {
    /** Nothing is on: an outline ring. */
    OFF,
    /** Waiting on the system: the ring in four dashes. */
    REQUESTED,
    /** On: a disc. */
    ON,
    /** On and doing something now: a slightly smaller disc with a ring of light around it. */
    LIVE,
    /** Failed: a ring broken at one to two o'clock. */
    FAILED,
    /** Cannot be on: the off ring in the muted colour. */
    UNAVAILABLE;

    /** Whether the lamp is lit, which is also when a lamp's field (a tile, a key) is lit. */
    val isLit: Boolean
        get() = this == ON || this == LIVE
}

/**
 * Every lamp the shade and Quick Settings draw goes through here, in Compose ([TallyLamp]) and in
 * views ([drawable]), so the shell's shared animated lamp can take their place in one spot. Until
 * then they are the token library's static lamp drawables (`tally_lamp_<form>_<size>`), which
 * follow the prototype's static lamp: a lit lamp in the lamp colour with the lamp outline as its
 * edge in light theme (the night variants have none), rings in the outline, accent, error and muted
 * colours. On a lit field every form is one colour, the colour for text on the lamp, and lit forms
 * use the edge-less `_plain` drawables.
 *
 * A drawable is the lamp plus a live lamp's ring of light, [boxDimen] square for every form of a
 * size, so forms swap without moving: centre the box on where a lamp of its size would sit.
 */
object TallyLamps {
    /** The sizes the token library draws, in dp. */
    const val SIZE_SMALL = 10
    const val SIZE = 12
    const val SIZE_LARGE = 14
    const val SIZE_XLARGE = 16

    /** The static lamp for [form] at [sizeDp] (10, 12, 14 or 16 dp), sized to its box. */
    fun drawable(
        context: Context,
        form: TallyLampForm,
        sizeDp: Int,
        onLitField: Boolean = false,
    ): Drawable {
        val drawable = context.getDrawable(drawableRes(form, sizeDp, plain = onLitField))!!.mutate()
        if (onLitField) drawable.setTint(context.getColor(TallyR.color.tally_on_lamp))
        val box = context.resources.getDimensionPixelSize(boxDimen(sizeDp))
        drawable.setBounds(0, 0, box, box)
        return drawable
    }

    /** The side of a lamp drawable's box: the lamp plus a live lamp's ring of light. */
    @DimenRes
    fun boxDimen(sizeDp: Int): Int =
        bySize(
            sizeDp,
            TallyR.dimen.tally_lamp_box_10,
            TallyR.dimen.tally_lamp_box_12,
            TallyR.dimen.tally_lamp_box_14,
            TallyR.dimen.tally_lamp_box_16,
        )

    @DrawableRes
    private fun drawableRes(form: TallyLampForm, sizeDp: Int, plain: Boolean): Int =
        when (form) {
            TallyLampForm.OFF ->
                bySize(
                    sizeDp,
                    TallyR.drawable.tally_lamp_off_10,
                    TallyR.drawable.tally_lamp_off_12,
                    TallyR.drawable.tally_lamp_off_14,
                    TallyR.drawable.tally_lamp_off_16,
                )
            TallyLampForm.REQUESTED ->
                bySize(
                    sizeDp,
                    TallyR.drawable.tally_lamp_requested_10,
                    TallyR.drawable.tally_lamp_requested_12,
                    TallyR.drawable.tally_lamp_requested_14,
                    TallyR.drawable.tally_lamp_requested_16,
                )
            TallyLampForm.ON ->
                if (plain) {
                    bySize(
                        sizeDp,
                        TallyR.drawable.tally_lamp_on_plain_10,
                        TallyR.drawable.tally_lamp_on_plain_12,
                        TallyR.drawable.tally_lamp_on_plain_14,
                        TallyR.drawable.tally_lamp_on_plain_16,
                    )
                } else {
                    bySize(
                        sizeDp,
                        TallyR.drawable.tally_lamp_on_10,
                        TallyR.drawable.tally_lamp_on_12,
                        TallyR.drawable.tally_lamp_on_14,
                        TallyR.drawable.tally_lamp_on_16,
                    )
                }
            TallyLampForm.LIVE ->
                if (plain) {
                    bySize(
                        sizeDp,
                        TallyR.drawable.tally_lamp_live_plain_10,
                        TallyR.drawable.tally_lamp_live_plain_12,
                        TallyR.drawable.tally_lamp_live_plain_14,
                        TallyR.drawable.tally_lamp_live_plain_16,
                    )
                } else {
                    bySize(
                        sizeDp,
                        TallyR.drawable.tally_lamp_live_10,
                        TallyR.drawable.tally_lamp_live_12,
                        TallyR.drawable.tally_lamp_live_14,
                        TallyR.drawable.tally_lamp_live_16,
                    )
                }
            TallyLampForm.FAILED ->
                bySize(
                    sizeDp,
                    TallyR.drawable.tally_lamp_failed_10,
                    TallyR.drawable.tally_lamp_failed_12,
                    TallyR.drawable.tally_lamp_failed_14,
                    TallyR.drawable.tally_lamp_failed_16,
                )
            TallyLampForm.UNAVAILABLE ->
                bySize(
                    sizeDp,
                    TallyR.drawable.tally_lamp_unavailable_10,
                    TallyR.drawable.tally_lamp_unavailable_12,
                    TallyR.drawable.tally_lamp_unavailable_14,
                    TallyR.drawable.tally_lamp_unavailable_16,
                )
        }

    private fun bySize(sizeDp: Int, small: Int, normal: Int, large: Int, xlarge: Int): Int =
        when {
            sizeDp <= SIZE_SMALL -> small
            sizeDp <= SIZE -> normal
            sizeDp <= SIZE_LARGE -> large
            else -> xlarge
        }
}

/**
 * A Tally lamp in Compose, [size] square (10, 12, 14 or 16 dp) and centred there; a live lamp's
 * ring of light reaches a little past it, so leave a few dp of room around it.
 *
 * @param onLitField the lamp sits on a lit (lamp-coloured) field, where every form is drawn in the
 *   colour for text on the lamp.
 */
@Composable
fun TallyLamp(
    form: TallyLampForm,
    size: Dp,
    modifier: Modifier = Modifier,
    onLitField: Boolean = false,
) {
    val context = LocalContext.current
    val sizeDp = size.value.roundToInt()
    // The drawables have night variants, so a theme change picks a new one.
    val configuration = LocalConfiguration.current
    val drawable =
        remember(context, configuration, form, sizeDp, onLitField) {
            TallyLamps.drawable(context, form, sizeDp, onLitField)
        }
    val box = dimensionResource(TallyLamps.boxDimen(sizeDp))
    Canvas(modifier.size(size)) {
        val side = box.roundToPx()
        val offset = ((this.size.width - side) / 2f).roundToInt()
        drawable.setBounds(offset, offset, offset + side, offset + side)
        drawIntoCanvas { drawable.draw(it.nativeCanvas) }
    }
}
