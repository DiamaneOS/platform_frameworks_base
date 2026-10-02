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

package com.android.systemui.tally.icons

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.shared.settings.data.repository.SecureSettingsRepository
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** DiamaneOS Tally's icon style, chosen in Wallpaper & style › Icons. */
enum class TallyIconStyle {
    /** DiamaneOS's own apps as keys in their own colours; every other app its own icon. */
    COLOUR,
    /** Every app a monochrome glyph on the theme's key. */
    MINIMAL,
    /** Every app its own icon. */
    NONE;

    companion object {
        /**
         * The secure setting Launcher keeps the style in, for each user (Launcher3's
         * TallyIconStyle): "colour", "minimal" or "none".
         */
        const val SETTING = "tally_icon_style"

        /** The style a value of [SETTING] names; the default, Colour, for none or another value. */
        fun fromSetting(value: String?): TallyIconStyle =
            when (value) {
                "minimal" -> MINIMAL
                "none" -> NONE
                else -> COLOUR
            }
    }
}

/**
 * The current user's icon style, so notifications draw their app icons as that user's Home draws
 * them. Launcher writes the setting and SystemUI only reads it: no permission is added. It is read
 * once something watches [style] (the notification pipeline, only with Tally on), and it is Colour,
 * the default, until then.
 */
@SysUISingleton
class TallyIconStyleRepository
@Inject
constructor(@Application scope: CoroutineScope, secureSettings: SecureSettingsRepository) {

    val style: StateFlow<TallyIconStyle> =
        secureSettings
            .stringSetting(TallyIconStyle.SETTING, null)
            .map { TallyIconStyle.fromSetting(it) }
            .stateIn(scope, SharingStarted.Lazily, TallyIconStyle.COLOUR)
}
