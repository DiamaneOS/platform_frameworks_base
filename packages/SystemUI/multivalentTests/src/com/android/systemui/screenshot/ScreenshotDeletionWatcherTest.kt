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

import android.content.ContentResolver
import android.content.Context
import android.database.ContentObserver
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.Executor
import kotlin.test.Test
import org.junit.Before
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@RunWith(AndroidJUnit4::class)
@SmallTest
class ScreenshotDeletionWatcherTest : SysuiTestCase() {
    private val fakeContext = mock<Context>()
    private val contentResolver = mock<ContentResolver>()
    private val directExecutor = Executor { it.run() }
    private val uri = Uri.parse("content://0@media/external_primary/images/media/123")

    private var goneCount = 0
    private lateinit var watcher: ScreenshotDeletionWatcher

    @Before
    fun setUp() {
        whenever(fakeContext.contentResolver).thenReturn(contentResolver)
        watcher =
            ScreenshotDeletionWatcher(fakeContext, Handler(Looper.getMainLooper()), directExecutor)
    }

    @Test
    fun watch_registersOnTheSavedItem() {
        watcher.watch(uri) { goneCount++ }

        verify(contentResolver).registerContentObserver(eq(uri), eq(false), any())
    }

    @Test
    fun watch_notMediaStore_doesNotRegister() {
        val documentUri =
            Uri.parse("content://com.android.externalstorage.documents/document/primary%3Ashot.png")

        watcher.watch(documentUri) { goneCount++ }

        verify(contentResolver, never()).registerContentObserver(any(), any(), any())
    }

    @Test
    fun change_itemDeleted_reportsGoneOnceAndStops() {
        val observer = watchAndCaptureObserver()
        returnRow(null)

        observer.onChange(false, listOf(uri), 0)
        observer.onChange(false, listOf(uri), 0)

        assertThat(goneCount).isEqualTo(1)
        verify(contentResolver).unregisterContentObserver(observer)
    }

    @Test
    fun change_itemTrashed_reportsGone() {
        val observer = watchAndCaptureObserver()
        returnRow(isPending = 0, isTrashed = 1)

        observer.onChange(false, listOf(uri), 0)

        assertThat(goneCount).isEqualTo(1)
    }

    @Test
    fun change_itemPendingAgain_reportsGone() {
        val observer = watchAndCaptureObserver()
        returnRow(isPending = 1, isTrashed = 0)

        observer.onChange(false, listOf(uri), 0)

        assertThat(goneCount).isEqualTo(1)
    }

    @Test
    fun change_itemStillThere_keepsWatching() {
        val observer = watchAndCaptureObserver()
        returnRow(isPending = 0, isTrashed = 0)

        observer.onChange(false, listOf(uri), 0)

        assertThat(goneCount).isEqualTo(0)
        verify(contentResolver, never()).unregisterContentObserver(any())
    }

    @Test
    fun change_queryFails_keepsWatching() {
        val observer = watchAndCaptureObserver()
        whenever(contentResolver.query(eq(uri), any(), any<Bundle>(), anyOrNull()))
            .thenThrow(SecurityException("no access"))

        observer.onChange(false, listOf(uri), 0)

        assertThat(goneCount).isEqualTo(0)
    }

    @Test
    fun change_providerUnavailable_keepsWatching() {
        val observer = watchAndCaptureObserver()
        whenever(contentResolver.query(eq(uri), any(), any<Bundle>(), anyOrNull()))
            .thenReturn(null)

        observer.onChange(false, listOf(uri), 0)

        assertThat(goneCount).isEqualTo(0)
    }

    @Test
    fun change_afterStop_doesNothing() {
        val observer = watchAndCaptureObserver()
        returnRow(null)

        watcher.stop()
        observer.onChange(false, listOf(uri), 0)

        assertThat(goneCount).isEqualTo(0)
        verify(contentResolver).unregisterContentObserver(observer)
    }

    @Test
    fun watch_newScreenshot_dropsTheOldWatch() {
        val oldObserver = watchAndCaptureObserver()
        val newUri = Uri.parse("content://0@media/external_primary/images/media/456")
        var newGoneCount = 0
        watcher.watch(newUri) { newGoneCount++ }
        returnRow(null)

        oldObserver.onChange(false, listOf(uri), 0)

        assertThat(goneCount).isEqualTo(0)
        assertThat(newGoneCount).isEqualTo(0)
        verify(contentResolver).unregisterContentObserver(oldObserver)
        verify(contentResolver, times(2)).registerContentObserver(any(), eq(false), any())
    }

    private fun watchAndCaptureObserver(): ContentObserver {
        watcher.watch(uri) { goneCount++ }
        val captor = argumentCaptor<ContentObserver>()
        verify(contentResolver).registerContentObserver(eq(uri), eq(false), captor.capture())
        return captor.firstValue
    }

    /** Makes the item query return one row with these values, or no row when [isPending] is null. */
    private fun returnRow(isPending: Int?, isTrashed: Int = 0) {
        val cursor =
            MatrixCursor(
                arrayOf(MediaStore.MediaColumns.IS_PENDING, MediaStore.MediaColumns.IS_TRASHED)
            )
        if (isPending != null) {
            cursor.addRow(arrayOf<Any>(isPending, isTrashed))
        }
        whenever(contentResolver.query(eq(uri), any(), any<Bundle>(), anyOrNull()))
            .thenReturn(cursor)
    }
}
