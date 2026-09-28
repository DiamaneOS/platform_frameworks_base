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
import android.util.TypedValue
import androidx.annotation.StyleRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement.spacedBy
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.DeviceFontFamilyName
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.android.systemui.privacy.PrivacyType
import com.android.systemui.res.R
import com.android.systemui.tally.TallyShell
import de.diamaneos.tally.R as TallyR

/**
 * Whether the status bar area under the chips in the status bar is dark, provided around them by
 * [ProvideTallyStatusBarArea], or null where nothing provides it (the desktop and ambient status
 * bars). A chip over an unknown area takes the `_light` variant, which keeps 3:1 against both white
 * and black by itself.
 */
val LocalTallyStatusBarAreaDark = staticCompositionLocalOf<Boolean?> { null }

/** Provides [LocalTallyStatusBarAreaDark] from [area] to [content] while Tally is on. */
@Composable
fun ProvideTallyStatusBarArea(area: TallyIndicatorArea?, content: @Composable () -> Unit) {
    if (!TallyShell.isEnabled || area == null) {
        content()
        return
    }
    val isAreaDark by area.isStatusBarAreaDark.collectAsState()
    CompositionLocalProvider(LocalTallyStatusBarAreaDark provides isAreaDark, content = content)
}

/**
 * Draws the indicator's edge in [color] just outside its rounded rectangle of [cornerRadius], so
 * the fill keeps its full size ([TallyIndicatorColors]).
 */
fun Modifier.tallyIndicatorEdge(color: Color, cornerRadius: Dp): Modifier = drawWithCache {
    val edge = TallyIndicatorColors.EDGE_WIDTH_DP.dp.toPx()
    val radius = minOf(cornerRadius.toPx(), size.minDimension / 2f)
    val ring =
        Path().apply {
            fillType = PathFillType.EvenOdd
            addRoundRect(
                RoundRect(
                    rect = Rect(-edge, -edge, size.width + edge, size.height + edge),
                    cornerRadius = CornerRadius(radius + edge),
                )
            )
            addRoundRect(
                RoundRect(
                    rect = Rect(0f, 0f, size.width, size.height),
                    cornerRadius = CornerRadius(radius),
                )
            )
        }
    onDrawBehind { drawPath(ring, color) }
}

/**
 * Tally's privacy chip in the shade header: one chip in the sensor colours for the area under it
 * (the capture colours for screen capture alone), with an icon for each type in use, at the stock
 * chip's height and icon size (which follow the text size).
 */
@Composable
fun TallySensorChip(
    privacyTypes: Set<PrivacyType>,
    isAreaDark: Boolean,
    modifier: Modifier = Modifier,
) {
    val types = remember(privacyTypes) { privacyTypes.sorted() }
    if (types.isEmpty()) return
    val context = LocalContext.current
    val colors =
        remember(context, isAreaDark, types) {
            TallyIndicatorColors.forPrivacyTypes(context, isAreaDark, types)
        }
    val radius = dimensionResource(TallyR.dimen.tally_radius_s)
    val iconSize = dimensionResource(R.dimen.ongoing_appops_chip_icon_size)
    Row(
        modifier =
            modifier
                .height(dimensionResource(R.dimen.ongoing_appops_chip_height))
                .tallyIndicatorEdge(Color(colors.edge), radius)
                .background(Color(colors.fill), RoundedCornerShape(radius))
                .padding(
                    horizontal =
                        dimensionResource(TallyR.dimen.tally_privacy_chip_padding_horizontal)
                ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = spacedBy(dimensionResource(TallyR.dimen.tally_privacy_chip_gap)),
    ) {
        types.forEach { type ->
            key(type.nameId) {
                Icon(
                    painter = painterResource(type.iconId),
                    contentDescription = null,
                    tint = Color(colors.onFill),
                    modifier = Modifier.size(iconSize),
                )
            }
        }
    }
}

/**
 * The capture chips' text: Tally's capture chip type (`TextAppearance.Tally.CaptureChip`, Sofia
 * Sans at 14 sp, weight 600, tabular figures), read from the token style. It stays in sp, so it
 * follows the text size as stock's chip text does.
 */
@Composable
fun tallyCaptureChipTextStyle(): TextStyle {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    return remember(context, configuration) {
        readTextAppearance(context, TallyR.style.TextAppearance_Tally_CaptureChip)
    }
}

// Sorted, as obtainStyledAttributes requires.
private val TEXT_APPEARANCE_ATTRS =
    intArrayOf(
            android.R.attr.textSize,
            android.R.attr.fontFamily,
            android.R.attr.fontFeatureSettings,
            android.R.attr.textFontWeight,
        )
        .apply { sort() }

/** The size (in sp), family, weight and font features of a text appearance, for Compose. */
private fun readTextAppearance(context: Context, @StyleRes appearance: Int): TextStyle {
    val attrs = context.obtainStyledAttributes(appearance, TEXT_APPEARANCE_ATTRS)
    try {
        val size = TypedValue()
        attrs.getValue(TEXT_APPEARANCE_ATTRS.indexOf(android.R.attr.textSize), size)
        val weight =
            attrs.getInt(
                TEXT_APPEARANCE_ATTRS.indexOf(android.R.attr.textFontWeight),
                NORMAL_WEIGHT,
            )
        val family = attrs.getString(TEXT_APPEARANCE_ATTRS.indexOf(android.R.attr.fontFamily))
        return TextStyle(
            fontFamily = family?.let { deviceFontFamily(it, weight) },
            fontWeight = FontWeight(weight),
            fontSize =
                if (
                    size.type == TypedValue.TYPE_DIMENSION &&
                        size.complexUnit == TypedValue.COMPLEX_UNIT_SP
                ) {
                    TypedValue.complexToFloat(size.data).sp
                } else {
                    TextUnit.Unspecified
                },
            fontFeatureSettings =
                attrs.getString(TEXT_APPEARANCE_ATTRS.indexOf(android.R.attr.fontFeatureSettings)),
        )
    } finally {
        attrs.recycle()
    }
}

/**
 * The system font family [name] at [weight], and at [weight] + 300 for Bold text, so that neither
 * is synthesised. A family that is not installed falls back to the default font.
 */
private fun deviceFontFamily(name: String, weight: Int): FontFamily {
    val bold = minOf(weight + BOLD_TEXT_WEIGHT_ADJUSTMENT, MAX_FONT_WEIGHT)
    return FontFamily(
        Font(DeviceFontFamilyName(name), FontWeight(weight)),
        Font(DeviceFontFamilyName(name), FontWeight(bold)),
    )
}

private const val NORMAL_WEIGHT = 400
private const val BOLD_TEXT_WEIGHT_ADJUSTMENT = 300
private const val MAX_FONT_WEIGHT = 1000
