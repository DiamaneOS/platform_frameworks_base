/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.systemui.statusbar.notification.stack;

import android.util.MathUtils;
import android.view.View;

import com.android.systemui.statusbar.notification.footer.ui.view.FooterView;

/** Tally's section spacing helpers for {@link StackScrollAlgorithm}. */
final class TallyStackSpacing {

    /** Tally: whether {@code child} is the first card under a section header. */
    static boolean followsSectionHeader(int visibleIndex, View child, View previousChild) {
        return visibleIndex > 0
                && previousChild instanceof SectionHeaderView
                && !(child instanceof FooterView);
    }

    /**
     * Tally: {@link StackScrollAlgorithm#getGapForLocation} with the shade's gap from
     * {@link StackScrollAlgorithm#getTallyShadeGap}. The lock screen keeps stock's gaps, and the
     * gap moves between the two as stock's does.
     */
    static float getTallyGapForLocation(float fractionToShade, boolean onKeyguard,
            float gapOnLockscreen, float shadeGap) {
        if (fractionToShade > 0f) {
            return MathUtils.lerp(gapOnLockscreen, shadeGap, fractionToShade);
        }
        if (onKeyguard) {
            return gapOnLockscreen;
        }
        return shadeGap;
    }
}
