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

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.SensorPrivacyManager
import android.platform.test.annotations.DisabledOnRavenwood
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import com.android.internal.R
import com.google.common.truth.Truth.assertThat
import java.nio.file.Files
import java.util.function.IntConsumer
import org.junit.Test

/**
 * Tests for [MomentsKernelFloor].
 *
 * Build/Install/Run: atest InputTests:MomentsKernelFloorTests
 */
class MomentsKernelFloorTests {

    private class FakePlatform : MomentsKernelFloor.Platform {
        // What the kernel's policy arms. An armed sensor is enforced only with a client in the
        // kernel for it.
        var armed = 0
        var clients = BOTH
        // What the kernel enforces: armed and with a client. Setting it gives a kernel armed
        // with exactly these sensors and a client for each.
        var enforced: Int
            get() = armed and clients
            set(value) {
                armed = value
                clients = BOTH
            }
        // What the kernel blocks now. The switch driver blocks only while the line is down.
        var blocked = 0
        var unreadable = false
        var users = intArrayOf(0, 10)
        var action = -1
        val properties = mutableMapOf<String, String>()
        var propertyWrites = 0
        // Property and kernel writes in order.
        val events = mutableListOf<String>()
        // Masks written to the kernel's policy file.
        val policyWrites = mutableListOf<Int>()
        // As the kernel: a write that keeps what it enforces is accepted. False refuses all.
        var kernelAccepts = true
        // Reports success but changes nothing.
        var kernelIgnores = false
        // The switch line is down: the kernel blocks what it enforces.
        var down = false
        val toggles = mutableListOf<Pair<Int, Boolean>>()
        val cameraToggles = mutableListOf<Pair<Int, Boolean>>()
        var onActionChanged: Runnable? = null
        var onUserSwitched: IntConsumer? = null

        override fun readState(): String? =
            if (unreadable) null
            else "sealed=1 policy=${hex(armed)} armed=${hex(armed)} enforced=${hex(enforced)} " +
                "switch=present down=${if (blocked != 0) 1 else 0} blocked=${hex(blocked)}\n"

        // The kernel prints %#x: "0" for zero, "0x1" otherwise.
        private fun hex(v: Int) = if (v == 0) "0" else "0x" + Integer.toHexString(v)

        override fun userIds() = users

        override fun setPhysicalToggle(userId: Int, sensor: Int, blocked: Boolean) {
            if (sensor == SensorPrivacyManager.Sensors.MICROPHONE) {
                toggles += userId to blocked
            } else {
                cameraToggles += userId to blocked
            }
        }

        override fun momentsAction() = action

        override fun setProperty(key: String, value: String) {
            properties[key] = value
            propertyWrites++
            events += "property $key=$value"
        }

        override fun writeKernelPolicy(mask: Int): Boolean {
            policyWrites += mask
            events += "kernel $mask"
            if (kernelIgnores) return true
            // Never drops an armed sensor.
            if (!kernelAccepts || (mask and armed) != armed) return false
            armed = mask
            if (down) blocked = armed
            return true
        }

        override fun watch(onActionChanged: Runnable, onUserSwitched: IntConsumer) {
            this.onActionChanged = onActionChanged
            this.onUserSwitched = onUserSwitched
        }

        // What each post named (kernel bits).
        val notices = mutableListOf<Int>()
        val noticesPosted
            get() = notices.size
        var noticesCancelled = 0

        override fun postAndroidOnlyNotice(missing: Int) {
            notices += missing
        }

        override fun cancelAndroidOnlyNotice() {
            noticesCancelled++
        }

        // What Android's own toggles block for the current user (kernel bits). Both by
        // default, as when SystemUI has made the switch's block.
        var androidBlocked = MomentsKernelFloor.KERNEL_MIC or MomentsKernelFloor.KERNEL_CAMERA
        var onAndroidBlockChanged: Runnable? = null

        override fun androidBlocks() = androidBlocked

        override fun watchAndroidBlock(onChanged: Runnable) {
            onAndroidBlockChanged = onChanged
        }

        fun androidNowBlocks(mask: Int) {
            androidBlocked = mask
            onAndroidBlockChanged!!.run()
        }

