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

package com.android.systemui.tally.recents

import android.app.IActivityManager
import android.app.IForegroundServiceObserver
import android.app.job.JobScheduler
import android.content.pm.PackageManager
import android.content.pm.UserInfo
import android.os.Binder
import android.os.Parcel
import android.os.PowerExemptionManager.REASON_ROLE_DIALER
import android.os.PowerExemptionManager.REASON_UNKNOWN
import android.os.UserHandle
import android.testing.TestableLooper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.animation.DialogTransitionAnimator
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.dump.DumpManager
import com.android.systemui.qs.FgsManagerControllerImpl
import com.android.systemui.settings.UserTracker
import com.android.systemui.shade.domain.interactor.FakeShadeDialogContextInteractor
import com.android.systemui.shared.recents.IStoppableAppsListener
import com.android.systemui.statusbar.phone.SystemUIDialog
import com.android.systemui.statusbar.policy.KeyguardStateController
import com.android.systemui.tally.TallyShell
import com.android.systemui.util.DeviceConfigProxyFake
import com.android.systemui.util.concurrency.FakeExecutor
import com.android.systemui.util.time.FakeSystemClock
import com.google.common.truth.Truth.assertThat
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

/** Only the current user's recents app hears about, and stops, what Active apps offers to stop. */
@SmallTest
@RunWith(AndroidJUnit4::class)
@TestableLooper.RunWithLooper
class TallyRecentsStopTest : SysuiTestCase() {

    private val systemClock = FakeSystemClock()
    private val mainExecutor = FakeExecutor(systemClock)
    private val fgsExecutor = FakeExecutor(systemClock)
    private val workExecutor = FakeExecutor(systemClock)
    private val userProfiles = mutableListOf<UserInfo>()
    private val reports = mutableListOf<Set<TallyStoppableApp>>()
    private val listener =
        object : IStoppableAppsListener.Stub() {
            override fun onStoppableAppsChanged(packageNames: Array<String>, userIds: IntArray) {
                reports.add(packageNames.zip(userIds.toList(), ::TallyStoppableApp).toSet())
            }
        }

    private val activityManager = mock<IActivityManager>()
    private val jobScheduler = mock<JobScheduler>()
    private val packageManager = mock<PackageManager>()
    private val userTracker = mock<UserTracker>()
    private val dialogTransitionAnimator = mock<DialogTransitionAnimator>()
    private val broadcastDispatcher = mock<BroadcastDispatcher>()
    private val dumpManager = mock<DumpManager>()
    private val systemUIDialogFactory = mock<SystemUIDialog.Factory>()
    private val keyguardStateController = mock<KeyguardStateController>()

    private lateinit var fgsObserver: IForegroundServiceObserver
    private lateinit var userTrackerCallback: UserTracker.Callback
    private lateinit var underTest: TallyRecentsStop

