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
import android.os.IBinder
import android.os.RemoteException
import android.os.UserHandle
import android.util.Log
import androidx.annotation.WorkerThread
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.qs.FgsManagerControllerImpl
import com.android.systemui.settings.UserTracker
import com.android.systemui.shared.recents.IStoppableAppsListener
import com.android.systemui.tally.TallyShell
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
 * heard, and only about the current user and its profiles. The work runs on the background thread.
 */
@SysUISingleton
class TallyRecentsStop
@Inject
constructor(
    @Application private val context: Context,
    private val fgsManagerController: FgsManagerControllerImpl,
    private val packageManager: PackageManager,
    private val userTracker: UserTracker,
    @Background private val backgroundExecutor: Executor,
) {
    private val recentsPackage: String? by lazy {
        ComponentName.unflattenFromString(
                context.getString(com.android.internal.R.string.config_recentsComponentName)
            )
            ?.packageName
    }

    // Background thread only.
    private var listener: IStoppableAppsListener? = null
    private var listenerDeath: IBinder.DeathRecipient? = null
    private var listenerUserId = UserHandle.USER_NULL
    private var reported: Set<TallyStoppableApp>? = null

    private val reportPending = AtomicBoolean(false)
    private val onStoppableAppsChanged = Runnable {
        if (reportPending.compareAndSet(false, true)) {
            backgroundExecutor.execute {
                reportPending.set(false)
                report()
            }
        }
    }

    /** Sets the listener of the recents app with [callingUid], or removes it (null). */
    fun setListener(listener: IStoppableAppsListener?, callingUid: Int) {
        if (TallyShell.isUnexpectedlyInLegacyMode()) return
        backgroundExecutor.execute {
            if (!isCurrentRecentsApp(callingUid)) return@execute
            clearListener()
            if (listener != null) addListener(listener, UserHandle.getUserId(callingUid))
        }
    }

    /** Stops [packageName] in [userId] for Recents' Stop button if the Active apps dialog would. */
    fun stopApp(packageName: String?, userId: Int, callingUid: Int) {
        if (TallyShell.isUnexpectedlyInLegacyMode()) return
        backgroundExecutor.execute {
            if (packageName == null || !isCurrentRecentsApp(callingUid)) return@execute
            val stopped =
                userTracker.userProfiles.any { it.id == userId } &&
                    fgsManagerController.stopIfStoppable(packageName, userId)
            if (!stopped) {
                Log.w(TAG, "Ignored Stop: Active apps offers none for $packageName in user $userId")
                // The app's policy may have changed since the last report: tell Recents again.
                report()
            }
        }
    }

    @WorkerThread
    private fun addListener(newListener: IStoppableAppsListener, userId: Int) {
        val binder = newListener.asBinder()
        val death =
            IBinder.DeathRecipient {
                backgroundExecutor.execute { if (listener?.asBinder() === binder) clearListener() }
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
        report()
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