        var delayed: Runnable? = null
        val delays = mutableListOf<Long>()

        override fun postDelayed(task: Runnable, delayMs: Long) {
            delayed = task
            delays += delayMs
        }

        override fun removeCallbacks(task: Runnable) {
            if (delayed === task) delayed = null
        }

        fun runDelayed() {
            val task = delayed!!
            delayed = null
            task.run()
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
        assertThat(platform.policyWrites).isEmpty()
        assertThat(platform.onActionChanged).isNull()
    }

    @Test
    fun sensorsOffChosen_armsTheKernelAtOnce() {
        // The switch is down and the kernel was sealed without the sensors.
        platform.action = Settings.Secure.MOMENTS_ACTION_SILENT
        platform.down = true
        val floor = floor()
        platform.blocked = 0
        floor.onSwitchChanged(true)
        assertThat(platform.toggles).isEmpty()
        assertThat(enforced).isEqualTo("0")

        platform.action = Settings.Secure.MOMENTS_ACTION_SENSORS_OFF
        platform.onActionChanged!!.run()

        // The kernel took the mask, and the property, the toggles and the notice follow its state.
        assertThat(platform.policyWrites).containsExactly(BOTH)
        assertThat(platform.enforced).isEqualTo(BOTH)
        assertThat(enforced).isEqualTo("3")
        assertThat(policy).isEqualTo("3")
        assertThat(platform.toggles).containsExactly(0 to true, 10 to true).inOrder()
        assertThat(platform.cameraToggles).containsExactly(0 to true, 10 to true).inOrder()
        assertThat(platform.noticesPosted).isEqualTo(0)
    }

    @Test
    fun sensorsOffChosen_propertyIsSetBeforeTheKernelWrite() {
        platform.action = Settings.Secure.MOMENTS_ACTION_SILENT
        floor()
        platform.events.clear()

        platform.action = Settings.Secure.MOMENTS_ACTION_SENSORS_OFF
        platform.onActionChanged!!.run()

        assertThat(platform.events.take(2)).containsExactly(
            "property ${MomentsKernelFloor.POLICY_PROPERTY}=3", "kernel 3").inOrder()
    }

    @Test
    fun sensorsOffChosen_switchUp_armsWithoutBlocking() {
        platform.action = Settings.Secure.MOMENTS_ACTION_SILENT
        val floor = floor()
        floor.onSwitchChanged(false)

        platform.action = Settings.Secure.MOMENTS_ACTION_SENSORS_OFF
        platform.onActionChanged!!.run()

        assertThat(platform.enforced).isEqualTo(BOTH)
        assertThat(enforced).isEqualTo("3")
        assertThat(platform.toggles).isEmpty()
        assertThat(platform.cameraToggles).isEmpty()

        // The next move down blocks, with the kernel and Android and no notice.
        platform.down = true
        platform.blocked = BOTH
        floor.onSwitchChanged(true)
        assertThat(platform.toggles).containsExactly(0 to true, 10 to true).inOrder()
        assertThat(platform.noticesPosted).isEqualTo(0)
    }

    @Test
    fun sensorsOffChosen_kernelRefuses_keepsToday() {
        platform.action = Settings.Secure.MOMENTS_ACTION_SILENT
        platform.kernelAccepts = false
        val floor = floor()

        platform.action = Settings.Secure.MOMENTS_ACTION_SENSORS_OFF
        platform.onActionChanged!!.run()

        // The property still arms the next boot and the kernel enforces nothing.
        assertThat(platform.policyWrites).containsExactly(BOTH)
        assertThat(policy).isEqualTo("3")
        assertThat(enforced).isEqualTo("0")
        assertThat(platform.toggles).isEmpty()

        // The notice comes with the next move down, as before.
        floor.onSwitchChanged(true)
        assertThat(platform.notices).containsExactly(BOTH)
    }

    @Test
    fun sensorsOffChosen_switchDown_kernelRefuses_postsTheNotice() {
        platform.action = Settings.Secure.MOMENTS_ACTION_SILENT
        platform.kernelAccepts = false
        val floor = floor()
        floor.onSwitchChanged(true)
        assertThat(platform.noticesPosted).isEqualTo(0)

        platform.action = Settings.Secure.MOMENTS_ACTION_SENSORS_OFF
        platform.onActionChanged!!.run()

        // Only Android blocks, so the user is told without waiting for another move down.
        assertThat(platform.notices).containsExactly(BOTH)
        assertThat(policy).isEqualTo("3")
    }

