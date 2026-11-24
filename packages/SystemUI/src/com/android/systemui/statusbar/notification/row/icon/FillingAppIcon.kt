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

package com.android.systemui.statusbar.notification.row.icon

import android.graphics.drawable.Drawable
import android.graphics.drawable.DrawableWrapper
import android.os.Handler
import android.os.Looper
import com.android.internal.diamaneos.cleancopies.CleanCopyGate

/**
 * DiamaneOS: a notification row's app icon drawn while the app's image had no clean copy (the default icon). Once the
 * system server made copies of the app's images ([CleanCopyGate.whenMade]) it loads the icon again off the main
 * thread, and shows the app's own icon in place, without a new inflation. The copy is made in the VM, nothing is
 * decoded here.
 */
internal class FillingAppIcon
private constructor(placeholder: Drawable, private val load: () -> Drawable) :
    DrawableWrapper(placeholder), CleanCopyGate.MadeListener {
    @Volatile private var filled = false

    override fun onMade(packageName: String, userId: Int) {
        if (filled) {
            return
        }
        val icon =
            try {
                load()
            } catch (e: RuntimeException) {
                return
            }
        if (icon is FillingAppIcon) {
            return // Still no copy: that icon waits in turn.
        }
        main.post {
            if (!filled) {
                filled = true
                drawable = icon
                invalidateSelf()
            }
        }
    }

    companion object {
        private val main = Handler(Looper.getMainLooper())

        /** [placeholder] now, the icon [load] gives (on a background thread) once copies of the app are made. */
        fun of(placeholder: Drawable, packageName: String, userId: Int, load: () -> Drawable): Drawable =
            FillingAppIcon(placeholder, load).also { CleanCopyGate.whenMade(it, packageName, userId) }
    }
}
