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

/**
 * What a Tally lamp shows. Lamps only ever show real state, and they always carry words: the text
 * next to the lamp, or the description of the item it belongs to, says what the state is and why.
 */
enum class TallyLampState {
    /** Nothing is on: a ring in the outline colour. */
    OFF,

    /**
     * Waiting on the system: the ring in four dashes in the accent colour, turning once per 1.6 s
     * for at most three turns, then still.
     */
    REQUESTED,

    /** On: a disc in the lamp colour. */
    ON,

    /** On and doing something now (emitting): a slightly smaller disc with a ring of light. */
    LIVE,

    /** Failed: a ring in the error colour, broken at one to two o'clock. */
    FAILED,

    /** Cannot be on: the off ring in the muted colour. The reason is always in words. */
    UNAVAILABLE;

    /** Whether the lamp is lit: on or live. */
    val isLit: Boolean
        get() = this == ON || this == LIVE

    companion object {
        /**
         * For a lamp's `requestedSinceMillis`: the caller does not know when the request started,
         * so the lamp counts the dashes' turns from when it first shows [REQUESTED].
         */
        const val SINCE_FIRST_SHOWN = Long.MIN_VALUE
    }
}
