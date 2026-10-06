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

import android.app.NotificationManager
import android.app.trust.TrustManager
import android.content.Context
import android.content.pm.SuspendDialogInfo
import android.hardware.SensorPrivacyManager.Sensors
import android.hardware.SensorPrivacyManager.Sources
import android.hardware.display.ColorDisplayManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.os.RemoteException
import android.os.UserHandle
import android.os.UserManager
import android.provider.Settings.Global
import android.provider.Settings.Secure
import android.util.Log
import android.view.IWindowManager
import com.android.internal.widget.LockPatternUtils
import com.android.internal.widget.LockPatternUtils.StrongAuthTracker.STRONG_AUTH_REQUIRED_AFTER_USER_LOCKDOWN
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.res.R
import com.android.systemui.settings.UserTracker
import com.android.systemui.statusbar.policy.IndividualSensorPrivacyController
import com.android.systemui.statusbar.policy.KeyguardStateController
import javax.inject.Inject

/**
 * The switch's changes, made through the APIs SystemUI's own tiles and power menu use, for the
 * current user. Each one is taken back only where the setting is still as the switch set it.
 */
@SysUISingleton
class MomentsPlatformImpl
@Inject
constructor(
    private val userTracker: UserTracker,
    private val sensorPrivacyController: IndividualSensorPrivacyController,
    private val keyguardStateController: KeyguardStateController,
    private val lockPatternUtils: LockPatternUtils,
    private val windowManager: IWindowManager,
) : MomentsPlatform {

    private val context: Context
        get() = userTracker.userContext

    private val user: Int
        get() = userTracker.userId

    override fun apply(effect: MomentsEffect): String? =
        when (effect.kind) {
            MomentsEffectKind.DND -> {
                val nm = context.getSystemService(NotificationManager::class.java)!!
                if (nm.zenMode == Global.ZEN_MODE_OFF) {
                    // The user's own Do Not Disturb, with the people and apps they allow.
                    nm.setZenMode(Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS, null, TAG, true)
                    DONE
                } else {
                    null
                }
            }
            MomentsEffectKind.HOME ->
                if (Secure.getIntForUser(context.contentResolver, HOME_ACTIVE, 0, user) != 1) {
                    Secure.putIntForUser(context.contentResolver, HOME_ACTIVE, 1, user)
                    DONE
                } else {
                    null
                }
            MomentsEffectKind.PAUSE_APPS -> {
                val packages = effect.param.split(',').filter { it.isNotEmpty() }.toTypedArray()
                val info =
                    SuspendDialogInfo.Builder()
                        .setTitle(R.string.tally_moments_paused_title)
                        .setMessage(R.string.tally_moments_paused_message)
                        .build()
                // Critical apps (phone, launcher, ...) are refused; they stay usable.
                val failed =
                    context.packageManager
                        .setPackagesSuspended(packages, true, null, null, info)
                        ?.toSet() ?: emptySet()
                packages.filter { it !in failed }.joinToString(",").ifEmpty { null }
            }
            MomentsEffectKind.GREYSCALE -> {
                // Saturation, as Bedtime mode does; the user's colour correction stays as it is.
                context.getSystemService(ColorDisplayManager::class.java)!!.setSaturationLevel(0)
                DONE
            }
            MomentsEffectKind.CAMERA -> blockSensor(Sensors.CAMERA)
            MomentsEffectKind.MICROPHONE -> blockSensor(Sensors.MICROPHONE)
            MomentsEffectKind.RINGER -> {
                val audio = context.getSystemService(AudioManager::class.java)!!
                val previous = audio.ringerModeInternal
                if (previous != AudioManager.RINGER_MODE_SILENT) {
                    audio.ringerModeInternal = AudioManager.RINGER_MODE_SILENT
                    previous.toString()
                } else {
                    null
                }
            }
            MomentsEffectKind.AIRPLANE ->
                if (Global.getInt(context.contentResolver, Global.AIRPLANE_MODE_ON, 0) == 0) {
                    context.getSystemService(ConnectivityManager::class.java)!!
                        .setAirplaneMode(true)
                    DONE
                } else {
                    null
                }
            MomentsEffectKind.LOCKDOWN -> {
                lockdown()
                // Lockdown ends only with the user's credential, never with the switch.
                null
            }
        }

    override fun revert(effect: MomentsEffect, undo: String) {
        when (effect.kind) {
            MomentsEffectKind.DND -> {
                val nm = context.getSystemService(NotificationManager::class.java)!!
                if (nm.zenModeConfig?.isManualActive == true) {
                    nm.setZenMode(Global.ZEN_MODE_OFF, null, TAG, true)
                }
            }
            MomentsEffectKind.HOME ->
                if (Secure.getIntForUser(context.contentResolver, HOME_ACTIVE, 0, user) == 1) {
                    Secure.putIntForUser(context.contentResolver, HOME_ACTIVE, 0, user)
                }
            MomentsEffectKind.PAUSE_APPS -> {
                val packages = undo.split(',').filter { it.isNotEmpty() }.toTypedArray()
                // Lifts only SystemUI's own suspension; another app's stays.
                context.packageManager.setPackagesSuspended(
                    packages,
                    false,
                    null,
                    null,
                    null as SuspendDialogInfo?,
                )
            }
            MomentsEffectKind.GREYSCALE ->
                context.getSystemService(ColorDisplayManager::class.java)!!.setSaturationLevel(100)
            MomentsEffectKind.CAMERA -> unblockSensor(Sensors.CAMERA)
            MomentsEffectKind.MICROPHONE -> unblockSensor(Sensors.MICROPHONE)
            MomentsEffectKind.RINGER -> {
                val audio = context.getSystemService(AudioManager::class.java)!!
                val previous = undo.toIntOrNull() ?: return
                if (audio.ringerModeInternal == AudioManager.RINGER_MODE_SILENT) {
                    audio.ringerModeInternal = previous
                }
            }
            MomentsEffectKind.AIRPLANE ->
                if (Global.getInt(context.contentResolver, Global.AIRPLANE_MODE_ON, 0) != 0) {
                    context.getSystemService(ConnectivityManager::class.java)!!
                        .setAirplaneMode(false)
                }
            MomentsEffectKind.LOCKDOWN -> {}
        }
    }

    override fun reassert(effect: MomentsEffect) {
        if (effect.kind == MomentsEffectKind.GREYSCALE) {
            context.getSystemService(ColorDisplayManager::class.java)!!.setSaturationLevel(0)
        }
    }

    private fun blockSensor(sensor: Int): String? {
        if (!sensorPrivacyController.supportsSensorToggle(sensor)) return null
        if (sensorPrivacyController.isSensorBlocked(sensor)) return null
        sensorPrivacyController.setSensorBlocked(Sources.OTHER, sensor, true)
        return DONE
    }

    private fun unblockSensor(sensor: Int) {
        if (sensorPrivacyController.isSensorBlocked(sensor)) {
            sensorPrivacyController.setSensorBlocked(Sources.OTHER, sensor, false)
        }
    }

    // As the power menu's Lockdown: only with a secure lock screen, then lock every profile.
    private fun lockdown() {
        if (!keyguardStateController.isMethodSecure) return
        lockPatternUtils.requireStrongAuth(
            STRONG_AUTH_REQUIRED_AFTER_USER_LOCKDOWN,
            UserHandle.USER_ALL,
        )
        try {
            windowManager.lockNow(null)
        } catch (e: RemoteException) {
            Log.e(TAG, "Could not lock the device", e)
        }
        val trustManager = context.getSystemService(TrustManager::class.java) ?: return
        val userManager = context.getSystemService(UserManager::class.java) ?: return
        for (id in userManager.getEnabledProfileIds(user)) {
            if (id != user) trustManager.setDeviceLockedForUser(id, true)
        }
    }

    private companion object {
        const val TAG = "TallyMoments"
        const val DONE = "1"
        const val HOME_ACTIVE = Secure.TALLY_MOMENTS_HOME_ACTIVE
    }
}
