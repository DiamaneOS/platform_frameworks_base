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

package com.android.systemui.tally.privacy

import android.content.res.Resources
import android.graphics.Color
import android.view.Display
import androidx.core.graphics.ColorUtils
import com.android.internal.colorextraction.ColorExtractor
import com.android.systemui.Flags
import com.android.systemui.colorextraction.SysuiColorExtractor
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor
import com.android.systemui.res.R
import com.android.systemui.shade.domain.interactor.ShadeInteractor
import com.android.systemui.statusbar.phone.SystemUIDialogManager
import com.android.systemui.statusbar.phone.domain.interactor.DarkIconInteractor
import com.android.systemui.statusbar.policy.ConfigurationController
import com.android.systemui.utils.coroutines.flow.conflatedCallbackFlow
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Tells Tally's privacy indicators whether the area under them is dark, so that they take the
 * `_dark` colour variant there and the `_light` one over a light area ([TallyIndicatorColors]).
 *
 * The area is read the way the status icons read it, never from night mode: the status bar's icon
 * tint (the app's light or dark status bar, as the window manager reports it), the lock screen's
 * own tint on the lock, and the shade's once it is pulled down. A dim of 15 % or more over the
 * status bar area makes it a dark area: the bouncer, a SystemUI dialog (the power menu included;
 * the few SystemUI dialogs that do not dim count too) and the dozing screen. An app's dimming
 * dialog reaches this through the app's status bar appearance. Where the area cannot be known to be
 * light, it counts as dark: a `_dark` variant with its black edge keeps 3:1 over any colour.
 *
 * Only Tally code reads this, so it is created only when Tally is on.
 */
@SysUISingleton
class TallyIndicatorArea
@Inject
constructor(
    @Application scope: CoroutineScope,
    @Main private val resources: Resources,
    darkIconInteractor: DarkIconInteractor,
    keyguardInteractor: KeyguardInteractor,
    shadeInteractor: ShadeInteractor,
    private val dialogManager: SystemUIDialogManager,
    private val colorExtractor: SysuiColorExtractor,
    private val configurationController: ConfigurationController,
) {
    /**
     * The status bar's own icon tint on the default display. A status bar split between light and
     * dark apps counts as dark.
     */
    private val isStatusBarTintDark: Flow<Boolean> =
        darkIconInteractor
            .darkState(Display.DEFAULT_DISPLAY)
            .map { it.areas.isNotEmpty() || it.darkIntensity < 0.5f }
            .distinctUntilChanged()

    /** Whether a SystemUI dialog shows, dimming everything behind it, the status bar included. */
    private val isDialogShowing: Flow<Boolean> = conflatedCallbackFlow {
        val listener = SystemUIDialogManager.Listener { showing -> trySend(showing) }
        dialogManager.registerListener(listener)
        trySend(dialogManager.shouldHideAffordance())
        awaitClose { dialogManager.unregisterListener(listener) }
    }

    /** The lock screen's own area: its status icons take the lock wallpaper's text colour. */
    private val isLockAreaDark: Flow<Boolean> =
        conflatedCallbackFlow {
                val listener =
                    ColorExtractor.OnColorsChangedListener { _, _ -> trySend(lockAreaDark()) }
                colorExtractor.addOnColorsChangedListener(listener)
                trySend(lockAreaDark())
                awaitClose { colorExtractor.removeOnColorsChangedListener(listener) }
            }
            .distinctUntilChanged()

    /** The shade's own area, from the colour of its header's icons. */
    private val isShadeAreaDark: Flow<Boolean> =
        conflatedCallbackFlow {
                val listener =
                    object : ConfigurationController.ConfigurationListener {
                        override fun onUiModeChanged() {
                            trySend(shadeAreaDark())
                        }

                        override fun onThemeChanged() {
                            trySend(shadeAreaDark())
                        }
                    }
                configurationController.addCallback(listener)
                trySend(shadeAreaDark())
                awaitClose { configurationController.removeCallback(listener) }
            }
            .distinctUntilChanged()

    /**
     * For indicators drawn in the status bar window (the privacy chip as it animates in, the
     * capture chips): the status bar's icon tint, or dark while a SystemUI dialog dims the bar. The
     * shade and the lock screen cover the status bar window, so they do not count here.
     */
    val isStatusBarAreaDark: StateFlow<Boolean> =
        combine(isStatusBarTintDark, isDialogShowing) { tintDark, dialog -> tintDark || dialog }
            .distinctUntilChanged()
            .stateIn(scope, SharingStarted.Eagerly, initialValue = true)

    /**
     * For indicators drawn above every window over the status bar area (the privacy dot, the lens
     * ring and the location lamp by the lens): what is shown under them. The lock screen's tint
     * while the lock is up and not covered by an app, else the status bar's; the shade's once it is
     * fully down, and while it moves, dark unless both are light; dark under a dim.
     */
    val isOverlayAreaDark: StateFlow<Boolean> =
        combine(
                combine(
                    isStatusBarTintDark,
                    keyguardInteractor.isKeyguardVisible,
                    isLockAreaDark,
                ) { tintDark, lockVisible, lockDark ->
                    if (lockVisible) lockDark else tintDark
                },
                shadeInteractor.anyExpansion,
                isShadeAreaDark,
                combine(
                    keyguardInteractor.primaryBouncerShowing,
                    keyguardInteractor.isDozing,
                    isDialogShowing,
                ) { bouncer, dozing, dialog ->
                    bouncer || dozing || dialog
                },
            ) { underDark, shadeExpansion, shadeDark, dimmed ->
                dimmed ||
                    when {
                        shadeExpansion <= 0f -> underDark
                        shadeExpansion >= 1f -> shadeDark
                        else -> underDark || shadeDark
                    }
            }
            .distinctUntilChanged()
            .stateIn(scope, SharingStarted.Eagerly, initialValue = true)

    private fun lockAreaDark(): Boolean = !colorExtractor.neutralColors.supportsDarkText()

    private fun shadeAreaDark(): Boolean {
        // As ShadeHeaderController: the header's icons are shade_header_text_color over the
        // blurred shade, and white over the opaque one.
        val iconColor =
            if (Flags.notificationShadeBlur()) {
                resources.getColor(R.color.shade_header_text_color, /* theme= */ null)
            } else {
                Color.WHITE
            }
        return ColorUtils.calculateLuminance(iconColor) > 0.5
    }
}
