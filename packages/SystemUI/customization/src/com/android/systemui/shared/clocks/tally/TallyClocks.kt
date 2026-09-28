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

package com.android.systemui.shared.clocks.tally

import android.content.res.Resources
import android.util.TypedValue
import com.android.systemui.customization.R as CustomizationR
import com.android.systemui.plugins.keyguard.ui.clocks.ClockId
import com.android.systemui.plugins.keyguard.ui.clocks.ClockMetadata
import com.android.systemui.plugins.keyguard.ui.clocks.ClockPickerConfig
import com.android.systemui.shared.clocks.DEFAULT_CLOCK_ID
import kotlin.math.roundToInt
import org.diamaneos.systemui.Flags

/**
 * The Tally lock clock in the shared clock library. SystemUI and Wallpaper & style (ThemePicker)
 * each build their clock registry from this library's DefaultClockProvider, so a clock offered here
 * is listed, previewed and picked in both, and a registry that was not told otherwise falls back to
 * it.
 */
object TallyClocks {
    /** The Tally clock's id in the clock registry and in the clock setting. */
    const val TALLY_CLOCK_ID: ClockId = "TALLY"

    /**
     * Whether the Tally shell is on. SystemUI code asks TallyShell, which lives in SystemUI itself
     * and so is out of this library's reach (Wallpaper & style links the library without it); the
     * library reads the same build flag here, and only here.
     */
    @JvmStatic
    val isEnabled: Boolean
        get() = Flags.tallyShell()

    /**
     * The clocks this library adds to DefaultClockProvider's: the Tally clock while Tally is on.
     */
    val clocks: List<ClockMetadata>
        get() = if (isEnabled) listOf(ClockMetadata(TALLY_CLOCK_ID)) else emptyList()

    /**
     * How far below the status bar the Tally clock starts: the prototype's date sits 10 dp under
     * its 48 dp status bar. Other clocks keep SystemUI's keyguard_clock_top_margin.
     */
    const val TOP_MARGIN_DP = 10f

    /** [TOP_MARGIN_DP] in pixels. */
    @JvmStatic
    fun topMarginPx(resources: Resources): Int =
        TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                TOP_MARGIN_DP,
                resources.displayMetrics,
            )
            .roundToInt()

    /** The clock a registry shows when the user never picked one: the Tally clock while on. */
    val fallbackClockId: ClockId
        get() = if (isEnabled) TALLY_CLOCK_ID else DEFAULT_CLOCK_ID

    /**
     * How the clock picker shows the Tally clock: by its name, which is also what TalkBack reads
     * for it, and a thumbnail of the clock's own digits, so that it is told apart from the stock
     * default clock.
     */
    fun pickerConfig(resources: Resources): ClockPickerConfig =
        ClockPickerConfig(
            TALLY_CLOCK_ID,
            resources.getString(CustomizationR.string.tally_clock_name),
            resources.getString(CustomizationR.string.tally_clock_name),
            resources.getDrawable(CustomizationR.drawable.tally_clock_thumbnail, null),
            isReactiveToTone = false,
        )
}
