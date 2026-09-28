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

@file:OptIn(ExperimentalTextApi::class)

package com.android.systemui.tally.shade

import android.content.Context
import android.util.TypedValue
import androidx.annotation.DimenRes
import androidx.annotation.StringRes
import androidx.annotation.StyleRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.DeviceFontFamilyName
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import de.diamaneos.tally.R as TallyR

/**
 * A Tally type role for Compose text, read from its text appearance and line height tokens
 * (`TextAppearance.Tally.*` and `tally_type_*_line_height`): Sofia Sans at the role's size, weight
 * (lighter in dark theme), tracking and figures. Sizes stay in sp, so the text follows the text
 * size setting as a TextView with the same appearance does.
 *
 * @param family the token naming the font family; [TallyR.string.tally_font_family_condensed] sets
 *   a label that does not fit in Semi Condensed.
 */
@Composable
fun tallyTextStyle(
    @StyleRes appearance: Int,
    @DimenRes lineHeight: Int = 0,
    @StringRes family: Int = TallyR.string.tally_font_family,
): TextStyle {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    return remember(context, configuration, density, appearance, lineHeight, family) {
        readTextStyle(context, density, appearance, lineHeight, family)
    }
}

// Sorted, as obtainStyledAttributes requires.
private val TEXT_ATTRS =
    intArrayOf(
            android.R.attr.textSize,
            android.R.attr.fontFamily,
            android.R.attr.letterSpacing,
            android.R.attr.fontFeatureSettings,
            android.R.attr.textFontWeight,
        )
        .apply { sort() }

private fun readTextStyle(
    context: Context,
    density: Density,
    @StyleRes appearance: Int,
    @DimenRes lineHeight: Int,
    @StringRes family: Int,
): TextStyle {
    val attrs = context.obtainStyledAttributes(appearance, TEXT_ATTRS)
    try {
        val size = TypedValue()
        attrs.getValue(TEXT_ATTRS.indexOf(android.R.attr.textSize), size)
        val weight = attrs.getInt(TEXT_ATTRS.indexOf(android.R.attr.textFontWeight), NORMAL)
        val features = attrs.getString(TEXT_ATTRS.indexOf(android.R.attr.fontFeatureSettings))
        return TextStyle(
            fontFamily = deviceFontFamily(context.getString(family), weight),
            fontWeight = FontWeight(weight),
            fontSize = size.toTextUnit(density),
            letterSpacing = attrs.getFloat(TEXT_ATTRS.indexOf(android.R.attr.letterSpacing), 0f).em,
            fontFeatureSettings = features,
            lineHeight =
                if (lineHeight != 0) {
                    TypedValue()
                        .also { context.resources.getValue(lineHeight, it, true) }
                        .toTextUnit(density)
                } else {
                    TextUnit.Unspecified
                },
            // As a line height in CSS or a TextView: the extra room split above and below.
            lineHeightStyle =
                LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
        )
    } finally {
        attrs.recycle()
    }
}

/**
 * The family as a named system font at [weight], plus [weight] + 300 for Bold text, so neither is
 * synthesised. A family that is not installed falls back to the default font.
 */
private fun deviceFontFamily(name: String, weight: Int): FontFamily {
    val font = DeviceFontFamilyName(name)
    val bold = minOf(weight + BOLD_TEXT_ADJUSTMENT, MAX_WEIGHT)
    return if (bold == weight) {
        FontFamily(Font(font, FontWeight(weight)))
    } else {
        FontFamily(Font(font, FontWeight(weight)), Font(font, FontWeight(bold)))
    }
}

private fun TypedValue.toTextUnit(density: Density): TextUnit {
    val value = TypedValue.complexToFloat(data)
    return when (complexUnit) {
        TypedValue.COMPLEX_UNIT_SP -> value.sp
        // A size in dp (the lock clock) must not follow the text size.
        TypedValue.COMPLEX_UNIT_DIP -> with(density) { value.dp.toSp() }
        else -> TextUnit.Unspecified
    }
}

private const val NORMAL = 400
private const val BOLD_TEXT_ADJUSTMENT = 300
private const val MAX_WEIGHT = 1000
