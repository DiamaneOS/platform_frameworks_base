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

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.android.systemui.tally.TallyShell

/**
 * Whether the status bar area under the chips in the status bar is dark, provided around them by
 * [ProvideTallyStatusBarArea]. Where nothing provides it, the area counts as dark: the `_dark`
 * variant with its black edge keeps 3:1 over any colour.
 */
val LocalTallyStatusBarAreaDark = staticCompositionLocalOf { true }

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
