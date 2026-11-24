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

import android.content.Context
import android.content.pm.PackageManager.MATCH_UNINSTALLED_PACKAGES
import android.os.UserHandle
import java.util.concurrent.ConcurrentHashMap

/**
 * DiamaneOS: what an app's icon depends on, so the icon caches never keep an old icon: the APK's path (new on every
 * update and every install), the icon resource, whether the app is enabled, the resource overlays applied to it and
 * the locales its resources are read in (an icon can differ by language). Read on each request
 * (the package manager serves it from its own cache until the package changes), so a missed broadcast cannot keep
 * an old icon either. "" when the package cannot be read.
 */
internal fun appIconStamp(
    context: Context,
    packageName: String,
    userHandle: UserHandle,
    perUser: Boolean = true,
): String =
    try {
        val info =
            context.packageManager.getApplicationInfoAsUser(
                packageName,
                MATCH_UNINSTALLED_PACKAGES,
                userHandle.identifier,
            )
        val overlays =
            (info.splitSourceDirs?.toList().orEmpty() +
                    info.overlayPaths?.toList().orEmpty() +
                    info.resourceDirs?.toList().orEmpty())
                .joinToString(",")
        val locales = context.resources.configuration.locales.toLanguageTags()
        // The enabled state is per user: left out for a cache shared by all users.
        val enabled = if (perUser) info.enabled.toString() else ""
        // DiamaneOS: a recycled numeric user ID never inherits a previous user's drawable state.
        val serial = context.getSystemService(android.os.UserManager::class.java)
            ?.getUserSerialNumber(userHandle.identifier) ?: -1
        "${info.sourceDir}:${info.icon}:$enabled:$overlays:$locales:s$serial"
    } catch (e: Exception) {
        ""
    }

/**
 * DiamaneOS: the newest stamp seen per package and user of an [AppIconCache], and the stamp each cache key was made
 * for, so its purge also drops the entries made for an older one (keys are never parsed).
 */
internal class AppIconStamps {
    private val latest = ConcurrentHashMap<String, String>()
    private val keys = ConcurrentHashMap<String, Pair<String, String>>()

    fun note(packageName: String, userKey: String, stamp: String) {
        latest["$packageName|$userKey"] = stamp
    }

    /** Records that cache entry [cacheKey] holds the icon of [packageName] for [userKey] made for [stamp]. */
    fun keyMade(cacheKey: String, packageName: String, userKey: String, stamp: String) {
        // Grows only when an app changes (an update, an overlay, a language): bounded all the same.
        if (keys.size > MAX_KEYS) keys.clear()
        keys[cacheKey] = "$packageName|$userKey" to stamp
    }

    /** Whether cache entry [cacheKey] was made for the newest stamp of its package (true when not known). */
    fun isCurrent(cacheKey: String): Boolean {
        val (app, stamp) = keys[cacheKey] ?: return true
        val newest = latest[app]
        return newest == null || newest == stamp
    }

    private companion object {
        const val MAX_KEYS = 4096
    }
}
