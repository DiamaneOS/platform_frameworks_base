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

package com.android.systemui.globalactions

import android.content.Context
import android.content.res.Configuration
import android.util.AttributeSet
import android.view.WindowInsets
import android.widget.FrameLayout
import com.android.systemui.res.R
import kotlin.math.max

/**
 * Holds the Tally power menu by the power key (the prototype's `.pm`): in portrait, at the right
 * edge and centred on the key's height as SystemUI knows it
 * (`physical_power_button_center_screen_location_y`, which the wake animation also uses), kept
 * clear of the system bars; in landscape, centred, as stock's menu is.
 */
class TallyPowerMenuContainer(context: Context, attrs: AttributeSet?) :
    FrameLayout(context, attrs) {
    private val location = IntArray(2)

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        val bars =
            insets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
            )
        setPadding(bars.left, bars.top, bars.right, bars.bottom)
        return insets
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (resources.configuration.orientation != Configuration.ORIENTATION_PORTRAIT) return
        val menu = findViewById<GlobalActionsLayout>(R.id.global_actions_view) ?: return
        val params = menu.layoutParams as LayoutParams
        val keyY =
            resources.getDimensionPixelSize(R.dimen.physical_power_button_center_screen_location_y)
        getLocationOnScreen(location)
        val minTop = paddingTop + params.topMargin
        val maxTop = max(minTop, height - paddingBottom - params.bottomMargin - menu.measuredHeight)
        val menuTop = (keyY - location[1] - menu.measuredHeight / 2).coerceIn(minTop, maxTop)
        val menuRight = width - paddingRight - params.rightMargin
        menu.layout(
            menuRight - menu.measuredWidth,
            menuTop,
            menuRight,
            menuTop + menu.measuredHeight,
        )
    }
}
