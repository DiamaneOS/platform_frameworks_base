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

package com.android.systemui.tally.volume

import android.content.Context
import android.util.TypedValue
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SliderState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.DeviceFontFamilyName
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.offset
import androidx.compose.ui.unit.sp
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.common.ui.compose.Icon
import com.android.systemui.res.R
import java.text.NumberFormat
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import org.diamaneos.tally.R as TallyR

/**
 * Tally's volume meter (the prototype's `.vol-meter`), drawn as the track of the volume dialog's
 * vertical slider: a column in the high surface with the key radius, a hairline at every step, a
 * neutral fill up to the level and the level's lamp tick at the top of the fill, with the stream's
 * icon (its muted form included) at the foot. A level is a value, not a state, so only the tick is
 * in the lamp colour. Everything else about the slider (touch, keys, haptics and what screen
 * readers hear and can do) is the stock slider's; this only draws.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TallyVolumeMeter(
    sliderState: SliderState,
    icon: Icon,
    isEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val ink = colorResource(TallyR.color.tally_ink)
    val trackColor = colorResource(TallyR.color.tally_surface_high)
    val lamp = colorResource(TallyR.color.tally_lamp)
    val lampOutline = colorResource(TallyR.color.tally_lamp_outline)
    val radius = dimensionResource(TallyR.dimen.tally_radius_s)
    val hairline = dimensionResource(TallyR.dimen.tally_stroke_hairline)
    Box(
        contentAlignment = Alignment.BottomCenter,
        modifier =
            modifier
                .fillMaxSize()
                .alpha(if (isEnabled) 1f else DISABLED_ALPHA)
                .clip(RoundedCornerShape(radius))
                .drawWithContent {
                    val height = size.height
                    val level = height * sliderState.coercedValueAsFraction
                    drawRect(trackColor)
                    drawRect(
                        ink.copy(alpha = FILL_ALPHA),
                        topLeft = Offset(0f, height - level),
                        size = Size(size.width, level),
                    )
                    // A hairline just above each step's level, where the tick stops.
                    val range = sliderState.valueRange
                    val steps = (range.endInclusive - range.start).roundToInt()
                    val line = hairline.toPx()
                    if (steps > 1 && height / steps >= MIN_STEP_SPACING.toPx()) {
                        for (step in 1 until steps) {
                            drawRect(
                                ink.copy(alpha = STEP_ALPHA),
                                topLeft = Offset(0f, height - height * step / steps - line),
                                size = Size(size.width, line),
                            )
                        }
                    }
                    drawContent()
                    // The tick sits inside the fill at its top, and at the foot at no volume. In
                    // light theme its edge is the lamp outline; in dark theme the outline is the
                    // lamp itself.
                    val tick = TICK_HEIGHT.toPx()
                    val tickTop = height - max(level, tick)
                    drawRect(lamp, topLeft = Offset(0f, tickTop), size = Size(size.width, tick))
                    drawRect(
                        lampOutline,
                        topLeft = Offset(line / 2f, tickTop + line / 2f),
                        size = Size(size.width - line, tick - line),
                        style = Stroke(line),
                    )
                },
    ) {
        Icon(
            icon = icon,
            tint = { ink },
            modifier = Modifier.padding(bottom = ICON_INSET).size(ICON_SIZE),
        )
    }
}

/**
 * The slider's thumb in the Tally meter: it draws nothing (the meter draws its own tick), so the
 * slider only uses it to lay out and follow the level.
 */
@Composable
fun TallyVolumeMeterThumb(modifier: Modifier = Modifier) {
    Spacer(modifier.fillMaxWidth())
}

object TallyVolumeMeterDefaults {
    /** The level moves on the stone spring, as the prototype's meter. */
    @Composable
    fun animationSpec(): AnimationSpec<Float> {
        val resources = LocalContext.current.resources
        return remember(resources) {
            spring(
                dampingRatio = resources.getFloat(TallyR.dimen.tally_spring_stone_damping_ratio),
                stiffness = resources.getFloat(TallyR.dimen.tally_spring_stone_stiffness),
            )
        }
    }
}

/**
 * The level readout above the meter (the prototype's `.vol-val`): the level as a number, in Tally's
 * readout type, centred over the slider. It is drawn, not a node, so screen readers keep hearing
 * only the stock slider, which already says the level. Where the slider is short (landscape), the
 * meter keeps the room and the number is left out.
 */
