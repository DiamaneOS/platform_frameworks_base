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

import android.os.Handler
import android.os.Looper
import android.provider.Settings.Secure
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.res.R
import com.android.systemui.tally.moments.MomentsEffectKind.AIRPLANE
import com.android.systemui.tally.moments.MomentsEffectKind.CAMERA
import com.android.systemui.tally.moments.MomentsEffectKind.DND
import com.android.systemui.tally.moments.MomentsEffectKind.GREYSCALE
import com.android.systemui.tally.moments.MomentsEffectKind.HOME
import com.android.systemui.tally.moments.MomentsEffectKind.LOCKDOWN
import com.android.systemui.tally.moments.MomentsEffectKind.MICROPHONE
import com.android.systemui.tally.moments.MomentsEffectKind.PAUSE_APPS
import com.android.systemui.tally.moments.MomentsEffectKind.RINGER
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Moments switch's state machine: what it changes and takes back, when it waits for the
 * unlock, that boot and restarts change nothing twice, and what it does before it is set up.
 */
@SmallTest
@RunWith(AndroidJUnit4::class)
class MomentsReconcilerTest : SysuiTestCase() {

    /** A phone whose settings start as [settings]; it records every call. */
    private class FakePlatform : MomentsPlatform {
        val settings = mutableMapOf<MomentsEffectKind, Boolean>()
        val calls = mutableListOf<String>()
        val unsupported = mutableSetOf<MomentsEffectKind>()

        override fun apply(effect: MomentsEffect): MomentsOutcome {
            calls += "apply ${effect.kind}"
            if (effect.kind in unsupported) return MomentsOutcome.UNSUPPORTED
            if (effect.kind == LOCKDOWN) return MomentsOutcome.ALREADY
            if (settings[effect.kind] == true) return MomentsOutcome.ALREADY
            settings[effect.kind] = true
            return MomentsOutcome.changed("1")
        }

        override fun revert(effect: MomentsEffect, undo: String) {
            calls += "revert ${effect.kind}"
            if (settings[effect.kind] == true) settings[effect.kind] = false
        }

        override fun reassert(effect: MomentsEffect) {
            calls += "reassert ${effect.kind}"
        }
    }

    private class FakeStore : MomentsStore {
        val records = mutableMapOf<Int, String>()
        val flipped = mutableSetOf<Int>()
        val notices = mutableSetOf<Int>()

        // Stored as text, as on the phone, so the codec is covered too.
        override fun record(user: Int) = MomentsRecordCodec.decode(records[user])

        override fun setRecord(user: Int, record: List<MomentsApplied>) {
            records[user] = MomentsRecordCodec.encode(record)
        }

        override fun hasFlipped(user: Int) = user in flipped

        override fun setFlipped(user: Int) {
            flipped += user
        }

        override fun noticeShown(user: Int) = user in notices

        override fun setNoticeShown(user: Int) {
            notices += user
        }
    }

    private val platform = FakePlatform()
    private val store = FakeStore()

    private fun reconciler() = MomentsReconciler(platform, store)

    private fun inputs(
        on: Boolean?,
        action: Int? = Secure.MOMENTS_ACTION_MOMENTS,
        flipped: Boolean = false,
        unlocked: Boolean = true,
        setupComplete: Boolean = true,
        pausedApps: List<String> = emptyList(),
        greyscale: Boolean = false,
        offline: Int = Secure.MOMENTS_OFFLINE_AIRPLANE,
    ) =
        MomentsInputs(
            on = on,
            flipped = flipped,
            setupComplete = setupComplete,
            unlocked = unlocked,
            config = MomentsConfig(action, pausedApps, greyscale, offline),
        )

    @Test
    fun moments_onThenOff_appliesAndTakesBack() {
        val r = reconciler()

        val on = r.reconcile(USER, inputs(on = true, flipped = true, pausedApps = listOf("a.b")))
        assertThat(on.inEffect).containsExactly(DND, HOME, PAUSE_APPS)
        assertThat(platform.settings).containsExactly(DND, true, HOME, true, PAUSE_APPS, true)

        val off = r.reconcile(USER, inputs(on = false, flipped = true, pausedApps = listOf("a.b")))
        assertThat(off.reverted).containsExactly(DND, HOME, PAUSE_APPS)
        assertThat(platform.settings.values).containsExactly(false, false, false)
        assertThat(store.record(USER)).isEmpty()
    }

