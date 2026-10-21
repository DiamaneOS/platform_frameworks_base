/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.shade;

import android.content.ContentResolver;
import android.content.Context;
import android.os.UserHandle;
import android.provider.Settings;
import android.view.Display;

/** The refresh rate that {@link NotificationAnimationRefreshRate} asks for. */
final class NotificationAnimationPeakRefreshRate {

    /**
     * The refresh rate for notification animations: the display's highest, within the user's
     * limit (Smooth display off caps it at 60 Hz), or 0 where the display adapts its refresh rate
     * to the toolkit's own votes.
     */
    static float forDisplay(Context context) {
        final Display display = context.getDisplay();
        if (display == null || display.hasArrSupport()) {
            return 0;
        }
        float highestRefreshRate = 0;
        for (Display.Mode mode : display.getSupportedModes()) {
            highestRefreshRate = Math.max(highestRefreshRate, mode.getRefreshRate());
        }
        final ContentResolver resolver = context.getContentResolver();
        final float minSetting = Settings.System.getFloatForUser(resolver,
                Settings.System.MIN_REFRESH_RATE, 0f, UserHandle.USER_CURRENT);
        final float peakSetting = Settings.System.getFloatForUser(resolver,
                Settings.System.PEAK_REFRESH_RATE,
                context.getResources().getInteger(
                        com.android.internal.R.integer.config_defaultPeakRefreshRate),
                UserHandle.USER_CURRENT);
        return NotificationAnimationRefreshRate.peakRefreshRate(
                highestRefreshRate, minSetting, peakSetting);
    }
}
