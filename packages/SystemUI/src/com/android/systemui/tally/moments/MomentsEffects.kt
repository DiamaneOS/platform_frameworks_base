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

import android.provider.Settings.Secure

/**
 * One change the Moments switch can make.
 *
 * @property lowersProtection undoing it weakens protection (sensors back on, airplane mode off,
 *   apps unpaused), so it waits until the phone is unlocked, as GrapheneOS's tiles do.
 * @property transient the system forgets it on reboot, so it is set again while the switch is on.
 */
enum class MomentsEffectKind(
    val key: String,
    val lowersProtection: Boolean = false,
    val transient: Boolean = false,
) {
    DND("dnd"),
    HOME("home"),
    PAUSE_APPS("pause", lowersProtection = true),
    GREYSCALE("grey", transient = true),
    CAMERA("camera", lowersProtection = true),
    MICROPHONE("mic", lowersProtection = true),
    RINGER("ringer"),
    AIRPLANE("airplane", lowersProtection = true),
    LOCKDOWN("lockdown"),
}

/** A change and its parameter (for [MomentsEffectKind.PAUSE_APPS], the sorted packages). */
data class MomentsEffect(val kind: MomentsEffectKind, val param: String = "")

/**
 * A change the switch made: [undo] holds what is needed to take it back, or is null when it
 * changed nothing (the setting was already so, or the change cannot be taken back, as Lockdown).
 */
data class MomentsApplied(val effect: MomentsEffect, val undo: String?)

/** What the user chose in Settings, for one user. */
data class MomentsConfig(
    /** A Secure.MOMENTS_ACTION_* value, or null until the user saves a choice. */
    val action: Int?,
    val pausedApps: List<String> = emptyList(),
    val greyscale: Boolean = false,
) {
    /** The changes the switch makes in its on position. */
    fun effects(): List<MomentsEffect> =
        when (action ?: Secure.MOMENTS_ACTION_MOMENTS) {
            Secure.MOMENTS_ACTION_MOMENTS ->
                buildList {
                    add(MomentsEffect(MomentsEffectKind.DND))
                    add(MomentsEffect(MomentsEffectKind.HOME))
                    val apps = pausedApps.filter { it.isNotBlank() }.distinct().sorted()
                    if (apps.isNotEmpty()) {
                        add(MomentsEffect(MomentsEffectKind.PAUSE_APPS, apps.joinToString(",")))
                    }
                    if (greyscale) add(MomentsEffect(MomentsEffectKind.GREYSCALE))
                }
            Secure.MOMENTS_ACTION_SENSORS_OFF ->
                listOf(
                    MomentsEffect(MomentsEffectKind.CAMERA),
                    MomentsEffect(MomentsEffectKind.MICROPHONE),
                )
            Secure.MOMENTS_ACTION_SILENT -> listOf(MomentsEffect(MomentsEffectKind.RINGER))
            Secure.MOMENTS_ACTION_AIRPLANE -> listOf(MomentsEffect(MomentsEffectKind.AIRPLANE))
            Secure.MOMENTS_ACTION_LOCKDOWN -> listOf(MomentsEffect(MomentsEffectKind.LOCKDOWN))
            else -> emptyList()
        }

    companion object {
        /**
         * The action as saved, with an earlier "airplane mode and/or Lockdown" choice (action 3
         * plus [legacyOffline] flags) read as Lockdown when Lockdown was on and the phone has a
         * secure lock screen, and as airplane mode otherwise. Settings writes the same rule back
         * when its page opens.
         */
        fun migratedAction(action: Int?, legacyOffline: Int?, secureLock: Boolean): Int? {
            if (action != Secure.MOMENTS_ACTION_AIRPLANE || legacyOffline == null) return action
            return if (legacyOffline and Secure.MOMENTS_OFFLINE_LOCKDOWN != 0 && secureLock) {
                Secure.MOMENTS_ACTION_LOCKDOWN
            } else {
                Secure.MOMENTS_ACTION_AIRPLANE
            }
        }
    }
}

/** Stores the changes the switch made as text, one per line: `kind|param|undo`. */
object MomentsRecordCodec {
    private const val NO_UNDO = "-"

    fun encode(record: List<MomentsApplied>): String =
        record.joinToString("\n") { a ->
            listOf(a.effect.kind.key, a.effect.param, a.undo?.let { "=$it" } ?: NO_UNDO)
                .joinToString("|")
        }

    fun decode(text: String?): List<MomentsApplied> {
        if (text.isNullOrEmpty()) return emptyList()
        return text.lines().mapNotNull { line ->
            val parts = line.split('|')
            if (parts.size != 3) return@mapNotNull null
            val kind = MomentsEffectKind.entries.find { it.key == parts[0] } ?: return@mapNotNull null
            val undo = if (parts[2] == NO_UNDO) null else parts[2].removePrefix("=")
            MomentsApplied(MomentsEffect(kind, parts[1]), undo)
        }
    }
}
