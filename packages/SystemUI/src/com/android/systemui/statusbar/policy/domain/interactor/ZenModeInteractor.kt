/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.statusbar.policy.domain.interactor

import android.content.Context
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.InsetDrawable
import android.media.AudioManager
import android.provider.Settings
import android.provider.Settings.Secure.ZEN_DURATION_FOREVER
import android.provider.Settings.Secure.ZEN_DURATION_PROMPT
import android.service.notification.ZenPolicy.STATE_DISALLOW
import android.service.notification.ZenPolicy.VISUAL_EFFECT_NOTIFICATION_LIST
import android.util.Log
import androidx.concurrent.futures.await
import com.android.settingslib.notification.data.repository.ZenModeRepository
import com.android.settingslib.notification.modes.ZenIconLoader
import com.android.settingslib.notification.modes.ZenMode
import com.android.settingslib.volume.shared.model.AudioStream
import com.android.systemui.common.shared.model.ContentDescription
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.shared.notifications.data.repository.NotificationSettingsRepository
import com.android.systemui.statusbar.policy.data.repository.DeviceProvisioningRepository
import com.android.systemui.statusbar.policy.data.repository.UserSetupRepository
import com.android.systemui.statusbar.policy.domain.model.ActiveZenModes
import com.android.systemui.statusbar.policy.domain.model.ZenModeInfo
import java.time.Duration
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import de.diamaneos.decode.client.ForeignIcons

/** The most an app's mode icon is decoded to. */
private const val MODE_ICON_MAX_PX = 256

/**
 * An interactor that performs business logic related to the status and configuration of Zen Mode
 * (or Do Not Disturb/DND Mode).
 */
