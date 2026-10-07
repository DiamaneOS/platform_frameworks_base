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

import android.app.AppOpsManager
import android.content.Context
import android.hardware.ISensorPrivacyManager
import android.hardware.SensorPrivacyManager
import android.hardware.SensorPrivacyManager.Sensors
import android.os.Binder
import android.os.Process
import android.os.RemoteException
import android.os.ServiceManager
import android.util.Log
import android.widget.Toast
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.settings.UserTracker
import com.android.systemui.tally.moments.MomentsEffectKind.CAMERA
import com.android.systemui.tally.moments.MomentsEffectKind.MICROPHONE
import com.android.systemui.util.concurrency.DelayableExecutor
import com.android.systemui.util.time.SystemClock
import java.util.concurrent.Executor
import javax.inject.Inject

/**
 * While the Moments switch blocks the camera or microphone, apps get black frames and silence
 * without interruption: the platform's "Unblock" prompt (an activity over the app, which pauses
 * it) is suppressed for those sensors, through the platform's own reminder suppression, and a
 * short toast names what the switch blocks instead. A block the user made themselves keeps
 * GrapheneOS's prompt. See [MomentsSensorOwnership].
 *
 * All state changes run on the background executor.
 */
@SysUISingleton
class MomentsSensorGuard
@Inject
constructor(
    @Application private val context: Context,
    @Background private val bgExecutor: DelayableExecutor,
    @Main private val mainExecutor: Executor,
    private val userTracker: UserTracker,
    private val sensorPrivacyManager: SensorPrivacyManager,
    private val appOpsManager: AppOpsManager,
    private val systemClock: SystemClock,
) {
    private val token = Binder()
    private val ownership = mutableMapOf<Int, MomentsSensorOwnership>()
    private val suppressed = mutableSetOf<Pair<Int, MomentsEffectKind>>()
    private val throttle = MomentsSensorNoteThrottle()
    private val pending = mutableSetOf<MomentsEffectKind>()
    private var flush: Runnable? = null
    private var service: ISensorPrivacyManager? = null

    private val privacyListener =
        object : SensorPrivacyManager.OnSensorPrivacyChangedListener {
            override fun onSensorPrivacyChanged(
                params: SensorPrivacyManager.OnSensorPrivacyChangedListener
                        .SensorPrivacyChangedParams
            ) {
                val kind = kindOf(params.sensor) ?: return
                val software = params.toggleType == SensorPrivacyManager.TOGGLE_TYPE_SOFTWARE
                bgExecutor.execute {
                    if (software && !params.isEnabled) {
                        ownership[userTracker.userId]?.onSoftwareUnblocked(kind)
                    }
                    updateSuppression()
                }
            }

            override fun onSensorPrivacyChanged(sensor: Int, enabled: Boolean) {}
        }

    private val opStarted =
        AppOpsManager.OnOpStartedListener { op, uid, _, _, flags, result ->
            onSensorOp(op, uid, flags, result)
        }

    private val opNoted =
        object : AppOpsManager.OnOpNotedInternalListener {
            override fun onOpNoted(
                code: Int,
                uid: Int,
                packageName: String,
                attributionTag: String?,
                flags: Int,
                result: Int,
            ) = onSensorOp(code, uid, flags, result)
        }

    /** Starts watching; [record] gives the switch's record for a user. */
    fun start(record: (Int) -> List<MomentsApplied>) {
        recordOf = record
        service =
            ISensorPrivacyManager.Stub.asInterface(
                ServiceManager.getService(Context.SENSOR_PRIVACY_SERVICE)
            )
        sensorPrivacyManager.addSensorPrivacyListener(bgExecutor, privacyListener)
        appOpsManager.startWatchingStarted(OPS, opStarted)
        appOpsManager.startWatchingNoted(OPS, opNoted)
        bgExecutor.execute { refresh() }
    }

    private var recordOf: (Int) -> List<MomentsApplied> = { emptyList() }

    /** After the record changed or the user switched. Background thread. */
    fun refresh() {
        val user = userTracker.userId
        ownership
            .getOrPut(user) { MomentsSensorOwnership() }
            .onRecord(MomentsSensorOwnership.inRecord(recordOf(user))) { softwareBlocked(it) }
        updateSuppression()
    }

    private fun isSwitchBlock(kind: MomentsEffectKind): Boolean {
        val owner = ownership[userTracker.userId] ?: return false
        return owner.isSwitchBlock(kind, softwareBlocked(kind), hardwareBlocked(kind))
    }

    // Background thread.
    private fun updateSuppression() {
        val user = userTracker.userId
        val wanted = KINDS.filter { isSwitchBlock(it) }.map { user to it }.toSet()
        for (entry in suppressed - wanted) setSuppressed(entry, false)
        for (entry in wanted - suppressed) setSuppressed(entry, true)
    }

    private fun setSuppressed(entry: Pair<Int, MomentsEffectKind>, suppress: Boolean) {
        try {
            service?.suppressToggleSensorPrivacyReminders(
                entry.first,
                sensorOf(entry.second),
                token,
                suppress,
            ) ?: return
            if (suppress) suppressed += entry else suppressed -= entry
        } catch (e: RemoteException) {
            Log.w(TAG, "Could not change the sensor prompt", e)
        }
    }

    // Binder thread. The platform reports a blocked sensor use as an ignored op, as the prompt's
    // own trigger does.
    private fun onSensorOp(op: Int, uid: Int, flags: Int, result: Int) {
        if (result != AppOpsManager.MODE_IGNORED) return
        if (flags and AppOpsManager.OP_FLAGS_ALL_TRUSTED == 0) return
        if (uid == Process.SYSTEM_UID) return
        val kind =
            when (op) {
                AppOpsManager.OP_CAMERA,
                AppOpsManager.OP_PHONE_CALL_CAMERA -> CAMERA
                AppOpsManager.OP_RECORD_AUDIO,
                AppOpsManager.OP_PHONE_CALL_MICROPHONE -> MICROPHONE
                else -> return
            }
        bgExecutor.execute {
            if (!isSwitchBlock(kind)) return@execute
            pending += kind
            // A camera and its microphone start together: one note for both.
            if (flush == null) flush = bgExecutor.executeDelayed(::showNote, GATHER_MS)
        }
    }

    private fun showNote() {
        flush = null
        val kinds = pending.toSet()
        pending.clear()
        if (!throttle.shouldShow(kinds, systemClock.elapsedRealtime())) return
        val text = MomentsSensorNoteThrottle.text(kinds)
        mainExecutor.execute { Toast.makeText(context, text, Toast.LENGTH_SHORT).show() }
    }

    private fun softwareBlocked(kind: MomentsEffectKind) =
        sensorPrivacyManager.isSensorPrivacyEnabled(
            SensorPrivacyManager.TOGGLE_TYPE_SOFTWARE,
            sensorOf(kind),
        )

    private fun hardwareBlocked(kind: MomentsEffectKind) =
        sensorPrivacyManager.isSensorPrivacyEnabled(
            SensorPrivacyManager.TOGGLE_TYPE_HARDWARE,
            sensorOf(kind),
        )

    fun dump(): String = "suppressed=$suppressed"

    private companion object {
        const val TAG = "TallyMoments"
        const val GATHER_MS = 500L
        val KINDS = listOf(CAMERA, MICROPHONE)
        val OPS =
            intArrayOf(
                AppOpsManager.OP_CAMERA,
                AppOpsManager.OP_PHONE_CALL_CAMERA,
                AppOpsManager.OP_RECORD_AUDIO,
                AppOpsManager.OP_PHONE_CALL_MICROPHONE,
            )

        fun sensorOf(kind: MomentsEffectKind) =
            if (kind == CAMERA) Sensors.CAMERA else Sensors.MICROPHONE

        fun kindOf(sensor: Int) =
            when (sensor) {
                Sensors.CAMERA -> CAMERA
                Sensors.MICROPHONE -> MICROPHONE
                else -> null
            }
    }
}
