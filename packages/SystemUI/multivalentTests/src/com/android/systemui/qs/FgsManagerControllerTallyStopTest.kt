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

package com.android.systemui.qs

import android.app.IActivityManager
import android.app.IForegroundServiceObserver
import android.app.job.IUserVisibleJobObserver
import android.app.job.JobScheduler
import android.app.job.UserVisibleJobSummary
import android.content.pm.PackageManager
import android.content.pm.UserInfo
import android.os.Binder
import android.os.PowerExemptionManager.REASON_ACTIVE_DEVICE_ADMIN
import android.os.PowerExemptionManager.REASON_DEVICE_DEMO_MODE
import android.os.PowerExemptionManager.REASON_DEVICE_DPC
import android.os.PowerExemptionManager.REASON_DISALLOW_APPS_CONTROL
import android.os.PowerExemptionManager.REASON_DPO_PROTECTED_APP
import android.os.PowerExemptionManager.REASON_PROC_STATE_PERSISTENT
import android.os.PowerExemptionManager.REASON_PROC_STATE_PERSISTENT_UI
import android.os.PowerExemptionManager.REASON_ROLE_DIALER
import android.os.PowerExemptionManager.REASON_SYSTEM_ALLOW_LISTED
import android.os.PowerExemptionManager.REASON_SYSTEM_EXEMPT_APP_OP
import android.os.PowerExemptionManager.REASON_SYSTEM_MODULE
import android.os.PowerExemptionManager.REASON_SYSTEM_UID
import android.os.PowerExemptionManager.REASON_UNKNOWN
import android.os.PowerExemptionManager.REASON_USER_DPC
import android.os.UserHandle
import android.testing.TestableLooper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.animation.DialogTransitionAnimator
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.dump.DumpManager
import com.android.systemui.settings.UserTracker
import com.android.systemui.shade.domain.interactor.FakeShadeDialogContextInteractor
import com.android.systemui.statusbar.phone.SystemUIDialog
import com.android.systemui.tally.TallyShell
import com.android.systemui.tally.recents.TallyStoppableApp
import com.android.systemui.util.DeviceConfigProxyFake
import com.android.systemui.util.concurrency.FakeExecutor
import com.android.systemui.util.time.FakeSystemClock
import com.google.common.truth.Truth.assertThat
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * Recents' "Still running · Stop" in the Tally shell: [FgsManagerControllerImpl] offers and stops
 * only what the Active apps dialog lists with a Stop button, and checks it again at every Stop.
 */
@SmallTest
@RunWith(AndroidJUnit4::class)
@TestableLooper.RunWithLooper
class FgsManagerControllerTallyStopTest : SysuiTestCase() {

    private val systemClock = FakeSystemClock()
    private val mainExecutor = FakeExecutor(systemClock)
    private val backgroundExecutor = FakeExecutor(systemClock)
    private val deviceConfigProxy = DeviceConfigProxyFake()
    private val userProfiles = mutableListOf<UserInfo>()
    private val appIds = mutableMapOf<String, Int>()

    private val activityManager = mock<IActivityManager>()
    private val jobScheduler = mock<JobScheduler>()
    private val packageManager = mock<PackageManager>()
    private val userTracker = mock<UserTracker>()
    private val dialogTransitionAnimator = mock<DialogTransitionAnimator>()
    private val broadcastDispatcher = mock<BroadcastDispatcher>()
    private val dumpManager = mock<DumpManager>()
    private val systemUIDialogFactory = mock<SystemUIDialog.Factory>()

    private lateinit var fmc: FgsManagerControllerImpl
    private lateinit var fgsObserver: IForegroundServiceObserver
    private lateinit var jobObserver: IUserVisibleJobObserver
    private lateinit var userTrackerCallback: UserTracker.Callback

    @Before
    fun setUp() {
        whenever(userTracker.userProfiles).thenReturn(userProfiles)
        fmc =
            FgsManagerControllerImpl(
                context.resources,
                mainExecutor,
                backgroundExecutor,
                systemClock,
                activityManager,
                jobScheduler,
                packageManager,
                userTracker,
                deviceConfigProxy,
                dialogTransitionAnimator,
                broadcastDispatcher,
                dumpManager,
                systemUIDialogFactory,
                FakeShadeDialogContextInteractor(context),
            )
        fmc.init()
        fgsObserver =
            argumentCaptor<IForegroundServiceObserver>()
                .apply { verify(activityManager).registerForegroundServiceObserver(capture()) }
                .firstValue
        jobObserver =
            argumentCaptor<IUserVisibleJobObserver>()
                .apply { verify(jobScheduler).registerUserVisibleJobObserver(capture()) }
                .firstValue
        userTrackerCallback =
            argumentCaptor<UserTracker.Callback>()
                .apply { verify(userTracker).addCallback(capture(), any()) }
                .firstValue
        setUserProfiles(0)
    }

