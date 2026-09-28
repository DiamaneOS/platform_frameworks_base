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

package com.android.systemui.tally.lock

import android.app.AlarmManager
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.text.format.DateFormat
import androidx.annotation.StringRes
import com.android.settingslib.AccessibilityContentDescriptions
import com.android.settingslib.R as SettingsLibR
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor
import com.android.systemui.privacy.PrivacyType
import com.android.systemui.res.R
import com.android.systemui.settings.UserTracker
import com.android.systemui.shade.domain.interactor.PrivacyChipInteractor
import com.android.systemui.statusbar.pipeline.battery.domain.interactor.BatteryInteractor
import com.android.systemui.statusbar.pipeline.wifi.domain.interactor.WifiInteractor
import com.android.systemui.statusbar.pipeline.wifi.shared.model.WifiNetworkModel
import com.android.systemui.statusbar.policy.BluetoothController
import com.android.systemui.statusbar.policy.NextAlarmController
import com.android.systemui.statusbar.policy.domain.interactor.ZenModeInteractor
import com.android.systemui.tally.lamp.TallyLampState
import com.android.systemui.util.time.SystemClock
import com.android.systemui.utils.coroutines.flow.conflatedCallbackFlow
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge

/** A string resource with an optional format argument, resolved by the view. */
data class TallyWords(@StringRes val res: Int, val arg: Any? = null)

/** One item of the lock screen's lamp strip. */
data class TallyStripItem(
    val kind: Kind,
    /** The lamp's state, or null for the battery, which is a readout without a lamp. */
    val lamp: TallyLampState?,
    /** What a screen reader says for the item: stock SystemUI's words, joined by the view. */
    val description: List<TallyWords>,
    /** The alarm's time, when the stock lock screen would show it. */
    val alarmTime: String? = null,
    /** The battery level (0 to 100) and charging state, for the battery readout. */
    val batteryLevel: Int = 0,
    val isCharging: Boolean = false,
) {
    /** The items in the strip's order: the camera and microphone first. */
    enum class Kind(
        /** An item that folds away, words kept in the strip's description, while it is off. */
        val foldsWhenOff: Boolean = false
    ) {
        CAMERA,
        MICROPHONE,
        WIFI(foldsWhenOff = true),
        BLUETOOTH(foldsWhenOff = true),
        ALARM,
        CALM(foldsWhenOff = true),
        BATTERY,
    }
}

/**
 * The lamp strip's state, from the controllers and interactors SystemUI already has: the privacy
 * items (the privacy chip's, with its holds), Wi-Fi, Bluetooth, the next alarm, Do Not Disturb
 * (shown as Calm) and the battery. It adds no source of its own and shows nothing the stock lock
 * screen does not: sensors say only "Camera in use" or "Microphone in use", the Wi-Fi and Bluetooth
 * items carry no network or device names, and the alarm's time shows only within the stock lock
 * screen's 12 hours (KeyguardSliceProvider).
 */
