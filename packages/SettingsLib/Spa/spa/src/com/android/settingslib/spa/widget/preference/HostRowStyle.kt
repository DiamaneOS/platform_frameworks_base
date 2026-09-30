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

package com.android.settingslib.spa.widget.preference

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp

/**
 * Tally: the rows of a view list that hosts a Compose preference row (Settings' ComposePreference
 * on a SettingsLib page). When a host provides it through [LocalHostRowStyle], [BaseLayout] lays
 * the row out with the host's metrics and sets its title and summary in the host's type, so the row
 * matches the view rows around it. Null, the default, keeps Spa's own rows.
 *
 * @property minHeight the row's minimum height
 * @property paddingStart the space before the icon, or before the text when there is no icon
 * @property paddingEnd the space after the widget
 * @property paddingVertical the space above and below the text
 * @property iconSize the icon's box
 * @property iconGap the space between the icon and the text
 * @property titleStyle merged into the title's style
 * @property bodyStyle merged into the summary's style
 */
@Immutable
data class HostRowStyle(
    val minHeight: Dp,
    val paddingStart: Dp,
    val paddingEnd: Dp,
    val paddingVertical: Dp,
    val iconSize: Dp,
    val iconGap: Dp,
    val titleStyle: TextStyle = TextStyle.Default,
    val bodyStyle: TextStyle = TextStyle.Default,
)

/** The [HostRowStyle] of the view list around a Compose row; null outside one. */
val LocalHostRowStyle = staticCompositionLocalOf<HostRowStyle?> { null }