    @Test
    fun alreadySet_isLeftAsTheUserHadIt() {
        platform.settings[DND] = true
        val r = reconciler()

        r.reconcile(USER, inputs(on = true))
        r.reconcile(USER, inputs(on = false))

        // The user had Do Not Disturb on before: the switch did not change it, so it stays on.
        assertThat(platform.settings[DND]).isTrue()
        assertThat(platform.calls).doesNotContain("revert DND")
    }

    @Test
    fun bootAndRestart_changeNothingTwice() {
        reconciler().reconcile(USER, inputs(on = true, greyscale = true))
        platform.calls.clear()

        // A new reconciler on the same store, as after a reboot or a SystemUI restart.
        val again = reconciler().reconcile(USER, inputs(on = true, greyscale = true))

        assertThat(again.inEffect).isEmpty()
        assertThat(again.reverted).isEmpty()
        // Only greyscale, which the system forgets on reboot, is set again.
        assertThat(platform.calls).containsExactly("reassert GREYSCALE")
    }

    @Test
    fun sensorsOff_raisesOnTheLockScreen() {
        val r = reconciler()

        r.reconcile(
            USER,
            inputs(on = true, action = Secure.MOMENTS_ACTION_SENSORS_OFF, unlocked = false),
        )

        assertThat(platform.settings).containsExactly(CAMERA, true, MICROPHONE, true)
    }

    @Test
    fun sensorsOff_lowersOnlyAfterTheUnlock() {
        val r = reconciler()
        r.reconcile(USER, inputs(on = true, action = Secure.MOMENTS_ACTION_SENSORS_OFF))

        val locked =
            r.reconcile(
                USER,
                inputs(on = false, action = Secure.MOMENTS_ACTION_SENSORS_OFF, unlocked = false),
            )
        assertThat(locked.waitingForUnlock).isTrue()
        assertThat(platform.settings).containsExactly(CAMERA, true, MICROPHONE, true)

        val unlocked =
            r.reconcile(USER, inputs(on = false, action = Secure.MOMENTS_ACTION_SENSORS_OFF))
        assertThat(unlocked.waitingForUnlock).isFalse()
        assertThat(platform.settings).containsExactly(CAMERA, false, MICROPHONE, false)
    }

    @Test
    fun moments_onTheLockScreen_takesBackWhatDoesNotLowerProtection() {
        val r = reconciler()
        r.reconcile(USER, inputs(on = true, pausedApps = listOf("a.b")))

        r.reconcile(USER, inputs(on = false, unlocked = false, pausedApps = listOf("a.b")))

        // Do Not Disturb and Home come back at once; paused apps wait for the unlock.
        assertThat(platform.settings).containsExactly(DND, false, HOME, false, PAUSE_APPS, true)
    }

    @Test
    fun flipBackBeforeUnlock_keepsWhatIsStillWaiting() {
        val r = reconciler()
        r.reconcile(USER, inputs(on = true, action = Secure.MOMENTS_ACTION_OFFLINE))
        r.reconcile(
            USER,
            inputs(on = false, action = Secure.MOMENTS_ACTION_OFFLINE, unlocked = false),
        )
        platform.calls.clear()

        r.reconcile(USER, inputs(on = true, action = Secure.MOMENTS_ACTION_OFFLINE))

        assertThat(platform.calls).isEmpty()
        assertThat(platform.settings).containsExactly(AIRPLANE, true)
    }

    @Test
    fun lockdown_happensOnEveryFlipOnAndIsNeverUndone() {
        val r = reconciler()
        val both = Secure.MOMENTS_OFFLINE_AIRPLANE or Secure.MOMENTS_OFFLINE_LOCKDOWN
        val action = Secure.MOMENTS_ACTION_OFFLINE

        r.reconcile(USER, inputs(on = true, action = action, offline = both))
        r.reconcile(USER, inputs(on = true, action = action, offline = both))
        r.reconcile(USER, inputs(on = false, action = action, offline = both))
        r.reconcile(USER, inputs(on = true, action = action, offline = both))

        assertThat(platform.calls.filter { it.endsWith("LOCKDOWN") })
            .containsExactly("apply LOCKDOWN", "apply LOCKDOWN")
        assertThat(platform.calls.indexOf("apply AIRPLANE"))
            .isLessThan(platform.calls.indexOf("apply LOCKDOWN"))
    }

