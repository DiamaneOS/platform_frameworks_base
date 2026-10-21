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
import com.android.systemui.tally.moments.MomentsEmergencyWarningState.Change.HIDE
import com.android.systemui.tally.moments.MomentsEmergencyWarningState.Companion.CALLBACK_WINDOW_MS
import com.android.systemui.tally.moments.MomentsEmergencyWarningState.Change.NONE
import com.android.systemui.tally.moments.MomentsEmergencyWarningState.Change.SHOW
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

/** When the warning shows during an emergency call while the switch keeps the microphone off. */
@SmallTest
@RunWith(AndroidJUnit4::class)
class MomentsEmergencyWarningStateTest : SysuiTestCase() {
    private val state = MomentsEmergencyWarningState()

    private fun showing() {
        state.kernelBlocksMic = true
        state.emergencyCall = true
        assertThat(state.update()).isEqualTo(SHOW)
    }

    @Test
    fun emergencyCallWithTheKernelBlock_showsOnce() {
        state.kernelBlocksMic = true
        assertThat(state.update()).isEqualTo(NONE)

        state.emergencyCall = true
        assertThat(state.update()).isEqualTo(SHOW)
        assertThat(state.update()).isEqualTo(NONE)
    }

    @Test
    fun noKernelBlock_noWarning() {
        // The floor is not armed: Android's own block is lifted for the call.
        state.emergencyCall = true

        assertThat(state.update()).isEqualTo(NONE)
    }

    @Test
    fun switchUp_hidesBeforeTheKernelRelease() {
        showing()

        state.switchUp = true
        assertThat(state.update()).isEqualTo(HIDE)
        state.kernelBlocksMic = false
        assertThat(state.update()).isEqualTo(NONE)
    }

    @Test
    fun callEnd_hides() {
        showing()

        state.emergencyCall = false
        assertThat(state.update()).isEqualTo(HIDE)
    }

    @Test
    fun closedByTheUser_returnsOnlyAfterTheSwitchMovesAgain() {
        showing()
        state.onDismissed()
        assertThat(state.update()).isEqualTo(NONE)

        state.switchUp = true
        assertThat(state.update()).isEqualTo(NONE)
        // Closing a hidden dialog is not the user's.
        state.onDismissed()
        state.switchUp = false
        assertThat(state.update()).isEqualTo(SHOW)
    }

    @Test
    fun callWithinTheCallbackWindow_shows() {
        state.kernelBlocksMic = true
        state.onEmergencyCall(nowMs = 0)
        state.onCallsEnded(nowMs = 1_000)
        assertThat(state.update()).isEqualTo(NONE)

        state.onCallOffHook(nowMs = 1_000 + CALLBACK_WINDOW_MS - 1)
        assertThat(state.update()).isEqualTo(SHOW)
        state.onCallsEnded(nowMs = 1_000 + CALLBACK_WINDOW_MS)
        assertThat(state.update()).isEqualTo(HIDE)
    }

    @Test
    fun callAfterTheWindowOrWithoutEmergencyCall_noWarning() {
        state.kernelBlocksMic = true
        state.onCallOffHook(nowMs = 0)
        assertThat(state.update()).isEqualTo(NONE)

        state.onEmergencyCall(nowMs = 0)
        state.onCallsEnded(nowMs = 0)
        state.onCallOffHook(nowMs = CALLBACK_WINDOW_MS)
        assertThat(state.update()).isEqualTo(NONE)
    }
}
