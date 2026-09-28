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

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Binder
import android.os.IBinder
import android.os.RemoteException
import android.os.UserHandle
import android.util.Log
import androidx.annotation.MainThread
import androidx.annotation.WorkerThread
import com.android.systemui.LauncherProxyService
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.LongRunning
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.qs.FgsManagerControllerImpl
import com.android.systemui.settings.UserTracker
import com.android.systemui.shared.recents.IStoppableAppsListener
import com.android.systemui.statusbar.policy.KeyguardStateController
import com.android.systemui.tally.TallyShell
import dagger.Lazy
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

/**
 * Recents' "Still running · Stop" in the Tally shell, behind `ISystemUiProxy`'s
 * `setStoppableAppsListener` and `stopApp`.
 *
 * Launcher learns only what the Active apps dialog already shows: the package and user of each app
 * that the dialog lists with a Stop button because it runs a foreground service. Stop goes through
 * the dialog's own policy in [FgsManagerControllerImpl], which checks the app again, so Launcher
 * needs no new permission and never stops an app itself. Only the current user's recents app is
 * heard, and only about the current user and its profiles. Like the dialog, which opens only once
 * the keyguard is gone, Stop does nothing while the lock screen shows.
 *
 * The work, with its reads of every app's policy, runs on SystemUI's long-running thread, not on
 * the background thread that the privacy indicators and Active apps itself use.
 */
