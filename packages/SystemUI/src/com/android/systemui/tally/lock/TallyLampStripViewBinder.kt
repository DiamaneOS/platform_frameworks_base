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
import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.android.app.tracing.coroutines.launchTraced as launch
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.statusbar.policy.ConfigurationController
import kotlinx.coroutines.DisposableHandle

/** Binds the lock screen's lamp strip to its view model. */
object TallyLampStripViewBinder {
    private const val TAG = "TallyLampStripViewBinder"

    /**
     * Shows the strip's items while the lock screen is visible, in the theme the lock wallpaper
     * calls for ([appContext]'s, which CentralSurfaces sets and announces through
     * [configurationController]), and fades the strip out as the device dozes: the always-on
     * display keeps the clock only.
     */
    @JvmStatic
    fun bind(
        view: TallyLampStripView,
        viewModel: TallyLampStripViewModel,
        keyguardInteractor: KeyguardInteractor,
        configurationController: ConfigurationController,
        appContext: Context,
    ): DisposableHandle {
        val configurationListener =
            object : ConfigurationController.ConfigurationListener {
                override fun onThemeChanged() {
                    view.setLightWallpaper(TallyWallTheme.isLightWallpaper(appContext))
                    view.refresh()
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
        view.refresh()

        val attachHandle =
            view.repeatWhenAttached {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    launch("$TAG#items") { viewModel.items.collect { view.setItems(it) } }
                    launch("$TAG#dozeAmount") {
                        keyguardInteractor.dozeAmount.collect {
                            view.alpha = 1f - it
                            // Faded out, the strip is not drawn, so its lamps stop, and screen
                            // readers skip it; it keeps its place in the layout.
                            view.visibility = if (it < 1f) View.VISIBLE else View.INVISIBLE
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
