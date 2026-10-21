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

package com.android.systemui.screenshot

import android.app.PendingIntent
import android.content.ClipboardManager
import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.UserManager
import android.view.Window
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.user.data.repository.FakeUserRepository
import com.android.systemui.user.data.repository.UserIconProvider
import com.android.systemui.user.utils.UserScopedService
import com.google.common.truth.Truth.assertThat
import kotlin.test.Test
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.runner.RunWith
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@RunWith(AndroidJUnit4::class)
@SmallTest
class ActionExecutorDeleteTest : SysuiTestCase() {
    private val scheduler = TestCoroutineScheduler()
    private val mainDispatcher = StandardTestDispatcher(scheduler)
    private val testScope = TestScope(mainDispatcher)

    private val intentExecutor = mock<ActionIntentExecutor>()
    private val userManager = mock<UserManager>()
    private lateinit var userRepository: FakeUserRepository
    private val clipboardManagerService = mock<UserScopedService<ClipboardManager>>()
    private val clipboardManager = mock<ClipboardManager>()
    private val window = mock<Window>()
    private val viewProxy = mock<ScreenshotShelfViewProxy>()
    private val onDismiss = mock<(() -> Unit)>()
    private val pendingIntent = mock<PendingIntent>()
    private val fakeContext = mock<Context>()
    private val contentResolver = mock<ContentResolver>()

    private lateinit var actionExecutor: ActionExecutor

    @Before
    fun setUp() {
        userRepository = FakeUserRepository(UserIconProvider(context, userManager, mainDispatcher))
    }

    @Test
    fun deleteScreenshot_deletesUriAndRequestsDismissal() = runTest {
        whenever(fakeContext.contentResolver).thenReturn(contentResolver)
        val uri = Uri.parse("content://media/external/images/media/123")
        whenever(contentResolver.delete(eq(uri), anyOrNull<Bundle>())).thenReturn(1)
        actionExecutor = createActionExecutor()

        actionExecutor.deleteScreenshot(uri)
        scheduler.advanceUntilIdle()

        verify(viewProxy).requestDismissal(null)
        verify(contentResolver).delete(eq(uri), anyOrNull<Bundle>())
    }

    @Test
    fun deleteScreenshot_whenDeleteFails_stillDismisses() = runTest {
        whenever(fakeContext.contentResolver).thenReturn(contentResolver)
        val uri = Uri.parse("content://media/external/images/media/123")
        whenever(contentResolver.delete(eq(uri), anyOrNull<Bundle>()))
            .thenThrow(SecurityException("no access"))
        actionExecutor = createActionExecutor()

        actionExecutor.deleteScreenshot(uri)
        scheduler.advanceUntilIdle()

        verify(viewProxy).requestDismissal(null)
    }

    @Test
    fun sharedTransition_hideSharedElements_dismisses() {
        actionExecutor = createActionExecutor()

        val callbacks = actionExecutor.startTransitionCallbacks()
        assertThat(actionExecutor.isPendingSharedTransition).isTrue()
        callbacks.hideSharedElements()
        callbacks.onFinish()

        verify(onDismiss, times(1)).invoke()
        assertThat(actionExecutor.isPendingSharedTransition).isFalse()
    }

    @Test
    fun sharedTransition_cancelledByOpenedApp_dismisses() {
        actionExecutor = createActionExecutor()

        val callbacks = actionExecutor.startTransitionCallbacks()
        callbacks.onFinish()

        verify(onDismiss).invoke()
        assertThat(actionExecutor.isPendingSharedTransition).isFalse()
    }

    @Test
    fun sharedTransition_afterPreviewDismissed_lateCallbacksDoNothing() {
        actionExecutor = createActionExecutor()

        val callbacks = actionExecutor.startTransitionCallbacks()
        actionExecutor.onPreviewDismissed()
        assertThat(actionExecutor.isPendingSharedTransition).isFalse()
        callbacks.hideSharedElements()
        callbacks.onFinish()

        verify(onDismiss, never()).invoke()
    }

    @Test
    fun sharedTransition_olderTransitionCallbacks_doNotEndNewerOne() {
        actionExecutor = createActionExecutor()

        val older = actionExecutor.startTransitionCallbacks()
        actionExecutor.onPreviewDismissed()
        val newer = actionExecutor.startTransitionCallbacks()
        older.onFinish()

        verify(onDismiss, never()).invoke()
        assertThat(actionExecutor.isPendingSharedTransition).isTrue()

        newer.hideSharedElements()
        verify(onDismiss).invoke()
    }

    private fun createActionExecutor(): ActionExecutor {
        return ActionExecutor(
            fakeContext,
            intentExecutor,
            userRepository,
            clipboardManagerService,
            testScope,
            mainDispatcher,
            window,
            viewProxy,
            onDismiss,
        )
    }
}