    @Before
    fun setUp() {
        assumeTrue(TallyShell.isEnabled)
        overrideResource(
            com.android.internal.R.string.config_recentsComponentName,
            "$RECENTS/.RecentsActivity",
        )
        whenever(userTracker.userProfiles).thenReturn(userProfiles)
        for (userId in listOf(0, 10, 11)) {
            doReturn(UserHandle.getUid(userId, RECENTS_APP_ID))
                .whenever(packageManager)
                .getPackageUidAsUser(RECENTS, userId)
        }
        val fmc =
            FgsManagerControllerImpl(
                context.resources,
                mainExecutor,
                fgsExecutor,
                systemClock,
                activityManager,
                jobScheduler,
                packageManager,
                userTracker,
                DeviceConfigProxyFake(),
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
        userTrackerCallback =
            argumentCaptor<UserTracker.Callback>()
                .apply { verify(userTracker).addCallback(capture(), any()) }
                .firstValue
        underTest =
            TallyRecentsStop(
                context,
                fmc,
                packageManager,
                userTracker,
                keyguardStateController,
                mainExecutor,
                workExecutor,
            )
        setUserProfiles(0, 10)
    }

    @Test
    fun setListener_fromRecents_hearsTheCurrentSetAtOnce() {
        startFgs("pkg", 0)
        startFgs("work", 10)

        underTest.setListener(listener, RECENTS_UID)
        runAll()

        assertThat(reports)
            .containsExactly(setOf(TallyStoppableApp("pkg", 0), TallyStoppableApp("work", 10)))
    }

    @Test
    fun setListener_fromAnotherApp_isIgnored() {
        startFgs("pkg", 0)

        underTest.setListener(listener, UserHandle.getUid(0, OTHER_APP_ID))
        runAll()
        startFgs("pkg2", 0)
        runAll()

        assertThat(reports).isEmpty()
    }

    @Test
    fun setListener_fromRecentsOfAnotherUser_isIgnored() {
        startFgs("pkg", 0)

        underTest.setListener(listener, UserHandle.getUid(11, RECENTS_APP_ID))
        runAll()

        assertThat(reports).isEmpty()
    }

    @Test
    fun listener_hearsEachChangeOnce() {
        underTest.setListener(listener, RECENTS_UID)
        runAll()

        val token = startFgs("pkg", 0)
        runAll()
        fgsObserver.onForegroundStateChanged(Binder(), "pkg", 0, true)
        runAll()
        fgsObserver.onForegroundStateChanged(token, "pkg", 0, false)
        runAll()

        assertThat(reports)
            .containsExactly(emptySet<TallyStoppableApp>(), setOf(TallyStoppableApp("pkg", 0)))
            .inOrder()
    }

    @Test
    fun reports_runOnTheirOwnThread_notOnActiveApps() {
        underTest.setListener(listener, RECENTS_UID)
        runAll()

        startFgs("pkg", 0)
        fgsExecutor.runAllReady()

        // Active apps' thread only handed the report over; the report and its reads come after.
        assertThat(reports).containsExactly(emptySet<TallyStoppableApp>())
        workExecutor.runAllReady()
        assertThat(reports)
            .containsExactly(emptySet<TallyStoppableApp>(), setOf(TallyStoppableApp("pkg", 0)))
            .inOrder()
    }

    @Test
    fun setListener_null_removesTheListener() {
        underTest.setListener(listener, RECENTS_UID)
        runAll()

        underTest.setListener(null, RECENTS_UID)
        runAll()
        startFgs("pkg", 0)
        runAll()

        assertThat(reports).containsExactly(emptySet<TallyStoppableApp>())
    }

    @Test
    fun userSwitch_dropsTheListener() {
        startFgs("pkg", 0)
        underTest.setListener(listener, RECENTS_UID)
        runAll()

        setUserProfiles(11)
        startFgs("pkg", 11)
        runAll()

        assertThat(reports).containsExactly(setOf(TallyStoppableApp("pkg", 0)))
    }

    @Test
    fun stopApp_fromRecents_stopsAnAppActiveAppsOffersToStop() {
        startFgs("pkg", 0)
        runAll()

        underTest.stopApp("pkg", 0, RECENTS_UID)
        mainExecutor.runNextReady()
        workExecutor.runNextReady()

        verify(activityManager).stopAppForUser("pkg", 0)
    }

    @Test
    fun stopApp_refused_bringsTheListenerUpToDate() {
        startFgs("pkg", 0)
        underTest.setListener(listener, RECENTS_UID)
        runAll()
        // The app became the default dialer while its service kept running.
        doReturn(REASON_ROLE_DIALER)
            .whenever(activityManager)
            .getBackgroundRestrictionExemptionReason(UserHandle.getUid(0, APP_ID))

        underTest.stopApp("pkg", 0, RECENTS_UID)
        runAll()

        verify(activityManager, never()).stopAppForUser(anyString(), anyInt())
        assertThat(reports)
            .containsExactly(setOf(TallyStoppableApp("pkg", 0)), emptySet<TallyStoppableApp>())
            .inOrder()
    }

    @Test
    fun setListener_again_hearsTheWholeSetAgain() {
        startFgs("pkg", 0)
        underTest.setListener(listener, RECENTS_UID)
        runAll()

        underTest.setListener(listener, RECENTS_UID)
        runAll()

        assertThat(reports)
            .containsExactly(setOf(TallyStoppableApp("pkg", 0)), setOf(TallyStoppableApp("pkg", 0)))
    }

    @Test
    fun stopApp_fromAnotherApp_doesNothing() {
        startFgs("pkg", 0)
        runAll()

        underTest.stopApp("pkg", 0, UserHandle.getUid(0, OTHER_APP_ID))
        runAll()

        verify(activityManager, never()).stopAppForUser(anyString(), anyInt())
    }

    @Test
    fun stopApp_fromRecentsOfAnotherUser_doesNothing() {
        startFgs("pkg", 0)
        runAll()

        underTest.stopApp("pkg", 0, UserHandle.getUid(11, RECENTS_APP_ID))
        runAll()

        verify(activityManager, never()).stopAppForUser(anyString(), anyInt())
    }

    @Test
    fun stopApp_forAnotherUser_doesNothing() {
        startFgs("pkg", 11)
        runAll()

        underTest.stopApp("pkg", 11, RECENTS_UID)
        runAll()

        verify(activityManager, never()).stopAppForUser(anyString(), anyInt())
    }

    @Test
    fun stopApp_forProfileThatUserTrackerNoLongerLists_doesNothing() {
        startFgs("work", 10)
        runAll()
        // The profile is gone for UserTracker; FgsManagerController has not heard of it yet.
        userProfiles.removeIf { it.id == 10 }

        underTest.stopApp("work", 10, RECENTS_UID)
        runAll()

        verify(activityManager, never()).stopAppForUser(anyString(), anyInt())
    }

    @Test
    fun listener_hearsOnlyProfilesThatUserTrackerLists() {
        startFgs("work", 10)
        userProfiles.removeIf { it.id == 10 }

        underTest.setListener(listener, RECENTS_UID)
        runAll()

        assertThat(reports).containsExactly(emptySet<TallyStoppableApp>())
    }

    @Test
    fun setListener_oneOfSystemUisOwnBinders_isNeverCalled() {
        startFgs("pkg", 0)
        // A binder that lives in SystemUI and is not a listener, handed back by Launcher.
        val transactions = mutableListOf<Int>()
        val local =
            object : Binder() {
                override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int) =
                    transactions.add(code)
            }

        underTest.setListener(IStoppableAppsListener.Stub.asInterface(local), RECENTS_UID)
        runAll()
        startFgs("pkg2", 0)
        runAll()

        assertThat(transactions).isEmpty()
    }

