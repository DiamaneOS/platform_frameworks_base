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

import de.diamaneos.decode.client.VmDecode
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import android.util.Size
import com.android.app.tracing.coroutines.launchTraced as launch
import com.android.systemui.Flags.clipboardOverlayMultiuser
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.res.R
import com.android.systemui.settings.UserTracker
import java.io.IOException
import java.util.function.Consumer
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class ClipboardImageLoader
@Inject
constructor(
    private val context: Context,
    private val userTracker: UserTracker,
    @Background private val bgDispatcher: CoroutineDispatcher,
    @Application private val mainScope: CoroutineScope,
) {
    private val TAG: String = "ClipboardImageLoader"

    suspend fun load(uri: Uri, timeoutMs: Long = 3000) = // DiamaneOS
        withTimeoutOrNull(timeoutMs) {
            withContext(bgDispatcher) {
                try {
                    val size = context.resources.getDimensionPixelSize(R.dimen.overlay_x_scale)
                    val multiuser = clipboardOverlayMultiuser()
                    val resolver =
                        if (multiuser) userTracker.userContentResolver else context.contentResolver
                    // DiamaneOS: decoded in the protected VM, never in SystemUI, in the clipboard's own lane per
                    // provider, and turned as loadThumbnail turns it. The image is of the user whose resolver opens it,
                    // or of the user the URI names.
                    ClipboardVmThumbnail.loader.load(
                        context,
                        resolver,
                        uri,
                        VmDecode.unknownOwner("clipboard"),
                        if (multiuser) userTracker.userId else context.userId,
                        VmDecode.PRIORITY_USER_WAITING,
                        size,
                        size * 4,
                        VmDecode.deadlineIn(timeoutMs),
                    )
                } catch (e: VmDecode.UnavailableException) {
                    Log.w(TAG, "Thumbnail not decoded in the VM: ${e.message}")
                    null
                } catch (e: VmDecode.RejectedException) {
                    Log.w(TAG, "Thumbnail not decoded in the VM: ${e.message}")
                    null
                }
            }
        }

    fun loadAsync(uri: Uri, callback: Consumer<Bitmap?>) {
        mainScope.launch { callback.accept(load(uri)) }
    }
}
