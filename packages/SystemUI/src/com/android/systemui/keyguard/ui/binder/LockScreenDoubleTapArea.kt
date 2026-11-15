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

package com.android.systemui.keyguard.ui.binder

import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import com.android.systemui.plugins.keyguard.ui.clocks.ClockViewIds
import com.android.systemui.res.R
import com.android.systemui.shared.R as sharedR
import com.android.systemui.tally.lock.TallyLockDateView

/**
 * Where on the lock screen a double tap turns the screen off (Settings.Secure.DOUBLE_TAP_TO_SLEEP).
 *
 * Buttons, notifications and the bouncer take their own touches, so a tap on them never reaches
 * the lock screen's touch view. The clock, the date and the smartspace do not take touches, so a
 * tap on them does reach it. This keeps a double tap there from counting.
 */
internal object LockScreenDoubleTapArea {

    /** Whether the screen point ([rawX], [rawY]) is on the clock, the date or the smartspace. */
    fun isOnLockScreenContent(touchView: View, rawX: Float, rawY: Float): Boolean {
        val window = touchView.rootView
        val root = window.findViewById<ViewGroup>(R.id.keyguard_root_view) ?: return false
        val windowOnScreen = IntArray(2)
        window.getLocationOnScreen(windowOnScreen)
        val x = (rawX - windowOnScreen[0]).toInt()
        val y = (rawY - windowOnScreen[1]).toInt()
        val bounds = Rect()
        for (i in 0 until root.childCount) {
            val child = root.getChildAt(i)
            if (!isContent(child) || !child.isShown || child.alpha == 0f) continue
            if (child.getGlobalVisibleRect(bounds) && bounds.contains(x, y)) return true
        }
        return false
    }

    private fun isContent(view: View): Boolean =
        view is TallyLockDateView ||
            when (view.id) {
                ClockViewIds.LOCKSCREEN_CLOCK_VIEW_LARGE,
                ClockViewIds.LOCKSCREEN_CLOCK_VIEW_SMALL,
                sharedR.id.bc_smartspace_view,
                sharedR.id.date_smartspace_view,
                sharedR.id.weather_smartspace_view -> true
                else -> false
            }
}
