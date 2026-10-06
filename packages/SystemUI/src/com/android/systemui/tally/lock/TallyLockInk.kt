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

import android.app.WallpaperColors
import android.app.WallpaperManager
import android.content.Context
import android.util.Log
import androidx.annotation.MainThread
import androidx.annotation.VisibleForTesting
import com.android.systemui.colorextraction.SysuiColorExtractor
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.settings.UserTracker
import com.android.systemui.statusbar.policy.ConfigurationController
import com.android.systemui.statusbar.policy.KeyguardStateController
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executor
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The ink of the Tally lock screen (the clock, the date line, the lamp strip, the status bar and
 * the fingerprint messages): dark on a light lock wallpaper, light on a dark one. It follows the
 * lock wallpaper's colours, whatever the theme: a wallpaper that hints dark text (the Paper's light
 * side, a light photo) takes dark ink, every other one light ink. Until the wallpaper has reported
 * its colours, it follows the theme: light ink in dark theme, dark ink in light theme.
 *
 * It is not read from SystemUI's theme, which stock code uses: the application context has the
 * framework's light default theme until CentralSurfaces first sets one, and Theme.SystemUI is
 * light in light theme over any wallpaper, so the theme can say "light" over a dark wallpaper.
 *
 * It is worked out again whenever it can change: when the wallpaper reports new colours (the
 * Paper reports new ones when the theme changes), when the night mode or the theme changes, when
 * the user changes, and when the lock screen shows or the device unlocks. Callers read
 * [isLightWallpaper] or collect [lightWallpaper]; Java callers add a listener.
 */
@SysUISingleton
class TallyLockInk
@Inject
constructor(
    @Application private val appContext: Context,
    private val colorExtractor: SysuiColorExtractor,
    private val wallpaperManager: WallpaperManager,
    @Main private val configurationController: ConfigurationController,
    private val userTracker: UserTracker,
    private val keyguardStateController: KeyguardStateController,
    @Main private val mainExecutor: Executor,
    @Background private val bgExecutor: Executor,
) {
    /** The current user's lock wallpaper colours, or null until the wallpaper reports them. */
    private var colors: WallpaperColors? = lockColors()

    /** Counts the colour extractor's reports, so a slower read for a new user never wins. */
    private var reports = 0

    private val _lightWallpaper = MutableStateFlow(lightWallpaper(colors, isNight()))

    /** Whether the lock wallpaper calls for dark ink; see [TallyLockInk]. */
    val lightWallpaper: StateFlow<Boolean> = _lightWallpaper.asStateFlow()

    /** The current value of [lightWallpaper]. */
    val isLightWallpaper: Boolean
        get() = _lightWallpaper.value

    private val listeners = CopyOnWriteArraySet<Runnable>()

    init {
        Log.i(TAG, "lightWallpaper=$isLightWallpaper (start, colors=$colors, night=${isNight()})")
        colorExtractor.addOnColorsChangedListener { _, _ ->
            reports++
            colors = lockColors()
            update("colors")
        }
        configurationController.addCallback(
            object : ConfigurationController.ConfigurationListener {
                override fun onUiModeChanged() = update("uiMode")

                override fun onThemeChanged() = update("theme")
            }
        )
        userTracker.addCallback(
            object : UserTracker.Callback {
                override fun onUserChanged(newUser: Int, userContext: Context) {
                    // The colour extractor may still hold the last user's colours: read the new
                    // user's, and until they come follow the theme.
                    colors = null
                    update("user")
                    readColorsFor(newUser)
                }
            },
            mainExecutor,
        )
        keyguardStateController.addCallback(
            object : KeyguardStateController.Callback {
                override fun onKeyguardShowingChanged() = update("showing")

                override fun onUnlockedChanged() = update("unlocked")
            }
        )
    }

    /** Calls [listener] on the main thread whenever [isLightWallpaper] changes. */
    fun addListener(listener: Runnable) {
        listeners += listener
    }

    fun removeListener(listener: Runnable) {
        listeners -= listener
    }

    @MainThread
    private fun update(reason: String) {
        val light = lightWallpaper(colors, isNight())
        if (light == _lightWallpaper.value) return
        Log.i(TAG, "lightWallpaper=$light ($reason, colors=$colors, night=${isNight()})")
        _lightWallpaper.value = light
        listeners.forEach { it.run() }
    }

    private fun readColorsFor(userId: Int) {
        if (!wallpaperManager.isWallpaperSupported) return
        val readAfter = reports
        bgExecutor.execute {
            val read = wallpaperManager.getWallpaperColors(WallpaperManager.FLAG_LOCK, userId)
            mainExecutor.execute {
                // A report the extractor heard in the meantime is newer.
                if (userTracker.userId != userId || reports != readAfter) return@execute
                colors = read
                update("user colors")
            }
        }
    }

    /** The lock wallpaper's colours (the home wallpaper's when they share one), if reported. */
    private fun lockColors(): WallpaperColors? =
        colorExtractor.getWallpaperColors(WallpaperManager.FLAG_LOCK)
            ?: colorExtractor.getWallpaperColors(WallpaperManager.FLAG_SYSTEM)

    private fun isNight(): Boolean = appContext.resources.configuration.isNightModeActive

    companion object {
        private const val TAG = "TallyLockInk"

        /**
         * Whether a lock wallpaper with [colors] takes dark ink: when its colours hint dark text;
         * with no colours yet, in light theme ([night] false).
         */
        @VisibleForTesting
        @JvmStatic
        fun lightWallpaper(colors: WallpaperColors?, night: Boolean): Boolean =
            if (colors != null) {
                (colors.colorHints and WallpaperColors.HINT_SUPPORTS_DARK_TEXT) != 0
            } else {
                !night
            }
    }
}
