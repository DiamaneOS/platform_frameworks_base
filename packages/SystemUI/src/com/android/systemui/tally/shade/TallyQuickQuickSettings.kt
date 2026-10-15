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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.compose.animation.scene.ContentScope
import com.android.systemui.compose.modifiers.sysuiResTag
import com.android.systemui.qs.composefragment.ui.GridAnchor
import com.android.systemui.qs.panels.ui.compose.BounceableInfo
import com.android.systemui.qs.panels.ui.compose.TileListener
import com.android.systemui.qs.panels.ui.compose.infinitegrid.Tile
import com.android.systemui.qs.panels.ui.viewmodel.BounceableTileViewModel
import com.android.systemui.qs.panels.ui.viewmodel.QuickQuickSettingsViewModel
import com.android.systemui.qs.shared.ui.QuickSettings.Elements.toElementKey
import de.diamaneos.tally.R as TallyR
import kotlin.math.max
import kotlin.math.min

/**
 * The first pull's tiles in Tally: one row of keycaps (52 dp, 22 % corners) in the user's tile
 * order, as many as fit up to six, spaced evenly across the row (shade.js `STAGE1`, app.css
 * `.sh-qs1`). Each is the upstream tile drawn as a keycap, with the upstream tile's clicks: a tap
 * toggles a tile that has a toggle and otherwise does the tile's main action, as stock's small
 * tiles do. The words are in each key's label for screen readers and in the expanded shade.
 */
@Composable
fun ContentScope.TallyQuickQuickSettings(
    viewModel: QuickQuickSettingsViewModel,
    listening: () -> Boolean,
    modifier: Modifier = Modifier,
) {
    val tiles = viewModel.allTileViewModels
    val squishiness by viewModel.squishinessViewModel.squishiness.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val keySize = dimensionResource(TallyR.dimen.tally_tile_size_small)
    val minGap = dimensionResource(TallyR.dimen.tally_space_xs)

    BoxWithConstraints(modifier.sysuiResTag("qqs_tile_layout")) {
        val width =
            if (constraints.hasBoundedWidth) maxWidth
            else keySize * MAX_KEYS + minGap * (MAX_KEYS - 1)
        val count = keyCount(width, keySize, minGap)
        val shown = remember(tiles, count) { tiles.take(count) }
        val bounceables = remember(shown) { List(shown.size) { BounceableTileViewModel() } }
        // Spaced as if the row were full, so a short row keeps the same rhythm from the start.
        val gap = if (count > 1) (width - keySize * count) / (count - 1) else 0.dp

        GridAnchor()
        Row(horizontalArrangement = Arrangement.spacedBy(gap.coerceAtLeast(minGap))) {
            shown.forEachIndexed { index, tile ->
                Element(tile.spec.toElementKey(), Modifier) {
                    Tile(
                        tile = tile,
                        iconOnly = true,
                        squishiness = { squishiness },
                        coroutineScope = scope,
                        // Keys stand apart, so a pressed key does not push its neighbours.
                        bounceableInfo =
                            BounceableInfo(
                                bounceables[index],
                                previousTile = null,
                                nextTile = null,
                                bounceEnd = false,
                            ),
                        tileHapticsViewModelFactory = viewModel.tileHapticsViewModelFactory,
                        modifier = Modifier.size(keySize),
                        detailsViewModel = null,
                        isVisible = listening,
                    )
                }
            }
        }
        TileListener(shown, listening)
    }
}

private fun keyCount(width: Dp, key: Dp, minGap: Dp): Int {
    val fit = ((width + minGap) / (key + minGap)).toInt()
    return min(MAX_KEYS, max(1, fit))
}

/** The prototype's first pull shows six keys. */
private const val MAX_KEYS = 6
