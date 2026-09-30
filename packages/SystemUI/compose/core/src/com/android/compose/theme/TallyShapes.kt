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

package com.android.compose.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.dimensionResource
import de.diamaneos.systemui.Flags
import de.diamaneos.tally.R as TallyR

/**
 * Tally's shapes for the Compose surfaces drawn in [PlatformTheme]: the shell's 4 / 8 / 12 / 20 dp
 * radii (the token library's `tally_radius_*`) in place of Material's full-round buttons and its 28
 * dp dialogs and sheets. Only while Tally is on.
 */
internal object TallyShapes {
    /**
     * Whether the Tally shell is on. SystemUI code asks TallyShell, which lives in SystemUI itself
     * and so is out of this library's reach; the library reads the same build flag here, and only
     * here, as the clock library does (TallyClocks).
     */
    val isEnabled: Boolean
        get() = Flags.tallyShell()

    /**
     * Material's shape scale in the Tally radii: menus and fields 4 dp (extra small), chips 8 dp
     * (small), cards 12 dp (medium), and the large surfaces, dialogs and sheets 20 dp (large and
     * extra large; Material's are 16 and 28 dp).
     */
    @Composable
    @ReadOnlyComposable
    fun shapes(): Shapes {
        val large = RoundedCornerShape(dimensionResource(TallyR.dimen.tally_radius_l))
        return Shapes(
            extraSmall = RoundedCornerShape(dimensionResource(TallyR.dimen.tally_radius_xs)),
            small = RoundedCornerShape(dimensionResource(TallyR.dimen.tally_radius_s)),
            medium = RoundedCornerShape(dimensionResource(TallyR.dimen.tally_radius_m)),
            large = large,
            extraLarge = large,
        )
    }

    /** A key: the Tally key radius, 8 dp, in place of Material's full-round button. */
    @Composable
    @ReadOnlyComposable
    fun key(): Shape = RoundedCornerShape(dimensionResource(TallyR.dimen.tally_radius_s))
}
