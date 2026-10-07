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

import androidx.annotation.StringRes
import com.android.systemui.res.R
import com.android.systemui.tally.moments.MomentsEffectKind.CAMERA
import com.android.systemui.tally.moments.MomentsEffectKind.MICROPHONE

/**
 * Which camera and microphone blocks are the Moments switch's, for one user.
 *
 * A block is the switch's while either:
 * - the hardware toggle blocks the sensor (on DiamaneOS phones only the switch's kernel floor
 *   sets it, from the kernel's own report), or
 * - the switch turned the software toggle on (its record holds the change) and nobody has turned
 *   it off since. Once the user unblocks it, a later block is theirs, even while the switch is
 *   still on.
 *
 * Only system state goes in: the switch's record and the sensor privacy toggles. Nothing an app
 * can set decides it.
 */
class MomentsSensorOwnership {
    private val owned = mutableSetOf<MomentsEffectKind>()
    private var lastInRecord: Set<MomentsEffectKind>? = null

    /**
     * Takes the sensors the switch's record says it blocked ([inRecord]). The first call (start
     * of SystemUI) counts those still blocked; later calls count the ones newly recorded.
     */
    fun onRecord(inRecord: Set<MomentsEffectKind>, softwareBlocked: (MomentsEffectKind) -> Boolean) {
        val previous = lastInRecord
        for (kind in inRecord) {
            if (previous == null) {
                if (softwareBlocked(kind)) owned += kind
            } else if (kind !in previous) {
                owned += kind
            }
        }
        owned.retainAll(inRecord)
        lastInRecord = inRecord
    }

    /** The software toggle for [kind] went off: any later block is not the switch's. */
    fun onSoftwareUnblocked(kind: MomentsEffectKind) {
        owned -= kind
    }

    fun isSwitchBlock(kind: MomentsEffectKind, softwareBlocked: Boolean, hardwareBlocked: Boolean) =
        hardwareBlocked || (softwareBlocked && kind in owned)

    companion object {
        /** The sensors whose software block the switch made, from its record. */
        fun inRecord(record: List<MomentsApplied>): Set<MomentsEffectKind> =
            record
                .filter { it.undo != null && (it.effect.kind == CAMERA || it.effect.kind == MICROPHONE) }
                .map { it.effect.kind }
                .toSet()
    }
}

/**
 * The note shown in place of the stock "Unblock" prompt when an app reaches for a sensor the
 * switch blocks: at most one per [minGapMs] unless it names more sensors than the last one.
 */
class MomentsSensorNoteThrottle(private val minGapMs: Long = MIN_GAP_MS) {
    private var lastAt = Long.MIN_VALUE
    private var lastKinds: Set<MomentsEffectKind> = emptySet()

    /** [enabled] is the user's "show a note" setting; off means silent blocking. */
    fun shouldShow(kinds: Set<MomentsEffectKind>, nowMs: Long, enabled: Boolean = true): Boolean {
        if (!enabled || kinds.isEmpty()) return false
        val recent = lastAt != Long.MIN_VALUE && nowMs - lastAt < minGapMs
        if (recent && lastKinds.containsAll(kinds)) return false
        lastAt = nowMs
        lastKinds = if (recent) lastKinds + kinds else kinds
        return true
    }

    companion object {
        const val MIN_GAP_MS = 10_000L

        @StringRes
        fun text(kinds: Set<MomentsEffectKind>): Int =
            when {
                CAMERA in kinds && MICROPHONE in kinds ->
                    R.string.tally_moments_note_camera_mic_off
                CAMERA in kinds -> R.string.tally_moments_note_camera_off
                else -> R.string.tally_moments_note_mic_off
            }
    }
}
