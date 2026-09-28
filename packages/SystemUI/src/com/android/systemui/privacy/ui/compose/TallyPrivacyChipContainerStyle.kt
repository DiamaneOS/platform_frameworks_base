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

package com.android.systemui.privacy.ui.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.TextStyle
import com.android.systemui.tally.TallyShell
import com.android.systemui.tally.shade.tallyTextStyle
import de.diamaneos.tally.R as TallyR

/**
 * Tally's look for the shade header's grouped privacy container: a tonal key (with the key radius
 * the Tally sensor chip inside it sets) whose "Privacy" and chevron are in ink and Tally type. Only
 * the drawing: which sensors it shows, when, and its tap (the privacy dialog) stay stock.
 *
 * It lives beside [PrivacyChipContainer] so that file reads it without new imports.
 */
internal object TallyPrivacyChipContainerStyle {
    val isEnabled: Boolean
        get() = TallyShell.isEnabled

    /** A tonal key, as the shade's other keys. */
    val container: Color
        @ReadOnlyComposable @Composable get() = colorResource(TallyR.color.tally_surface_high)

    /** Ink, which is light in dark theme and dark in light theme, as the container's text. */
    val chevronAndText: Color
        @ReadOnlyComposable @Composable get() = colorResource(TallyR.color.tally_ink)

    /** Tally's plain chevron in place of the rounded, filled one. */
    val chevron: Int = com.android.internal.R.drawable.ic_chevron_end

    @Composable
    fun textStyle(): TextStyle =
        tallyTextStyle(
            TallyR.style.TextAppearance_Tally_LabelSmall,
            TallyR.dimen.tally_type_label_small_line_height,
        )
}