@Stable
class TallyVolumeReadout
internal constructor(
    private val measurer: TextMeasurer,
    private val style: TextStyle,
    private val color: Color,
    private val gapPx: Int,
    private val minMeterPx: Int,
    private val format: NumberFormat,
) {
    /** Draws [level] rounded above whatever it modifies, which moves down to make room. */
    fun modifier(level: Float, isEnabled: Boolean): Modifier {
        val text = measurer.measure(format.format(level.roundToInt()), style)
        val room = text.size.height + gapPx
        var isShown = true
        return Modifier.drawBehind {
                if (isShown) {
                    drawText(
                        text,
                        color = if (isEnabled) color else color.copy(alpha = DISABLED_ALPHA),
                        topLeft = Offset((size.width - text.size.width) / 2f, 0f),
                    )
                }
            }
            .layout { measurable, constraints ->
                isShown = constraints.maxHeight - room >= minMeterPx
                val top = if (isShown) room else 0
                val placeable = measurable.measure(constraints.offset(vertical = -top))
                layout(placeable.width, placeable.height + top) { placeable.place(0, top) }
            }
    }
}

@Composable
fun rememberTallyVolumeReadout(): TallyVolumeReadout {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val color = colorResource(TallyR.color.tally_ink)
    val gap = dimensionResource(R.dimen.tally_volume_readout_gap)
    return remember(context, configuration, density, measurer, color, gap) {
        TallyVolumeReadout(
            measurer = measurer,
            style = readoutStyle(context),
            color = color,
            gapPx = with(density) { gap.roundToPx() },
            minMeterPx = with(density) { MIN_METER_UNDER_READOUT.roundToPx() },
            format = NumberFormat.getIntegerInstance(),
        )
    }
}

/**
 * Tally's readout type (`TextAppearance.Tally.Readout`, `tally_type_readout_line_height`) for
 * Compose: Sofia Sans at the role's size, weight, tracking and tabular figures, in sp so it follows
 * the text size. The family also carries the weight Bold text asks for, so neither is synthesised.
 */
private fun readoutStyle(context: Context): TextStyle {
    val attrs =
        context.obtainStyledAttributes(TallyR.style.TextAppearance_Tally_Readout, TEXT_ATTRS)
    try {
        val weight = attrs.getInt(TEXT_ATTRS.indexOf(android.R.attr.textFontWeight), NORMAL)
        val family = DeviceFontFamilyName(context.getString(TallyR.string.tally_font_family))
        return TextStyle(
            fontFamily =
                FontFamily(
                    Font(family, FontWeight(weight)),
                    Font(family, FontWeight(min(weight + BOLD_TEXT_ADJUSTMENT, MAX_WEIGHT))),
                ),
            fontWeight = FontWeight(weight),
            fontSize =
                TypedValue()
                    .also { attrs.getValue(TEXT_ATTRS.indexOf(android.R.attr.textSize), it) }
                    .toSp(),
            letterSpacing = attrs.getFloat(TEXT_ATTRS.indexOf(android.R.attr.letterSpacing), 0f).em,
            fontFeatureSettings =
                attrs.getString(TEXT_ATTRS.indexOf(android.R.attr.fontFeatureSettings)),
            lineHeight =
                TypedValue()
                    .also {
                        context.resources.getValue(
                            TallyR.dimen.tally_type_readout_line_height,
                            it,
                            true,
                        )
                    }
                    .toSp(),
            lineHeightStyle =
                LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
        )
    } finally {
        attrs.recycle()
    }
}

private fun TypedValue.toSp(): TextUnit =
    if (complexUnit == TypedValue.COMPLEX_UNIT_SP) TypedValue.complexToFloat(data).sp
    else TextUnit.Unspecified

// Sorted, as obtainStyledAttributes requires.
private val TEXT_ATTRS =
    intArrayOf(
            android.R.attr.textSize,
            android.R.attr.letterSpacing,
            android.R.attr.fontFeatureSettings,
            android.R.attr.textFontWeight,
        )
        .apply { sort() }

/** The prototype's meter: an ink fill at 16 %, hairlines at 18 %, a 4 dp lamp tick. */
private const val FILL_ALPHA = 0.16f
private const val STEP_ALPHA = 0.18f
private val TICK_HEIGHT: Dp = 4.dp
/** Hairlines closer than this (streams with many steps) would read as a texture, not steps. */
private val MIN_STEP_SPACING: Dp = 4.dp
/** The readout shows only if the meter under it keeps at least half its full 240 dp. */
private val MIN_METER_UNDER_READOUT: Dp = 120.dp
/** The stream's icon, centred in a 48 dp square at the foot of the meter. */
private val ICON_SIZE: Dp = 20.dp
private val ICON_INSET: Dp = 14.dp
/** Tally draws a control that cannot be used in its colours at 38 %. */
private const val DISABLED_ALPHA = 0.38f
private const val NORMAL = 400
private const val BOLD_TEXT_ADJUSTMENT = 300
private const val MAX_WEIGHT = 1000
