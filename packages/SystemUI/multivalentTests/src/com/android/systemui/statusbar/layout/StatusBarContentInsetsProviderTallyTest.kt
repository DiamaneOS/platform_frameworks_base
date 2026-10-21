/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.statusbar.layout

import android.content.Context
import android.content.res.Configuration
import android.graphics.Rect
import android.view.Display
import android.view.DisplayCutout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.CameraProtectionInfo
import com.android.systemui.SysUICutoutInformation
import com.android.systemui.SysUICutoutProvider
import com.android.systemui.SysuiTestCase
import com.android.systemui.dump.DumpManager
import com.android.systemui.statusbar.commandline.CommandRegistry
import com.android.systemui.statusbar.data.repository.StatusBarConfigurationController
import com.android.systemui.statusbar.phone.ConfigurationControllerImpl
import com.android.systemui.tally.TallyShell
import com.android.systemui.util.leak.RotationUtils.ROTATION_NONE
import com.google.common.truth.Truth.assertThat
import java.util.Locale
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

@RunWith(AndroidJUnit4::class)
@SmallTest
class StatusBarContentInsetsProviderTallyTest : SysuiTestCase() {

    private val sysUICutout = mock<SysUICutoutInformation>()
    private val dc = mock<DisplayCutout>()
    private val contextMock = mock<Context>()
    private val display = mock<Display>()
    private val configuration = Configuration()

    private lateinit var configurationController: StatusBarConfigurationController

    @Before
    fun setup() {
        whenever(sysUICutout.cutout).thenReturn(dc)
        whenever(contextMock.display).thenReturn(display)

        context.ensureTestableResources()
        whenever(contextMock.resources).thenReturn(context.resources)
        whenever(contextMock.resources.configuration).thenReturn(configuration)
        whenever(contextMock.createConfigurationContext(any())).thenAnswer {
            context.createConfigurationContext(it.arguments[0] as Configuration)
        }
        configurationController = ConfigurationControllerImpl(contextMock)
        setNoCutout()
    }

    @Test
    fun tally_onLayoutDirectionChanged_afterStart_listenerNotifiedAndInsetsSwapSides() {
        assumeTrue(TallyShell.isEnabled)
        configuration.windowConfiguration.setMaxBounds(Rect(0, 0, 1080, 2160))
        configuration.setLayoutDirection(Locale.US)
        configurationController.onConfigurationChanged(configuration)
        val provider =
            StatusBarContentInsetsProviderImpl(
                contextMock,
                configurationController,
                mock<DumpManager>(),
                mock<CommandRegistry>(),
                mock<SysUICutoutProvider>(),
                Display.DEFAULT_DISPLAY,
            )
        val listener =
            object : StatusBarContentInsetsChangedListener {
                var triggered = false

                override fun onStatusBarContentInsetsChanged() {
                    triggered = true
                }
            }
        provider.start()
        provider.addCallback(listener)
        val ltrInsets = provider.getStatusBarContentInsetsForRotation(ROTATION_NONE)
        // Tally's privacy dot needs more room at the end than the rounded corner at the start.
        assumeTrue(ltrInsets.left != ltrInsets.right)

        // WHEN the layout direction changes at run time
        configuration.setLayoutDirection(Locale.forLanguageTag("ar"))
        configurationController.onConfigurationChanged(configuration)

        // THEN the listener is notified, and the dot's room is at the new end
        assertThat(listener.triggered).isTrue()
        val rtlInsets = provider.getStatusBarContentInsetsForRotation(ROTATION_NONE)
        assertThat(rtlInsets.left).isEqualTo(ltrInsets.right)
        assertThat(rtlInsets.right).isEqualTo(ltrInsets.left)
    }

    private fun setNoCutout() {
        setCutoutBounds()
    }

    private fun setCutoutBounds(
        left: Rect = Rect(),
        top: Rect = Rect(),
        right: Rect = Rect(),
        bottom: Rect = Rect(),
    ) {
        whenever(dc.boundingRects)
            .thenReturn(listOf(left, top, right, bottom).filter { !it.isEmpty })
        whenever(dc.boundingRectLeft).thenReturn(left)
        whenever(dc.boundingRectTop).thenReturn(top)
        whenever(dc.boundingRectRight).thenReturn(right)
        whenever(dc.boundingRectBottom).thenReturn(bottom)
    }

    private fun setCameraProtectionBounds(protectionBounds: Rect) {
        val protectionInfo = mock<CameraProtectionInfo> { on { bounds } doReturn protectionBounds }
        whenever(sysUICutout.cameraProtection).thenReturn(protectionInfo)
    }

    companion object {
        private const val BOTTOM_ALIGNED_MARGIN_NONE = -1
    }
}
