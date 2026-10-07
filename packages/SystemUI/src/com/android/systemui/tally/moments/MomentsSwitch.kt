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

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.input.IInputManager
import android.hardware.input.IMomentsSwitchListener
import android.hardware.input.InputManager
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.RemoteException
import android.os.ServiceManager
import android.os.UserHandle
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.provider.Settings.Secure
import android.util.Log
import android.widget.Toast
import com.android.systemui.BootCompleteCache
import com.android.systemui.CoreStartable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.keyguard.WakefulnessLifecycle
import com.android.systemui.res.R
import com.android.systemui.settings.UserTracker
import com.android.systemui.statusbar.VibratorHelper
import com.android.systemui.statusbar.policy.DeviceProvisionedController
import com.android.systemui.statusbar.policy.KeyguardStateController
import com.android.systemui.util.NotificationChannels
import com.android.systemui.util.concurrency.DelayableExecutor
import java.io.PrintWriter
import java.util.concurrent.Executor
import javax.inject.Inject

/**
 * The Moments switch: the built-in slider on devices that set `config_momentsSwitchCode`.
 *
 * It follows the switch through the input service (the only reader, behind a signature
 * permission), waits [SETTLE_MS] so that a quick flip back does nothing, and hands the result to
 * [MomentsReconciler]. Each move gives a short haptic tick at once and, once settled, a brief
 * toast. Boot, a SystemUI restart, a user switch, the unlock, a change in Settings and the
 * screen turning on all reconcile again; the reconciler makes each change only once.
 *
 * All work runs on the background thread, one step at a time.
 */