    @Test
    fun listener_thatFails_isDroppedWithoutTakingSystemUiDown() {
        var calls = 0
        val failing =
            object : IStoppableAppsListener.Stub() {
                override fun onStoppableAppsChanged(
                    packageNames: Array<String>,
                    userIds: IntArray,
                ) {
                    calls++
                    throw SecurityException("Binder invocation to an incorrect interface")
                }
            }

        underTest.setListener(failing, RECENTS_UID)
        runAll()
        startFgs("pkg", 0)
        runAll()

        assertThat(calls).isEqualTo(1)
    }

    @Test
    fun stopApp_whileLocked_doesNothing() {
        startFgs("pkg", 0)
        runAll()
        whenever(keyguardStateController.isShowing).thenReturn(true)

        underTest.stopApp("pkg", 0, RECENTS_UID)
        runAll()

        verify(activityManager, never()).stopAppForUser(anyString(), anyInt())
    }

    @Test
    fun stopApp_whileOccluded_doesNothing() {
        // The secure camera, the emergency dialer or a ringing alarm over the lock screen.
        startFgs("pkg", 0)
        runAll()
        whenever(keyguardStateController.isShowing).thenReturn(true)
        whenever(keyguardStateController.isOccluded).thenReturn(true)

        underTest.stopApp("pkg", 0, RECENTS_UID)
        runAll()

        verify(activityManager, never()).stopAppForUser(anyString(), anyInt())
    }

    @Test
    fun stopApp_whileDozing_doesNothing() {
        // The always-on display over a lock screen that needs no credential: showing, not locked.
        startFgs("pkg", 0)
        runAll()
        whenever(keyguardStateController.isShowing).thenReturn(true)
        whenever(keyguardStateController.isUnlocked).thenReturn(true)

        underTest.stopApp("pkg", 0, RECENTS_UID)
        runAll()

        verify(activityManager, never()).stopAppForUser(anyString(), anyInt())
    }

    @Test
    fun stopApp_askedWhileLocked_staysRefusedAfterUnlock() {
        startFgs("pkg", 0)
        runAll()
        whenever(keyguardStateController.isShowing).thenReturn(true)

        underTest.stopApp("pkg", 0, RECENTS_UID)
        mainExecutor.runAllReady()
        whenever(keyguardStateController.isShowing).thenReturn(false)
        runAll()

        verify(activityManager, never()).stopAppForUser(anyString(), anyInt())
    }

    @Test
    fun stopApp_withoutPackage_doesNothing() {
        underTest.stopApp(null, 0, RECENTS_UID)
        runAll()

        verify(activityManager, never()).stopAppForUser(anyString(), anyInt())
    }

    /** Runs everything posted to the three threads, including what they post to each other. */
    private fun runAll() {
        while (
            mainExecutor.runAllReady() + fgsExecutor.runAllReady() + workExecutor.runAllReady() > 0
        ) {}
    }

    private fun setUserProfiles(current: Int, vararg profiles: Int) {
        userProfiles.clear()
        userProfiles.add(UserInfo(current, "current:$current", 0))
        profiles.forEach { userProfiles.add(UserInfo(it, "profile:$it", 0)) }
        whenever(userTracker.userId).thenReturn(current)
        userTrackerCallback.onUserChanged(current, context)
        userTrackerCallback.onProfilesChanged(userProfiles)
    }

    private fun startFgs(packageName: String, userId: Int): Binder {
        val uid = UserHandle.getUid(userId, APP_ID)
        doReturn(uid).whenever(packageManager).getPackageUidAsUser(packageName, userId)
        doReturn(REASON_UNKNOWN)
            .whenever(activityManager)
            .getBackgroundRestrictionExemptionReason(uid)
        return Binder().also { fgsObserver.onForegroundStateChanged(it, packageName, userId, true) }
    }

    private companion object {
        const val RECENTS = "com.example.recents"
        const val RECENTS_APP_ID = 10_050
        const val OTHER_APP_ID = 10_060
        const val APP_ID = 10_070
        val RECENTS_UID = UserHandle.getUid(0, RECENTS_APP_ID)
    }
}
