/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.systemui.screencapture.record.smallscreen.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp

/**
 * Lays out the post-recording keys in one row, all as wide as each other while every label fits,
 * else each as wide as its own label with the spare width shared out, and when even that does not
 * fit (large text), one under the other at the full width, so that no key cuts its label off.
 */
@Composable
internal fun PostRecordButtonRow(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        val count = measurables.size
        val gap = 16.dp.roundToPx()
        val needed = measurables.map { it.maxIntrinsicWidth(constraints.maxHeight) }
        val width =
            if (constraints.hasBoundedWidth) constraints.maxWidth
            else needed.sum() + gap * (count - 1).coerceAtLeast(0)
        val free = width - gap * (count - 1).coerceAtLeast(0)
        val widths =
            when {
                count == 0 -> emptyList()
                free <= 0 -> null
                needed.all { it <= free / count } -> List(count) { free / count }
                needed.sum() <= free -> {
                    val spare = (free - needed.sum()) / count
                    needed.map { it + spare }
                }
                else -> null
            }
        if (widths != null) {
            val placeables =
                measurables.mapIndexed { i, measurable ->
                    measurable.measure(
                        Constraints(
                            minWidth = widths[i],
                            maxWidth = widths[i],
                            maxHeight = constraints.maxHeight,
                        )
                    )
                }
            val height = placeables.maxOfOrNull { it.height } ?: 0
            layout(width, height.coerceAtLeast(constraints.minHeight)) {
                var x = 0
                placeables.forEach {
                    it.placeRelative(x, (height - it.height) / 2)
                    x += it.width + gap
                }
            }
        } else {
            val stackGap = 8.dp.roundToPx()
            val placeables =
                measurables.map {
                    it.measure(
                        Constraints(
                            minWidth = width,
                            maxWidth = width,
                            maxHeight = constraints.maxHeight,
                        )
                    )
                }
            val height = placeables.sumOf { it.height } + stackGap * (count - 1)
            layout(width, height.coerceAtLeast(constraints.minHeight)) {
                var y = 0
                placeables.forEach {
                    it.placeRelative(0, y)
                    y += it.height + stackGap
                }
            }
        }
    }
}