    @Test
    fun sensorsOffChosen_switchDown_kernelArmsWithoutAMicClient_namesTheMic() {
        platform.action = Settings.Secure.MOMENTS_ACTION_SILENT
        platform.clients = MomentsKernelFloor.KERNEL_CAMERA
        val floor = floor()
        floor.onSwitchChanged(true)

        platform.action = Settings.Secure.MOMENTS_ACTION_SENSORS_OFF
        platform.onActionChanged!!.run()

        // Armed but not enforced: the state file says the camera only.
        assertThat(platform.armed).isEqualTo(BOTH)
        assertThat(enforced).isEqualTo("2")
        assertThat(platform.notices).containsExactly(MomentsKernelFloor.KERNEL_MIC)
    }

    @Test
    fun sensorsOffChosen_switchDown_noClientAtAll_namesBoth() {
        platform.action = Settings.Secure.MOMENTS_ACTION_SILENT
        platform.clients = 0
        val floor = floor()
        floor.onSwitchChanged(true)

        platform.action = Settings.Secure.MOMENTS_ACTION_SENSORS_OFF
        platform.onActionChanged!!.run()

        assertThat(enforced).isEqualTo("0")
        assertThat(platform.notices).containsExactly(BOTH)
    }

    @Test
    fun sensorsOffChosen_switchDown_kernelRefuses_noticeWaitsForAndroidsBlock() {
        platform.action = Settings.Secure.MOMENTS_ACTION_SILENT
        platform.kernelAccepts = false
        platform.androidBlocked = 0
        val floor = floor()
        floor.onSwitchChanged(true)

        platform.action = Settings.Secure.MOMENTS_ACTION_SENSORS_OFF
        platform.onActionChanged!!.run()
        assertThat(platform.noticesPosted).isEqualTo(0)

        platform.androidNowBlocks(BOTH)
        assertThat(platform.notices).containsExactly(BOTH)
    }

    @Test
    fun sensorsOffChosen_switchUp_kernelRefuses_noticeComesWithTheMoveDown() {
        platform.action = Settings.Secure.MOMENTS_ACTION_SILENT
        platform.kernelAccepts = false
        val floor = floor()
        floor.onSwitchChanged(false)

        platform.action = Settings.Secure.MOMENTS_ACTION_SENSORS_OFF
        platform.onActionChanged!!.run()
        assertThat(platform.noticesPosted).isEqualTo(0)

        floor.onSwitchChanged(true)
        assertThat(platform.notices).containsExactly(BOTH)
    }

    @Test
    fun sensorsOffChosen_writeReportsSuccessButKernelStaysOpen_stateFileCounts() {
        platform.action = Settings.Secure.MOMENTS_ACTION_SILENT
        platform.kernelIgnores = true
        val floor = floor()

        platform.action = Settings.Secure.MOMENTS_ACTION_SENSORS_OFF
        platform.onActionChanged!!.run()

        assertThat(enforced).isEqualTo("0")
        floor.onSwitchChanged(true)
        assertThat(platform.notices).containsExactly(BOTH)
    }

    @Test
    fun sensorsOffChosen_kernelEnforcesOnlyTheMic_addsTheCamera() {
        platform.enforced = MomentsKernelFloor.KERNEL_MIC
        platform.action = Settings.Secure.MOMENTS_ACTION_SILENT
        floor()

        platform.action = Settings.Secure.MOMENTS_ACTION_SENSORS_OFF
        platform.onActionChanged!!.run()

        assertThat(platform.policyWrites).containsExactly(BOTH)
        assertThat(enforced).isEqualTo("3")
    }

    @Test
    fun sensorsOffChosen_kernelEnforcesBothAlready_noWrite() {
        platform.enforced = BOTH
        platform.action = Settings.Secure.MOMENTS_ACTION_SILENT
        floor()

        platform.action = Settings.Secure.MOMENTS_ACTION_SENSORS_OFF
        platform.onActionChanged!!.run()

        assertThat(platform.policyWrites).isEmpty()
        assertThat(policy).isEqualTo("3")
    }

