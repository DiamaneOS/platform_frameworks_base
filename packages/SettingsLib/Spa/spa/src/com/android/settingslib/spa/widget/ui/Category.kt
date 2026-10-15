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

package com.android.settingslib.spa.widget.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.DeviceFontFamilyName
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.android.settingslib.spa.framework.compose.thenIf
import com.android.settingslib.spa.framework.theme.SettingsDimension
import com.android.settingslib.spa.framework.theme.SettingsShape
import com.android.settingslib.spa.framework.theme.SettingsSpace
import com.android.settingslib.spa.framework.theme.SettingsTheme
import com.android.settingslib.spa.framework.theme.isSpaExpressiveEnabled
import com.android.settingslib.spa.widget.preference.Preference
import com.android.settingslib.spa.widget.preference.PreferenceModel
import java.util.Locale

/**
 * A category title that is placed before a group of similar items.
 *
 * Tally: the section title of SettingsLib's pages (SettingsTheme's TextAppearance.SettingsLib.Tally
 * .Section): small muted capitals, lined up with the rows' text. Colour is spent only where
 * something is on, so the title is not in the accent. A screen reader still reads the title as
 * written.
 */
@Composable
fun CategoryTitle(title: String) {
    val locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    Text(
        text = title.uppercase(locale),
        modifier =
            Modifier.padding(
                    start = SettingsDimension.itemPaddingStart,
                    top = SettingsSpace.small3,
                    end = SettingsDimension.itemPaddingEnd,
                    bottom = SettingsSpace.extraSmall4,
                )
                .clearAndSetSemantics { text = AnnotatedString(title) },
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = TallySectionTitleStyle,
    )
}

/**
 * Tally's section type (the token spec's section role, as SettingsTheme's values-v36/tally_rows.xml
 * writes it out): 11 sp at weight 500 with 0.06 em tracking on a 16 sp line, in Sofia Sans.
 */
private val TallySectionTitleStyle =
    TextStyle(
        fontFamily = FontFamily(Font(DeviceFontFamilyName("sofia-sans"), FontWeight.Medium)),
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.06.em,
    )

/**
 * A container that is used to group similar items. A [Category] displays a [CategoryTitle] and
 * visually separates groups of items.
 *
 * @param content The content of the category.
 */
@Composable
fun Category(
    title: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    var displayTitle by remember { mutableStateOf(false) }
    Column(
        modifier =
            Modifier.thenIf(isSpaExpressiveEnabled && displayTitle) {
                Modifier.padding(
                    horizontal = SettingsSpace.small1,
                    vertical = SettingsSpace.extraSmall4,
                )
            }
    ) {
        if (title != null && displayTitle) CategoryTitle(title = title)
        Column(
            modifier =
                modifier
                    .onGloballyPositioned { coordinates ->
                        displayTitle = coordinates.size.height > 0
                    }
                    .thenIf(isSpaExpressiveEnabled) {
                        Modifier.fillMaxWidth().clip(SettingsShape.CornerLarge2)
                    },
            verticalArrangement =
                if (isSpaExpressiveEnabled) Arrangement.spacedBy(SettingsSpace.extraSmall1)
                else Arrangement.Top,
            content = { CompositionLocalProvider(LocalIsInCategory provides true) { content() } },
        )
    }
}

/** LocalIsInCategory containing the if the current composable is in a category. */
internal val LocalIsInCategory = staticCompositionLocalOf { false }

@Preview
@Composable
private fun CategoryPreview() {
    SettingsTheme {
        Category(title = "Appearance") {
            Preference(
                object : PreferenceModel {
                    override val title = "Title"
                    override val summary = { "Summary" }
                }
            )
            Preference(
                object : PreferenceModel {
                    override val title = "Title"
                    override val summary = { "Summary" }
                    override val icon =
                        @Composable { SettingsIcon(imageVector = Icons.Outlined.TouchApp) }
                }
            )
        }
    }
}
