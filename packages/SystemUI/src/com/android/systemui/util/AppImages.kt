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

package com.android.systemui.util

import android.content.Context
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import de.diamaneos.decode.client.ForeignIcons
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * DiamaneOS: an image another app hands SystemUI ([icon], of any type) for a caller that may suspend: what was made
 * before, else made on an image thread for [ownerPackage] (charged to it, its bitmap files and data or URI images
 * decoded in the protected VM) while the caller suspends. Null when it cannot be made: the caller shows nothing or
 * its own fallback. Never decoded in this process, never a wait on the calling thread.
 */
suspend fun loadAppImage(
    context: Context,
    icon: Icon?,
    userId: Int,
    ownerPackage: String,
    maxPx: Int,
): Drawable? {
    if (icon == null) return null
    ForeignIcons.loadNoWait(context, icon, userId, ownerPackage, maxPx, null)?.let {
        return it
    }
    return suspendCancellableCoroutine { cont ->
        ForeignIcons.loadAsync(context, icon, userId, ownerPackage, maxPx, { it.run() }) {
            if (cont.isActive) cont.resume(it)
        }
    }
}
