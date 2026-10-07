/*
 * Copyright (C) 2026 The DiamaneOS Project
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

package com.android.systemui.screenshot

import android.content.ContentProvider
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.MainThread
import androidx.annotation.WorkerThread
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import java.util.concurrent.Executor
import javax.inject.Inject

/**
 * Tally: tells the screenshot preview when the screenshot it shows is gone, whichever app removed
 * it (the preview's Delete, an editor, Files, a gallery). It observes the one saved MediaStore item
 * while the preview shows it and checks the item on each change; no polling. The item is SystemUI's
 * own file, so this needs no access SystemUI does not already have.
 */
class ScreenshotDeletionWatcher
@Inject
constructor(
    private val context: Context,
    @Background private val bgHandler: Handler,
    @Main private val mainExecutor: Executor,
) {
    private var observer: ContentObserver? = null

    /**
     * Starts watching [uri], replacing any earlier watch. Calls [onGone] once, on the main thread,
     * when the item no longer exists, is in the trash or is pending again. Only MediaStore items
     * are watched.
     */
    @MainThread
    fun watch(uri: Uri, onGone: () -> Unit) {
        stop()
        if (!isMediaStoreUri(uri)) {
            return
        }
        val resolver = context.contentResolver
        val newObserver =
            object : ContentObserver(bgHandler) {
                override fun onChange(selfChange: Boolean, uris: Collection<Uri>, flags: Int) {
                    if (!isGone(uri)) {
                        return
                    }
                    mainExecutor.execute {
                        // Ignore a change that raced with stop() or a newer watch().
                        if (observer === this) {
                            stop()
                            onGone()
                        }
                    }
                }
            }
        observer = newObserver
        try {
            // Takes the user from the URI, so a work profile screenshot is watched there.
            resolver.registerContentObserver(uri, false, newObserver)
        } catch (e: Exception) {
            Log.w(TAG, "Cannot watch the screenshot for deletion", e)
            observer = null
        }
    }

    /** Stops watching. Safe to call when nothing is watched. */
    @MainThread
    fun stop() {
        val current = observer ?: return
        observer = null
        context.contentResolver.unregisterContentObserver(current)
    }

    /** Whether the saved screenshot at [uri] is no longer there to show. Blocking. */
    @WorkerThread
    private fun isGone(uri: Uri): Boolean {
        val queryArgs =
            Bundle().apply {
                putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
                putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
            }
        return try {
            context.contentResolver.query(uri, PROJECTION, queryArgs, null).use { cursor ->
                when {
                    // The provider is unavailable; that says nothing about the file.
                    cursor == null -> false
                    !cursor.moveToFirst() -> true
                    else -> cursor.getInt(0) != 0 || cursor.getInt(1) != 0
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Cannot check the screenshot", e)
            false
        }
    }

    private fun isMediaStoreUri(uri: Uri): Boolean =
        uri.scheme == "content" &&
            ContentProvider.getAuthorityWithoutUserId(uri.authority) == MediaStore.AUTHORITY

    companion object {
        private const val TAG = "ScreenshotDeletion"
        private val PROJECTION =
            arrayOf(MediaStore.MediaColumns.IS_PENDING, MediaStore.MediaColumns.IS_TRASHED)
    }
}
