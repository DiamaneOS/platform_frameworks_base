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

import android.content.Context
import android.content.res.Resources
import android.graphics.Color
import android.util.TypedValue
import androidx.annotation.ColorInt
import com.android.systemui.privacy.PrivacyItem
import com.android.systemui.privacy.PrivacyType
import de.diamaneos.tally.R as TallyR

/**
 * The colours of one Tally privacy indicator over the status bar area: its fill, what is drawn on
 * the fill, and the edge around it.
 *
 * Indicators over the status bar area take the variant for the area under them, never the one for
 * night mode: the `_dark` variant over a dark area (light status icons, or a dim over the area) and
 * the `_light` one over a light area. The edge is white around a `_light` variant and black around
 * a `_dark` one: invisible over the area the variant was chosen for, it keeps the indicator at 3:1
 * or more over the mid-tones a dim leaves. The colours are fixed resources outside the palette.
 */
data class TallyIndicatorColors(
    @ColorInt val fill: Int,
    @ColorInt val onFill: Int,
    @ColorInt val edge: Int,
) {
    companion object {
        /** Width of the edge around a chip, in dp. */
        const val EDGE_WIDTH_DP = 2f

        /** Camera, microphone and location in use. */
        @JvmStatic
        fun sensor(context: Context, isAreaDark: Boolean): TallyIndicatorColors =
            if (isAreaDark) {
                TallyIndicatorColors(
                    fill = context.getColor(TallyR.color.tally_sensor_dark),
                    onFill = context.getColor(TallyR.color.tally_on_sensor_dark),
                    edge = Color.BLACK,
                )
            } else {
                TallyIndicatorColors(
                    fill = context.getColor(TallyR.color.tally_sensor_light),
                    onFill = context.getColor(TallyR.color.tally_on_sensor_light),
                    edge = Color.WHITE,
                )
            }

        /** The screen being recorded, cast or shared. */
        @JvmStatic
        fun capture(context: Context, isAreaDark: Boolean): TallyIndicatorColors =
            if (isAreaDark) {
                TallyIndicatorColors(
                    fill = context.getColor(TallyR.color.tally_capture_dark),
                    onFill = context.getColor(TallyR.color.tally_on_capture_dark),
                    edge = Color.BLACK,
                )
            } else {
                TallyIndicatorColors(
                    fill = context.getColor(TallyR.color.tally_capture_light),
                    onFill = context.getColor(TallyR.color.tally_on_capture_light),
                    edge = Color.WHITE,
                )
            }

        /**
         * The colours for privacy items of [types]: the capture colours when screen capture (media
         * projection) is all they show, else the sensor colours. A sensor in use wins, and so do
         * unknown items, so a sensor is never shown in the capture colour.
         */
        @JvmStatic
        fun forPrivacyTypes(
            context: Context,
            isAreaDark: Boolean,
            types: Collection<PrivacyType>?,
        ): TallyIndicatorColors =
            if (isCaptureOnly(types)) capture(context, isAreaDark) else sensor(context, isAreaDark)

        /** [forPrivacyTypes] for the types of [items]. */
        @JvmStatic
        fun forPrivacyItems(
            context: Context,
            isAreaDark: Boolean,
            items: List<PrivacyItem>?,
        ): TallyIndicatorColors =
            forPrivacyTypes(context, isAreaDark, items?.map { it.privacyType })

        /** Whether [types] are screen capture (media projection) and nothing else. */
        @JvmStatic
        fun isCaptureOnly(types: Collection<PrivacyType>?): Boolean =
            !types.isNullOrEmpty() && types.all { it == PrivacyType.TYPE_MEDIA_PROJECTION }

        /** Converts a length in dp to pixels for [resources]. */
        @JvmStatic
        fun dpToPx(resources: Resources, dp: Float): Float =
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, resources.displayMetrics)
    }
}
