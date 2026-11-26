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

package com.android.systemui.media.controls.domain.pipeline

import android.content.Context
import android.graphics.drawable.Icon
import android.service.notification.StatusBarNotification
import de.diamaneos.decode.client.AppIcons
import de.diamaneos.decode.client.ForeignIcons
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor

/**
 * DiamaneOS: the app images of a media pipeline ([MediaDataProcessor], [LegacyMediaDataManagerImpl]) never need a
 * decode of the app's bytes in SystemUI: pixels as they are, URI and data icons decoded in the protected VM, the app's
 * resources through an image gate. Nothing in the pipeline waits for an image: one not made yet is made on an image
 * thread, and the notification is loaded again then through [onNotificationAdded] on [foregroundExecutor].
 */
internal class MediaIconSafety(
    private val context: Context,
    private val foregroundExecutor: Executor,
    private val onNotificationAdded: (String, StatusBarNotification) -> Unit,
) {
    // The last media notification seen per key, loaded again once an icon of it was made.
    private val latestSbn = ConcurrentHashMap<String, StatusBarNotification>()

    fun start() {
        MediaIconReload.addListener { reloadForIcon(it) }
        MediaIconReload.addPackageListener { reloadForPackage(it) }
    }

    /** Only media notifications are kept for a reload after an icon was made. */
    fun onAdded(key: String, sbn: StatusBarNotification, isMedia: Boolean) {
        if (isMedia) latestSbn[key] = sbn else latestSbn.remove(key)
    }

    fun onRemoved(key: String) {
        latestSbn.remove(key)
    }

    /** The app icon the loader read for [sbn], safe to show. */
    fun appIcon(icon: Icon?, sbn: StatusBarNotification): Icon? =
        safeIcon(icon, sbn, AppIcons.iconPx(context))

    /** The artwork the loader read for [sbn], safe to show, at most the screen's short side. */
    fun artwork(icon: Icon?, sbn: StatusBarNotification): Icon? =
        safeIcon(
            icon,
            sbn,
            context.resources.displayMetrics.let { minOf(it.widthPixels, it.heightPixels) }.coerceAtLeast(1),
        )

    private fun safeIcon(icon: Icon?, sbn: StatusBarNotification, px: Int): Icon? =
        ForeignIcons.safeIconNoWait(context, icon, sbn.normalizedUserId, sbn.packageName, px) {
            MediaIconReload.reload(sbn)
        }

    // Every notification of the key that asked is told: the latest one of the key is loaded again (a media app
    // re-posts its notification, so an older object asked for the icon).
    private fun reloadForIcon(sbn: StatusBarNotification) {
        foregroundExecutor.execute { latestSbn[sbn.key]?.let { onNotificationAdded(it.key, it) } }
    }

    // An icon of a media session of the package was made: its notifications are loaded again.
    private fun reloadForPackage(packageName: String) {
        foregroundExecutor.execute {
            latestSbn.values.filter { it.packageName == packageName }.forEach { onNotificationAdded(it.key, it) }
        }
    }
}

/**
 * DiamaneOS: asks the media pipeline to load a media notification again once an icon of it (an action icon, the app
 * icon, the artwork) was made on an image thread: nothing in the pipeline waits for one.
 */
object MediaIconReload {
    /** The most a media action icon of an app is decoded to. */
    const val ACTION_ICON_MAX_PX = 256

    private val listeners = CopyOnWriteArrayList<(StatusBarNotification) -> Unit>()

    /** Called with the notification whose icon was made, on the main thread. */
    fun addListener(listener: (StatusBarNotification) -> Unit) {
        listeners += listener
    }

    fun reload(sbn: StatusBarNotification) {
        listeners.forEach { it(sbn) }
    }

    private val packageListeners = CopyOnWriteArrayList<(String) -> Unit>()

    /** Called with the package whose media session icon was made, on the main thread. */
    fun addPackageListener(listener: (String) -> Unit) {
        packageListeners += listener
    }

    /** An icon of a media session of [packageName] (not of a notification) was made. */
    fun reloadPackage(packageName: String) {
        packageListeners.forEach { it(packageName) }
    }

    private val reloaders = ConcurrentHashMap<String, Runnable>()

    /**
     * The one callback per package for its session icons: an icon asked for by many actions tells it once, and one
     * reload of the package follows. At most 64 packages are kept.
     */
    fun reloaderFor(packageName: String): Runnable {
        if (reloaders.size > 64) reloaders.clear()
        return reloaders.getOrPut(packageName) { Runnable { reloadPackage(packageName) } }
    }
}
