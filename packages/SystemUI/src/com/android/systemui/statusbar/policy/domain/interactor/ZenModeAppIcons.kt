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

package com.android.systemui.statusbar.policy.domain.interactor

import android.app.ActivityManager
import android.content.Context
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.InsetDrawable
import com.android.settingslib.notification.modes.ZenMode
import com.android.systemui.common.shared.model.ContentDescription
import com.android.systemui.common.shared.model.Icon
import de.diamaneos.decode.client.ForeignIcons
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * DiamaneOS: a mode's icon can be a drawable of the app that made the rule. That one never goes through the mode icon
 * loader (which would decode the app's image files natively, and keep whatever it got): it is made on an image
 * thread, charged to that app, its bitmap files decoded in the protected VM, and the caller suspends. When it cannot
 * be made, the generic mode icon shows, and it is asked for again the next time.
 */
internal object ZenModeAppIcons {
    /** The most an app's mode icon is decoded to. */
    private const val MODE_ICON_MAX_PX = 256

    /** The icon of [mode] when it is an app's, else null (the mode icon loader takes it). */
    suspend fun load(context: Context, mode: ZenMode): Icon.Loaded? {
        val appPackage = mode.iconKey.resPackage
        if (appPackage.isNullOrEmpty()) {
            return null
        }
        val icon = android.graphics.drawable.Icon.createWithResource(appPackage, mode.iconKey.resId)
        // The modes shown are the current user's, so is the app that made the rule (not SystemUI's own user).
        val userId = ActivityManager.getCurrentUser()
        // What was made before, else made now on an image thread.
        val appIcon =
            ForeignIcons.loadNoWait(context, icon, userId, appPackage, MODE_ICON_MAX_PX, null)
                ?: suspendCancellableCoroutine<Drawable?> { cont ->
                    ForeignIcons.loadAsync(context, icon, userId, appPackage, MODE_ICON_MAX_PX, { it.run() }) {
                        cont.resume(it)
                    }
                }
        val shown =
            appIcon?.let { monochromeIfPresent(it) }
                ?: context.getDrawable(com.android.internal.R.drawable.ic_zen_mode_type_other)!!
        return Icon.Loaded(
            drawable = shown.mutate(),
            contentDescription = ContentDescription.Loaded(mode.name),
            packageName = if (appIcon != null) appPackage else "android",
            resId =
                if (appIcon != null) mode.iconKey.resId
                else com.android.internal.R.drawable.ic_zen_mode_type_other,
        )
    }

    // As the mode icon loader does for an app's icon: its monochrome layer when it has one.
    private fun monochromeIfPresent(icon: Drawable): Drawable {
        if (icon is AdaptiveIconDrawable) {
            icon.monochrome?.let {
                return InsetDrawable(it, -2.0f * AdaptiveIconDrawable.getExtraInsetFraction())
            }
        }
        return icon
    }
}
