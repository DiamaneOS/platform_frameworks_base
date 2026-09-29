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
import android.content.Context
import android.media.MediaMetadata
import android.provider.Settings.Secure.LOCK_SCREEN_SHOW_NOTIFICATIONS
import android.provider.Settings.Secure.MEDIA_CONTROLS_LOCK_SCREEN
import android.text.format.DateFormat
import androidx.annotation.StringRes
import com.android.settingslib.AccessibilityContentDescriptions
import com.android.settingslib.R as SettingsLibR
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor
import com.android.systemui.media.NotificationMediaManager
import com.android.systemui.privacy.PrivacyType
import com.android.systemui.res.R
import com.android.systemui.settings.UserTracker
import com.android.systemui.shade.domain.interactor.PrivacyChipInteractor
import com.android.systemui.shared.settings.data.repository.SecureSettingsRepository
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
    /** The alarm's time (the alarm item shows only when the stock lock screen shows the alarm). */
    val alarmTime: String? = null,
    /** The battery level (0 to 100) and charging state, for the battery readout. */
    val batteryLevel: Int = 0,
    val isCharging: Boolean = false,
    /** Whether the battery readout shows the percentage, as stock's keyguard status bar would. */
    val showBatteryPercent: Boolean = false,
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
 * One item of the always-on display's strip: what the stock always-on date line showed besides the
 * date, and nothing more.
 */
data class TallyAodItem(
    val kind: Kind,
    /** The item's words: the alarm's time, or the playing media's title and artist. */
    val words: String?,
    /** What a screen reader says for the item, when not its words: stock SystemUI's words. */
    val description: TallyWords?,
) {
    /** The items in the always-on strip's order. */
    enum class Kind {
        ALARM,
        CALM,
        MEDIA,
    }
}

