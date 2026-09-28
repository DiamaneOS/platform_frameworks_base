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

package com.android.systemui.tally.lock

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.settings.UserFileManager
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import javax.inject.Inject

/**
 * How many wakes each user has seen the side fingerprint sensor's hint on, for the Tally lock
 * screen's indication line (KeyguardIndicationController), which shows the hint on a user's first
 * few wakes only, across restarts.
 *
 * The count is one small number per user in SystemUI's own preferences for that user
 * ([UserFileManager]: SystemUI's device-protected storage, private to SystemUI, deleted with the
 * user and not in SystemUI's backup). It is read and written on the background thread; callers on
 * the main thread see the copy in memory, and a user whose count is not read yet has no wakes left
 * until it is.
 */
@SysUISingleton
class TallySideFpsHintCounter
@Inject
constructor(
    private val userFileManager: UserFileManager,
    @Background private val bgExecutor: Executor,
) {
    private val wakesShown = ConcurrentHashMap<Int, Int>()

    /**
     * Reads [userId]'s count in the background if it is not read yet. A count that cannot be read
     * (a value of another type under the key, a file that cannot be opened) leaves the user out, so
     * the hint stays away, and never throws on the background thread, which would take SystemUI
     * down.
     */
    fun prefetch(userId: Int) {
        if (wakesShown.containsKey(userId)) return
        bgExecutor.execute {
            val shown =
                try {
                    prefs(userId).getInt(KEY_WAKES_SHOWN, 0)
                } catch (e: RuntimeException) {
                    Log.w(TAG, "Cannot read the fingerprint hint count of user $userId", e)
                    return@execute
                }
            wakesShown.putIfAbsent(userId, shown)
        }
    }

    /** Whether [userId] has seen the hint on fewer than [maxWakes] wakes; false until read. */
    fun hasWakesLeft(userId: Int, maxWakes: Int): Boolean {
        val shown = wakesShown[userId] ?: return false
        return shown < maxWakes
    }

    /**
     * Counts one more wake with the hint for [userId], written in the background; a count that
     * cannot be written stays counted in memory and never throws on the background thread.
     */
    fun countWake(userId: Int) {
        val shown = (wakesShown[userId] ?: return) + 1
        wakesShown[userId] = shown
        bgExecutor.execute {
            try {
                prefs(userId).edit().putInt(KEY_WAKES_SHOWN, shown).apply()
            } catch (e: RuntimeException) {
                Log.w(TAG, "Cannot write the fingerprint hint count of user $userId", e)
            }
        }
    }

    private fun prefs(userId: Int): SharedPreferences =
        userFileManager.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE, userId)

    @VisibleForTesting
    internal companion object {
        const val TAG = "TallySideFpsHintCounter"
        const val FILE_NAME = "tally_lock_screen"
        const val KEY_WAKES_SHOWN = "side_fps_hint_wakes_shown"
    }
}
