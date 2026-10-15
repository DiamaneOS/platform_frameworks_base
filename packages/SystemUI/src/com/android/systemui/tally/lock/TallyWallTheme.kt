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

package com.android.systemui.tally.lock

import android.content.Context
import android.content.res.Configuration

/**
 * The night mode a lock wallpaper calls for, in which Tally tokens give the lock screen's ink. Which
 * one the wallpaper calls for comes from [TallyLockInk], from the wallpaper's colours: SystemUI's
 * theme is no guide (see there).
 */
object TallyWallTheme {
    /** [context] in the night mode the wallpaper calls for, so Tally tokens resolve to it. */
    @JvmStatic
    fun context(context: Context, lightWallpaper: Boolean): Context {
        val config = Configuration(context.resources.configuration)
        val night =
            if (lightWallpaper) Configuration.UI_MODE_NIGHT_NO else Configuration.UI_MODE_NIGHT_YES
        config.uiMode = (config.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or night
        return context.createConfigurationContext(config)
    }
}
