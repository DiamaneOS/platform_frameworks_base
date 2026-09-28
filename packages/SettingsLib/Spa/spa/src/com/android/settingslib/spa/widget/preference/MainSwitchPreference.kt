/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.settingslib.spa.widget.preference

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.settingslib.spa.R
import com.android.settingslib.spa.framework.theme.SettingsShape
import com.android.settingslib.spa.framework.theme.SettingsSpace
import com.android.settingslib.spa.framework.theme.SettingsTheme
import com.android.settingslib.spa.framework.theme.isSpaExpressiveEnabled

@Composable
fun MainSwitchPreference(model: SwitchPreferenceModel) {
    MainSwitchPreference(model = model, modifier = Modifier)
}

@Composable
internal fun MainSwitchPreference(model: SwitchPreferenceModel, modifier: Modifier) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        TallyMainSwitchRow(model, modifier)
        return
    }
    Surface(
        modifier = Modifier.padding(SettingsSpace.small1),
        color = MaterialTheme.colorScheme.primaryContainer,
        shape =
            if (isSpaExpressiveEnabled) SettingsShape.CornerFull
            else SettingsShape.CornerExtraLarge1,
    ) {
        InternalSwitchPreference(
            title = model.title,
            modifier = modifier,
            checked = model.checked(),
            changeable = model.changeable(),
            onCheckedChange = model.onCheckedChange,
            paddingStart = if (isSpaExpressiveEnabled) SettingsSpace.medium1 else 20.dp,
            paddingEnd = SettingsSpace.small3,
            paddingVertical = if (isSpaExpressiveEnabled) SettingsSpace.small1 else 24.dp,
        )
    }
}

/**
 * The main switch as a Tally row, in place of the coloured pill: the row in a card of its own, in
 * the surface colour with a hairline edge, and the Tally switch at its end, as the prototype's
 * switch rows in their Settings section. Only its look differs; the row is the same switch
 * preference, with the same semantics.
 */
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
@Composable
private fun TallyMainSwitchRow(model: SwitchPreferenceModel, modifier: Modifier) {
    val padding = dimensionResource(R.dimen.settingslib_tally_row_padding)
    Surface(
        modifier = Modifier.padding(SettingsSpace.small1),
        color = colorResource(R.color.settingslib_tally_surface),
        contentColor = colorResource(R.color.settingslib_tally_ink),
        shape = RoundedCornerShape(dimensionResource(R.dimen.settingslib_tally_row_radius)),
        border =
            BorderStroke(
                dimensionResource(R.dimen.settingslib_tally_row_hairline),
                colorResource(R.color.settingslib_tally_outline_variant),
            ),
    ) {
        InternalSwitchPreference(
            title = model.title,
            modifier = modifier,
            checked = model.checked(),
            changeable = model.changeable(),
            onCheckedChange = model.onCheckedChange,
            paddingStart = padding,
            paddingEnd = padding,
            paddingVertical = if (isSpaExpressiveEnabled) SettingsSpace.small1 else 24.dp,
        )
    }
}

@Preview
@Composable
private fun MainSwitchPreferencePreview() {
    SettingsTheme {
        Column {
            MainSwitchPreference(
                object : SwitchPreferenceModel {
                    override val title = "Use Dark theme"
                    override val checked = { true }
                    override val onCheckedChange: (Boolean) -> Unit = {}
                }
            )
            MainSwitchPreference(
                object : SwitchPreferenceModel {
                    override val title = "Use Dark theme"
                    override val checked = { false }
                    override val onCheckedChange: (Boolean) -> Unit = {}
                }
            )
        }
    }
}