/**
 * The lamp strip's state, from the controllers and interactors SystemUI already has: the privacy
 * items (the privacy chip's, with its holds), Wi-Fi, Bluetooth, the next alarm, Do Not Disturb
 * (shown as Calm) and the battery. It adds no source of its own and shows nothing the stock lock
 * screen does not: sensors say only "Camera in use" or "Microphone in use", the Wi-Fi and Bluetooth
 * items carry no network or device names, and the alarm shows only within the stock lock screen's
 * 12 hours (KeyguardSliceProvider).
 *
 * For the always-on display it gives only what the stock always-on date line (KeyguardSliceProvider
 * on the keyguard slice) showed besides the date: the next alarm's time within those 12 hours, Do
 * Not Disturb while it is on, and the title and artist of media that is playing, from the same
 * NotificationMediaManager. The media shows only where GrapheneOS's always-on line (its built-in
 * smartspace, which replaces the slice) shows it; see [aodMediaAllowed] and [mediaWords].
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
    private val mediaManager: NotificationMediaManager,
    secureSettingsRepository: SecureSettingsRepository,
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
     * to update the always-on display, where a plain delay may not run: for the date line and the
     * alarm's window.
     */
    val minutes: Flow<Long> =
        merge(minuteTicks, keyguardInteractor.dozeTimeTick.map { systemClock.currentTimeMillis() })

    private val alarm: Flow<TallyStripItem?> =
        combine(nextAlarm, minutes) { info, now ->
            // As the stock lock screen (KeyguardSliceProvider): the alarm shows only when it rings
            // 12 hours from now or sooner, and then with its time, in the user's 12 or 24 hour
            // format and without AM or PM. A later alarm shows nothing.
            if (info == null || info.triggerTime > now + TimeUnit.HOURS.toMillis(ALARM_HOURS)) {
                return@combine null
            }
            val is24 = DateFormat.is24HourFormat(context, userTracker.userId)
            val time = DateFormat.format(if (is24) "HH:mm" else "h:mm", info.triggerTime).toString()
            TallyStripItem(
                TallyStripItem.Kind.ALARM,
                TallyLampState.ON,
                listOf(TallyWords(R.string.accessibility_quick_settings_alarm, time)),
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

    /**
     * The battery glyph always, as stock's keyguard status bar shows its battery icon, and the
     * percentage only while charging or when the user's battery percentage setting is on, as
     * stock's keyguard status bar (BatteryViewModel.ShowPercentWhenChargingOrSetting).
     */
    private val battery: Flow<TallyStripItem?> =
        combine(
            batteryInteractor.level,
            batteryInteractor.isCharging,
            batteryInteractor.isBatteryPercentSettingEnabled,
        ) { level, charging, percentSetting ->
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
                showBatteryPercent = charging || percentSetting,
            )
        }

    /** The items to show, in the strip's order. */
    val items: Flow<List<TallyStripItem>> =
        combine(listOf(camera, microphone, wifi, bluetooth, alarm, calm, battery)) { items ->
                items.filterNotNull()
            }
            .distinctUntilChanged()

    /** The title and artist of the media that is playing, as [mediaWords] takes them. */
    private val playingMedia: Flow<String?> =
        conflatedCallbackFlow {
                val listener =
                    object : NotificationMediaManager.MediaListener {
                        override fun onPrimaryMetadataOrStateChanged(
                            metadata: MediaMetadata?,
                            state: Int,
                        ) {
                            trySend(
                                mediaWords(
                                    metadata,
                                    state,
                                    hasMediaIcon = mediaManager.mediaIcon != null,
                                    noTitle = context.getString(R.string.music_controls_no_title),
                                )
                            )
                        }
                    }
                mediaManager.addCallback(listener)
                awaitClose { mediaManager.removeCallback(listener) }
            }
            .distinctUntilChanged()

    private val mediaAllowed: Flow<Boolean> = aodMediaAllowed(secureSettingsRepository)

    /** The items the always-on display shows, in the always-on strip's order. */
    val aodItems: Flow<List<TallyAodItem>> =
        combine(alarm, zenModeInteractor.isZenModeEnabled, playingMedia, mediaAllowed) {
                alarm,
                dnd,
                media,
                showMedia ->
                listOfNotNull(
                    alarm?.alarmTime?.let {
                        TallyAodItem(
                            TallyAodItem.Kind.ALARM,
                            it,
                            TallyWords(R.string.accessibility_quick_settings_alarm, it),
                        )
                    },
                    if (!dnd) null
                    else
                        TallyAodItem(
                            TallyAodItem.Kind.CALM,
                            null,
                            TallyWords(R.string.accessibility_quick_settings_dnd),
                        ),
                    media
                        ?.takeIf { showMedia }
                        ?.let { TallyAodItem(TallyAodItem.Kind.MEDIA, it, null) },
                )
            }
            .distinctUntilChanged()

    /**
     * Lit only when stock's status bar, which the lock screen shows, shows its Bluetooth icon
     * (PhoneStatusBarPolicy.updateBluetooth): Bluetooth on and a device connected whose audio is
     * active or which is not an audio-only device. Otherwise the lamp is off and the words say only
     * "Bluetooth", so the strip never tells whether Bluetooth is on, off or turning on.
     */
    private fun bluetoothItem(): TallyStripItem? {
        if (!bluetoothController.isBluetoothSupported) return null
        val connected =
            bluetoothController.isBluetoothEnabled &&
                bluetoothController.isBluetoothConnected &&
                (bluetoothController.isBluetoothAudioActive ||
                    !bluetoothController.isBluetoothAudioProfileOnly)
        return if (connected) {
            TallyStripItem(
                TallyStripItem.Kind.BLUETOOTH,
                TallyLampState.LIVE,
                listOf(TallyWords(R.string.accessibility_bluetooth_connected)),
            )
        } else {
            TallyStripItem(
                TallyStripItem.Kind.BLUETOOTH,
                TallyLampState.OFF,
                listOf(TallyWords(R.string.accessibility_quick_settings_bluetooth)),
            )
        }
    }

    internal companion object {
        /** Between the media's title and its artist, as the prototype joins a name and a value. */
        private const val MEDIA_SEPARATOR = " · "

        /** KeyguardSliceProvider.ALARM_VISIBILITY_HOURS: the stock lock screen's alarm window. */
        private const val ALARM_HOURS = 12L
        private val MINUTE_MILLIS = TimeUnit.MINUTES.toMillis(1)

        /**
         * Whether the always-on strip may show the playing media, for the current user: as
         * GrapheneOS's always-on line, only while the lock screen shows notifications
         * (LockscreenSmartspaceController drops the whole line otherwise) and "Show media on lock
         * screen" is on (SystemUISmartspaceService), with the defaults they read them with.
         */
        fun aodMediaAllowed(settings: SecureSettingsRepository): Flow<Boolean> =
            combine(
                    settings.intSetting(LOCK_SCREEN_SHOW_NOTIFICATIONS, 0),
                    settings.boolSetting(MEDIA_CONTROLS_LOCK_SCREEN, true),
                ) { notifications, media ->
                    notifications == 1 && media
                }
                .distinctUntilChanged()

        /**
         * The words for the media, as the stock always-on line takes them: none unless it plays,
         * stock's "No title" ([noTitle]) for an empty title, and the artist after the title only
         * when the media's notification has an icon, as GrapheneOS's line shows the artist only
         * beside that icon.
         */
        fun mediaWords(
            metadata: MediaMetadata?,
            state: Int,
            hasMediaIcon: Boolean,
            noTitle: CharSequence,
        ): String? {
            if (metadata == null || !NotificationMediaManager.isPlayingState(state)) return null
            val title =
                metadata.getText(MediaMetadata.METADATA_KEY_TITLE)?.takeIf { it.isNotEmpty() }
                    ?: noTitle
            val artist = metadata.getText(MediaMetadata.METADATA_KEY_ARTIST)
            return if (artist.isNullOrEmpty() || !hasMediaIcon) title.toString()
            else "$title$MEDIA_SEPARATOR$artist"
        }
    }
}