    @Test
    fun silent_restoresTheRingerOnlyIfStillSilent() {
        val r = reconciler()
        r.reconcile(USER, inputs(on = true, action = Secure.MOMENTS_ACTION_SILENT))
        // The user changes the ringer themselves meanwhile.
        platform.settings[RINGER] = false

        r.reconcile(USER, inputs(on = false, action = Secure.MOMENTS_ACTION_SILENT))

        assertThat(platform.calls).contains("revert RINGER")
        assertThat(platform.settings[RINGER]).isFalse()
    }

    @Test
    fun changingTheActionWhileOn_swapsTheChanges() {
        val r = reconciler()
        r.reconcile(USER, inputs(on = true))

        r.reconcile(USER, inputs(on = true, action = Secure.MOMENTS_ACTION_SILENT))

        assertThat(platform.settings).containsExactly(DND, false, HOME, false, RINGER, true)
    }

    @Test
    fun nothing_changesNothing() {
        val result =
            reconciler().reconcile(USER, inputs(on = true, action = Secure.MOMENTS_ACTION_NOTHING))

        assertThat(result.inEffect).isEmpty()
        assertThat(platform.calls).isEmpty()
    }

    @Test
    fun sensorsOff_unsupported_isNotInEffectAndNoToast() {
        platform.unsupported += setOf(CAMERA, MICROPHONE)
        val action = Secure.MOMENTS_ACTION_SENSORS_OFF

        val result = reconciler().reconcile(USER, inputs(on = true, action = action))

        assertThat(result.inEffect).isEmpty()
        assertThat(MomentsToast.text(true, action, result)).isNull()
    }

    @Test
    fun sensorsOff_cameraOnly_toastNamesOnlyTheCamera() {
        platform.unsupported += MICROPHONE
        val action = Secure.MOMENTS_ACTION_SENSORS_OFF
        val r = reconciler()

        val on = r.reconcile(USER, inputs(on = true, action = action))
        val off = r.reconcile(USER, inputs(on = false, action = action))

        assertThat(MomentsToast.text(true, action, on))
            .isEqualTo(R.string.tally_moments_toast_camera_blocked)
        assertThat(MomentsToast.text(false, action, off))
            .isEqualTo(R.string.tally_moments_toast_camera_unblocked)
    }

    @Test
    fun lockdownWithoutSecureLock_noToast() {
        platform.unsupported += LOCKDOWN
        val action = Secure.MOMENTS_ACTION_OFFLINE

        val result =
            reconciler()
                .reconcile(
                    USER,
                    inputs(on = true, action = action, offline = Secure.MOMENTS_OFFLINE_LOCKDOWN),
                )

        assertThat(result.inEffect).isEmpty()
        assertThat(MomentsToast.text(true, action, result)).isNull()
    }

    @Test
    fun toasts_nameWhatHappened() {
        val r = reconciler()

        val on = r.reconcile(USER, inputs(on = true))
        assertThat(MomentsToast.text(true, null, on)).isEqualTo(R.string.tally_moments_toast_on)
        val off = r.reconcile(USER, inputs(on = false))
        assertThat(MomentsToast.text(false, null, off)).isEqualTo(R.string.tally_moments_toast_off)

        // The ringer was already silent: nothing to say either way.
        platform.settings[RINGER] = true
        val silent = Secure.MOMENTS_ACTION_SILENT
        val silentOn = r.reconcile(USER, inputs(on = true, action = silent))
        assertThat(MomentsToast.text(true, silent, silentOn))
            .isEqualTo(R.string.tally_moments_toast_silent_on)
        val silentOff = r.reconcile(USER, inputs(on = false, action = silent))
        assertThat(MomentsToast.text(false, silent, silentOff)).isNull()
    }