    @Test
    fun systemRunning_sensorsOffChosenButKernelOpen_armsIt() {
        // The property did not reach init, or the kernel was sealed without the sensors.
        platform.action = Settings.Secure.MOMENTS_ACTION_SENSORS_OFF
        floor()

        assertThat(platform.policyWrites).containsExactly(BOTH)
        assertThat(enforced).isEqualTo("3")
    }

    @Test
    fun systemRunning_sensorsOffChosenAndArmed_noWrite() {
        platform.enforced = BOTH
        platform.action = Settings.Secure.MOMENTS_ACTION_SENSORS_OFF
        floor()

        assertThat(platform.policyWrites).isEmpty()
    }

    @Test
    fun leavingSensorsOff_neverWritesTheKernel_andAndroidKeepsTheKernelsBlock() {
        platform.enforced = BOTH
        platform.action = Settings.Secure.MOMENTS_ACTION_SENSORS_OFF
        val floor = floor()
        platform.down = true
        platform.blocked = BOTH
        floor.onSwitchChanged(true)
        platform.toggles.clear()
        platform.cameraToggles.clear()

        platform.action = Settings.Secure.MOMENTS_ACTION_SILENT
        platform.onActionChanged!!.run()

        // Only the property changes, for the next boot. The kernel still enforces and blocks.
        assertThat(policy).isEqualTo("0")
        assertThat(platform.policyWrites).isEmpty()
        assertThat(enforced).isEqualTo("3")
        assertThat(platform.toggles).isEmpty()
        assertThat(platform.cameraToggles).isEmpty()

        // Android's hardware toggles follow the kernel until it releases them.
        floor.onSwitchChanged(true)
        assertThat(platform.toggles).isEmpty()
        platform.down = false
        platform.blocked = 0
        floor.onSwitchChanged(false)
        assertThat(platform.cameraToggles).containsExactly(0 to false, 10 to false).inOrder()
        platform.runDelayed()
        assertThat(platform.toggles).containsExactly(0 to false, 10 to false).inOrder()
    }

    @Test
    fun choosingSensorsOffAgainWhileTheKernelStillEnforces_noWrite() {
        platform.enforced = BOTH
        platform.action = Settings.Secure.MOMENTS_ACTION_SENSORS_OFF
        floor()
        platform.action = Settings.Secure.MOMENTS_ACTION_SILENT
        platform.onActionChanged!!.run()

        platform.action = Settings.Secure.MOMENTS_ACTION_SENSORS_OFF
        platform.onActionChanged!!.run()

        assertThat(platform.policyWrites).isEmpty()
        assertThat(policy).isEqualTo("3")
    }

    @Test
    fun otherActions_neverWriteTheKernel() {
        floor()
        for (action in listOf(
            Settings.Secure.MOMENTS_ACTION_MOMENTS,
            Settings.Secure.MOMENTS_ACTION_SILENT,
            Settings.Secure.MOMENTS_ACTION_AIRPLANE,
            Settings.Secure.MOMENTS_ACTION_NOTHING,
            Settings.Secure.MOMENTS_ACTION_LOCKDOWN,
            -1,
        )) {
            platform.action = action
            platform.onActionChanged!!.run()
        }

        assertThat(platform.policyWrites).isEmpty()
    }

    @Test
    fun refusedBootWrite_thenChosenAgain_armsAndTheNoticeStaysGone() {
        platform.action = Settings.Secure.MOMENTS_ACTION_SENSORS_OFF
        platform.kernelAccepts = false
        val floor = floor()
        floor.onSwitchChanged(true)
        assertThat(platform.notices).containsExactly(BOTH)

        platform.action = Settings.Secure.MOMENTS_ACTION_SILENT
        platform.onActionChanged!!.run()
        assertThat(platform.noticesCancelled).isEqualTo(1)

        platform.kernelAccepts = true
        platform.action = Settings.Secure.MOMENTS_ACTION_SENSORS_OFF
        platform.onActionChanged!!.run()
        floor.onSwitchChanged(true)

        assertThat(enforced).isEqualTo("3")
        assertThat(platform.noticesPosted).isEqualTo(1)
    }

