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
import android.icu.util.TimeZone
import android.text.TextUtils
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import com.android.systemui.shared.clocks.tally.TallyClocks
import de.diamaneos.tally.R as TallyR
import java.util.Date
import java.util.Locale

/**
 * The date line of the Tally lock screen under a clock that has no date of its own (any clock but
 * the Tally clock, whose face carries the date), where the stock date line was: the Tally clock's
 * date format and lock date type, in the ink of the theme the lock wallpaper calls for, toward the
 * dark theme's ink on the always-on display, as the Tally clock's date.
 */
class TallyLockDateView(context: Context) : TextView(context) {
    private var dateFormat = TallyClocks.dateFormat(Locale.getDefault())
    private var timeMillis = 0L
    private var lightWallpaper = false
    private var dozeAmount = 0f
    private var ink = 0
    private var darkInk = 0

    init {
        includeFontPadding = false
        setSingleLine(true)
        ellipsize = TextUtils.TruncateAt.END
        refresh()
    }

    /** Shows the date of [millis] in the current time zone. */
    fun setTime(millis: Long) {
        timeMillis = millis
        dateFormat.timeZone = TimeZone.getDefault()
        text = dateFormat.format(Date(millis))
    }

    /** Draws the date in the ink the lock wallpaper calls for: dark on a light one. */
    fun setLightWallpaper(light: Boolean) {
        lightWallpaper = light
        loadColors()
    }

    /** Moves the colour toward the dark theme's ink as the device dozes. */
    fun setDozeAmount(amount: Float) {
        dozeAmount = amount
        applyColor()
    }

    /**
     * Reads resources and the locale again, after a theme, density, font scale or locale change.
     */
    fun refresh() {
        setTextAppearance(TallyR.style.TextAppearance_Tally_LockDate)
        dateFormat = TallyClocks.dateFormat(Locale.getDefault())
        if (timeMillis != 0L) setTime(timeMillis)
        loadColors()
    }

    private fun loadColors() {
        ink = TallyWallTheme.context(context, lightWallpaper).getColor(TallyR.color.tally_ink)
        darkInk = TallyWallTheme.context(context, false).getColor(TallyR.color.tally_ink)
        applyColor()
    }

    private fun applyColor() {
        setTextColor(ColorUtils.blendARGB(ink, darkInk, dozeAmount))
    }
}
