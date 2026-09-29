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

import android.media.MediaMetadata
import android.media.session.PlaybackState
import android.provider.Settings.Secure.MEDIA_CONTROLS_LOCK_SCREEN
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.shared.settings.data.repository.FakeSecureSettingsRepository
import com.android.systemui.tally.lock.TallyLampStripViewModel.Companion.aodItemsFor
import com.android.systemui.tally.lock.TallyLampStripViewModel.Companion.mediaOnLockScreen
import com.android.systemui.tally.lock.TallyLampStripViewModel.Companion.mediaWords
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The always-on strip shows the playing media only where GrapheneOS's always-on line shows it: with
 * "Show media on lock screen" on, and the artist only when the media's notification has an icon.
 * (With the lock screen hiding notifications it shows nothing: TallyLockNotificationsOffTest.)
 */
@SmallTest
@RunWith(AndroidJUnit4::class)
class TallyAodMediaTest : SysuiTestCase() {
    private val settings = FakeSecureSettingsRepository()

    @Test
    fun settingNeverWritten_mediaOnLockScreen() = runTest {
        // As GrapheneOS's line reads it: on unless set.
        assertThat(mediaOnLockScreen(settings).first()).isTrue()
    }

    @Test
    fun showMediaOnLockScreenOff_noMediaOnLockScreen() = runTest {
        settings.setBoolean(MEDIA_CONTROLS_LOCK_SCREEN, false)

        assertThat(mediaOnLockScreen(settings).first()).isFalse()
    }

    @Test
    fun showMediaOnLockScreenOff_alwaysOnLeavesOutOnlyTheMedia() {
        val items =
            aodItemsFor(
                alarmTime = "7:30",
                dnd = true,
                media = "Low Tide",
                notificationsShown = true,
                mediaOnLockScreen = false,
            )

        assertThat(items.map { it.kind })
            .containsExactly(TallyAodItem.Kind.ALARM, TallyAodItem.Kind.CALM)
            .inOrder()
    }

    @Test
    fun showMediaOnLockScreenOn_alwaysOnShowsTheMedia() {
        val items =
            aodItemsFor(
                alarmTime = null,
                dnd = false,
                media = "Low Tide · Marisa Vale",
                notificationsShown = true,
                mediaOnLockScreen = true,
            )

        assertThat(items.single().kind).isEqualTo(TallyAodItem.Kind.MEDIA)
        assertThat(items.single().words).isEqualTo("Low Tide · Marisa Vale")
    }

    @Test
    fun notPlaying_noWords() {
        val metadata = metadata(title = "Low Tide", artist = "Marisa Vale")

        assertThat(mediaWords(metadata, PlaybackState.STATE_PAUSED, true, NO_TITLE)).isNull()
        assertThat(mediaWords(metadata, PlaybackState.STATE_BUFFERING, true, NO_TITLE)).isNull()
        assertThat(mediaWords(null, PlaybackState.STATE_PLAYING, true, NO_TITLE)).isNull()
    }

    @Test
    fun playingWithTheNotificationsIcon_titleAndArtist() {
        val metadata = metadata(title = "Low Tide", artist = "Marisa Vale")

        assertThat(mediaWords(metadata, PlaybackState.STATE_PLAYING, true, NO_TITLE))
            .isEqualTo("Low Tide · Marisa Vale")
    }

    @Test
    fun playingWithoutTheNotificationsIcon_titleOnly() {
        val metadata = metadata(title = "Low Tide", artist = "Marisa Vale")

        assertThat(mediaWords(metadata, PlaybackState.STATE_PLAYING, false, NO_TITLE))
            .isEqualTo("Low Tide")
    }

    @Test
    fun emptyTitle_stocksNoTitle() {
        val metadata = metadata(title = "", artist = null)

        assertThat(mediaWords(metadata, PlaybackState.STATE_PLAYING, true, NO_TITLE))
            .isEqualTo(NO_TITLE)
    }

    private fun metadata(title: String?, artist: String?): MediaMetadata =
        MediaMetadata.Builder()
            .apply {
                title?.let { putString(MediaMetadata.METADATA_KEY_TITLE, it) }
                artist?.let { putString(MediaMetadata.METADATA_KEY_ARTIST, it) }
            }
            .build()

    private companion object {
        const val NO_TITLE = "No title"
    }
}
