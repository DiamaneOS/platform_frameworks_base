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

/** Makes and takes back the switch's changes for the current user. */
interface MomentsPlatform {
    /** Makes [effect]'s change; returns what [revert] needs, or null if it changed nothing. */
    fun apply(effect: MomentsEffect): String?

    /** Takes back a change made earlier, but only where the setting is still as the switch set it. */
    fun revert(effect: MomentsEffect, undo: String)

    /** Sets a transient change again (the system forgets it on reboot); harmless when set. */
    fun reassert(effect: MomentsEffect)
}

/** What the switch has done, per user. It survives SystemUI restarts and reboots. */
interface MomentsStore {
    fun record(user: Int): List<MomentsApplied>

    fun setRecord(user: Int, record: List<MomentsApplied>)

    /** Whether the user has moved the switch since setup finished. */
    fun hasFlipped(user: Int): Boolean

    fun setFlipped(user: Int)

    fun noticeShown(user: Int): Boolean

    fun setNoticeShown(user: Int)
}

/** The facts one reconcile works from. */
data class MomentsInputs(
    /** The switch's position (true = Moments on), or null while it is not known. */
    val on: Boolean?,
    /** Whether the switch moved since the last reconcile (not a boot or restart read). */
    val flipped: Boolean,
    val setupComplete: Boolean,
    val unlocked: Boolean,
    val config: MomentsConfig,
)

data class MomentsResult(
    val applied: Boolean = false,
    val reverted: Boolean = false,
    /** Some changes wait for the unlock before they are taken back. */
    val waitingForUnlock: Boolean = false,
    /** Post the one-time "choose what it does" notice. */
    val showNotice: Boolean = false,
    /** The notice is no longer needed. */
    val dismissNotice: Boolean = false,
)

/**
 * The Moments switch's state machine. Each call compares what the switch should have done (its
 * position and the user's choice) with what it has done (the stored record), and closes the gap:
 * it makes missing changes and takes back changes that are no longer wanted. So boot, a SystemUI
 * restart or a repeated report change nothing twice.
 *
 * - Before setup finishes the switch does nothing.
 * - Until the user saves a choice or moves the switch, it does nothing either; if it is on, the
 *   user gets one notice asking what it should do. A move counts as choosing the default, Moments.
 * - Raising protection always happens at once, also on the lock screen. Taking back a change that
 *   lowers protection waits for the unlock.
 * - A change is taken back only where the setting is still as the switch left it.
 */
class MomentsReconciler(private val platform: MomentsPlatform, private val store: MomentsStore) {

    fun reconcile(user: Int, inputs: MomentsInputs): MomentsResult {
        if (!inputs.setupComplete) return MomentsResult()

        if (inputs.flipped && !store.hasFlipped(user)) store.setFlipped(user)
        val configured = inputs.config.action != null || store.hasFlipped(user)
        if (!configured) {
            if (inputs.on == true && !store.noticeShown(user)) {
                store.setNoticeShown(user)
                return MomentsResult(showNotice = true)
            }
            return MomentsResult()
        }
        val on = inputs.on ?: return MomentsResult(dismissNotice = true)

        val wanted = if (on) inputs.config.effects() else emptyList()
        val record = store.record(user)
        val kept = mutableListOf<MomentsApplied>()
        var reverted = false
        var waiting = false
        for (applied in record) {
            when {
                applied.effect in wanted -> kept += applied
                applied.undo == null -> reverted = true
                applied.effect.kind.lowersProtection && !inputs.unlocked -> {
                    kept += applied
                    waiting = true
                }
                else -> {
                    platform.revert(applied.effect, applied.undo)
                    reverted = true
                }
            }
        }
        if (kept != record) store.setRecord(user, kept)

        var appliedAny = false
        for (effect in wanted) {
            val existing = kept.find { it.effect == effect }
            if (existing != null) {
                if (effect.kind.transient && existing.undo != null) platform.reassert(effect)
                continue
            }
            kept += MomentsApplied(effect, platform.apply(effect))
            appliedAny = true
            store.setRecord(user, kept)
        }
        return MomentsResult(
            applied = appliedAny,
            reverted = reverted,
            waitingForUnlock = waiting,
            dismissNotice = true,
        )
    }
}