@SysUISingleton
class TallyRecentsStop
@Inject
constructor(
    @Application private val context: Context,
    private val fgsManagerController: FgsManagerControllerImpl,
    private val packageManager: PackageManager,
    private val userTracker: UserTracker,
    private val keyguardStateController: KeyguardStateController,
    private val launcherProxyService: Lazy<LauncherProxyService>,
    @Main private val mainExecutor: Executor,
    @LongRunning private val workExecutor: Executor,
) {
    private val recentsPackage: String? by lazy {
        ComponentName.unflattenFromString(
                context.getString(com.android.internal.R.string.config_recentsComponentName)
            )
            ?.packageName
    }

    // workExecutor's thread only.
    private var listener: IStoppableAppsListener? = null
    private var listenerDeath: IBinder.DeathRecipient? = null
    private var listenerUserId = UserHandle.USER_NULL
    private var reported: Set<TallyStoppableApp>? = null

    // Main thread only.
    private var watchingConnection = false
    private val connectionListener =
        object : LauncherProxyService.LauncherProxyListener {
            override fun onConnectionChanged(isConnected: Boolean) {
                // SystemUI let go of Launcher: its listener must not outlive that binding.
                if (!isConnected) workExecutor.execute { clearListener() }
            }
        }

    private val reportPending = AtomicBoolean(false)
    // Runs on Active apps' background thread: only hands the report over to workExecutor.
    private val onStoppableAppsChanged = Runnable {
        if (reportPending.compareAndSet(false, true)) {
            workExecutor.execute {
                reportPending.set(false)
                report()
            }
        }
    }

    /** Sets the listener of the recents app with [callingUid], or removes it (null). */
    fun setListener(listener: IStoppableAppsListener?, callingUid: Int) {
        if (TallyShell.isUnexpectedlyInLegacyMode()) return
        workExecutor.execute {
            if (!isCurrentRecentsApp(callingUid)) return@execute
            clearListener()
            if (listener != null) addListener(listener, UserHandle.getUserId(callingUid))
        }
    }

    /**
     * Stops [packageName] in [userId] for Recents' Stop button if the Active apps dialog would, and
     * only while the lock screen is gone.
     */
    fun stopApp(packageName: String?, userId: Int, callingUid: Int) {
        if (TallyShell.isUnexpectedlyInLegacyMode()) return
        // The keyguard state changes on the main thread: read it there, when Recents asks.
        mainExecutor.execute {
            val keyguardShowing = keyguardStateController.isShowing
            workExecutor.execute { stop(packageName, userId, callingUid, keyguardShowing) }
        }
    }

    @WorkerThread
    private fun stop(packageName: String?, userId: Int, callingUid: Int, keyguardShowing: Boolean) {
        if (packageName == null || !isCurrentRecentsApp(callingUid)) return
        if (keyguardShowing) {
            // Also while occluded (the secure camera, the emergency dialer, an alarm) and dozing.
            refuse(packageName, userId, "the lock screen is showing")
        } else if (userTracker.userProfiles.none { it.id == userId }) {
            refuse(packageName, userId, "not the current user or one of its profiles")
        } else if (!fgsManagerController.stopIfStoppable(packageName, userId)) {
            refuse(packageName, userId, "Active apps offers no Stop for it")
        }
    }

    @WorkerThread
    private fun refuse(packageName: String, userId: Int, why: String) {
        Log.w(TAG, "Ignored Stop for $packageName in user $userId: $why")
        // The app's policy may have changed since the last report: tell Recents again.
        report()
    }

    @WorkerThread
    private fun addListener(newListener: IStoppableAppsListener, userId: Int) {
        val binder = newListener.asBinder()
        val local = binder.queryLocalInterface(IStoppableAppsListener.DESCRIPTOR)
        if (binder is Binder && local !is IStoppableAppsListener) {
            // One of SystemUI's own binders handed back: calling it would run another interface's
            // code here, in SystemUI.
            Log.w(TAG, "Ignored a listener that is one of SystemUI's own binders")
            return
        }
        val death =
            IBinder.DeathRecipient {
                workExecutor.execute { if (listener?.asBinder() === binder) clearListener() }
            }
        try {
            binder.linkToDeath(death, 0)
        } catch (e: RemoteException) {
            return // Recents has gone already.
        }
        listener = newListener
        listenerDeath = death
        listenerUserId = userId
        fgsManagerController.init()
        fgsManagerController.addOnStoppableAppsChangedListener(onStoppableAppsChanged)
        mainExecutor.execute { watchConnection() }
        report()
    }

    @MainThread
    private fun watchConnection() {
        if (watchingConnection) return
        watchingConnection = true
        launcherProxyService.get().addCallback(connectionListener)
    }

    @WorkerThread
    private fun clearListener() {
        val old = listener ?: return
        fgsManagerController.removeOnStoppableAppsChangedListener(onStoppableAppsChanged)
        listenerDeath?.let { old.asBinder().unlinkToDeath(it, 0) }
        listener = null
        listenerDeath = null
        listenerUserId = UserHandle.USER_NULL
        reported = null
    }

    @WorkerThread
    private fun report() {
        val current = listener ?: return
        if (listenerUserId != userTracker.userId) {
            // Another user is in front now: that user's recents app hears nothing more.
            clearListener()
            return
        }
        val profiles = userTracker.userProfiles.map { it.id }
        val apps = fgsManagerController.getStoppableApps().filter { it.userId in profiles }
        val set = apps.toSet()
        if (set == reported) return
        try {
            current.onStoppableAppsChanged(
                apps.map { it.packageName }.toTypedArray(),
                apps.map { it.userId }.toIntArray(),
            )
            reported = set
        } catch (e: RemoteException) {
            clearListener()
        } catch (e: RuntimeException) {
            // Whatever the listener does, it must not take SystemUI down with it.
            Log.w(TAG, "Dropped Recents' listener, which failed", e)
            clearListener()
        }
    }

    @WorkerThread
    private fun isCurrentRecentsApp(uid: Int): Boolean {
        val userId = UserHandle.getUserId(uid)
        val recents = recentsPackage
        val isRecents =
            userId == userTracker.userId &&
                recents != null &&
                try {
                    packageManager.getPackageUidAsUser(recents, userId) == uid
                } catch (e: PackageManager.NameNotFoundException) {
                    false
                }
        if (!isRecents) Log.w(TAG, "Ignored uid $uid: not the current user's recents app")
        return isRecents
    }

    private companion object {
        const val TAG = "TallyRecentsStop"
    }
}
