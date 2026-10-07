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
        // What the kernel blocks now; the switch driver blocks only while the line is down.
        var blocked = 0
        var unreadable = false
        var users = intArrayOf(0, 10)
        var action = -1
        val properties = mutableMapOf<String, String>()
        var propertyWrites = 0
        val toggles = mutableListOf<Pair<Int, Boolean>>()
        var onActionChanged: Runnable? = null
        var onUserSwitched: IntConsumer? = null

        override fun readState(): String? =
            if (unreadable) null
            else "sealed=1 policy=0x3 armed=0x3 enforced=${hex(enforced)} switch=present " +
                "down=${if (blocked != 0) 1 else 0} blocked=${hex(blocked)}\n"

        // The kernel prints %#x: "0" for zero, "0x1" otherwise.
        private fun hex(v: Int) = if (v == 0) "0" else "0x" + Integer.toHexString(v)

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

        var noticesPosted = 0
        var noticesCancelled = 0

        override fun postAndroidOnlyMicNotice() {
            noticesPosted++
        }

        override fun cancelAndroidOnlyMicNotice() {
            noticesCancelled++
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

        platform.unreadable = true
        floor()
        assertThat(enforced).isEqualTo("0")
    }

    @Test
    fun kernelBlocks_setsTheHardwareToggleForEveryUser() {
        platform.enforced = MomentsKernelFloor.KERNEL_MIC
        val floor = floor()

        platform.blocked = 3
        floor.onSwitchChanged(true)
        assertThat(platform.toggles).containsExactly(0 to true, 10 to true).inOrder()

        platform.toggles.clear()
        platform.blocked = 0
        floor.onSwitchChanged(false)
        assertThat(platform.toggles).containsExactly(0 to false, 10 to false).inOrder()
    }

    @Test
    fun notEnforced_softwareTogglesStayInCharge() {
        platform.enforced = 0
        val floor = floor()

        platform.blocked = 3
        floor.onSwitchChanged(true)
        floor.onSwitchChanged(false)

        assertThat(platform.toggles).isEmpty()
    }

    @Test
    fun cameraOnlyEnforced_noMicToggle() {
        platform.enforced = MomentsKernelFloor.KERNEL_CAMERA
        val floor = floor()

        platform.blocked = 3
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
    fun clientRegisteredAfterBoot_isPickedUpOnTheNextReport() {
        platform.enforced = 0
        platform.blocked = 3
        val floor = floor()
        floor.onSwitchChanged(true)
        assertThat(platform.toggles).isEmpty()

        platform.enforced = MomentsKernelFloor.KERNEL_MIC
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

        platform.blocked = 3
        floor.onSwitchChanged(true)
        platform.toggles.clear()
        platform.onUserSwitched!!.accept(11)

        assertThat(platform.toggles).containsExactly(11 to true)
    }

    @Test
    fun injectedSwitch_kernelNotBlocking_noHardwareClaim() {
        // An EV_SW injected on the evdev node moves Android's view only; the GPIO, and so the
        // kernel, still say "not blocked".
        platform.enforced = MomentsKernelFloor.KERNEL_MIC
        platform.blocked = 0
        val floor = floor()

        floor.onSwitchChanged(true)

        assertThat(platform.toggles).isEmpty()
    }

    @Test
    fun kernelBlocksWhileAndroidSaysOff_hardwareToggleFollowsTheKernel() {
        // The reverse: an injected "up" while the line is down; the kernel still blocks.
        platform.enforced = MomentsKernelFloor.KERNEL_MIC
        platform.blocked = 3
        val floor = floor()

        floor.onSwitchChanged(false)

        assertThat(platform.toggles).containsExactly(0 to true, 10 to true).inOrder()
    }

    @Test
    fun repeatedReport_readsTheKernelAgain() {
        platform.enforced = MomentsKernelFloor.KERNEL_MIC
        val floor = floor()
        floor.onSwitchChanged(true)
        assertThat(platform.toggles).isEmpty()

        // The real line goes down later; Android's view was already "on".
        platform.blocked = 3
        floor.onSwitchChanged(true)

        assertThat(platform.toggles).containsExactly(0 to true, 10 to true).inOrder()
    }

    @Test
    fun unreadableState_claimsNoKernelBlock() {
        platform.enforced = MomentsKernelFloor.KERNEL_MIC
        platform.blocked = 3
        platform.unreadable = true
        val floor = floor()

        floor.onSwitchChanged(true)

        assertThat(platform.toggles).isEmpty()
    }

    @Test
    fun parseState_readsTheDriversLine() {
        assertThat(
                MomentsKernelFloor.parseState(
                    "sealed=1 policy=0x3 armed=0x3 enforced=0x1 switch=present down=1 blocked=0x3\n"
                )
            )
            .asList()
            .containsExactly(1, 3)
            .inOrder()
        assertThat(MomentsKernelFloor.parseState("enforced=0 blocked=0")).asList()
            .containsExactly(0, 0)
        assertThat(MomentsKernelFloor.parseState("enforced=0x1")).isNull()
        assertThat(MomentsKernelFloor.parseState("enforced=x blocked=0")).isNull()
        assertThat(MomentsKernelFloor.parseState(null)).isNull()
    }

    @Test
    fun notArmed_switchDown_noticeOncePerBoot() {
        platform.action = Settings.Secure.MOMENTS_ACTION_SENSORS_OFF
        // Sealed without the microphone: the kernel enforces nothing.
        val floor = floor()

        floor.onSwitchChanged(false)
        assertThat(platform.noticesPosted).isEqualTo(0)
        floor.onSwitchChanged(true)
        floor.onSwitchChanged(true)
        floor.onSwitchChanged(false)
        floor.onSwitchChanged(true)

        assertThat(platform.noticesPosted).isEqualTo(1)
    }

    @Test
    fun armed_noNotice() {
        platform.action = Settings.Secure.MOMENTS_ACTION_SENSORS_OFF
        platform.enforced = MomentsKernelFloor.KERNEL_MIC
        val floor = floor()

        platform.blocked = MomentsKernelFloor.KERNEL_MIC
        floor.onSwitchChanged(true)

        assertThat(platform.noticesPosted).isEqualTo(0)
    }

    @Test
    fun otherAction_noNotice() {
        platform.action = Settings.Secure.MOMENTS_ACTION_SILENT
        val floor = floor()

        floor.onSwitchChanged(true)

        assertThat(platform.noticesPosted).isEqualTo(0)
    }

    @Test
    fun actionChangedAway_cancelsTheNotice() {
        platform.action = Settings.Secure.MOMENTS_ACTION_SENSORS_OFF
        val floor = floor()
        floor.onSwitchChanged(true)

        platform.action = Settings.Secure.MOMENTS_ACTION_SILENT
        platform.onActionChanged!!.run()

        assertThat(platform.noticesCancelled).isEqualTo(1)
        // Choosing it again in the same boot does not post a second notice.
        platform.action = Settings.Secure.MOMENTS_ACTION_SENSORS_OFF
        platform.onActionChanged!!.run()
        floor.onSwitchChanged(true)
        assertThat(platform.noticesPosted).isEqualTo(1)
    }

    @Test
    fun androidOnlyMicBlock_readsOnlyTheKernelForArming() {
        val sensors = Settings.Secure.MOMENTS_ACTION_SENSORS_OFF
        assertThat(MomentsKernelFloor.androidOnlyMicBlock(sensors, true, 0)).isTrue()
        assertThat(
                MomentsKernelFloor.androidOnlyMicBlock(sensors, true, MomentsKernelFloor.KERNEL_MIC)
            )
            .isFalse()
        assertThat(MomentsKernelFloor.androidOnlyMicBlock(sensors, false, 0)).isFalse()
        assertThat(MomentsKernelFloor.androidOnlyMicBlock(sensors, null, 0)).isFalse()
        assertThat(
                MomentsKernelFloor.androidOnlyMicBlock(Settings.Secure.MOMENTS_ACTION_MOMENTS, true, 0)
            )
            .isFalse()
    }
}
