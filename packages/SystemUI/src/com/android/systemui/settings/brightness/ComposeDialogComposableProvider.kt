/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.settings.brightness

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.android.compose.theme.PlatformTheme
import com.android.systemui.brightness.ui.compose.BrightnessSliderContainer
import com.android.systemui.brightness.ui.compose.BrightnessSliderDimensions
import com.android.systemui.brightness.ui.compose.ContainerColors
import com.android.systemui.brightness.ui.viewmodel.BrightnessSliderViewModel
import com.android.systemui.lifecycle.rememberViewModel
import com.android.systemui.tally.TallyShell
import de.diamaneos.tally.R as TallyR

object ComposeDialogComposableProvider {

    fun setComposableBrightness(composeView: ComposeView, content: ComposableProvider) {
        composeView.apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { PlatformTheme { content.ProvideComposableContent() } }
        }
    }
}

@Composable
private fun BrightnessSliderForDialog(
    brightnessSliderViewModelFactory: BrightnessSliderViewModel.Factory,
    dimensions: BrightnessSliderDimensions = BrightnessSliderDimensions.Default,
) {
    val viewModel =
        rememberViewModel(traceName = "BrightnessDialog.viewModel") {
            brightnessSliderViewModelFactory.create(false)
        }
    // Tally: the slider floats over the page on a Tally panel, with the card radius, where the
    // shade's background (the default frame) is the page's own colour and so showed no panel.
    val tally = TallyShell.isEnabled
    BrightnessSliderContainer(
        viewModel = viewModel,
        containerColors =
            if (tally) {
                ContainerColors.tallyPanelColors
            } else {
                ContainerColors.singleColor(ContainerColors.defaultContainerColor)
            },
        modifier = Modifier.fillMaxWidth().padding(8.dp),
        dimensions =
            if (tally) {
                dimensions.copy(
                    backgroundRoundedCorner = dimensionResource(TallyR.dimen.tally_radius_m)
                )
            } else {
                dimensions
            },
    )
}

class ComposableProvider(
    private val brightnessSliderViewModelFactory: BrightnessSliderViewModel.Factory,
    private val isExpandedAudioTileDetailsEnabled: Boolean,
) {
    @Composable
    fun ProvideComposableContent() {
        BrightnessSliderForDialog(
            brightnessSliderViewModelFactory = brightnessSliderViewModelFactory,
            dimensions =
                if (isExpandedAudioTileDetailsEnabled) {
                    expandedAudioDialogDimensions
                } else {
                    BrightnessSliderDimensions.Default
                },
        )
    }
}

private val expandedAudioDialogDimensions =
    BrightnessSliderDimensions(
        iconSize = DpSize(24.dp, 24.dp),
        thumbHeight = 40.dp,
        thumbWidth = 3.dp,
        trackHeight = 32.dp,
        verticalPadding = (-4).dp,
        backgroundRoundedCorner = 28.dp,
        backgroundFrameWidth = 12.dp,
        backgroundFrameHeight = 4.dp,
    )
