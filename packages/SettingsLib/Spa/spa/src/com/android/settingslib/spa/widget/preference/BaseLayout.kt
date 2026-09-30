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

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.android.settingslib.spa.framework.compose.highlightBackground
import com.android.settingslib.spa.framework.compose.thenIf
import com.android.settingslib.spa.framework.theme.SettingsDimension
import com.android.settingslib.spa.framework.theme.SettingsOpacity.alphaForEnabled
import com.android.settingslib.spa.framework.theme.SettingsShape
import com.android.settingslib.spa.framework.theme.SettingsSize
import com.android.settingslib.spa.framework.theme.SettingsSpace
import com.android.settingslib.spa.framework.theme.SettingsTheme
import com.android.settingslib.spa.framework.theme.isSpaExpressiveEnabled
import com.android.settingslib.spa.widget.ui.LocalIsInCategory
import com.android.settingslib.spa.widget.ui.SettingsTitle

@Composable
internal fun BaseLayout(
    title: String,
    subTitle: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    titleContentDescription: String? = null,
    icon: @Composable (() -> Unit)? = null,
    enabled: () -> Boolean = { true },
    paddingStart: Dp = SettingsDimension.itemPaddingStart,
    paddingEnd: Dp = SettingsDimension.itemPaddingEnd,
    paddingVertical: Dp = SettingsDimension.itemPaddingVertical,
    widget: @Composable () -> Unit = {},
) {
    val surfaceBright = MaterialTheme.colorScheme.surfaceBright
    // Tally: a row that a view list hosts (Settings' ComposePreference on a SettingsLib page)
    // takes that list's row metrics and type, so it matches the view rows around it.
    val hostStyle = LocalHostRowStyle.current
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .semantics(mergeDescendants = true) {}
                .thenIf(isSpaExpressiveEnabled && hostStyle == null) {
                    Modifier.heightIn(min = SettingsDimension.preferenceMinHeight)
                }
                .thenIf(isSpaExpressiveEnabled && LocalIsInCategory.current) {
                    Modifier.highlightBackground(
                        originalColor = surfaceBright,
                        shape = SettingsShape.CornerExtraSmall2,
                    )
                }
                .then(hostStyle?.let { Modifier.heightIn(min = it.minHeight) } ?: Modifier)
                .padding(end = hostStyle?.paddingEnd ?: paddingEnd),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val alphaModifier = Modifier.alphaForEnabled(enabled())
        BaseIcon(icon, alphaModifier, paddingStart)
        HostTypography(hostStyle) {
            Titles(
                title = title,
                titleContentDescription = titleContentDescription,
                subTitle = subTitle,
                modifier =
                    alphaModifier
                        .weight(1f)
                        .padding(vertical = hostStyle?.paddingVertical ?: paddingVertical),
            )
        }
        widget()
    }
}

/** Tally: the host list's type for the title and the summary (see [HostRowStyle]). */
@Composable
private fun HostTypography(hostStyle: HostRowStyle?, content: @Composable () -> Unit) {
    if (hostStyle == null) {
        content()
        return
    }
    val typography = MaterialTheme.typography
    MaterialTheme(
        colorScheme = MaterialTheme.colorScheme,
        shapes = MaterialTheme.shapes,
        typography =
            typography.copy(
                titleMedium = typography.titleMedium.merge(hostStyle.titleStyle),
                bodyMedium = typography.bodyMedium.merge(hostStyle.bodyStyle),
            ),
        content = content,
    )
}

@Composable
internal fun BaseIcon(icon: @Composable (() -> Unit)?, modifier: Modifier, paddingStart: Dp) {
    val hostStyle = LocalHostRowStyle.current
    if (hostStyle != null) {
        // Tally: the host list's icon column (see HostRowStyle).
        Spacer(modifier = Modifier.width(width = hostStyle.paddingStart))
        if (icon != null) {
            Box(modifier = modifier.size(hostStyle.iconSize), contentAlignment = Alignment.Center) {
                icon()
            }
            Spacer(modifier = Modifier.width(width = hostStyle.iconGap))
        }
    } else if (isSpaExpressiveEnabled) {
        Spacer(modifier = Modifier.width(width = paddingStart))
        if (icon != null) {
            Box(
                modifier = modifier.size(SettingsSize.medium3),
                contentAlignment = Alignment.Center,
            ) {
                icon()
            }
            Spacer(modifier = Modifier.width(width = SettingsSpace.extraSmall6))
        }
    } else {
        if (icon != null) {
            Box(
                modifier = modifier.size(SettingsDimension.itemIconContainerSize),
                contentAlignment = Alignment.Center,
            ) {
                icon()
            }
        } else {
            Spacer(modifier = Modifier.width(width = paddingStart))
        }
    }
}

// Extracts a scope to avoid frequent recompose outside scope.
@Composable
private fun Titles(
    title: String,
    titleContentDescription: String?,
    subTitle: @Composable () -> Unit,
    modifier: Modifier,
) {
    Column(modifier) {
        SettingsTitle(title, titleContentDescription)
        subTitle()
    }
}

@Preview
@Composable
private fun BaseLayoutPreview() {
    SettingsTheme {
        BaseLayout(title = "Title", subTitle = { HorizontalDivider(thickness = 10.dp) })
    }
}
