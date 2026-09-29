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

import android.provider.Settings.Secure.LOCK_SCREEN_SHOW_NOTIFICATIONS
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.shared.settings.data.repository.FakeSecureSettingsRepository
import com.android.systemui.tally.lock.TallyLampStripViewModel.Companion.alarmShows
import com.android.systemui.tally.lock.TallyLampStripViewModel.Companion.aodItemsFor
import com.android.systemui.tally.lock.TallyLampStripViewModel.Companion.lockScreenShowsNotifications
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

/**
 * While the user's lock screen hides notifications, GrapheneOS's smartspace line shows none of its
 * rows, so the lock strip leaves out the alarm and the always-on strip shows nothing.
 */
@SmallTest
@RunWith(AndroidJUnit4::class)
class TallyLockNotificationsOffTest : SysuiTestCase() {
    private val settings = FakeSecureSettingsRepository()

    @Test
    fun settingNeverWritten_notificationsHidden() = runTest {
        // As GrapheneOS's line reads it: off unless set.
        assertThat(lockScreenShowsNotifications(settings).first()).isFalse()
    }

    @Test
    fun lockScreenNotificationsOn_notificationsShown() = runTest {
        settings.setInt(LOCK_SCREEN_SHOW_NOTIFICATIONS, 1)

        assertThat(lockScreenShowsNotifications(settings).first()).isTrue()
    }

    @Test
    fun lockScreenNotificationsOff_notificationsHidden() = runTest {
        settings.setInt(LOCK_SCREEN_SHOW_NOTIFICATIONS, 0)

        assertThat(lockScreenShowsNotifications(settings).first()).isFalse()
    }

    @Test
    fun notificationsHidden_noAlarmEvenWithinTwelveHours() {
        assertThat(alarmShows(NOW + HOUR, NOW, notificationsShown = false)).isFalse()
    }

    @Test
    fun notificationsShown_alarmOnlyWithinTwelveHours() {
        assertThat(alarmShows(NOW + HOUR, NOW, notificationsShown = true)).isTrue()
        assertThat(alarmShows(NOW + 12 * HOUR, NOW, notificationsShown = true)).isTrue()
        assertThat(alarmShows(NOW + 12 * HOUR + 1, NOW, notificationsShown = true)).isFalse()
    }

    @Test
    fun alarmThatIsDueOrPassed_isNotShown() {
        // As GrapheneOS's line: only an alarm still to come (delta > 0).
        assertThat(alarmShows(NOW - 1, NOW, notificationsShown = true)).isFalse()
        assertThat(alarmShows(NOW, NOW, notificationsShown = true)).isFalse()
        assertThat(alarmShows(NOW + 1, NOW, notificationsShown = true)).isTrue()
    }

    @Test
    fun notificationsHidden_alwaysOnShowsNothing() {
        val items =
            aodItemsFor(
                alarmTime = "7:30",
                dnd = true,
                media = "Low Tide · Marisa Vale",
                notificationsShown = false,
                mediaOnLockScreen = true,
            )

        assertThat(items).isEmpty()
    }

    @Test
    fun notificationsShown_alwaysOnShowsTheLinesRows() {
        val items =
            aodItemsFor(
                alarmTime = "7:30",
                dnd = true,
                media = "Low Tide · Marisa Vale",
                notificationsShown = true,
                mediaOnLockScreen = true,
            )

        assertThat(items.map { it.kind })
            .containsExactly(
                TallyAodItem.Kind.ALARM,
                TallyAodItem.Kind.CALM,
                TallyAodItem.Kind.MEDIA,
            )
            .inOrder()
    }

    private companion object {
        val HOUR = TimeUnit.HOURS.toMillis(1)
        const val NOW = 1_790_000_000_000L
    }
}
