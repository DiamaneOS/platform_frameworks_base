/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.screenshot

import android.net.Uri
import android.os.Process
import android.os.UserHandle
import android.platform.test.annotations.EnableFlags
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.internal.logging.uiEventLoggerFake
import com.android.systemui.Flags as SysuiFlags
import com.android.systemui.SysuiTestCase
import com.android.systemui.kosmos.runTest
import com.android.systemui.res.R
import com.android.systemui.screencapture.data.repository.fakeScreenCaptureDeviceStateRepository
import com.android.systemui.screenshot.ui.viewmodel.ActionButtonAppearance
import com.android.systemui.screenshot.ui.viewmodel.PreviewAction
import com.android.systemui.testKosmosNew
import com.google.common.truth.Truth.assertThat
import java.util.UUID
import kotlin.test.Test
import org.junit.Before
import org.junit.runner.RunWith
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify

@RunWith(AndroidJUnit4::class)
class DefaultScreenshotActionsProviderDeleteTest : SysuiTestCase() {
    private val kosmos = testKosmosNew()
    private val actionExecutor = mock<ActionExecutor>()
    private val actionsCallback = mock<ScreenshotActionsController.ActionsCallback>()

    private val request = ScreenshotData.forTesting(userHandle = UserHandle.OWNER)
    private val validResult =
        ScreenshotSavedResult(Uri.parse("test://uri"), Process.myUserHandle(), 0)

    private lateinit var actionsProvider: ScreenshotActionsProvider

    @Before
    fun setUp() {
        kosmos.uiEventLoggerFake.logs.clear()
        // Default to small screen.
        kosmos.fakeScreenCaptureDeviceStateRepository.setLargeScreen(false)
    }

    @Test
    @EnableFlags(SysuiFlags.FLAG_LARGE_SCREEN_SCREENCAPTURE)
    fun deleteAction_smallScreen_includesButton() =
        kosmos.runTest {
            actionsProvider = createActionsProvider()

            val actionButtonAppearanceCaptor = argumentCaptor<ActionButtonAppearance>()
            verify(actionsCallback, atLeastOnce())
                .provideActionButton(actionButtonAppearanceCaptor.capture(), any(), any())

            val buttonDescriptions = actionButtonAppearanceCaptor.allValues.map { it.description }
            assertThat(buttonDescriptions)
                .contains(context.getString(R.string.tally_screenshot_delete_description))
        }

    @Test
    @EnableFlags(SysuiFlags.FLAG_LARGE_SCREEN_SCREENCAPTURE)
    fun deleteAction_largeScreen_doesNotIncludeButton() =
        kosmos.runTest {
            fakeScreenCaptureDeviceStateRepository.setLargeScreen(true)
            actionsProvider = createActionsProvider()

            val actionButtonAppearanceCaptor = argumentCaptor<ActionButtonAppearance>()
            verify(actionsCallback, atLeastOnce())
                .provideActionButton(actionButtonAppearanceCaptor.capture(), any(), any())

            val buttonDescriptions = actionButtonAppearanceCaptor.allValues.map { it.description }
            assertThat(buttonDescriptions)
                .doesNotContain(context.getString(R.string.tally_screenshot_delete_description))
        }

    @Test
    @EnableFlags(SysuiFlags.FLAG_LARGE_SCREEN_SCREENCAPTURE)
    fun deleteAction_beforeScreenshotCompleted_deletesOnceSaved() =
        kosmos.runTest {
            actionsProvider = createActionsProvider()

            val actionButtonCaptor = argumentCaptor<() -> Unit>()
            // share, edit, delete
            verify(actionsCallback, times(3))
                .provideActionButton(any(), any(), actionButtonCaptor.capture())
            actionButtonCaptor.thirdValue.invoke()
            verify(actionExecutor, never()).deleteScreenshot(any())

            actionsProvider.setCompletedScreenshot(validResult)

            verify(actionExecutor).deleteScreenshot(eq(validResult.uri))
            verify(actionExecutor, never()).startSharedTransition(any(), any(), any())
        }

    @Test
    @EnableFlags(SysuiFlags.FLAG_LARGE_SCREEN_SCREENCAPTURE)
    fun deleteAction_withResult_deletesScreenshot() =
        kosmos.runTest {
            actionsProvider = createActionsProvider()
            actionsProvider.setCompletedScreenshot(validResult)

            val actionButtonCaptor = argumentCaptor<() -> Unit>()
            // share, edit, delete
            verify(actionsCallback, times(3))
                .provideActionButton(any(), any(), actionButtonCaptor.capture())
            actionButtonCaptor.thirdValue.invoke()

            verify(actionExecutor).deleteScreenshot(eq(validResult.uri))
        }

    @Test
    @EnableFlags(SysuiFlags.FLAG_LARGE_SCREEN_SCREENCAPTURE)
    fun actions_afterScreenshotDeleted_doNothing() =
        kosmos.runTest {
            actionsProvider = createActionsProvider()
            actionsProvider.setCompletedScreenshot(validResult)

            actionsProvider.onScreenshotDeleted()

            val previewActionCaptor = argumentCaptor<PreviewAction>()
            verify(actionsCallback).providePreviewAction(previewActionCaptor.capture())
            val actionButtonCaptor = argumentCaptor<() -> Unit>()
            // share, edit, delete
            verify(actionsCallback, times(3))
                .provideActionButton(any(), any(), actionButtonCaptor.capture())
            previewActionCaptor.firstValue.onClick.invoke()
            actionButtonCaptor.allValues.forEach { it.invoke() }

            verifyNoMoreInteractions(actionExecutor)
        }

    @Test
    @EnableFlags(SysuiFlags.FLAG_LARGE_SCREEN_SCREENCAPTURE)
    fun pendingAction_whenScreenshotDeleted_isDropped() =
        kosmos.runTest {
            actionsProvider = createActionsProvider()

            val actionButtonCaptor = argumentCaptor<() -> Unit>()
            // share, edit, delete
            verify(actionsCallback, times(3))
                .provideActionButton(any(), any(), actionButtonCaptor.capture())
            // Edit, tapped before saving finished.
            actionButtonCaptor.secondValue.invoke()
            actionsProvider.onScreenshotDeleted()
            actionsProvider.setCompletedScreenshot(validResult)

            verifyNoMoreInteractions(actionExecutor)
        }

    private fun createActionsProvider(): ScreenshotActionsProvider {
        return kosmos.screenshotActionsProviderFactory.create(
            UUID.randomUUID(),
            request,
            actionExecutor,
            actionsCallback,
        )
    }
}
