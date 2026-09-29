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

import de.diamaneos.systemui.Flags

/**
 * Whether DiamaneOS Tally is on in WM Shell. It is SystemUI's one Tally build flag
 * (`de.diamaneos.systemui.tally_shell`, fixed read-only), so the flag that turns Tally off in
 * SystemUI turns it off here too, and WM Shell is then GrapheneOS's own.
 *
 * WM Shell cannot ask SystemUI's `TallyShell` (SystemUI depends on WM Shell, not the other way
 * round), so this object is the one place in WM Shell that reads the generated flag class. Every
 * Tally change in WM Shell asks here.
 */
@Suppress("NOTHING_TO_INLINE")
object TallyWmShell {
    const val FLAG_NAME = Flags.FLAG_TALLY_SHELL

    /** Is Tally on. A constant in the build, so R8 drops the side that is off. */
    @JvmStatic
    inline val isEnabled
        get() = Flags.tallyShell()
}