    @Test
    fun settingsObserver_firesForAnAppThatIsNotSystemUid() {
        var changes = 0
        val observer = MomentsSettingsObserver(Handler(Looper.getMainLooper())) { changes++ }

        // ContentService's delivery path: the hidden user-id form, which for any uid but the
        // system's goes on to the Uri forms, never the UserHandle one.
        observer.onChange(
            false,
            listOf(Secure.getUriFor(Secure.TALLY_MOMENTS_ACTION)),
            0,
            USER,
        )

        assertThat(changes).isEqualTo(1)
    }

    @Test
    fun beforeSetup_doesNothing() {
        val result =
            reconciler()
                .reconcile(USER, inputs(on = true, action = null, flipped = true, setupComplete = false))

        assertThat(result).isEqualTo(MomentsResult())
        assertThat(platform.calls).isEmpty()
        assertThat(store.hasFlipped(USER)).isFalse()
    }

    @Test
    fun firstBoot_switchOnButNotChosen_noticeOnceAndNoAction() {
        val r = reconciler()

        val first = r.reconcile(USER, inputs(on = true, action = null))
        val second = r.reconcile(USER, inputs(on = true, action = null))

        assertThat(first.showNotice).isTrue()
        assertThat(second.showNotice).isFalse()
        assertThat(platform.calls).isEmpty()
    }

    @Test
    fun firstBoot_switchOff_noNotice() {
        val result = reconciler().reconcile(USER, inputs(on = false, action = null))

        assertThat(result.showNotice).isFalse()
        assertThat(platform.calls).isEmpty()
    }

    @Test
    fun firstBoot_savingAChoiceWhileOn_appliesIt() {
        val r = reconciler()
        r.reconcile(USER, inputs(on = true, action = null))

        val saved = r.reconcile(USER, inputs(on = true, action = Secure.MOMENTS_ACTION_SILENT))

        assertThat(saved.inEffect).containsExactly(RINGER)
        assertThat(saved.dismissNotice).isTrue()
        assertThat(platform.settings).containsExactly(RINGER, true)
    }

    @Test
    fun firstBoot_aFlipChoosesTheDefault() {
        val r = reconciler()
        r.reconcile(USER, inputs(on = true, action = null))

        // Off, then on again: the default, Moments, applies.
        val off = r.reconcile(USER, inputs(on = false, action = null, flipped = true))
        assertThat(off.dismissNotice).isTrue()
        assertThat(platform.calls).isEmpty()

        r.reconcile(USER, inputs(on = true, action = null, flipped = true))
        assertThat(platform.settings).containsExactly(DND, true, HOME, true)
    }

    @Test
    fun unknownPosition_changesNothing() {
        val r = reconciler()
        r.reconcile(USER, inputs(on = true))
        platform.calls.clear()

        r.reconcile(USER, inputs(on = null))

        assertThat(platform.calls).isEmpty()
    }

    @Test
    fun usersKeepTheirOwnRecords() {
        val r = reconciler()
        r.reconcile(USER, inputs(on = true, action = Secure.MOMENTS_ACTION_SILENT))

        r.reconcile(OTHER_USER, inputs(on = true, action = Secure.MOMENTS_ACTION_SILENT))

        // The ringer was silent already for the second user: nothing to take back for them.
        assertThat(store.record(OTHER_USER).single().undo).isNull()
        assertThat(store.record(USER).single().undo).isEqualTo("1")
    }

    @Test
    fun codec_roundTrips() {
        val record =
            listOf(
                MomentsApplied(MomentsEffect(PAUSE_APPS, "a.b,c.d"), "a.b"),
                MomentsApplied(MomentsEffect(RINGER), "2"),
                MomentsApplied(MomentsEffect(LOCKDOWN), null),
                MomentsApplied(MomentsEffect(GREYSCALE), ""),
            )

        assertThat(MomentsRecordCodec.decode(MomentsRecordCodec.encode(record)))
            .containsExactlyElementsIn(record)
            .inOrder()
    }

    private companion object {
        const val USER = 0
        const val OTHER_USER = 10
    }
}
