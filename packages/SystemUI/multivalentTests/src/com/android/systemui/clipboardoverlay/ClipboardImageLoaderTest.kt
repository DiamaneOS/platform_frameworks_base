/*
 * Copyright (C) 2023 The Android Open Source Project
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
package com.android.systemui.clipboardoverlay

import android.content.ContentResolver
import android.content.Context
import android.content.pm.UserInfo
import android.graphics.Bitmap
import android.net.Uri
import android.platform.test.annotations.DisableFlags
import android.platform.test.annotations.EnableFlags
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.Flags.FLAG_CLIPBOARD_OVERLAY_MULTIUSER
import com.android.systemui.SysuiTestCase
import com.android.systemui.res.R
import com.android.systemui.settings.FakeUserTracker
import de.diamaneos.decode.client.VmDecode
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.whenever

@SmallTest
@RunWith(AndroidJUnit4::class)
class ClipboardImageLoaderTest : SysuiTestCase() {
    @Mock private lateinit var mockContext: Context

    @Mock private lateinit var mockContentResolver: ContentResolver
    @Mock private lateinit var mockSecondaryContentResolver: ContentResolver

    private lateinit var clipboardImageLoader: ClipboardImageLoader
    private var fakeUserTracker: FakeUserTracker =
        FakeUserTracker(userContentResolverProvider = { mockContentResolver })

    private val userInfos = listOf(UserInfo(0, "system", 0), UserInfo(50, "secondary", 0))

    // DiamaneOS: the clipboard's thumbnails come from the VM (ClipboardVmThumbnail), here a fake that records what
    // it was asked and gives a bitmap, throws or takes its time.
    private class FakeVm(val result: () -> Bitmap) : ClipboardVmThumbnail.Loader {
        var resolver: ContentResolver? = null
        var uri: Uri? = null
        var maxWidth = 0
        var maxHeight = 0
        var calls = 0

        override fun load(
            context: Context,
            resolver: ContentResolver,
            uri: Uri,
            owner: String,
            ownerUser: Int,
            priority: Int,
            maxWidth: Int,
            maxHeight: Int,
            deadline: Long,
        ): Bitmap {
            calls++
            this.resolver = resolver
            this.uri = uri
            this.maxWidth = maxWidth
            this.maxHeight = maxHeight
            return result()
        }
    }

    private val testBitmap = Bitmap.createBitmap(50, 50, Bitmap.Config.ARGB_8888)
    private lateinit var fakeVm: FakeVm

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)

        fakeUserTracker.set(userInfos, 0)
        fakeVm = FakeVm { testBitmap }
        ClipboardVmThumbnail.loader = fakeVm
    }

    @After
    fun tearDown() {
        ClipboardVmThumbnail.loader = ClipboardVmThumbnail.VM
    }

    // The thumbnail was asked of the VM for testUri through resolver, in the clipboard's box, never decoded here.
    private fun assertAskedOfTheVm(testUri: Uri, resolver: ContentResolver) {
        val size = context.resources.getDimensionPixelSize(R.dimen.overlay_x_scale)
        assertEquals(1, fakeVm.calls)
        assertEquals(testUri, fakeVm.uri)
        assertSame(resolver, fakeVm.resolver)
        assertEquals(size, fakeVm.maxWidth)
        assertEquals(size * 4, fakeVm.maxHeight)
        verify(resolver, never()).loadThumbnail(any(), any(), any())
    }

    @Test
    @Throws(IOException::class)
    @DisableFlags(FLAG_CLIPBOARD_OVERLAY_MULTIUSER)
    fun test_imageLoadSuccess_legacy() = runTest {
        val testDispatcher = StandardTestDispatcher(this.testScheduler)
        fakeUserTracker =
            FakeUserTracker(userContentResolverProvider = { mockSecondaryContentResolver })
        fakeUserTracker.set(userInfos, 1)

        clipboardImageLoader =
            ClipboardImageLoader(
                mockContext,
                fakeUserTracker,
                testDispatcher,
                CoroutineScope(testDispatcher),
            )
        val testUri = Uri.parse("testUri")
        whenever<ContentResolver?>(mockContext.contentResolver)
            .thenReturn(mockSecondaryContentResolver)
        whenever(mockContext.resources).thenReturn(context.resources)

        val res = clipboardImageLoader.load(testUri)

        assertSame(testBitmap, res)
        assertAskedOfTheVm(testUri, mockSecondaryContentResolver)
    }

    // DiamaneOS: clipboard_overlay_multiuser is a fixed false in this release (READ_ONLY=false in the build's
    // aconfig values), so this path never runs on DiamaneOS and the test skips on the phone ("code is optimized").
    // test_imageLoadSuccess_legacy covers the path that runs.
    @Test
    @Throws(IOException::class)
    @EnableFlags(FLAG_CLIPBOARD_OVERLAY_MULTIUSER)
    fun test_imageLoadSuccess() = runTest {
        val testDispatcher = StandardTestDispatcher(this.testScheduler)
        fakeUserTracker =
            FakeUserTracker(userContentResolverProvider = { mockSecondaryContentResolver })
        fakeUserTracker.set(userInfos, 1)

        clipboardImageLoader =
            ClipboardImageLoader(
                mockContext,
                fakeUserTracker,
                testDispatcher,
                CoroutineScope(testDispatcher),
            )
        val testUri = Uri.parse("testUri")
        whenever(mockContext.resources).thenReturn(context.resources)

        val res = clipboardImageLoader.load(testUri)

        assertSame(testBitmap, res)
        assertAskedOfTheVm(testUri, mockSecondaryContentResolver)
    }

    @Test
    @Throws(IOException::class)
    fun test_imageLoadFailure() = runTest {
        val testDispatcher = StandardTestDispatcher(this.testScheduler)
        clipboardImageLoader =
            ClipboardImageLoader(
                mockContext,
                fakeUserTracker,
                testDispatcher,
                CoroutineScope(testDispatcher),
            )
        val testUri = Uri.parse("testUri")
        whenever(mockContext.contentResolver).thenReturn(mockContentResolver)
        whenever(mockContext.resources).thenReturn(context.resources)

        fakeVm = FakeVm { throw VmDecode.RejectedException("invalid file") }
        ClipboardVmThumbnail.loader = fakeVm

        val res = clipboardImageLoader.load(testUri)

        assertAskedOfTheVm(testUri, mockContentResolver)
        assertNull(res)
    }

    /** The decode service unavailable: no preview, and the image is not decoded in SystemUI instead. */
    @Test
    @Throws(IOException::class)
    fun test_vmUnavailable_failsClosed() = runTest {
        val testDispatcher = StandardTestDispatcher(this.testScheduler)
        clipboardImageLoader =
            ClipboardImageLoader(
                mockContext,
                fakeUserTracker,
                testDispatcher,
                CoroutineScope(testDispatcher),
            )
        val testUri = Uri.parse("testUri")
        whenever(mockContext.contentResolver).thenReturn(mockContentResolver)
        whenever(mockContext.resources).thenReturn(context.resources)
        fakeVm = FakeVm { throw VmDecode.UnavailableException("the VM is not available") }
        ClipboardVmThumbnail.loader = fakeVm

        val res = clipboardImageLoader.load(testUri)

        assertAskedOfTheVm(testUri, mockContentResolver)
        assertNull(res)
    }

    /** A thumbnail that takes longer than the load's time: no preview once the time is up. */
    @Test
    @Throws(IOException::class)
    fun test_vmTooSlow_noPreview(): Unit = runBlocking {
        clipboardImageLoader =
            ClipboardImageLoader(mockContext, fakeUserTracker, Dispatchers.IO, CoroutineScope(Dispatchers.IO))
        val testUri = Uri.parse("testUri")
        whenever(mockContext.contentResolver).thenReturn(mockContentResolver)
        whenever(mockContext.resources).thenReturn(context.resources)
        fakeVm = FakeVm {
            Thread.sleep(1_000)
            testBitmap
        }
        ClipboardVmThumbnail.loader = fakeVm

        val start = System.currentTimeMillis()
        val res = clipboardImageLoader.load(testUri, timeoutMs = 100)

        assertNull(res)
        assertEquals(1, fakeVm.calls)
        assertTrue(System.currentTimeMillis() - start < 10_000)
        verify(mockContentResolver, never()).loadThumbnail(any(), any(), any())
        Unit
    }
}
