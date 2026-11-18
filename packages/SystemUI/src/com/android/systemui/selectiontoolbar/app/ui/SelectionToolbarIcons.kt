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

package com.android.systemui.selectiontoolbar.app.ui

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.widget.ImageView
import de.diamaneos.decode.client.ForeignIcons
import de.diamaneos.decode.client.VmDecode
import java.util.WeakHashMap

/**
 * DiamaneOS: the icons of a selection toolbar's menu items, which the app whose text is selected hands over. Never
 * decoded here: pixels as they are, data and URI images in the protected VM, the app's resources through an image
 * gate, made on an image thread in a lane of the toolbar's own. Until it is made the button shows nothing,
 * then the icon, if the button still shows that item.
 */
internal object SelectionToolbarIcons {
    private val OWNER = VmDecode.unknownOwner("selection-toolbar")
    private const val MAX_PX = 256
    // What each button shows now (main thread).
    private val shown = WeakHashMap<ImageView, Icon>()

    fun show(view: ImageView, icon: Icon) {
        shown[view] = icon
        view.setImageDrawable(load(view, icon) ?: ColorDrawable(Color.TRANSPARENT))
    }

    private fun load(view: ImageView, icon: Icon): Drawable? =
        ForeignIcons.loadNoWait(view.context, icon, view.context.userId, OWNER, MAX_PX) {
            if (shown[view] === icon) {
                load(view, icon)?.let { view.setImageDrawable(it) }
            }
        }
}
