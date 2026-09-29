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

import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.android.app.tracing.coroutines.launchTraced as launch
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor
import com.android.systemui.keyguard.ui.viewmodel.AodBurnInViewModel
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.statusbar.policy.ConfigurationController
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.flow.combine

/** Binds the always-on display's strip to its view model. */
object TallyAodStripViewBinder {
    private const val TAG = "TallyAodStripViewBinder"

    /**
     * Shows the always-on items only while the device dozes (KeyguardInteractor.isDozing, the flag
     * stock's always-on date line used): the strip fades in with the doze amount as the lamp strip
     * fades out, is invisible (not drawn, not read out) whenever the device is not dozing, even
     * with the doze amount up, and moves with the clock for burn-in protection, as the stock
     * always-on date line did.
     */
    @JvmStatic
    fun bind(
        view: TallyAodStripView,
        viewModel: TallyLampStripViewModel,
        keyguardInteractor: KeyguardInteractor,
        aodBurnInViewModel: AodBurnInViewModel,
        configurationController: ConfigurationController,
    ): DisposableHandle {
        val configurationListener =
            object : ConfigurationController.ConfigurationListener {
                override fun onDensityOrFontScaleChanged() {
                    view.refresh()
                }

                override fun onLocaleListChanged() {
                    view.refresh()
                }
            }
        configurationController.addCallback(configurationListener)
        view.refresh()

        val attachHandle =
            view.repeatWhenAttached {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    launch("$TAG#items") { viewModel.aodItems.collect { view.setItems(it) } }
                    launch("$TAG#dozing") {
                        combine(keyguardInteractor.isDozing, keyguardInteractor.dozeAmount) {
                                dozing,
                                amount ->
                                alwaysOnAlpha(dozing, amount)
                            }
                            .collect {
                                view.alpha = it
                                view.visibility = if (it > 0f) View.VISIBLE else View.INVISIBLE
                            }
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

    /** The always-on strip's alpha: the doze amount while the device dozes, else none. */
    @JvmStatic
    fun alwaysOnAlpha(isDozing: Boolean, dozeAmount: Float): Float =
        if (isDozing) dozeAmount else 0f
}
