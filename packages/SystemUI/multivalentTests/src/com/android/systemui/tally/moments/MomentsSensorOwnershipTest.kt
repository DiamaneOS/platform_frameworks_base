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

package com.android.systemui.tally.moments

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.res.R
import com.android.systemui.tally.moments.MomentsEffectKind.CAMERA
import com.android.systemui.tally.moments.MomentsEffectKind.MICROPHONE
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Which sensor blocks are the Moments switch's (no prompt, a note instead) and which are the
 * user's own (GrapheneOS's prompt), and how often the note shows.
 */
@SmallTest
@RunWith(AndroidJUnit4::class)
class MomentsSensorOwnershipTest : SysuiTestCase() {
    private val blocked = mutableSetOf<MomentsEffectKind>()
    private val ownership = MomentsSensorOwnership()

    private fun record(vararg kinds: MomentsEffectKind) = kinds.toSet()

    private fun isSwitchBlock(kind: MomentsEffectKind, hardware: Boolean = false) =
        ownership.isSwitchBlock(kind, kind in blocked, hardware)

    @Test
    fun switchBlock_isTheSwitchs() {
        ownership.onRecord(record()) { it in blocked }
        blocked += setOf(CAMERA, MICROPHONE)
        ownership.onRecord(record(CAMERA, MICROPHONE)) { it in blocked }

        assertThat(isSwitchBlock(CAMERA)).isTrue()
        assertThat(isSwitchBlock(MICROPHONE)).isTrue()
    }

    @Test
    fun usersOwnBlock_keepsThePrompt() {
        ownership.onRecord(record()) { it in blocked }
        blocked += CAMERA // from Quick Settings, switch off

        assertThat(isSwitchBlock(CAMERA)).isFalse()
    }

    @Test
    fun alreadyBlockedByUser_whenTheSwitchCameOn_staysTheUsers() {
        // The switch found the camera blocked, so its record holds no change for it.
        blocked += CAMERA
        ownership.onRecord(record()) { it in blocked }
        ownership.onRecord(MomentsSensorOwnership.inRecord(listOf(
            MomentsApplied(MomentsEffect(CAMERA), null),
        ))) { it in blocked }

        assertThat(isSwitchBlock(CAMERA)).isFalse()
    }

    @Test
    fun userUnblocksAndBlocksAgainWhileOn_becomesTheUsers() {
        ownership.onRecord(record()) { it in blocked }
        blocked += CAMERA
        ownership.onRecord(record(CAMERA)) { it in blocked }

        blocked -= CAMERA
        ownership.onSoftwareUnblocked(CAMERA)
        blocked += CAMERA
        ownership.onRecord(record(CAMERA)) { it in blocked }

        assertThat(isSwitchBlock(CAMERA)).isFalse()
    }

    @Test
    fun flipOffAndOn_isTheSwitchsAgain() {
        ownership.onRecord(record()) { it in blocked }
        blocked += CAMERA
        ownership.onRecord(record(CAMERA)) { it in blocked }
        ownership.onSoftwareUnblocked(CAMERA)
        blocked -= CAMERA
        ownership.onRecord(record()) { it in blocked }

        blocked += CAMERA
        ownership.onRecord(record(CAMERA)) { it in blocked }

        assertThat(isSwitchBlock(CAMERA)).isTrue()
    }

    @Test
    fun systemUiRestart_countsRecordedBlocksStillOn() {
        blocked += MICROPHONE
        ownership.onRecord(record(CAMERA, MICROPHONE)) { it in blocked }

        assertThat(isSwitchBlock(MICROPHONE)).isTrue()
        // The camera was unblocked while SystemUI was down; a later block is the user's.
        blocked += CAMERA
        assertThat(isSwitchBlock(CAMERA)).isFalse()
    }

    @Test
    fun kernelHardwareBlock_isAlwaysTheSwitchs() {
        ownership.onRecord(record()) { it in blocked }

        assertThat(isSwitchBlock(MICROPHONE, hardware = true)).isTrue()
    }

    @Test
    fun note_rateLimitedAndNamesOnlyWhatIsBlocked() {
        val throttle = MomentsSensorNoteThrottle(minGapMs = 10_000)

        assertThat(throttle.shouldShow(setOf(CAMERA), 0)).isTrue()
        assertThat(throttle.shouldShow(setOf(CAMERA), 5_000)).isFalse()
        // More than the last note named: show.
        assertThat(throttle.shouldShow(setOf(CAMERA, MICROPHONE), 6_000)).isTrue()
        assertThat(throttle.shouldShow(setOf(MICROPHONE), 7_000)).isFalse()
        assertThat(throttle.shouldShow(setOf(MICROPHONE), 16_001)).isTrue()
        assertThat(throttle.shouldShow(emptySet(), 40_000)).isFalse()

        assertThat(MomentsSensorNoteThrottle.text(setOf(CAMERA, MICROPHONE)))
            .isEqualTo(R.string.tally_moments_note_camera_mic_off)
        assertThat(MomentsSensorNoteThrottle.text(setOf(CAMERA)))
            .isEqualTo(R.string.tally_moments_note_camera_off)
        assertThat(MomentsSensorNoteThrottle.text(setOf(MICROPHONE)))
            .isEqualTo(R.string.tally_moments_note_mic_off)
    }

    @Test
    fun note_turnedOff_isSilent() {
        val throttle = MomentsSensorNoteThrottle(minGapMs = 10_000)

        assertThat(throttle.shouldShow(setOf(CAMERA, MICROPHONE), 0, enabled = false)).isFalse()
        // Turning it back on shows the next one at once.
        assertThat(throttle.shouldShow(setOf(CAMERA), 1_000, enabled = true)).isTrue()
    }
}
