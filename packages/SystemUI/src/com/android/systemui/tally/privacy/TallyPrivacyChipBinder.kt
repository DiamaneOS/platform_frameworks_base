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

package com.android.systemui.tally.privacy

import android.view.View
import androidx.annotation.MainThread
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.privacy.OngoingPrivacyChip

/** Keeps the privacy chip in the status bar in the colours for the area under it. */
object TallyPrivacyChipBinder {
    /**
     * Binds [view] if it is the privacy chip; other status bar event chips are left as they are.
     */
    @JvmStatic
    @MainThread
    fun bind(view: View, area: TallyIndicatorArea) {
        val chip = view as? OngoingPrivacyChip ?: return
        // Right from its first frame, then as the area changes while the chip is shown.
        chip.tallyAreaDark = area.isStatusBarAreaDark.value
        chip.repeatWhenAttached {
            area.isStatusBarAreaDark.collect { dark -> chip.tallyAreaDark = dark }
        }
    }
}
