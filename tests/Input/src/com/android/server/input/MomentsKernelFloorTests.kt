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
        val cameraToggles = mutableListOf<Pair<Int, Boolean>>()
        var onActionChanged: Runnable? = null
        var onUserSwitched: IntConsumer? = null

        override fun readState(): String? =
            if (unreadable) null
            else "sealed=1 policy=0x3 armed=0x3 enforced=${hex(enforced)} switch=present " +
                "down=${if (blocked != 0) 1 else 0} blocked=${hex(blocked)}\n"

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
    fun notArmed_eachMoveDown_posts() {
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
        // this alerts; if it still shows, the post only updates it (it alerts once).
        floor.onSwitchChanged(false)
        assertThat(platform.noticesPosted).isEqualTo(1)
        floor.onSwitchChanged(true)
        assertThat(platform.notices).containsExactly(BOTH, BOTH)
        // Moving up leaves it.
        floor.onSwitchChanged(false)
        assertThat(platform.noticesCancelled).isEqualTo(0)
    }

    @Test
    fun silentThenSensorsOff_downUpDown_postsOnEachMoveDown() {
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
        platform.action = Settings.Secure.MOMENTS_ACTION_SENSORS_OFF
        platform.enforced = MomentsKernelFloor.KERNEL_CAMERA
        val floor = floor()

        floor.onSwitchChanged(true)

        assertThat(platform.notices).containsExactly(MomentsKernelFloor.KERNEL_MIC)
    }

    @Test
    fun micEnforced_namesOnlyTheCamera() {
        platform.action = Settings.Secure.MOMENTS_ACTION_SENSORS_OFF
        platform.enforced = MomentsKernelFloor.KERNEL_MIC
        val floor = floor()

        platform.blocked = MomentsKernelFloor.KERNEL_MIC
        floor.onSwitchChanged(true)

        assertThat(platform.notices).containsExactly(MomentsKernelFloor.KERNEL_CAMERA)
    }

    @Test
    fun firstReportAtBoot_switchAlreadyDown_posts() {
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

        // Choosing it again posts nothing by itself; the next move down does.
        platform.action = Settings.Secure.MOMENTS_ACTION_SENSORS_OFF
        platform.onActionChanged!!.run()
        assertThat(platform.noticesPosted).isEqualTo(1)
        floor.onSwitchChanged(false)
        floor.onSwitchChanged(true)
        assertThat(platform.noticesPosted).isEqualTo(2)
    }

    @Test
    fun actionChangedAway_neverPosted_noCancel() {
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
            .isEqualTo(context.getString(R.string.moments_restart_action))
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
