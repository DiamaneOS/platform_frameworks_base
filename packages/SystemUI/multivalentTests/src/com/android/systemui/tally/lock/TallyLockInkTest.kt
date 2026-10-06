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
import android.content.res.Configuration
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.internal.colorextraction.ColorExtractor
import com.android.systemui.SysuiTestCase
import com.android.systemui.colorextraction.SysuiColorExtractor
import com.android.systemui.settings.UserTracker
import com.android.systemui.statusbar.policy.ConfigurationController
import com.android.systemui.statusbar.policy.KeyguardStateController
import com.android.systemui.util.concurrency.FakeExecutor
import com.android.systemui.util.time.FakeSystemClock
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * The lock screen's ink: from the lock wallpaper's colours in either theme, from the theme only
 * until the colours are known, and kept current as colours, theme and user change.
 */
@SmallTest
@RunWith(AndroidJUnit4::class)
class TallyLockInkTest : SysuiTestCase() {
    private val extractor = mock<SysuiColorExtractor>()
    private val wallpaperManager =
        mock<WallpaperManager> { on { isWallpaperSupported } doReturn true }
    private val configurationController = mock<ConfigurationController>()
    private val userTracker = mock<UserTracker> { on { userId } doReturn USER }
    private val keyguardStateController = mock<KeyguardStateController>()
    private val mainExecutor = FakeExecutor(FakeSystemClock())
    private val bgExecutor = FakeExecutor(FakeSystemClock())

    private fun ink(night: Boolean) =
        TallyLockInk(
            themed(night),
            extractor,
            wallpaperManager,
            configurationController,
            userTracker,
            keyguardStateController,
            mainExecutor,
            bgExecutor,
        )

    private fun themed(night: Boolean): Context {
        val config = Configuration(context.resources.configuration)
        config.uiMode =
            (config.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        return context.createConfigurationContext(config)
    }

    private fun reportLockColors(colors: WallpaperColors?) {
        whenever(extractor.getWallpaperColors(WallpaperManager.FLAG_LOCK)).thenReturn(colors)
    }

    @Test
    fun darkWallpaper_lightInkInBothThemes() {
        reportLockColors(DARK)

        assertThat(ink(night = true).isLightWallpaper).isFalse()
        assertThat(ink(night = false).isLightWallpaper).isFalse()
    }

    @Test
    fun lightWallpaper_darkInkInBothThemes() {
        reportLockColors(LIGHT)

        assertThat(ink(night = true).isLightWallpaper).isTrue()
        assertThat(ink(night = false).isLightWallpaper).isTrue()
    }

    @Test
    fun noColorsYet_followsTheTheme() {
        assertThat(ink(night = true).isLightWallpaper).isFalse()
        assertThat(ink(night = false).isLightWallpaper).isTrue()
    }

    @Test
    fun sharedWallpaper_usesTheHomeWallpapersColors() {
        whenever(extractor.getWallpaperColors(WallpaperManager.FLAG_SYSTEM)).thenReturn(LIGHT)

        assertThat(ink(night = true).isLightWallpaper).isTrue()
    }

    @Test
    fun newColors_update_andTellListeners() {
        reportLockColors(DARK)
        val ink = ink(night = false)
        var told = 0
        ink.addListener { told++ }
        val listener = argumentCaptor<ColorExtractor.OnColorsChangedListener>()
        verify(extractor).addOnColorsChangedListener(listener.capture())

        // The Paper turns light with the theme.
        reportLockColors(LIGHT)
        listener.firstValue.onColorsChanged(extractor, WallpaperManager.FLAG_LOCK)

        assertThat(ink.isLightWallpaper).isTrue()
        assertThat(ink.lightWallpaper.value).isTrue()
        assertThat(told).isEqualTo(1)

        // Nothing new: listeners are not told again.
        listener.firstValue.onColorsChanged(extractor, WallpaperManager.FLAG_LOCK)
        assertThat(told).isEqualTo(1)
    }

    @Test
    fun userSwitch_readsTheNewUsersColors() {
        reportLockColors(DARK)
        val ink = ink(night = true)
        whenever(wallpaperManager.getWallpaperColors(eq(WallpaperManager.FLAG_LOCK), eq(OTHER)))
            .thenReturn(LIGHT)
        whenever(userTracker.userId).thenReturn(OTHER)
        val callback = argumentCaptor<UserTracker.Callback>()
        verify(userTracker).addCallback(callback.capture(), any())

        callback.firstValue.onUserChanged(OTHER, context)
        assertThat(ink.isLightWallpaper).isFalse()

        bgExecutor.runAllReady()
        mainExecutor.runAllReady()
        assertThat(ink.isLightWallpaper).isTrue()
    }

    @Test
    fun userSwitch_newerReportWins() {
        reportLockColors(DARK)
        val ink = ink(night = true)
        whenever(wallpaperManager.getWallpaperColors(eq(WallpaperManager.FLAG_LOCK), eq(OTHER)))
            .thenReturn(DARK)
        whenever(userTracker.userId).thenReturn(OTHER)
        val callback = argumentCaptor<UserTracker.Callback>()
        verify(userTracker).addCallback(callback.capture(), any())
        val listener = argumentCaptor<ColorExtractor.OnColorsChangedListener>()
        verify(extractor).addOnColorsChangedListener(listener.capture())

        callback.firstValue.onUserChanged(OTHER, context)
        bgExecutor.runAllReady()
        reportLockColors(LIGHT)
        listener.firstValue.onColorsChanged(extractor, WallpaperManager.FLAG_LOCK)
        mainExecutor.runAllReady()

        assertThat(ink.isLightWallpaper).isTrue()
    }

    private companion object {
        const val USER = 0
        const val OTHER = 11
        val LIGHT =
            WallpaperColors(
                Color.valueOf(0xFFD3CCC2.toInt()),
                null,
                null,
                WallpaperColors.HINT_SUPPORTS_DARK_TEXT,
            )
        val DARK =
            WallpaperColors(
                Color.valueOf(0xFF100E0B.toInt()),
                null,
                null,
                WallpaperColors.HINT_SUPPORTS_DARK_THEME,
            )
    }
}
