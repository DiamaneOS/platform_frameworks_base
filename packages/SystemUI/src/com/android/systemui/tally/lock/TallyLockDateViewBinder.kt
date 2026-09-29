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

import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.android.app.tracing.coroutines.launchTraced as launch
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor
import com.android.systemui.keyguard.ui.viewmodel.AodBurnInViewModel
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.statusbar.policy.ConfigurationController
import kotlinx.coroutines.DisposableHandle

/** Binds the Tally lock screen's date line (under clocks other than the Tally clock). */
object TallyLockDateViewBinder {
    private const val TAG = "TallyLockDateViewBinder"

    /**
     * Shows the date, once a minute and on each always-on display tick, in the theme the lock
     * wallpaper calls for ([appContext]'s, which CentralSurfaces sets and announces through
     * [configurationController]). On the always-on display the line stays, as the stock date line
     * did, toward the dark theme's ink and moved with the clock for burn-in protection.
     */
    @JvmStatic
    fun bind(
        view: TallyLockDateView,
        viewModel: TallyLampStripViewModel,
        keyguardInteractor: KeyguardInteractor,
        aodBurnInViewModel: AodBurnInViewModel,
        configurationController: ConfigurationController,
        appContext: Context,
    ): DisposableHandle {
        val configurationListener =
            object : ConfigurationController.ConfigurationListener {
                override fun onThemeChanged() {
                    view.setLightWallpaper(TallyWallTheme.isLightWallpaper(appContext))
                }

                override fun onUiModeChanged() {
                    view.refresh()
                }

                override fun onDensityOrFontScaleChanged() {
                    view.refresh()
                }

                override fun onLocaleListChanged() {
                    view.refresh()
                }
            }
        configurationController.addCallback(configurationListener)
        view.setLightWallpaper(TallyWallTheme.isLightWallpaper(appContext))

        val attachHandle =
            view.repeatWhenAttached {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    launch("$TAG#minutes") { viewModel.minutes.collect { view.setTime(it) } }
                    launch("$TAG#dozeAmount") {
                        keyguardInteractor.dozeAmount.collect { view.setDozeAmount(it) }
                    }
                    launch("$TAG#burnIn") {
                        aodBurnInViewModel.movement.collect {
                            view.translationX = it.translationX.toFloat()
                            view.translationY = it.translationY.toFloat()
                        }
                    }
                }
            }

        return DisposableHandle {
            attachHandle.dispose()
            configurationController.removeCallback(configurationListener)
        }
    }
}