@SysUISingleton
class ZenModeInteractor
@Inject
constructor(
    private val context: Context,
    private val zenModeRepository: ZenModeRepository,
    private val notificationSettingsRepository: NotificationSettingsRepository,
    @Background private val bgDispatcher: CoroutineDispatcher,
    @Background private val backgroundScope: CoroutineScope,
    private val iconLoader: ZenIconLoader,
    deviceProvisioningRepository: DeviceProvisioningRepository,
    userSetupRepository: UserSetupRepository,
) {
    /**
     * List of predicates to determine if the [ZenMode] blocks an audio stream. Typical use case
     * would be: `zenModeByStreamPredicates[stream](zenMode)`
     */
    private val zenModeByStreamPredicates =
        mapOf<Int, (ZenMode) -> Boolean>(
            AudioManager.STREAM_MUSIC to { it.policy.priorityCategoryMedia == STATE_DISALLOW },
            AudioManager.STREAM_ALARM to { it.policy.priorityCategoryAlarms == STATE_DISALLOW },
            AudioManager.STREAM_SYSTEM to { it.policy.priorityCategorySystem == STATE_DISALLOW },
            AudioManager.STREAM_ASSISTANT to { it.policy.priorityCategoryMedia == STATE_DISALLOW },
        )

    val hasNextAlarm: StateFlow<Boolean> = zenModeRepository.hasNextAlarm

    val isZenAvailable: Flow<Boolean> =
        combine(
            deviceProvisioningRepository.isDeviceProvisioned,
            userSetupRepository.isUserSetUp,
        ) { isDeviceProvisioned, isUserSetUp ->
            isDeviceProvisioned && isUserSetUp
        }

    val isZenModeEnabled: Flow<Boolean> =
        zenModeRepository.globalZenMode
            .map {
                when (it ?: Settings.Global.ZEN_MODE_OFF) {
                    Settings.Global.ZEN_MODE_ALARMS -> true
                    Settings.Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS -> true
                    Settings.Global.ZEN_MODE_NO_INTERRUPTIONS -> true
                    Settings.Global.ZEN_MODE_OFF -> false
                    else -> false
                }
            }
            .distinctUntilChanged()

    val areNotificationsHiddenInShade: Flow<Boolean> =
        combine(isZenModeEnabled, zenModeRepository.consolidatedNotificationPolicy) {
                dndEnabled,
                policy ->
                if (!dndEnabled) {
                    false
                } else {
                    val showInNotificationList = policy?.showInNotificationList() ?: true
                    !showInNotificationList
                }
            }
            .distinctUntilChanged()

    val modes: StateFlow<List<ZenMode>> = zenModeRepository.modes

    /**
     * Returns the special "manual DND" mode.
     *
     * This should only be used when there is a strong reason to handle DND specifically (such as
     * legacy UI pieces that haven't been updated to use modes more generally, or if the user
     * explicitly wants a shortcut to DND). Please prefer using [modes] or [activeModes] in all
     * other scenarios.
     */
    val dndMode: StateFlow<ZenMode?> =
        zenModeRepository.modes
            .map { modes -> modes.singleOrNull { it.isManualDnd } }
            .stateIn(scope = backgroundScope, started = SharingStarted.Eagerly, initialValue = null)

    /**
     * Returns the current state of the special "manual DND" mode.
     *
     * This should only be used when there is a strong reason to handle DND specifically (such as
     * legacy UI pieces that haven't been updated to use modes more generally, or if the user
     * explicitly wants a shortcut to DND). Please prefer using [modes] or [activeModes] in all
     * other scenarios.
     */
    fun getDndMode(): ZenMode {
        return zenModeRepository.getModes().single { it.isManualDnd }
    }

    /** Flow returning the currently active mode(s), if any. */
    val activeModes: Flow<ActiveZenModes> =
        modes
            .map { modes -> buildActiveZenModes(modes) }
            .flowOn(bgDispatcher)
            .distinctUntilChanged()

    fun canBeBlockedByZenMode(stream: AudioStream): Boolean =
        zenModeByStreamPredicates.containsKey(stream.value)

    fun activeModesBlockingStream(stream: AudioStream): Flow<ActiveZenModes> {
        val isBlockingStream = zenModeByStreamPredicates[stream.value]
        require(isBlockingStream != null) {
            "$stream is unsupported. Use canBeBlockedByZenMode to check if the stream can be affected by the Zen Mode."
        }
        return modes
            .map { modes -> modes.filter { isBlockingStream(it) } }
            .map { modes -> buildActiveZenModes(modes) }
            .flowOn(bgDispatcher)
            .distinctUntilChanged()
    }

    private suspend fun buildActiveZenModes(modes: List<ZenMode>): ActiveZenModes {
        val activeModesList =
            modes.filter { mode -> mode.isActive }.sortedWith(ZenMode.PRIORITIZING_COMPARATOR)
        val mainActiveMode =
            activeModesList.firstOrNull()?.let { ZenModeInfo(it.id, it.name, getModeIcon(it)) }

        return ActiveZenModes(activeModesList.map { m -> m.name }, mainActiveMode)
    }

    val mainActiveMode: Flow<ZenModeInfo?> = activeModes.map { a -> a.main }.distinctUntilChanged()

    val modesHidingNotifications: Flow<ActiveZenModes> by lazy {
        modes
            .map { modes ->
                modes.filter { mode ->
                    mode.isActive &&
                        !mode.policy.isVisualEffectAllowed(
                            /* effect = */ VISUAL_EFFECT_NOTIFICATION_LIST,
                            /* defaultVal = */ true,
                        )
                }
            }
            .map { modes -> buildActiveZenModes(modes) }
            .flowOn(bgDispatcher)
            .distinctUntilChanged()
    }

    suspend fun getModeIcon(mode: ZenMode): Icon.Loaded {
        // A mode's icon can be a drawable of the app that made the rule. That one never goes through the loader
        // (which would decode the app's image files natively, and keep whatever it got): it is made on an image
        // thread, charged to that app, its bitmap files decoded in the protected VM, and this coroutine suspends.
        // When it cannot be made, the generic mode icon shows, and the next time it is asked for again.
        val appPackage = mode.iconKey.resPackage
        if (!appPackage.isNullOrEmpty()) {
            val icon = android.graphics.drawable.Icon.createWithResource(appPackage, mode.iconKey.resId)
            // What was made before, else made now on an image thread.
            val appIcon =
                ForeignIcons.loadNoWait(context, icon, context.userId, appPackage, MODE_ICON_MAX_PX, null)
                    ?: suspendCancellableCoroutine<Drawable?> { cont ->
                        ForeignIcons.loadAsync(
                            context,
                            icon,
                            context.userId,
                            appPackage,
                            MODE_ICON_MAX_PX,
                            { it.run() },
                        ) {
                            cont.resume(it)
                        }
                    }
            val shown = appIcon?.let { monochromeIfPresent(it) }
                ?: context.getDrawable(com.android.internal.R.drawable.ic_zen_mode_type_other)!!
            return Icon.Loaded(
                drawable = shown.mutate(),
                contentDescription = ContentDescription.Loaded(mode.name),
                packageName = if (appIcon != null) appPackage else "android",
                resId = if (appIcon != null) mode.iconKey.resId else
                    com.android.internal.R.drawable.ic_zen_mode_type_other,
            )
        }
        val zenIcon = iconLoader.getIcon(context, mode).await()
        val drawable = zenIcon.drawable
        // Return a copy of the icon, to prevent callers from making changes that will be reflected
        // everywhere.
        val drawableCopy = drawable.constantState?.newDrawable()?.mutate() ?: drawable.mutate()
        return Icon.Loaded(
            drawable = drawableCopy,
            contentDescription = ContentDescription.Loaded(mode.name),
            packageName = zenIcon.key.resPackage,
            resId = zenIcon.key.resId,
        )
    }

    // As the mode icon loader does for an app's icon: its monochrome layer when it has one.
    private fun monochromeIfPresent(icon: Drawable): Drawable {
        if (icon is AdaptiveIconDrawable) {
            icon.monochrome?.let {
                return InsetDrawable(it, -2.0f * AdaptiveIconDrawable.getExtraInsetFraction())
            }
        }
        return icon
    }

    fun activateMode(zenMode: ZenMode) {
        if (zenMode.isManualDnd) {
            val duration =
                when (zenDuration) {
                    ZEN_DURATION_PROMPT -> {
                        Log.e(
                            TAG,
                            "Interactor cannot handle showing the zen duration prompt. " +
                                "Please use EnableZenModeDialog when this setting is active.",
                        )
                        null
                    }

                    ZEN_DURATION_FOREVER -> null
                    else -> Duration.ofMinutes(zenDuration.toLong())
                }

            zenModeRepository.activateMode(zenMode, duration)
        } else {
            zenModeRepository.activateMode(zenMode)
        }
    }

    fun deactivateMode(zenMode: ZenMode) {
        zenModeRepository.deactivateMode(zenMode)
    }

    fun setHasNextAlarm(hasNextAlarm: Boolean) {
        zenModeRepository.hasNextAlarm.value = hasNextAlarm
    }

    fun deactivateAllModes() {
        // Deactivate in reverse order of priority. This will prevent flickering in the
        // "active mode" icon (which is the highest-priority one).
        val modesToDeactivate =
            zenModeRepository
                .getModes()
                .filter { it.isActive }
                .sortedWith(ZenMode.PRIORITIZING_COMPARATOR.reversed())
        for (mode in modesToDeactivate) {
            deactivateMode(mode)
        }
    }

    private val zenDuration
        get() = notificationSettingsRepository.zenDuration.value

    fun shouldAskForZenDuration(mode: ZenMode): Boolean =
        mode.isManualDnd && (zenDuration == ZEN_DURATION_PROMPT)

    companion object {
        private const val TAG = "ZenModeInteractor"
    }
}
