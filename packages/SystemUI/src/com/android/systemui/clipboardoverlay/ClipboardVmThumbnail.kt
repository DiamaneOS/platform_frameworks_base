/*
 * Copyright (C) 2026 DiamaneOS
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
import android.graphics.Bitmap
import android.net.Uri
import androidx.annotation.VisibleForTesting
import de.diamaneos.decode.client.VmDecode

/**
 * DiamaneOS: where the clipboard preview (ClipboardModel, ClipboardImageLoader) gets its thumbnail: from
 * [VmDecode.loadThumbnail], decoded in the protected VM and turned as ContentResolver.loadThumbnail turns it. A
 * failure throws VmDecode.UnavailableException or VmDecode.RejectedException, which the callers show as no preview.
 * Tests put a [Loader] of their own in [loader].
 */
object ClipboardVmThumbnail {
    fun interface Loader {
        @Throws(VmDecode.UnavailableException::class, VmDecode.RejectedException::class)
        fun load(
            context: Context,
            resolver: ContentResolver,
            uri: Uri,
            owner: String,
            priority: Int,
            maxWidth: Int,
            maxHeight: Int,
            deadline: Long,
        ): Bitmap
    }

    /** The VM's thumbnail. */
    val VM = Loader { context, resolver, uri, owner, priority, maxWidth, maxHeight, deadline ->
        VmDecode.loadThumbnail(context, resolver, uri, owner, priority, maxWidth, maxHeight, deadline)
    }

    @VisibleForTesting @Volatile var loader: Loader = VM
}
