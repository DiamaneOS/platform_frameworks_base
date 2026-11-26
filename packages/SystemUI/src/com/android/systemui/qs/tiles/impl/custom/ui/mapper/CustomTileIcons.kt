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

package com.android.systemui.qs.tiles.impl.custom.ui.mapper

import android.app.IUriGrantsManager
import android.content.Context
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import de.diamaneos.decode.client.ForeignIcons
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow

/**
 * DiamaneOS: a custom tile's icon and its default icon (from the tile app's manifest) are made without decoding the
 * app's bytes in SystemUI: URI and data icons in the protected VM, the app's resources through an image gate, on an
 * image thread. The calling thread never waits: once an icon is made the tile's data is read again
 * ([CustomTileIconsMade]) and it shows.
 */
internal object CustomTileIcons {
    private const val TILE_ICON_MAX_PX = 256

    /**
     * The tile's [icon], or null when there is none or [packageName] (uid [callingAppUid]) may not read its URI (as
     * upstream's loadDrawableCheckingUriGrant, the default icon shows then).
     */
    fun load(
        userContext: Context,
        icon: Icon?,
        uriGrantsManager: IUriGrantsManager,
        callingAppUid: Int,
        packageName: String,
    ): Drawable? =
        icon
            ?.takeIf { ForeignIcons.uriGranted(it, uriGrantsManager, callingAppUid, packageName) }
            ?.let { load(userContext, it, packageName) }

    /** [icon] of the tile app [packageName], made in the VM or through an image gate, or null. */
    fun load(userContext: Context, icon: Icon?, packageName: String): Drawable? =
        icon?.let {
            ForeignIcons.loadNoWait(userContext, it, userContext.userId, packageName, TILE_ICON_MAX_PX) {
                CustomTileIconsMade.made(packageName)
            }
        }
}

/** Tells the custom tiles of an app that one of its icons was made on an image thread, so they map again. */
object CustomTileIconsMade {
    private val made =
        MutableSharedFlow<String>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    fun made(packageName: String) {
        made.tryEmit(packageName)
    }

    fun madeFor(packageName: String): Flow<Unit> = flow { made.collect { if (it == packageName) emit(Unit) } }
}
