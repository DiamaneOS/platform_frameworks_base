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

import android.content.Context
import android.os.Build
import androidx.annotation.ColorInt
import androidx.annotation.RequiresApi
import com.android.settingslib.widget.theme.R

/**
 * The Tally switch's sizes (px), colours and motion, from the values generated from the Tally token
 * spec (`res/values-v34/tally_switch.xml`, export settingslib-switch). The colours are the
 * context's theme: dark theme reads the night values.
 */
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
internal class TallySwitchSpec(
    /** Pixels per dp. */
    val density: Float,
    val width: Float,
    val height: Float,
    val radius: Float,
    val borderWidth: Float,
    val borderRadius: Float,
    val fillInset: Float,
    val thumbSize: Float,
    val thumbRadius: Float,
    val litEdge: Float,
    val litEdgeRadius: Float,
    val litFillInset: Float,
    val focusRingWidth: Float,
    val focusRingOffset: Float,
    @ColorInt val surfaceHigh: Int,
    @ColorInt val outline: Int,
    @ColorInt val lamp: Int,
    @ColorInt val lampOutline: Int,
    @ColorInt val onLamp: Int,
    @ColorInt val press: Int,
    @ColorInt val accent: Int,
    val thumbStiffness: Float,
    val thumbDampingRatio: Float,
    val wipeStiffness: Float,
    val wipeDampingRatio: Float,
    val tapImpulse: Float,
    val reducedMotionFadeMillis: Long,
) {
    /** How far the thumb travels, in dp: the switch's width less its height. */
    val travelDp: Float
        get() = (width - height) / density

    companion object {
        fun from(context: Context): TallySwitchSpec {
            val res = context.resources
            return TallySwitchSpec(
                density = res.displayMetrics.density,
                width = res.getDimension(R.dimen.settingslib_tally_switch_width),
                height = res.getDimension(R.dimen.settingslib_tally_switch_height),
                radius = res.getDimension(R.dimen.settingslib_tally_switch_radius),
                borderWidth = res.getDimension(R.dimen.settingslib_tally_switch_border_width),
                borderRadius = res.getDimension(R.dimen.settingslib_tally_switch_border_radius),
                fillInset = res.getDimension(R.dimen.settingslib_tally_switch_fill_inset),
                thumbSize = res.getDimension(R.dimen.settingslib_tally_switch_thumb_size),
                thumbRadius = res.getDimension(R.dimen.settingslib_tally_switch_thumb_radius),
                litEdge = res.getDimension(R.dimen.settingslib_tally_switch_lit_edge),
                litEdgeRadius = res.getDimension(R.dimen.settingslib_tally_switch_lit_edge_radius),
                litFillInset = res.getDimension(R.dimen.settingslib_tally_switch_lit_fill_inset),
                focusRingWidth = res.getDimension(R.dimen.settingslib_tally_focus_ring_width),
                focusRingOffset = res.getDimension(R.dimen.settingslib_tally_focus_ring_offset),
                surfaceHigh = context.getColor(R.color.settingslib_tally_surface_high),
                outline = context.getColor(R.color.settingslib_tally_outline),
                lamp = context.getColor(R.color.settingslib_tally_lamp),
                lampOutline = context.getColor(R.color.settingslib_tally_lamp_outline),
                onLamp = context.getColor(R.color.settingslib_tally_on_lamp),
                press = context.getColor(R.color.settingslib_tally_press),
                accent = context.getColor(R.color.settingslib_tally_accent),
                thumbStiffness = res.getFloat(R.dimen.settingslib_tally_spring_pebble_stiffness),
                thumbDampingRatio =
                    res.getFloat(R.dimen.settingslib_tally_spring_pebble_damping_ratio),
                wipeStiffness = res.getFloat(R.dimen.settingslib_tally_spring_fill_stiffness),
                wipeDampingRatio =
                    res.getFloat(R.dimen.settingslib_tally_spring_fill_damping_ratio),
                tapImpulse = res.getFloat(R.dimen.settingslib_tally_motion_tap_impulse),
                reducedMotionFadeMillis =
                    res.getInteger(R.integer.settingslib_tally_reduced_motion_fade_ms).toLong(),
            )
        }
    }
}
