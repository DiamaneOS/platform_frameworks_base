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

import android.content.Context
import android.hardware.SensorPrivacyManager
import android.hardware.SensorPrivacyManager.Sensors
import android.os.Process
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.telecom.TelecomManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.telephony.emergency.EmergencyNumber
import android.util.Log
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.res.R
import com.android.systemui.statusbar.VibratorHelper
import com.android.systemui.statusbar.phone.DialogDelegate
import com.android.systemui.statusbar.phone.SystemUIDialog
import java.util.concurrent.Executor
import javax.inject.Inject

/**
 * Warns with a dialog and a vibration when an emergency call starts while the Moments switch keeps
 * the microphone off in the kernel. Android lifts its own microphone block for an emergency call,
 * but the kernel cannot tell calls apart. Only sliding the switch up turns the microphone back on.
 *
 * The emergency call is Telephony's outgoing emergency call event, the one that also lifts
 * Android's block. Any call within five minutes after it counts too, as the emergency service may
 * call back after a dropped call, and Android does not lift its block for that. The kernel's block is the microphone's hardware toggle, which the input service
 * sets from the kernel's own report. All state changes run on the background executor.
 */
@SysUISingleton
class MomentsEmergencyWarning
@Inject
constructor(
    @Application private val context: Context,
    @Background private val bgExecutor: Executor,
    @Main private val mainExecutor: Executor,
    private val telephonyManager: TelephonyManager,
    private val telecomManager: TelecomManager?,
    private val sensorPrivacyManager: SensorPrivacyManager,
    private val vibratorHelper: VibratorHelper,
    private val dialogFactory: SystemUIDialog.Factory,
) {
    private val decision = MomentsEmergencyWarningState()

    // Main thread.
    private var dialog: SystemUIDialog? = null

    private val callListener =
        object :
            TelephonyCallback(),
            TelephonyCallback.OutgoingEmergencyCallListener,
            TelephonyCallback.CallStateListener {
            override fun onOutgoingEmergencyCall(
                placedEmergencyNumber: EmergencyNumber,
                subscriptionId: Int,
            ) {
                decision.onEmergencyCall(SystemClock.elapsedRealtime())
                update()
            }

            override fun onCallStateChanged(state: Int) {
                when (state) {
                    TelephonyManager.CALL_STATE_IDLE ->
                        decision.onCallsEnded(SystemClock.elapsedRealtime())
                    TelephonyManager.CALL_STATE_OFFHOOK ->
                        decision.onCallOffHook(SystemClock.elapsedRealtime())
                    else -> return
                }
                update()
            }
        }

    private val privacyListener =
        object : SensorPrivacyManager.OnSensorPrivacyChangedListener {
            override fun onSensorPrivacyChanged(
                params: SensorPrivacyManager.OnSensorPrivacyChangedListener
                        .SensorPrivacyChangedParams
            ) {
                if (
                    params.sensor != Sensors.MICROPHONE ||
                        params.toggleType != SensorPrivacyManager.TOGGLE_TYPE_HARDWARE
                ) {
                    return
                }
                decision.kernelBlocksMic = params.isEnabled
                update()
            }

            override fun onSensorPrivacyChanged(sensor: Int, enabled: Boolean) {}
        }

    fun start() {
        telephonyManager.registerTelephonyCallback(bgExecutor, callListener)
        sensorPrivacyManager.addSensorPrivacyListener(bgExecutor, privacyListener)
        bgExecutor.execute {
            decision.kernelBlocksMic =
                sensorPrivacyManager.isSensorPrivacyEnabled(
                    SensorPrivacyManager.TOGGLE_TYPE_HARDWARE,
                    Sensors.MICROPHONE,
                )
            update()
        }
    }

    /** A report of the switch, before it settles. Background thread. */
    fun onSwitch(on: Boolean) {
        decision.switchUp = !on
        update()
    }

    fun dump(): String = decision.toString()

    // Background thread.
    private fun update() {
        // A call that ends before it dials reports no idle state, so Telecom has the last word.
        if (decision.emergencyCall && telecomManager?.isInEmergencyCall != true) {
            decision.emergencyCall = false
        }
        when (decision.update()) {
            MomentsEmergencyWarningState.Change.SHOW -> {
                Log.i(TAG, "Emergency call while the switch keeps the microphone off")
                vibratorHelper.vibrate(
                    Process.myUid(),
                    context.opPackageName,
                    EFFECT,
                    "Moments emergency warning",
                    ATTRIBUTES,
                )
                mainExecutor.execute(::show)
            }
            MomentsEmergencyWarningState.Change.HIDE -> mainExecutor.execute(::hide)
            MomentsEmergencyWarningState.Change.NONE -> {}
        }
    }

    // Main thread. It stays over the call screen and the lock screen until the switch goes up,
    // the call ends or the user closes it.
    private fun show() {
        if (dialog != null) return
        val warning =
            dialogFactory.create(
                object : DialogDelegate<SystemUIDialog> {},
                context,
                SystemUIDialog.DEFAULT_THEME,
                /* dismissOnDeviceLock= */ false,
                /* shouldAcsdDismissDialog= */ false,
            )
        warning.setTitle(R.string.sensor_privacy_start_use_mic_blocked_dialog_title)
        warning.setMessage(R.string.tally_moments_emergency_mic_off)
        warning.setPositiveButton(android.R.string.ok, null)
        warning.setShowForAllUsers(true)
        warning.setOnDismissListener {
            dialog = null
            bgExecutor.execute { decision.onDismissed() }
        }
        dialog = warning
        warning.show()
    }

    // Main thread.
    private fun hide() {
        dialog?.dismiss()
    }

    private companion object {
        const val TAG = "TallyMoments"

        // Two long pulses, to be felt with the phone at the ear.
        val EFFECT: VibrationEffect =
            VibrationEffect.createWaveform(longArrayOf(0, 500, 250, 500), /* repeat= */ -1)

        // Alarm usage: it also plays in silent mode and with battery saver on.
        val ATTRIBUTES: VibrationAttributes =
            VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM)
    }
}
