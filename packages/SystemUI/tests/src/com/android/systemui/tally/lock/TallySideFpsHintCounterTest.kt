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
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.settings.FakeUserFileManager
import com.android.systemui.tally.lock.TallySideFpsHintCounter.Companion.FILE_NAME
import com.android.systemui.tally.lock.TallySideFpsHintCounter.Companion.KEY_WAKES_SHOWN
import com.android.systemui.util.concurrency.FakeExecutor
import com.android.systemui.util.time.FakeSystemClock
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

/** The fingerprint hint's per-user count: five wakes per user, kept, and safe to read. */
@SmallTest
@RunWith(AndroidJUnit4::class)
class TallySideFpsHintCounterTest : SysuiTestCase() {
    private val files = FakeUserFileManager()
    private val bgExecutor = FakeExecutor(FakeSystemClock())

    private fun counter() = TallySideFpsHintCounter(files, bgExecutor)

    private fun prefs(userId: Int) =
        files.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE, userId)

    @Test
    fun countNotReadYet_noWakesLeft() {
        val counter = counter()

        counter.prefetch(USER)

        assertThat(counter.hasWakesLeft(USER, MAX_WAKES)).isFalse()
    }

    @Test
    fun fiveWakes_thenNone_andKept() {
        val counter = counter()
        counter.prefetch(USER)
        bgExecutor.runAllReady()

        repeat(MAX_WAKES) {
            assertThat(counter.hasWakesLeft(USER, MAX_WAKES)).isTrue()
            counter.countWake(USER)
        }
        bgExecutor.runAllReady()

        assertThat(counter.hasWakesLeft(USER, MAX_WAKES)).isFalse()
        assertThat(prefs(USER).getInt(KEY_WAKES_SHOWN, 0)).isEqualTo(MAX_WAKES)
        val afterRestart = counter()
        afterRestart.prefetch(USER)
        bgExecutor.runAllReady()
        assertThat(afterRestart.hasWakesLeft(USER, MAX_WAKES)).isFalse()
    }

    @Test
    fun valueOfTheWrongType_noWakesLeftAndNothingThrows() {
        prefs(USER).edit().putString(KEY_WAKES_SHOWN, "five").apply()
        val counter = counter()

        counter.prefetch(USER)
        bgExecutor.runAllReady()
        counter.countWake(USER)
        bgExecutor.runAllReady()

        assertThat(counter.hasWakesLeft(USER, MAX_WAKES)).isFalse()
        assertThat(prefs(USER).getString(KEY_WAKES_SHOWN, null)).isEqualTo("five")
    }

    @Test
    fun usersAreCountedApart() {
        prefs(USER).edit().putInt(KEY_WAKES_SHOWN, MAX_WAKES).apply()
        val counter = counter()

        counter.prefetch(USER)
        counter.prefetch(OTHER_USER)
        bgExecutor.runAllReady()

        assertThat(counter.hasWakesLeft(USER, MAX_WAKES)).isFalse()
        assertThat(counter.hasWakesLeft(OTHER_USER, MAX_WAKES)).isTrue()
    }

    private companion object {
        const val USER = 0
        const val OTHER_USER = 10
        const val MAX_WAKES = 5
    }
}