    @Test
    fun stopIfStoppable_appWithForegroundService_stopsItAsTheDialogDoes() {
        assumeTrue(TallyShell.isEnabled)
        startFgs("pkg", 0)

        assertThat(fmc.stopIfStoppable("pkg", 0)).isTrue()

        verify(jobScheduler).notePendingUserRequestedAppStop("pkg", 0, "task manager")
        verify(activityManager).stopAppForUser("pkg", 0)
    }

    @Test
    fun stopIfStoppable_appWithoutForegroundService_doesNothing() {
        assumeTrue(TallyShell.isEnabled)
        setExemptionReason("pkg", 0, REASON_UNKNOWN)

        assertThat(fmc.stopIfStoppable("pkg", 0)).isFalse()

        verify(activityManager, never()).stopAppForUser(anyString(), anyInt())
    }

    @Test
    fun stopIfStoppable_foregroundServiceEnded_doesNothing() {
        assumeTrue(TallyShell.isEnabled)
        val token = startFgs("pkg", 0)
        fgsObserver.onForegroundStateChanged(token, "pkg", 0, false)

        assertThat(fmc.stopIfStoppable("pkg", 0)).isFalse()

        verify(activityManager, never()).stopAppForUser(anyString(), anyInt())
    }

    @Test
    fun stopIfStoppable_userVisibleJobOnly_doesNothing() {
        assumeTrue(TallyShell.isEnabled)
        startJob("pkg", 0)

        assertThat(fmc.stopIfStoppable("pkg", 0)).isFalse()

        verify(activityManager, never()).stopAppForUser(anyString(), anyInt())
    }

    @Test
    fun stopIfStoppable_appsTheDialogShowsWithoutStop_doNothing() {
        assumeTrue(TallyShell.isEnabled)
        for (reason in NO_STOP_REASONS) {
            startFgs("pkg$reason", 0, reason)

            assertThat(fmc.stopIfStoppable("pkg$reason", 0)).isFalse()
        }

        verify(activityManager, never()).stopAppForUser(anyString(), anyInt())
    }

    @Test
    fun stopIfStoppable_readsThePolicyAgain() {
        assumeTrue(TallyShell.isEnabled)
        startFgs("pkg", 0)
        assertThat(fmc.getStoppableApps()).containsExactly(TallyStoppableApp("pkg", 0))

        // The app became the default dialer while its service kept running.
        setExemptionReason("pkg", 0, REASON_ROLE_DIALER)

        assertThat(fmc.stopIfStoppable("pkg", 0)).isFalse()
        verify(activityManager, never()).stopAppForUser(anyString(), anyInt())
    }

    @Test
    fun stopIfStoppable_workProfileAppOfCurrentUser_stopsIt() {
        assumeTrue(TallyShell.isEnabled)
        setUserProfiles(0, 10)
        startFgs("pkg", 10)

        assertThat(fmc.stopIfStoppable("pkg", 10)).isTrue()

        verify(activityManager).stopAppForUser("pkg", 10)
        verify(activityManager, never()).stopAppForUser("pkg", 0)
    }

    @Test
    fun stopIfStoppable_appOfAnotherUser_doesNothing() {
        assumeTrue(TallyShell.isEnabled)
        startFgs("pkg", 11)

        assertThat(fmc.stopIfStoppable("pkg", 11)).isFalse()

        verify(activityManager, never()).stopAppForUser(anyString(), anyInt())
    }

    @Test
    fun stopIfStoppable_sameAppInAnotherUser_doesNothing() {
        assumeTrue(TallyShell.isEnabled)
        startFgs("pkg", 0)

        assertThat(fmc.stopIfStoppable("pkg", 11)).isFalse()

        verify(activityManager, never()).stopAppForUser(anyString(), anyInt())
    }