@SysUISingleton
class TallyLampStripViewModel
@Inject
constructor(
    @Application private val context: Context,
    privacyChipInteractor: PrivacyChipInteractor,
    wifiInteractor: WifiInteractor,
    private val bluetoothController: BluetoothController,
    private val nextAlarmController: NextAlarmController,
    zenModeInteractor: ZenModeInteractor,
    batteryInteractor: BatteryInteractor,
    private val userTracker: UserTracker,
    private val systemClock: SystemClock,
    keyguardInteractor: KeyguardInteractor,
) {
    private val sensorsInUse: Flow<Set<PrivacyType>> =
        privacyChipInteractor.privacyItems
            .map { items -> items.mapTo(mutableSetOf()) { it.privacyType } }
            .distinctUntilChanged()

    private val camera: Flow<TallyStripItem?> =
        sensorsInUse.map { types ->
            if (PrivacyType.TYPE_CAMERA !in types) return@map null
            TallyStripItem(
                TallyStripItem.Kind.CAMERA,
                TallyLampState.LIVE,
                listOf(TallyWords(R.string.accessibility_camera_in_use)),
            )
        }

    private val microphone: Flow<TallyStripItem?> =
        sensorsInUse.map { types ->
            if (PrivacyType.TYPE_MICROPHONE !in types) return@map null
            TallyStripItem(
                TallyStripItem.Kind.MICROPHONE,
                TallyLampState.LIVE,
                listOf(TallyWords(R.string.accessibility_microphone_in_use)),
            )
        }

    private val wifi: Flow<TallyStripItem?> =
        combine(wifiInteractor.isEnabled, wifiInteractor.wifiNetwork) { enabled, network ->
            val words =
                when {
                    !enabled -> SettingsLibR.string.accessibility_wifi_off
                    network is WifiNetworkModel.Active ->
                        AccessibilityContentDescriptions.WIFI_CONNECTION_STRENGTH[network.level]
                    else -> AccessibilityContentDescriptions.WIFI_NO_CONNECTION
                }
            val lamp =
                if (network is WifiNetworkModel.Active) TallyLampState.ON else TallyLampState.OFF
            TallyStripItem(TallyStripItem.Kind.WIFI, lamp, listOf(TallyWords(words)))
        }

    private val bluetooth: Flow<TallyStripItem?> =
        conflatedCallbackFlow {
                val callback =
                    object : BluetoothController.Callback {
                        override fun onBluetoothStateChange(enabled: Boolean) {
                            trySend(Unit)
                        }

                        override fun onBluetoothDevicesChanged() {
                            trySend(Unit)
                        }
                    }
                bluetoothController.addCallback(callback)
                trySend(Unit)
                awaitClose { bluetoothController.removeCallback(callback) }
            }
            .map { bluetoothItem() }

    private val nextAlarm: Flow<AlarmManager.AlarmClockInfo?> = conflatedCallbackFlow {
        val callback = NextAlarmController.NextAlarmChangeCallback { trySend(it) }
        nextAlarmController.addCallback(callback)
        awaitClose { nextAlarmController.removeCallback(callback) }
    }

    /** The time, once a minute while the strip is shown, so the alarm's window moves on. */
    private val minuteTicks: Flow<Long> = flow {
        while (true) {
            val now = systemClock.currentTimeMillis()
            emit(now)
            delay(MINUTE_MILLIS - now % MINUTE_MILLIS)
        }
    }

    /**
     * The time once a minute, and at each always-on display tick, which comes when the device wakes
     * to update the always-on display, where a plain delay may not run: for the date line.
     */
    val minutes: Flow<Long> =
        merge(minuteTicks, keyguardInteractor.dozeTimeTick.map { systemClock.currentTimeMillis() })

    private val alarm: Flow<TallyStripItem?> =
        combine(nextAlarm, minuteTicks) { info, now ->
            if (info == null) return@combine null
            // As the stock lock screen: the time shows only when the alarm is 12 hours away or
            // less, in the user's 12 or 24 hour format and without AM or PM.
            val soon = info.triggerTime <= now + TimeUnit.HOURS.toMillis(ALARM_HOURS)
            val time =
                if (!soon) null
                else {
                    val is24 = DateFormat.is24HourFormat(context, userTracker.userId)
                    DateFormat.format(if (is24) "HH:mm" else "h:mm", info.triggerTime).toString()
                }
            TallyStripItem(
                TallyStripItem.Kind.ALARM,
                TallyLampState.ON,
                listOf(
                    if (time == null) TallyWords(R.string.status_bar_alarm)
                    else TallyWords(R.string.accessibility_quick_settings_alarm, time)
                ),
                alarmTime = time,
            )
        }

    private val calm: Flow<TallyStripItem?> =
        zenModeInteractor.isZenModeEnabled.map { on ->
            TallyStripItem(
                TallyStripItem.Kind.CALM,
                if (on) TallyLampState.ON else TallyLampState.OFF,
                listOf(TallyWords(if (on) R.string.dnd_is_on else R.string.dnd_is_off)),
            )
        }

    private val battery: Flow<TallyStripItem?> =
        combine(batteryInteractor.level, batteryInteractor.isCharging) { level, charging ->
            if (level == null) return@combine null
            val words =
                if (charging) R.string.accessibility_battery_level_charging
                else R.string.accessibility_battery_level
            TallyStripItem(
                TallyStripItem.Kind.BATTERY,
                lamp = null,
                listOf(TallyWords(words, level)),
                batteryLevel = level,
                isCharging = charging,
            )
        }

    /** The items to show, in the strip's order. */
    val items: Flow<List<TallyStripItem>> =
        combine(listOf(camera, microphone, wifi, bluetooth, alarm, calm, battery)) { items ->
                items.filterNotNull()
            }
            .distinctUntilChanged()

    private fun bluetoothItem(): TallyStripItem? {
        if (!bluetoothController.isBluetoothSupported) return null
        val (lamp, words) =
            when {
                bluetoothController.isBluetoothConnected ->
                    TallyLampState.LIVE to
                        listOf(TallyWords(R.string.accessibility_bluetooth_connected))
                bluetoothController.isBluetoothEnabled ->
                    TallyLampState.ON to
                        listOf(TallyWords(R.string.accessibility_quick_settings_bluetooth_on))
                bluetoothController.bluetoothState == BluetoothAdapter.STATE_TURNING_ON ->
                    TallyLampState.REQUESTED to
                        listOf(
                            TallyWords(R.string.quick_settings_bluetooth_label),
                            TallyWords(R.string.quick_settings_bluetooth_secondary_label_transient),
                        )
                else -> TallyLampState.OFF to listOf(TallyWords(R.string.bt_is_off))
            }
        return TallyStripItem(TallyStripItem.Kind.BLUETOOTH, lamp, words)
    }

    private companion object {
        /** KeyguardSliceProvider.ALARM_VISIBILITY_HOURS: the stock lock screen's alarm window. */
        const val ALARM_HOURS = 12L
        val MINUTE_MILLIS = TimeUnit.MINUTES.toMillis(1)
    }
}
