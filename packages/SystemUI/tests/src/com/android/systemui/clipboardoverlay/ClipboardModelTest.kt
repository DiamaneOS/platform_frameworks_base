/*
 * Copyright (C) 2022 The Android Open Source Project
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

import android.content.ClipData
import android.content.ClipDescription
import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.PersistableBundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.res.R
import de.diamaneos.decode.client.VmDecode
import java.io.IOException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
class ClipboardModelTest : SysuiTestCase() {
    @Mock private lateinit var mClipboardUtils: ClipboardOverlayUtils
    @Mock private lateinit var mMockContext: Context
    @Mock private lateinit var mMockContentResolver: ContentResolver
    private lateinit var mSampleClipData: ClipData

    // DiamaneOS: the clipboard's thumbnails come from the VM (ClipboardVmThumbnail), here a fake that records what
    // it was asked and gives a bitmap or throws.
    private class FakeVm(val result: () -> Bitmap) : ClipboardVmThumbnail.Loader {
        var owner: String? = null
        var maxWidth = 0
        var maxHeight = 0
        var calls = 0

        override fun load(
            context: Context,
            resolver: ContentResolver,
            uri: Uri,
            owner: String,
            priority: Int,
            maxWidth: Int,
            maxHeight: Int,
            deadline: Long,
        ): Bitmap {
            calls++
            this.owner = owner
            this.maxWidth = maxWidth
            this.maxHeight = maxHeight
            return result()
        }
    }

    private val testBitmap = Bitmap.createBitmap(50, 50, Bitmap.Config.ARGB_8888)
    private lateinit var mFakeVm: FakeVm

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
        mSampleClipData = ClipData("Test", arrayOf("text/plain"), ClipData.Item("Test Item"))
        mFakeVm = FakeVm { testBitmap }
        ClipboardVmThumbnail.loader = mFakeVm
    }

    @After
    fun tearDown() {
        ClipboardVmThumbnail.loader = ClipboardVmThumbnail.VM
    }

    private fun imageModel(source: String): ClipboardModel {
        whenever(mMockContext.contentResolver).thenReturn(mMockContentResolver)
        whenever(mMockContext.resources).thenReturn(mContext.resources)
        whenever(mMockContentResolver.getType(any())).thenReturn("text")
        val imageClipData = ClipData("Test", arrayOf("image/png"), ClipData.Item(Uri.parse("test")))
        return ClipboardModel.fromClipData(mMockContext, mClipboardUtils, imageClipData, source)
    }

    @Test
    fun test_textClipData() {
        val source = "test source"
        val model = ClipboardModel.fromClipData(mContext, mClipboardUtils, mSampleClipData, source)
        assertEquals(mSampleClipData, model.clipData)
        assertEquals(source, model.source)
        assertEquals(ClipboardModel.Type.TEXT, model.type)
        assertEquals(mSampleClipData.getItemAt(0).text, model.text)
        assertEquals(mSampleClipData.getItemAt(0).textLinks, model.textLinks)
        assertEquals(mSampleClipData.getItemAt(0).uri, model.uri)
        assertFalse(model.isSensitive)
        assertFalse(model.isRemote)
        assertNull(model.loadThumbnail(mContext))
    }

    @Test
    fun test_sensitiveExtra() {
        val description = mSampleClipData.description
        val b = PersistableBundle()
        b.putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
        description.extras = b
        val data = ClipData(description, mSampleClipData.getItemAt(0))
        val (_, _, _, _, _, _, sensitive) =
            ClipboardModel.fromClipData(mContext, mClipboardUtils, data, "")
        assertTrue(sensitive)
    }

    @Test
    fun test_remoteExtra() {
        whenever(mClipboardUtils.isRemoteCopy(any(), any(), any())).thenReturn(true)
        val model = ClipboardModel.fromClipData(mContext, mClipboardUtils, mSampleClipData, "")
        assertTrue(model.isRemote)
    }

    @Test
    @Throws(IOException::class)
    fun test_imageClipData() {
        val model = imageModel("source app")
        assertEquals(ClipboardModel.Type.IMAGE, model.type)
        assertEquals(testBitmap, model.loadThumbnail(mMockContext))
        // Asked of the VM in the clipboard's box, charged to the source, never decoded here.
        val size = mContext.resources.getDimensionPixelSize(R.dimen.overlay_x_scale)
        assertEquals(1, mFakeVm.calls)
        assertEquals("source app", mFakeVm.owner)
        assertEquals(size, mFakeVm.maxWidth)
        assertEquals(size * 4, mFakeVm.maxHeight)
        verify(mMockContentResolver, never()).loadThumbnail(any(), any(), any())
        // Kept: a second look asks nothing again.
        assertEquals(testBitmap, model.loadThumbnail(mMockContext))
        assertEquals(1, mFakeVm.calls)
    }

    /** The decode service unavailable: no preview, and the image is not decoded in SystemUI instead. */
    @Test
    fun test_imageVmUnavailable_failsClosed() {
        mFakeVm = FakeVm { throw VmDecode.UnavailableException("the VM is not available") }
        ClipboardVmThumbnail.loader = mFakeVm
        val model = imageModel("")
        assertNull(model.loadThumbnail(mMockContext))
        assertEquals(1, mFakeVm.calls)
        verify(mMockContentResolver, never()).loadThumbnail(any(), any(), any())
    }

    /** An image the VM refuses: no preview. */
    @Test
    fun test_imageVmRefused_noPreview() {
        mFakeVm = FakeVm { throw VmDecode.RejectedException("invalid file") }
        ClipboardVmThumbnail.loader = mFakeVm
        val model = imageModel("")
        assertNull(model.loadThumbnail(mMockContext))
        verify(mMockContentResolver, never()).loadThumbnail(any(), any(), any())
    }

    @Test
    fun test_nullMimeType() {
        whenever(mMockContext.contentResolver).thenReturn(mMockContentResolver)
        whenever(mMockContext.resources).thenReturn(mContext.resources)
        whenever(mMockContentResolver.getType(any())).thenReturn("text")
        val imageClipData = ClipData("Test", arrayOf(), ClipData.Item(Uri.parse("test")))
        val model = ClipboardModel.fromClipData(mMockContext, mClipboardUtils, imageClipData, "")
        assertEquals(ClipboardModel.Type.URI, model.type)
        assertEquals(null, model.loadThumbnail(mMockContext))
        assertEquals(0, mFakeVm.calls)
    }
}
