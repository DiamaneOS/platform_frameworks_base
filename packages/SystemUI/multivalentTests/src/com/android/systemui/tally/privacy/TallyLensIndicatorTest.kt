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

package com.android.systemui.tally.privacy

import android.Manifest
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.graphics.Path
import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.view.Display
import android.view.DisplayCutout
import android.view.DisplayInfo
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.privacy.PrivacyApplication
import com.android.systemui.privacy.PrivacyItem
import com.android.systemui.privacy.PrivacyItemController
import com.android.systemui.privacy.PrivacyType
import com.android.systemui.settings.DisplayTracker
import com.android.systemui.tally.TallyShell
import java.util.concurrent.Executor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * The lens ring lights only while an app uses a camera and the front camera is open, counts a
 * camera whose facing cannot be read as the front one, and lights for any camera where SystemUI
 * cannot follow cameras opening. The ring's window is added only when something lights.
 */
@SmallTest
@RunWith(AndroidJUnit4::class)
class TallyLensIndicatorTest : SysuiTestCase() {
    private val windowManager = mock<WindowManager>()
    private val cameraManager = mock<CameraManager>()
    private val privacyItemController = mock<PrivacyItemController>()
    private val display = mock<Display>()
    private val displayTracker =
        mock<DisplayTracker> {
            on { defaultDisplayId } doReturn Display.DEFAULT_DISPLAY
            on { getDisplay(Display.DEFAULT_DISPLAY) } doReturn display
        }
    private val indicatorArea =
        mock<TallyIndicatorArea> { on { isOverlayAreaDark } doReturn MutableStateFlow(true) }

    @Before
    fun setUp() {
        // The indicator starts only while Tally is on; tests take the build's flag.
        assumeTrue(TallyShell.isEnabled)
        // A punch hole at the top of a 1080 x 2400 display.
        val cutout =
            mock<DisplayCutout> {
                on { boundingRects } doReturn listOf(Rect(510, 20, 570, 80))
                on { cutoutPath } doReturn
                    Path().apply { addCircle(540f, 50f, 30f, Path.Direction.CW) }
            }
        whenever(display.getDisplayInfo(any())).thenAnswer {
            (it.arguments[0] as DisplayInfo).apply {
                displayCutout = cutout
                rotation = Surface.ROTATION_0
                logicalWidth = 1080
                logicalHeight = 2400
                logicalDensityDpi = 480
            }
            true
        }
    }

    @Test
    fun frontCameraOpen_cameraInUse_lightsTheRing() {
        facing(FRONT_ID, CameraCharacteristics.LENS_FACING_FRONT)
        start(followsCameras = true)

        cameraCallback().onCameraOpened(FRONT_ID, CAMERA_APP)
        privacyCallback().onPrivacyItemsChanged(listOf(cameraInUse()))

        verify(windowManager).addView(any<TallyLensIndicatorView>(), any<ViewGroup.LayoutParams>())
    }

    @Test
    fun backCameraOnly_leavesTheRingOff() {
        facing(BACK_ID, CameraCharacteristics.LENS_FACING_BACK)
        start(followsCameras = true)

        cameraCallback().onCameraOpened(BACK_ID, CAMERA_APP)
        privacyCallback().onPrivacyItemsChanged(listOf(cameraInUse()))

        verify(windowManager, never()).addView(any<View>(), any<ViewGroup.LayoutParams>())
    }

    @Test
    fun unknownFacing_countsAsTheFrontCamera() {
        // Characteristics that do not say which way the camera faces.
        whenever(cameraManager.getCameraCharacteristics(UNKNOWN_ID)).thenReturn(mock())
        start(followsCameras = true)

        cameraCallback().onCameraOpened(UNKNOWN_ID, CAMERA_APP)
        privacyCallback().onPrivacyItemsChanged(listOf(cameraInUse()))

        verify(windowManager).addView(any<TallyLensIndicatorView>(), any<ViewGroup.LayoutParams>())
    }

    @Test
    fun noOpenCloseReports_anyCameraInUseLightsTheRing() {
        start(followsCameras = false)

        privacyCallback().onPrivacyItemsChanged(listOf(cameraInUse()))

        verify(cameraManager, never())
            .registerAvailabilityCallback(
                any<Executor>(),
                any<CameraManager.AvailabilityCallback>(),
            )
        verify(windowManager).addView(any<TallyLensIndicatorView>(), any<ViewGroup.LayoutParams>())
    }

    /** Starts an indicator whose context holds the open and close listener permission or not. */
    private fun start(followsCameras: Boolean) {
        val permissionContext =
            object : ContextWrapper(context) {
                override fun checkSelfPermission(permission: String): Int =
                    when {
                        permission != Manifest.permission.CAMERA_OPEN_CLOSE_LISTENER ->
                            super.checkSelfPermission(permission)
                        followsCameras -> PackageManager.PERMISSION_GRANTED
                        else -> PackageManager.PERMISSION_DENIED
                    }
            }
        TallyLensIndicator(
                permissionContext,
                Executor { it.run() },
                TestScope(),
                windowManager,
                displayTracker,
                privacyItemController,
                indicatorArea,
                cameraManager,
            )
            .start()
    }

    private fun facing(cameraId: String, facing: Int) {
        val characteristics =
            mock<CameraCharacteristics> {
                on { get(CameraCharacteristics.LENS_FACING) } doReturn facing
            }
        whenever(cameraManager.getCameraCharacteristics(cameraId)).thenReturn(characteristics)
    }

    private fun cameraCallback(): CameraManager.AvailabilityCallback =
        argumentCaptor<CameraManager.AvailabilityCallback>()
            .apply {
                verify(cameraManager).registerAvailabilityCallback(any<Executor>(), capture())
            }
            .firstValue

    private fun privacyCallback(): PrivacyItemController.Callback =
        argumentCaptor<PrivacyItemController.Callback>()
            .apply { verify(privacyItemController).addCallback(capture()) }
            .firstValue

    private fun cameraInUse() =
        PrivacyItem(PrivacyType.TYPE_CAMERA, PrivacyApplication(CAMERA_APP, 10123))

    private companion object {
        const val BACK_ID = "0"
        const val FRONT_ID = "1"
        const val UNKNOWN_ID = "2"
        const val CAMERA_APP = "com.example.camera"
    }
}
