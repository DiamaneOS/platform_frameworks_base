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
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import com.android.systemui.HardwareBgDrawable
import com.android.systemui.res.R
import kotlin.math.min

/**
 * The Tally power menu's list (the prototype's `.pm`): the actions as rows, in the stock order, and
 * the small ones (screenshot, bug report) as keys two to a row under them. Which actions show (the
 * keyguard's and setup's filtering included), their order and what they do come from the stock
 * adapter unchanged; only where they sit changes.
 */
class TallyGlobalActionsLayout(context: Context, attrs: AttributeSet?) :
    GlobalActionsLayout(context, attrs) {
    private val keys = ArrayList<View>()

    init {
        // A tap on the sheet is not a tap outside it, as in the stock layout.
        setOnClickListener {}
    }

    override fun shouldReverseListItems(): Boolean = false

    // The sheet's background is in the layout (tally_global_actions).
    override fun getBackgroundDrawable(backgroundColor: Int): HardwareBgDrawable? = null

    // Tally draws no blur behind its surfaces.
    override fun setIsBlurSupported(isBlurSupported: Boolean) {}

    override fun addToListView(v: View, reverse: Boolean) {
        if (v.getTag(R.id.tally_power_menu_key) == true) {
            keys.add(v)
        } else {
            super.addToListView(v, reverse)
        }
    }

    override fun onUpdateList() {
        keys.clear()
        super.onUpdateList()
        for (start in keys.indices step KEYS_PER_ROW) {
            val row =
                LinearLayout(context).apply {
                    orientation = HORIZONTAL
                    dividerDrawable = context.getDrawable(R.drawable.tally_power_menu_gap)
                    showDividers = SHOW_DIVIDER_MIDDLE
                }
            for (key in keys.subList(start, min(start + KEYS_PER_ROW, keys.size))) {
                row.addView(key, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            }
            listView.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        keys.clear()
    }

    override fun getAnimationOffsetX(): Float = 0f

    override fun getAnimationOffsetY(): Float = 0f

    private companion object {
        const val KEYS_PER_ROW = 2
    }
}