    @Test
    fun getStoppableApps_listsOnlyWhatTheDialogShowsWithStop() {
        assumeTrue(TallyShell.isEnabled)
        setUserProfiles(0, 10)
        startFgs("music", 0)
        startFgs("work.sync", 10)
        startFgs("dialer", 0, REASON_ROLE_DIALER)
        startFgs("admin", 10, REASON_ACTIVE_DEVICE_ADMIN)
        startFgs("other.user", 11)
        startJob("job.only", 0)

        assertThat(fmc.getStoppableApps())
            .containsExactly(TallyStoppableApp("music", 0), TallyStoppableApp("work.sync", 10))
    }

    @Test
    fun getStoppableApps_followsTheCurrentUser() {
        assumeTrue(TallyShell.isEnabled)
        startFgs("pkg", 0)
        startFgs("pkg", 11)

        setUserProfiles(11)

        assertThat(fmc.getStoppableApps()).containsExactly(TallyStoppableApp("pkg", 11))
    }

    @Test
    fun stoppableAppsListener_runsOnChangesUntilRemoved() {
        assumeTrue(TallyShell.isEnabled)
        var runs = 0
        val listener = Runnable { runs++ }
        fmc.addOnStoppableAppsChangedListener(listener)

        val token = startFgs("pkg", 0)
        backgroundExecutor.runAllReady()
        assertThat(runs).isEqualTo(1)

        fmc.removeOnStoppableAppsChangedListener(listener)
        fgsObserver.onForegroundStateChanged(token, "pkg", 0, false)
        backgroundExecutor.runAllReady()
        assertThat(runs).isEqualTo(1)
    }

    @Test
    fun withoutTally_nothingIsOfferedOrStopped() {
        assumeFalse(TallyShell.isEnabled)
        var runs = 0
        fmc.addOnStoppableAppsChangedListener { runs++ }
        startFgs("pkg", 0)
        backgroundExecutor.runAllReady()

        assertThat(fmc.getStoppableApps()).isEmpty()
        assertThat(fmc.stopIfStoppable("pkg", 0)).isFalse()
        assertThat(runs).isEqualTo(0)
        verify(activityManager, never()).stopAppForUser(anyString(), anyInt())
    }

    private fun setUserProfiles(current: Int, vararg profiles: Int) {
        userProfiles.clear()
        userProfiles.add(UserInfo(current, "current:$current", 0))
        profiles.forEach { userProfiles.add(UserInfo(it, "profile:$it", 0)) }
        whenever(userTracker.userId).thenReturn(current)
        userTrackerCallback.onUserChanged(current, context)
        userTrackerCallback.onProfilesChanged(userProfiles)
    }

    private fun setExemptionReason(packageName: String, userId: Int, reason: Int) {
        val uid = UserHandle.getUid(userId, appIds.getOrPut(packageName) { 10_000 + appIds.size })
        doReturn(uid).whenever(packageManager).getPackageUidAsUser(packageName, userId)
        doReturn(reason).whenever(activityManager).getBackgroundRestrictionExemptionReason(uid)
    }

    private fun startFgs(packageName: String, userId: Int, reason: Int = REASON_UNKNOWN): Binder {
        setExemptionReason(packageName, userId, reason)
        return Binder().also { fgsObserver.onForegroundStateChanged(it, packageName, userId, true) }
    }

    private fun startJob(packageName: String, userId: Int) {
        setExemptionReason(packageName, userId, REASON_UNKNOWN)
        val uid = UserHandle.getUid(userId, appIds.getValue(packageName))
        jobObserver.onUserVisibleJobStateChanged(
            UserVisibleJobSummary(uid, packageName, userId, packageName, null, 0),
            true,
        )
    }

    private companion object {
        /** Reasons for which the Active apps dialog hides an app's Stop button or the whole app. */
        val NO_STOP_REASONS =
            listOf(
                REASON_SYSTEM_UID,
                REASON_DEVICE_DEMO_MODE,
                REASON_SYSTEM_ALLOW_LISTED,
                REASON_DEVICE_DPC,
                REASON_DISALLOW_APPS_CONTROL,
                REASON_DPO_PROTECTED_APP,
                REASON_USER_DPC,
                REASON_ACTIVE_DEVICE_ADMIN,
                REASON_PROC_STATE_PERSISTENT,
                REASON_PROC_STATE_PERSISTENT_UI,
                REASON_ROLE_DIALER,
                REASON_SYSTEM_MODULE,
                REASON_SYSTEM_EXEMPT_APP_OP,
            )
    }
}
