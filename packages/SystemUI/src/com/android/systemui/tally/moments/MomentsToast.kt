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

package com.android.systemui.tally.moments

import android.provider.Settings.Secure
import androidx.annotation.StringRes
import com.android.systemui.res.R
import com.android.systemui.tally.moments.MomentsEffectKind.AIRPLANE
import com.android.systemui.tally.moments.MomentsEffectKind.CAMERA
import com.android.systemui.tally.moments.MomentsEffectKind.LOCKDOWN
import com.android.systemui.tally.moments.MomentsEffectKind.MICROPHONE
import com.android.systemui.tally.moments.MomentsEffectKind.RINGER

/**
 * The toast after a flip. It names only what is now in effect (on) or was taken back (off), so
 * it never says the switch did something this phone could not do.
 */
object MomentsToast {
    @StringRes
    fun text(on: Boolean, action: Int?, result: MomentsResult): Int? {
        if (!on && result.waitingForUnlock) return R.string.tally_moments_toast_after_unlock
        val kinds = if (on) result.inEffect else result.reverted
        if (kinds.isEmpty()) return null
        return when (action ?: Secure.MOMENTS_ACTION_MOMENTS) {
            Secure.MOMENTS_ACTION_MOMENTS ->
                if (on) R.string.tally_moments_toast_on else R.string.tally_moments_toast_off
            Secure.MOMENTS_ACTION_SENSORS_OFF ->
                when {
                    CAMERA in kinds && MICROPHONE in kinds ->
                        if (on) R.string.tally_moments_toast_sensors_blocked
                        else R.string.tally_moments_toast_sensors_unblocked
                    CAMERA in kinds ->
                        if (on) R.string.tally_moments_toast_camera_blocked
                        else R.string.tally_moments_toast_camera_unblocked
                    MICROPHONE in kinds ->
                        if (on) R.string.tally_moments_toast_mic_blocked
                        else R.string.tally_moments_toast_mic_unblocked
                    else -> null
                }
            Secure.MOMENTS_ACTION_SILENT ->
                when {
                    RINGER !in kinds -> null
                    on -> R.string.tally_moments_toast_silent_on
                    else -> R.string.tally_moments_toast_silent_off
                }
            Secure.MOMENTS_ACTION_AIRPLANE ->
                when {
                    AIRPLANE !in kinds -> null
                    on -> R.string.tally_moments_toast_airplane_on
                    else -> R.string.tally_moments_toast_airplane_off
                }
            // Lockdown is never undone by the switch, so it has no "off" toast.
            Secure.MOMENTS_ACTION_LOCKDOWN ->
                if (on && LOCKDOWN in kinds) R.string.tally_moments_toast_lockdown else null
            else -> null
        }
    }
}
