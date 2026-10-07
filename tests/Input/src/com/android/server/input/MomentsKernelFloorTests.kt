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

package com.android.server.input

import android.provider.Settings
import com.google.common.truth.Truth.assertThat
import java.util.function.IntConsumer
import org.junit.Test

/**
 * Tests for [MomentsKernelFloor].
 *
 * Build/Install/Run: atest InputTests:MomentsKernelFloorTests
 */
class MomentsKernelFloorTests {

    private class FakePlatform : MomentsKernelFloor.Platform {
        var enforced = 0
        var users = intArrayOf(0, 10)
        var action = -1
        val properties = mutableMapOf<String, String>()
        var propertyWrites = 0
        val toggles = mutableListOf<Pair<Int, Boolean>>()
        var onActionChanged: Runnable? = null
        var onUserSwitched: IntConsumer? = null

        override fun readEnforced() = enforced

        override fun userIds() = users

        override fun setPhysicalMicToggle(userId: Int, blocked: Boolean) {
            toggles += userId to blocked
        }

        override fun momentsAction() = action

        override fun setProperty(key: String, value: String) {
            properties[key] = value
            propertyWrites++
        }

        override fun watch(onActionChanged: Runnable, onUserSwitched: IntConsumer) {
            this.onActionChanged = onActionChanged
            this.onUserSwitched = onUserSwitched
        }
    }

    private val platform = FakePlatform()

    private fun floor(enabled: Boolean = true) =
        MomentsKernelFloor(platform, enabled).also { it.systemRunning() }

    private val policy
        get() = platform.properties[MomentsKernelFloor.POLICY_PROPERTY]

    private val enforced
        get() = platform.properties[MomentsKernelFloor.ENFORCED_PROPERTY]

    @Test
    fun noKernelFloor_isInert() {
        platform.enforced = MomentsKernelFloor.KERNEL_MIC
        platform.action = Settings.Secure.MOMENTS_ACTION_SENSORS_OFF
        val floor = floor(enabled = false)

        floor.onSwitchChanged(true)

        assertThat(platform.properties).isEmpty()
        assertThat(platform.toggles).isEmpty()
        assertThat(platform.onActionChanged).isNull()
    }

    @Test
    fun policy_followsTheSensorsAction() {
        platform.action = Settings.Secure.MOMENTS_ACTION_SENSORS_OFF
        floor()
        assertThat(policy).isEqualTo("3")

        platform.action = Settings.Secure.MOMENTS_ACTION_SILENT
        platform.onActionChanged!!.run()
        assertThat(policy).isEqualTo("0")

        platform.action = -1
        platform.onActionChanged!!.run()
        assertThat(policy).isEqualTo("0")
    }

    @Test
    fun policy_unsetActionDisarms() {
        floor()
        assertThat(policy).isEqualTo("0")
    }

    @Test
    fun policy_sameValueIsNotWrittenAgain() {
        platform.action = Settings.Secure.MOMENTS_ACTION_MOMENTS
        floor()
        val writes = platform.propertyWrites
        platform.action = Settings.Secure.MOMENTS_ACTION_NOTHING
        platform.onActionChanged!!.run()
        assertThat(platform.propertyWrites).isEqualTo(writes)
    }

    @Test
    fun enforced_isPublishedAndUnreadableCountsAsNothing() {
        platform.enforced = MomentsKernelFloor.KERNEL_MIC
        floor()
        assertThat(enforced).isEqualTo("1")

        platform.enforced = -1
        floor()
        assertThat(enforced).isEqualTo("0")
    }

    @Test
    fun micEnforced_switchOnSetsTheHardwareToggleForEveryUser() {
        platform.enforced = MomentsKernelFloor.KERNEL_MIC
        val floor = floor()

        floor.onSwitchChanged(true)
        assertThat(platform.toggles).containsExactly(0 to true, 10 to true).inOrder()

        platform.toggles.clear()
        floor.onSwitchChanged(false)
        assertThat(platform.toggles).containsExactly(0 to false, 10 to false).inOrder()
    }

    @Test
    fun notEnforced_softwareTogglesStayInCharge() {
        platform.enforced = 0
        val floor = floor()

        floor.onSwitchChanged(true)
        floor.onSwitchChanged(false)

        assertThat(platform.toggles).isEmpty()
    }

    @Test
    fun cameraOnlyEnforced_noMicToggle() {
        platform.enforced = MomentsKernelFloor.KERNEL_CAMERA
        val floor = floor()

        floor.onSwitchChanged(true)

        assertThat(platform.toggles).isEmpty()
    }

    @Test
    fun switchOffAtBoot_neverClearsTheSoftwareToggle() {
        // Turning the hardware toggle off also clears the software toggle; never do that
        // without having turned it on.
        platform.enforced = MomentsKernelFloor.KERNEL_MIC
        val floor = floor()

        floor.onSwitchChanged(false)

        assertThat(platform.toggles).isEmpty()
    }

    @Test
    fun clientRegisteredAfterBoot_isPickedUpOnTheNextChange() {
        platform.enforced = 0
        val floor = floor()
        floor.onSwitchChanged(true)
        assertThat(platform.toggles).isEmpty()

        platform.enforced = MomentsKernelFloor.KERNEL_MIC
        floor.onSwitchChanged(false)
        floor.onSwitchChanged(true)

        assertThat(platform.toggles).containsExactly(0 to true, 10 to true).inOrder()
        assertThat(enforced).isEqualTo("1")
    }

    @Test
    fun userSwitch_newUserGetsTheHardwareToggleWhileBlocked() {
        platform.enforced = MomentsKernelFloor.KERNEL_MIC
        val floor = floor()
        platform.onUserSwitched!!.accept(11)
        assertThat(platform.toggles).isEmpty()

        floor.onSwitchChanged(true)
        platform.toggles.clear()
        platform.onUserSwitched!!.accept(11)

        assertThat(platform.toggles).containsExactly(11 to true)
    }
}
