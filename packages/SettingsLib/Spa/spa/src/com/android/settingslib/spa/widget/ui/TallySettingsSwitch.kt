/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.settingslib.spa.widget.ui

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.android.settingslib.spa.framework.compose.contentDescription
import com.android.settingslib.spa.framework.util.wrapOnSwitchWithLog

/** [SettingsSwitch] as the Tally switch. */
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
@Composable
internal fun TallySettingsSwitch(
    checked: Boolean?,
    changeable: () -> Boolean,
    contentDescription: String?,
    onCheckedChange: ((newChecked: Boolean) -> Unit)?,
    interactionSource: MutableInteractionSource,
) {
    // The Tally switch, with the semantics of the Material switch in SettingsSwitch.
    if (checked != null) {
        TallySwitch(
            checked = checked,
            onCheckedChange = wrapOnSwitchWithLog(onCheckedChange),
            modifier = Modifier.contentDescription(contentDescription),
            enabled = changeable(),
            interactionSource = interactionSource,
        )
    } else {
        TallySwitch(
            checked = false,
            onCheckedChange = null,
            enabled = false,
            interactionSource = interactionSource,
        )
    }
}
