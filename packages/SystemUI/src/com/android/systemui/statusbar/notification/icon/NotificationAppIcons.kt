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

package com.android.systemui.statusbar.notification.icon

import android.app.Notification
import android.content.ComponentCallbacks
import android.content.Context
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.content.res.Configuration
import android.graphics.drawable.Icon
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.collection.notifcollection.CommonNotifCollection
import de.diamaneos.decode.client.AppIcons
import de.diamaneos.decode.client.ForeignIcons
import de.diamaneos.decode.client.NotificationImageGate
import de.diamaneos.decode.client.VmIcons
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * DiamaneOS: the app images of [IconManager]'s icons. Status bar, shelf, always-on display and heads-up icons are
 * drawn from these descriptors on the main thread, where nothing may be decoded in the VM: an app resource icon loads
 * through resources behind an image gate (its bitmap files decoded in the VM) or is a placeholder until an image
 * thread has made it, and a URI or data icon is decoded in the VM on an image thread. [rebuild] builds an entry's
 * icons again once its images are made.
 */
internal class NotificationAppIcons(
    private val shadeContext: Context,
    private val notifCollection: CommonNotifCollection,
    private val rebuild: (NotificationEntry) -> Unit,
) {
    // The configuration the app resource icons were last built for.
    private var iconConfiguration: Configuration? = null

    /**
     * An app's resource icons are loaded through resources behind an image gate in the current configuration. After
     * a change that can pick other resource files (dark mode, rotation, density, locale) the icons that are app
     * resources are built again. The icons shown stay until the new ones are ready. Window-only changes (bounds, a
     * fold, split screen) rebuild nothing.
     */
    fun attach() {
        iconConfiguration = Configuration(shadeContext.resources.configuration)
        shadeContext.registerComponentCallbacks(
            object : ComponentCallbacks {
                override fun onConfigurationChanged(newConfig: Configuration) {
                    if (!AppIcons.resourceConfigChanged(iconConfiguration, newConfig)) {
                        return
                    }
                    iconConfiguration = Configuration(newConfig)
                    val entries = notifCollection.allNotifs.filter { usesAppImage(it) }
                    // The gated resources of the new configuration are made off the main thread first.
                    val wanted =
                        entries.flatMap { entry ->
                            listOfNotNull(entry.sbn.notification.smallIcon, entry.sbn.notification.getLargeIcon())
                                .map {
                                    AppIcons.Wanted(
                                        NotificationImageGate.imageUserOf(entry.sbn),
                                        entry.sbn.packageName,
                                        it,
                                    )
                                }
                        }
                    AppIcons.prepare(shadeContext, wanted) { entries.forEach { rebuildIfCurrent(it) } }
                }

                @Deprecated("Deprecated in Java") override fun onLowMemory() {}
            }
        )
    }

    /** Icon retries still to come for a removed entry are dropped (they would keep the entry). */
    fun onEntryCleanUp(entry: NotificationEntry) = AppIcons.cancelRetries(entry.key)

    /**
     * The icon the views of [entry] load on the main thread: a gated copy, an image the VM made, or a placeholder
     * until an image thread has made it, after which the entry's icons are built again.
     */
    fun forView(icon: Icon, entry: NotificationEntry): Icon =
        AppIcons.forView(shadeContext, icon, NotificationImageGate.imageUserOf(entry.sbn), entry.sbn.packageName,
            entry.key) {
            rebuildIfCurrent(entry)
        } ?: withoutVmIcon(icon)

    /**
     * The launcher shortcut icon of a conversation: a file or URI icon is decoded in the VM on an image thread (the
     * calling thread does not wait for it), a resource icon goes through [forView] later.
     */
    suspend fun shortcutIcon(launcherApps: LauncherApps, shortcut: ShortcutInfo): Icon? =
        suspendCancellableCoroutine { cont ->
            ForeignIcons.shortcutIconAsync(
                shadeContext,
                launcherApps,
                shortcut,
                ForeignIcons.shortcutPx(shadeContext),
                { it.run() },
            ) {
                cont.resume(it)
            }
        }

    private fun rebuildIfCurrent(entry: NotificationEntry) {
        if (notifCollection.getEntry(entry.key) === entry) {
            rebuild(entry)
        }
    }

    // An entry whose icons may be app images: its small or large icon is an app resource, a URI or data (decoded at a
    // size from the density), or it shows a conversation (the avatar can be a sender's or the shortcut's icon).
    private fun usesAppImage(entry: NotificationEntry): Boolean {
        val n = entry.sbn.notification
        return AppIcons.isAppResource(n.smallIcon) ||
            AppIcons.isAppResource(n.getLargeIcon()) ||
            VmIcons.needsVm(n.smallIcon) ||
            VmIcons.needsVm(n.getLargeIcon()) ||
            entry.ranking.conversationShortcutInfo != null ||
            n.extras.containsKey(Notification.EXTRA_MESSAGES)
    }

    companion object {
        /**
         * [shown] is the small icon [smallIcon]: a descriptor holds the icon as [forView] gave it (a gated copy, or
         * the same pixels), so it is compared by content.
         */
        @JvmStatic
        fun isSmallIcon(shown: Icon?, smallIcon: Icon?): Boolean =
            shown === smallIcon || (smallIcon != null && shown?.sameAs(smallIcon) == true)

        /**
         * An icon made of another app's compressed image bytes (a URI or createWithData) becomes the neutral system
         * icon, for views that only show SystemUI's own icons. The notification row itself shows the image, decoded
         * in the VM.
         */
        @JvmStatic
        fun withoutVmIcon(icon: Icon): Icon = VmIcons.withoutVmIcon(icon, AppIcons.neutral())
    }
}
