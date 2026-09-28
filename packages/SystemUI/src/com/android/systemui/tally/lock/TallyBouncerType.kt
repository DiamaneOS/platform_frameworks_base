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

package com.android.systemui.tally.lock

import android.content.Context
import android.graphics.Typeface
import androidx.annotation.StyleRes
import de.diamaneos.tally.R as TallyR

/**
 * The Tally type for bouncer text whose typeface SystemUI sets in code: the PIN digits and the
 * bouncer's message lines. Only the typeface changes, to the family and weight of a Tally text
 * appearance (Sofia Sans); the sizes, colours and everything else stay as the bouncer sets them.
 */
object TallyBouncerType {
    /** The family and weight of the Tally text appearance [appearance], in [context]'s theme. */
    @JvmStatic
    fun typeface(context: Context, @StyleRes appearance: Int): Typeface {
        val values = context.obtainStyledAttributes(appearance, ATTRS)
        try {
            val family =
                values.getString(FAMILY) ?: context.getString(TallyR.string.tally_font_family)
            val weight = values.getInt(WEIGHT, REGULAR_WEIGHT)
            return Typeface.create(Typeface.create(family, Typeface.NORMAL), weight, false)
        } finally {
            values.recycle()
        }
    }

    // obtainStyledAttributes takes the attributes in ascending order of their ids.
    private val ATTRS = intArrayOf(android.R.attr.fontFamily, android.R.attr.textFontWeight)
    private const val FAMILY = 0
    private const val WEIGHT = 1
    private const val REGULAR_WEIGHT = 400
}
