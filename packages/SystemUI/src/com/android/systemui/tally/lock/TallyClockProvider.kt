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
import android.content.res.Resources
import com.android.systemui.customization.R as CustomizationR
import com.android.systemui.plugins.keyguard.ui.clocks.ClockController
import com.android.systemui.plugins.keyguard.ui.clocks.ClockMessageBuffers
import com.android.systemui.plugins.keyguard.ui.clocks.ClockMetadata
import com.android.systemui.plugins.keyguard.ui.clocks.ClockPickerConfig
import com.android.systemui.plugins.keyguard.ui.clocks.ClockProvider
import com.android.systemui.plugins.keyguard.ui.clocks.ClockSettings

/**
 * The clocks of [defaultProvider] plus the Tally lock clock. ClockRegistryModule registers it in
 * place of the default provider while the Tally shell is on and makes the Tally clock the fallback,
 * so a user who never picked a clock gets the Tally clock, and every stock clock stays available.
 */
class TallyClockProvider(
    private val defaultProvider: ClockProvider,
    private val resources: Resources,
) : ClockProvider {

    override fun initialize(buffers: ClockMessageBuffers?) {
        defaultProvider.initialize(buffers)
    }

    override fun getClocks(): List<ClockMetadata> =
        defaultProvider.getClocks() + ClockMetadata(TALLY_CLOCK_ID)

    override fun createClock(ctx: Context, settings: ClockSettings): ClockController? {
        if (settings.clockId != TALLY_CLOCK_ID) return defaultProvider.createClock(ctx, settings)
        return TallyClockController(ctx, settings)
    }

    override fun getClockPickerConfig(settings: ClockSettings): ClockPickerConfig {
        if (settings.clockId != TALLY_CLOCK_ID) {
            return defaultProvider.getClockPickerConfig(settings)
        }
        // The Tally clock is this build's default clock: it takes the default clock's translated
        // name, description and thumbnail rather than new text.
        return ClockPickerConfig(
            TALLY_CLOCK_ID,
            resources.getString(CustomizationR.string.clock_default_name),
            resources.getString(CustomizationR.string.clock_default_description),
            resources.getDrawable(CustomizationR.drawable.clock_default_thumbnail, null),
            isReactiveToTone = false,
        )
    }

    companion object {
        const val TALLY_CLOCK_ID = "TALLY"
    }
}