@SysUISingleton
class MomentsSwitch
@Inject
constructor(
    @Application private val context: Context,
    @Background private val bgExecutor: DelayableExecutor,
    @Main private val mainExecutor: Executor,
    private val userTracker: UserTracker,
    private val keyguardStateController: KeyguardStateController,
    private val deviceProvisionedController: DeviceProvisionedController,
    private val wakefulnessLifecycle: WakefulnessLifecycle,
    private val vibratorHelper: VibratorHelper,
    private val bootCompleteCache: BootCompleteCache,
    private val sensorGuard: MomentsSensorGuard,
    platform: MomentsPlatformImpl,
    store: MomentsStoreImpl,
) : CoreStartable {

    private val reconciler = MomentsReconciler(platform, store)
    private val store: MomentsStore = store

    // Background thread only.
    private var lastReported: Boolean? = null
    private var lastReconciled: Boolean? = null
    private var settle: Runnable? = null
    private var lastResult: MomentsResult? = null

    private var inputManager: IInputManager? = null

    private val listener =
        object : IMomentsSwitchListener.Stub() {
            override fun onMomentsSwitchChanged(whenNanos: Long, on: Boolean) {
                bgExecutor.execute { onReport(on) }
            }
        }

    // BootCompleteCache keeps listeners as weak references: this field holds this one.
    private val bootCompleteListener =
        object : BootCompleteCache.BootCompleteListener {
            override fun onBootComplete() = requery()
        }

    private val settingsObserver =
        MomentsSettingsObserver(Handler(Looper.getMainLooper())) { reconcileSoon() }

    override fun start() {
        if (switchCode(context) < 0) return
        val im = IInputManager.Stub.asInterface(ServiceManager.getService(Context.INPUT_SERVICE))
        inputManager = im
        try {
            // The service answers at once with the current position.
            im.registerMomentsSwitchListener(listener)
        } catch (e: RemoteException) {
            Log.e(TAG, "Could not follow the switch", e)
            return
        }
        sensorGuard.start { user -> store.record(user) }
        for (key in SETTINGS_KEYS) {
            context.contentResolver.registerContentObserver(
                Secure.getUriFor(key),
                false,
                settingsObserver,
                UserHandle.USER_ALL,
            )
        }
        userTracker.addCallback(
            object : UserTracker.Callback {
                override fun onUserChanged(newUser: Int, userContext: Context) = reconcileSoon()
            },
            bgExecutor,
        )
        keyguardStateController.addCallback(
            object : KeyguardStateController.Callback {
                override fun onUnlockedChanged() {
                    if (keyguardStateController.isUnlocked) reconcileSoon()
                }
            }
        )
        deviceProvisionedController.addCallback(
            object : DeviceProvisionedController.DeviceProvisionedListener {
                override fun onDeviceProvisionedChanged() = reconcileSoon()

                override fun onUserSetupChanged() = reconcileSoon()
            }
        )
        // Read the position again once boot completes and on each wake-up, in case a report
        // was lost (the input reader drops events after a buffer overrun).
        if (bootCompleteCache.addListener(bootCompleteListener)) requery()
        wakefulnessLifecycle.addObserver(
            object : WakefulnessLifecycle.Observer {
                override fun onStartedWakingUp() = requery()
            }
        )
    }

    private fun requery() {
        bgExecutor.execute {
            val on = query() ?: return@execute
            if (on != lastReported) onReport(on)
        }
    }

    private fun query(): Boolean? =
        try {
            when (inputManager?.momentsSwitchState) {
                InputManager.SWITCH_STATE_ON -> true
                InputManager.SWITCH_STATE_OFF -> false
                else -> null
            }
        } catch (e: RemoteException) {
            null
        }

    private fun onReport(on: Boolean) {
        val previous = lastReported
        lastReported = on
        if (previous != null && previous != on && isSetupComplete(userTracker.userId)) {
            vibratorHelper.vibrate(
                Process.myUid(),
                context.opPackageName,
                VibrationEffect.get(VibrationEffect.EFFECT_TICK),
                "Moments switch",
                HAPTIC_ATTRIBUTES,
            )
        }
        settle?.run()
        settle = bgExecutor.executeDelayed({
            settle = null
            reconcile(fromSwitch = true)
        }, SETTLE_MS)
    }

    private fun reconcileSoon() {
        bgExecutor.execute { if (settle == null) reconcile(fromSwitch = false) }
    }

    private fun reconcile(fromSwitch: Boolean) {
        val on = lastReported
        val flipped = fromSwitch && on != null && lastReconciled != null && on != lastReconciled
        val user = userTracker.userId
        val config = readConfig(user)
        val result =
            reconciler.reconcile(
                user,
                MomentsInputs(
                    on = on,
                    flipped = flipped,
                    setupComplete = isSetupComplete(user),
                    unlocked = keyguardStateController.isUnlocked,
                    config = config,
                ),
            )
        if (on != null) lastReconciled = on
        lastResult = result
        sensorGuard.refresh()
        if (result.showNotice) postNotice(user)
        if (result.dismissNotice) cancelNotice(user)
        if (flipped && on != null) {
            MomentsToast.text(on, config.action, result)?.let { text ->
                mainExecutor.execute { Toast.makeText(context, text, Toast.LENGTH_SHORT).show() }
            }
        }
    }

    private fun isSetupComplete(user: Int) =
        deviceProvisionedController.isDeviceProvisioned &&
            deviceProvisionedController.isUserSetup(user)

    private fun readConfig(user: Int): MomentsConfig {
        val resolver = context.contentResolver
        val action =
            MomentsConfig.migratedAction(
                Secure.getStringForUser(resolver, Secure.TALLY_MOMENTS_ACTION, user)
                    ?.toIntOrNull(),
                Secure.getStringForUser(resolver, Secure.TALLY_MOMENTS_OFFLINE, user)
                    ?.toIntOrNull(),
                keyguardStateController.isMethodSecure,
            )
        val paused =
            Secure.getStringForUser(resolver, Secure.TALLY_MOMENTS_PAUSED_APPS, user)
                ?.split(',')
                ?.filter { it.isNotBlank() } ?: emptyList()
        return MomentsConfig(
            action = action,
            pausedApps = paused,
            greyscale = Secure.getIntForUser(resolver, Secure.TALLY_MOMENTS_GREYSCALE, 0, user) == 1,
        )
    }

    private fun postNotice(user: Int) {
        val handle = UserHandle.of(user)
        val intent = Intent(ACTION_SETTINGS).setPackage(SETTINGS_PACKAGE)
        val pending =
            PendingIntent.getActivityAsUser(
                context,
                0,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                null,
                handle,
            )
        val notification =
            Notification.Builder(context, NotificationChannels.SETUP)
                .setSmallIcon(R.drawable.ic_settings)
                .setContentTitle(context.getString(R.string.tally_moments_notice_title))
                .setContentText(context.getString(R.string.tally_moments_notice_text))
                .setContentIntent(pending)
                .setAutoCancel(true)
                .setLocalOnly(true)
                .build()
        context
            .getSystemService(NotificationManager::class.java)
            ?.notifyAsUser(NOTICE_TAG, NOTICE_ID, notification, handle)
    }

    private fun cancelNotice(user: Int) {
        context
            .getSystemService(NotificationManager::class.java)
            ?.cancelAsUser(NOTICE_TAG, NOTICE_ID, UserHandle.of(user))
    }

    override fun dump(pw: PrintWriter, args: Array<out String>) {
        pw.println("MomentsSwitch:")
        pw.println("  code: ${switchCode(context)}")
        pw.println("  position: ${lastReported?.let { if (it) "on" else "off" } ?: "unknown"}")
        pw.println("  last result: $lastResult")
        pw.println("  record: ${store.record(userTracker.userId)}")
        pw.println("  sensor prompts: ${sensorGuard.dump()}")
    }

    companion object {
        private const val TAG = "TallyMoments"

        /** How long the switch must rest before anything changes. */
        const val SETTLE_MS = 275L

        const val ACTION_SETTINGS = "de.diamaneos.settings.MOMENTS_SWITCH_SETTINGS"
        private const val SETTINGS_PACKAGE = "com.android.settings"
        private const val NOTICE_TAG = "tally_moments"
        private const val NOTICE_ID = 1

        private val SETTINGS_KEYS =
            listOf(
                Secure.TALLY_MOMENTS_ACTION,
                Secure.TALLY_MOMENTS_PAUSED_APPS,
                Secure.TALLY_MOMENTS_GREYSCALE,
                Secure.TALLY_MOMENTS_OFFLINE,
            )

        private val HAPTIC_ATTRIBUTES =
            VibrationAttributes.createForUsage(VibrationAttributes.USAGE_HARDWARE_FEEDBACK)

        fun switchCode(context: Context): Int =
            context.resources.getInteger(com.android.internal.R.integer.config_momentsSwitchCode)
    }
}
