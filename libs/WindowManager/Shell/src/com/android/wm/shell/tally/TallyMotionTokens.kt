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

package com.android.wm.shell.tally

import android.content.res.Resources
import de.diamaneos.tally.R as TallyR

/**
 * Tally's motion values in WM Shell, from the token library (`tallytokens`): the slab spring
 * (full-screen surfaces: windows and pages, stiffness 420), the fill spring (effects: opacity,
 * stiffness 1600), both with a damping ratio of 1, and the tap impulse (0.6).
 */
object TallyMotionTokens {
    @Volatile private var cached: TallyPageMotion? = null

    /** The page motion at the density of [res]; made once per density. */
    @JvmStatic
    fun pageMotion(res: Resources): TallyPageMotion {
        val density = res.displayMetrics.density
        cached?.let { if (it.density == density) return it }
        return TallyPageMotion(
                res.getFloat(TallyR.dimen.tally_spring_slab_stiffness),
                res.getFloat(TallyR.dimen.tally_spring_fill_stiffness),
                res.getFloat(TallyR.dimen.tally_motion_tap_impulse),
                density,
            )
            .also { cached = it }
    }

    /** The fill spring, for fades. */
    @JvmStatic fun fill(res: Resources): TallySpring = pageMotion(res).fill
}
