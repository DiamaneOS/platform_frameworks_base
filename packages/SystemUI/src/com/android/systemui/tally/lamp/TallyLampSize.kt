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

package com.android.systemui.tally.lamp

import androidx.annotation.DimenRes
import org.diamaneos.tally.R as TallyR

/**
 * The token sizes of a Tally lamp. A lamp may take any size, but these are the ones the shell uses;
 * lamps smaller than [DEFAULT] draw with the thinner ring, halo and gap.
 */
enum class TallyLampSize {
    /** 10 dp: inline readouts, chips, media output. */
    SMALL,

    /** 12 dp: the lock strip, rows, the power menu. */
    DEFAULT,

    /** 14 dp: tallies, Quick Settings tiles, sheet keys. */
    LARGE,

    /** 16 dp: tile lamps at 200 % text. */
    XLARGE;

    /** The lamp's diameter. */
    @get:DimenRes
    val sizeRes: Int
        get() =
            when (this) {
                SMALL -> TallyR.dimen.tally_lamp_size_small
                DEFAULT -> TallyR.dimen.tally_lamp_size
                LARGE -> TallyR.dimen.tally_lamp_size_large
                XLARGE -> TallyR.dimen.tally_lamp_size_xlarge
            }

    /**
     * The token box of this size: the lamp plus the live lamp's ring of light, which the static
     * lamp drawables (`tally_lamp_<form>_<size>`) fill. The animated lamp's views measure this, or
     * up to a pixel more on each side so that the lamp sits on whole pixels.
     */
    @get:DimenRes
    val boxRes: Int
        get() =
            when (this) {
                SMALL -> TallyR.dimen.tally_lamp_box_10
                DEFAULT -> TallyR.dimen.tally_lamp_box_12
                LARGE -> TallyR.dimen.tally_lamp_box_14
                XLARGE -> TallyR.dimen.tally_lamp_box_16
            }
}