    @Test
    fun policyPath_isTheStateFilesSibling() {
        assertThat(MomentsKernelFloor.policyPath("/sys/kernel/privacy_switch/state").toString())
            .isEqualTo("/sys/kernel/privacy_switch/policy")
    }

    @Test
    fun writePolicyFile_writesTheMaskAndNeverCreatesTheFile() {
        val dir = Files.createTempDirectory("privsw")
        try {
            val file = dir.resolve("policy")

            // Opened for writing only: a missing file stays missing.
            assertThat(MomentsKernelFloor.writePolicyFile(file, BOTH)).isFalse()
            assertThat(Files.exists(file)).isFalse()

            Files.writeString(file, "0")
            assertThat(MomentsKernelFloor.writePolicyFile(file, BOTH)).isTrue()
            assertThat(Files.readString(file)).isEqualTo("3")
        } finally {
            dir.toFile().deleteRecursively()
        }
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
        platform.runDelayed()
        assertThat(platform.toggles).containsExactly(0 to false, 10 to false).inOrder()
    }

    @Test
    fun kernelReleasesTheMic_androidWaits_cameraDoesNot() {
        platform.enforced = BOTH
        val floor = floor()
        platform.blocked = BOTH
        floor.onSwitchChanged(true)
        platform.toggles.clear()
        platform.cameraToggles.clear()

        platform.blocked = 0
        floor.onSwitchChanged(false)
        // A repeated report does not start the wait again.
        floor.onSwitchChanged(false)

        assertThat(platform.toggles).isEmpty()
        assertThat(platform.cameraToggles).containsExactly(0 to false, 10 to false).inOrder()
        assertThat(platform.delays).containsExactly(MomentsKernelFloor.MIC_RELEASE_DELAY_MS)
    }

