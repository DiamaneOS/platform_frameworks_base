/*
 * Copyright (C) 2026 The Android Open Source Project
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

package com.android.systemui.shade

import android.os.Handler

/**
 * Asks for the display's peak refresh rate while the notification stack animates.
 *
 * A display that switches between fixed refresh rates may idle at a very low rate, and
 * SurfaceFlinger only raises it on touch or for content it sees update often; on such a display it
 * does not use the toolkit's frame rate category votes. An animation that starts without a touch,
 * such as a heads-up or a notification its app replaces, then runs at the idle rate until
 * SurfaceFlinger's heuristics notice it. A minimum refresh rate on the shade window raises the rate
 * within one mode switch.
 *
 * Each batch of stack animations starts or renews the request. It is held for [HOLD_MS] after the
 * animations end, so back-to-back batches do not switch modes in between, and it ends [TIMEOUT_MS]
 * after the last batch started if an end is missed. [clear] drops it at once, for example when the
 * shade window hides or the device dozes.
 */
class NotificationAnimationRefreshRate(
    private val handler: Handler,
    private val peakRefreshRate: RateSource,
    private val onChanged: Runnable,
) {
    /** Supplies the refresh rate to ask for, or 0 to ask for none. */
    fun interface RateSource {
        fun refreshRate(): Float
    }

    /** The minimum refresh rate the shade window asks for, or 0 for none. */
    var minRefreshRate = 0f
        private set

    private val release = Runnable { update(0f) }

    /** Called when a batch of stack animations starts, and when all of them have ended. */
    fun onStackAnimating(animating: Boolean) {
        handler.removeCallbacks(release)
        if (animating) {
            handler.postDelayed(release, TIMEOUT_MS)
            if (minRefreshRate == 0f) {
                update(peakRefreshRate.refreshRate())
            }
        } else if (minRefreshRate > 0f) {
            handler.postDelayed(release, HOLD_MS)
        }
    }

    /** Drops the request without calling back: the caller applies the change itself. */
    fun clear() {
        handler.removeCallbacks(release)
        minRefreshRate = 0f
    }

    private fun update(rate: Float) {
        if (rate == minRefreshRate) {
            return
        }
        minRefreshRate = rate
        onChanged.run()
    }

    companion object {
        /** How long the request outlasts the animations. */
        const val HOLD_MS = 500L

        /** How long the request lasts after the last batch started, whatever happens. */
        const val TIMEOUT_MS = 3000L

        /**
         * The rate to ask for: the display's highest refresh rate within the user's limit, read the
         * way DisplayModeDirector reads the settings. The limit is the larger of the minimum and
         * peak refresh rate settings; infinity asks for the highest rate, and a peak of 0 sets no
         * limit.
         */
        @JvmStatic
        fun peakRefreshRate(
            highestRefreshRate: Float,
            minSetting: Float,
            peakSetting: Float,
        ): Float {
            val min = if (minSetting.isInfinite()) highestRefreshRate else minSetting
            val peak =
                if (peakSetting.isInfinite() || peakSetting == 0f) highestRefreshRate
                else peakSetting
            return minOf(highestRefreshRate, maxOf(min, peak))
        }

        /**
         * The shade window's minimum refresh rate: the keyguard's own request, raised for
         * notification animations but never above the keyguard's maximum. 0 means none.
         */
        @JvmStatic
        fun combineMinRefreshRate(
            keyguardMin: Float,
            keyguardMax: Float,
            animationMin: Float,
        ): Float {
            val min = maxOf(keyguardMin, animationMin)
            return if (keyguardMax > 0f) minOf(min, keyguardMax) else min
        }
    }
}