    @Test
    fun moveDownDuringTheWait_micStaysBlocked() {
        platform.enforced = MomentsKernelFloor.KERNEL_MIC
        val floor = floor()
        platform.blocked = BOTH
        floor.onSwitchChanged(true)
        platform.blocked = 0
        floor.onSwitchChanged(false)
        platform.toggles.clear()

        platform.blocked = BOTH
        floor.onSwitchChanged(true)

        assertThat(platform.delayed).isNull()
        assertThat(platform.toggles).isEmpty()
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
    fun cameraOnlyEnforced_cameraToggleOnly() {
        platform.enforced = MomentsKernelFloor.KERNEL_CAMERA
        val floor = floor()

        platform.blocked = 3
        floor.onSwitchChanged(true)

        assertThat(platform.toggles).isEmpty()
        assertThat(platform.cameraToggles).containsExactly(0 to true, 10 to true).inOrder()
    }

    @Test
    fun bothEnforced_eachSensorFollowsItsOwnBit() {
        platform.enforced = MomentsKernelFloor.KERNEL_MIC or MomentsKernelFloor.KERNEL_CAMERA
        val floor = floor()

        // Only the camera bit blocked (a kernel with the camera client only blocking).
        platform.blocked = MomentsKernelFloor.KERNEL_CAMERA
        floor.onSwitchChanged(true)
        assertThat(platform.toggles).isEmpty()
        assertThat(platform.cameraToggles).containsExactly(0 to true, 10 to true).inOrder()

        platform.cameraToggles.clear()
        platform.blocked = 3
        floor.onSwitchChanged(true)
        assertThat(platform.toggles).containsExactly(0 to true, 10 to true).inOrder()
        assertThat(platform.cameraToggles).isEmpty()

        platform.blocked = 0
        floor.onSwitchChanged(false)
        assertThat(platform.cameraToggles).containsExactly(0 to false, 10 to false).inOrder()
    }

    @Test
    fun userSwitch_newUserGetsBothHardwareToggles() {
        platform.enforced = MomentsKernelFloor.KERNEL_MIC or MomentsKernelFloor.KERNEL_CAMERA
        val floor = floor()
        platform.blocked = 3
        floor.onSwitchChanged(true)
        platform.toggles.clear()
        platform.cameraToggles.clear()

        platform.onUserSwitched!!.accept(11)

        assertThat(platform.toggles).containsExactly(11 to true)
        assertThat(platform.cameraToggles).containsExactly(11 to true)
    }

    @Test
    fun switchOffAtBoot_neverClearsTheSoftwareToggle() {
        // Turning the hardware toggle off also clears the software toggle. Never do that
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
        // An EV_SW injected on the evdev node moves Android's view only. The GPIO, and so the
        // kernel, still say "not blocked".
        platform.enforced = MomentsKernelFloor.KERNEL_MIC
        platform.blocked = 0
        val floor = floor()

        floor.onSwitchChanged(true)

        assertThat(platform.toggles).isEmpty()
    }

    @Test
    fun kernelBlocksWhileAndroidSaysOff_hardwareToggleFollowsTheKernel() {
        // The reverse: an injected "up" while the line is down. The kernel still blocks.
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

        // The real line goes down later. Android's view was already "on".
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
    fun notArmed_eachMoveDown_posts() {
        platform.kernelAccepts = false
        platform.action = Settings.Secure.MOMENTS_ACTION_SENSORS_OFF
        // Sealed without the camera and microphone: the kernel enforces nothing.
        val floor = floor()

        floor.onSwitchChanged(false)
        assertThat(platform.noticesPosted).isEqualTo(0)
        floor.onSwitchChanged(true)
        assertThat(platform.notices).containsExactly(BOTH)
        // Repeated reports while down are not a move down.
        floor.onSwitchChanged(true)
        assertThat(platform.noticesPosted).isEqualTo(1)

        // Up and down again at once: posted again. If the user dismissed it,
        // this alerts. If it still shows, the post only updates it (it alerts once).
        floor.onSwitchChanged(false)
        assertThat(platform.noticesPosted).isEqualTo(1)
        floor.onSwitchChanged(true)
        assertThat(platform.notices).containsExactly(BOTH, BOTH)
        // Moving up leaves it.
        floor.onSwitchChanged(false)
        assertThat(platform.noticesCancelled).isEqualTo(0)
    }

    @Test
    fun notArmed_postsOnlyOnceAndroidBlocksBoth() {
        platform.kernelAccepts = false
        platform.action = Settings.Secure.MOMENTS_ACTION_SENSORS_OFF
        platform.androidBlocked = 0
        val floor = floor()

        // SystemUI blocks a moment after the move, one sensor at a time.
        floor.onSwitchChanged(true)
        assertThat(platform.noticesPosted).isEqualTo(0)
        platform.androidNowBlocks(MomentsKernelFloor.KERNEL_CAMERA)
        assertThat(platform.noticesPosted).isEqualTo(0)
        platform.androidNowBlocks(BOTH)
        assertThat(platform.notices).containsExactly(BOTH)

        // Later toggle changes do not post it again.
        platform.androidNowBlocks(MomentsKernelFloor.KERNEL_CAMERA)
        platform.androidNowBlocks(BOTH)
        assertThat(platform.noticesPosted).isEqualTo(1)
    }

    @Test
    fun notArmed_androidDoesNotBlock_noNotice() {
        platform.kernelAccepts = false
        platform.action = Settings.Secure.MOMENTS_ACTION_SENSORS_OFF
        // As during an emergency call: the microphone toggle cannot change.
        platform.androidBlocked = 0
        val floor = floor()

        floor.onSwitchChanged(true)
        platform.androidNowBlocks(MomentsKernelFloor.KERNEL_CAMERA)
        floor.onSwitchChanged(true)
        assertThat(platform.noticesPosted).isEqualTo(0)

        // Up again: a block made later is not the switch's move, so nothing is posted.
        floor.onSwitchChanged(false)
        platform.androidNowBlocks(BOTH)
        assertThat(platform.noticesPosted).isEqualTo(0)
    }

    @Test
    fun silentThenSensorsOff_downUpDown_postsOnEachMoveDown() {
        platform.kernelAccepts = false
        platform.action = Settings.Secure.MOMENTS_ACTION_SILENT
        val floor = floor()
        floor.onSwitchChanged(false)

        platform.action = Settings.Secure.MOMENTS_ACTION_SENSORS_OFF
        platform.onActionChanged!!.run()
        assertThat(platform.noticesPosted).isEqualTo(0)

        floor.onSwitchChanged(true)
        floor.onSwitchChanged(false)
        floor.onSwitchChanged(true)

        assertThat(platform.notices).containsExactly(BOTH, BOTH)
        assertThat(platform.noticesCancelled).isEqualTo(0)
    }

    @Test
    fun cameraEnforced_namesOnlyTheMicrophone() {
        platform.kernelAccepts = false
        platform.action = Settings.Secure.MOMENTS_ACTION_SENSORS_OFF
        platform.enforced = MomentsKernelFloor.KERNEL_CAMERA
        val floor = floor()

        floor.onSwitchChanged(true)

        assertThat(platform.notices).containsExactly(MomentsKernelFloor.KERNEL_MIC)
    }

    @Test
    fun micEnforced_namesOnlyTheCamera() {
        platform.kernelAccepts = false
        platform.action = Settings.Secure.MOMENTS_ACTION_SENSORS_OFF
        platform.enforced = MomentsKernelFloor.KERNEL_MIC
        val floor = floor()

        platform.blocked = MomentsKernelFloor.KERNEL_MIC
        floor.onSwitchChanged(true)

        assertThat(platform.notices).containsExactly(MomentsKernelFloor.KERNEL_CAMERA)
    }

    @Test
    fun firstReportAtBoot_switchAlreadyDown_posts() {
        platform.kernelAccepts = false
        platform.action = Settings.Secure.MOMENTS_ACTION_SENSORS_OFF
        val floor = floor()

        floor.onSwitchChanged(true)

        assertThat(platform.noticesPosted).isEqualTo(1)
    }

    @Test
    fun armed_noNotice() {
        platform.action = Settings.Secure.MOMENTS_ACTION_SENSORS_OFF
        platform.enforced = BOTH
        val floor = floor()

        platform.blocked = BOTH
        floor.onSwitchChanged(true)

        assertThat(platform.noticesPosted).isEqualTo(0)
    }

    @Test
    fun kernelEnforcesBothLater_cancelsTheNotice() {
        platform.kernelAccepts = false
        // A kernel client registered after boot: the next report sees both enforced.
        platform.action = Settings.Secure.MOMENTS_ACTION_SENSORS_OFF
        platform.enforced = MomentsKernelFloor.KERNEL_MIC
        val floor = floor()
        floor.onSwitchChanged(true)
        assertThat(platform.notices).containsExactly(MomentsKernelFloor.KERNEL_CAMERA)

        platform.enforced = BOTH
        platform.blocked = BOTH
        floor.onSwitchChanged(true)

        assertThat(platform.noticesCancelled).isEqualTo(1)
        assertThat(platform.noticesPosted).isEqualTo(1)
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
        platform.kernelAccepts = false
        platform.action = Settings.Secure.MOMENTS_ACTION_SENSORS_OFF
        val floor = floor()
        floor.onSwitchChanged(true)

        platform.action = Settings.Secure.MOMENTS_ACTION_SILENT
        platform.onActionChanged!!.run()
        assertThat(platform.noticesCancelled).isEqualTo(1)
        // Another change away does not cancel again.
        platform.action = Settings.Secure.MOMENTS_ACTION_NOTHING
        platform.onActionChanged!!.run()
        assertThat(platform.noticesCancelled).isEqualTo(1)

        // Choosing it again with the switch down and a kernel that stays open tells the user at
        // once. Each later move down does too.
        platform.action = Settings.Secure.MOMENTS_ACTION_SENSORS_OFF
        platform.onActionChanged!!.run()
        assertThat(platform.noticesPosted).isEqualTo(2)
        floor.onSwitchChanged(false)
        floor.onSwitchChanged(true)
        assertThat(platform.noticesPosted).isEqualTo(3)
    }

    @Test
    fun actionChangedAway_neverPosted_noCancel() {
        platform.kernelAccepts = false
        platform.action = Settings.Secure.MOMENTS_ACTION_SENSORS_OFF
        floor()

        platform.action = Settings.Secure.MOMENTS_ACTION_SILENT
        platform.onActionChanged!!.run()

        assertThat(platform.noticesCancelled).isEqualTo(0)
    }

    @Test
    fun androidOnlyBlock_readsOnlyTheKernelForArming() {
        val sensors = Settings.Secure.MOMENTS_ACTION_SENSORS_OFF
        val mic = MomentsKernelFloor.KERNEL_MIC
        val camera = MomentsKernelFloor.KERNEL_CAMERA
        assertThat(MomentsKernelFloor.androidOnlyBlock(sensors, true, 0)).isEqualTo(BOTH)
        assertThat(MomentsKernelFloor.androidOnlyBlock(sensors, true, mic)).isEqualTo(camera)
        assertThat(MomentsKernelFloor.androidOnlyBlock(sensors, true, camera)).isEqualTo(mic)
        assertThat(MomentsKernelFloor.androidOnlyBlock(sensors, true, BOTH)).isEqualTo(0)
        assertThat(MomentsKernelFloor.androidOnlyBlock(sensors, false, 0)).isEqualTo(0)
        assertThat(MomentsKernelFloor.androidOnlyBlock(sensors, null, 0)).isEqualTo(0)
        assertThat(
                MomentsKernelFloor.androidOnlyBlock(Settings.Secure.MOMENTS_ACTION_MOMENTS, true, 0)
            )
            .isEqualTo(0)
    }

    @Test
    @DisabledOnRavenwood(blockedBy = [Notification::class])
    fun notice_staysUntilDismissed_alertsOnce_hasRestart() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val open = PendingIntent.getActivity(context, 0, Intent("test.OPEN"),
            PendingIntent.FLAG_IMMUTABLE)
        val restart = PendingIntent.getBroadcast(context, 0,
            Intent("test.RESTART").setPackage(context.packageName), PendingIntent.FLAG_IMMUTABLE)

        val n = MomentsKernelFloor.buildNotice(context, "channel", BOTH, open, restart)

        assertThat(n.flags and Notification.FLAG_AUTO_CANCEL).isEqualTo(0)
        assertThat(n.flags and Notification.FLAG_ONLY_ALERT_ONCE).isNotEqualTo(0)
        assertThat(n.flags and Notification.FLAG_ONGOING_EVENT).isEqualTo(0)
        assertThat(n.contentIntent).isSameInstanceAs(open)
        assertThat(n.actions).hasLength(1)
        assertThat(n.actions[0].title.toString())
            .isEqualTo(context.getString(R.string.global_action_restart))
        assertThat(n.actions[0].actionIntent).isSameInstanceAs(restart)
        assertThat(n.actions[0].actionIntent.isImmutable).isTrue()
    }

    @Test
    @DisabledOnRavenwood(blockedBy = [Notification::class])
    fun notice_namesWhatOnlyAndroidBlocks() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val pending = PendingIntent.getBroadcast(context, 0,
            Intent("test.RESTART").setPackage(context.packageName), PendingIntent.FLAG_IMMUTABLE)
        fun texts(missing: Int) =
            MomentsKernelFloor.buildNotice(context, "channel", missing, pending, pending).extras
                .let {
                    it.getCharSequence(Notification.EXTRA_TITLE).toString() to
                        it.getCharSequence(Notification.EXTRA_TEXT).toString()
                }

        assertThat(texts(BOTH)).isEqualTo(
            context.getString(R.string.moments_android_only_title) to
                context.getString(R.string.moments_android_only_text))
        assertThat(texts(MomentsKernelFloor.KERNEL_MIC)).isEqualTo(
            context.getString(R.string.moments_android_only_mic_title) to
                context.getString(R.string.moments_android_only_mic_text))
        assertThat(texts(MomentsKernelFloor.KERNEL_CAMERA)).isEqualTo(
            context.getString(R.string.moments_android_only_camera_title) to
                context.getString(R.string.moments_android_only_camera_text))
        for (missing in listOf(BOTH, MomentsKernelFloor.KERNEL_MIC,
                MomentsKernelFloor.KERNEL_CAMERA)) {
            assertThat(texts(missing).toList().joinToString(" ")).doesNotContain("hardware")
        }
    }

    private companion object {
        const val BOTH = MomentsKernelFloor.KERNEL_MIC or MomentsKernelFloor.KERNEL_CAMERA
    }
}
