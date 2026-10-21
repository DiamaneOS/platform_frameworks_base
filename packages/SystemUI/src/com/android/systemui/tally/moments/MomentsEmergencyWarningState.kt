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

/**
 * When the emergency call warning shows: during an outgoing emergency call, or a call within
 * [CALLBACK_WINDOW_MS] after one such as the emergency service calling back, while the kernel keeps
 * the microphone off and the switch is not reported up. Android releases the microphone a moment
 * after the kernel does, so the switch's own report hides the warning first. Once the user closes
 * it, it stays closed until the switch goes up or the call ends.
 */
class MomentsEmergencyWarningState {
    enum class Change {
        NONE,
        SHOW,
        HIDE,
    }

    var emergencyCall = false
    var callbackCall = false
    var kernelBlocksMic = false
    var switchUp = false
    private var showing = false
    private var dismissed = false
    // Elapsed realtime of the end of the last emergency call, or of its start until it ends.
    private var lastEmergencyMs: Long? = null

    fun onEmergencyCall(nowMs: Long) {
        emergencyCall = true
        lastEmergencyMs = nowMs
    }

    /** A call, incoming or outgoing, is off hook. */
    fun onCallOffHook(nowMs: Long) {
        val last = lastEmergencyMs ?: return
        if (nowMs - last < CALLBACK_WINDOW_MS) callbackCall = true
    }

    /** Every call ended. A callback restarts the window, as the emergency is still going on. */
    fun onCallsEnded(nowMs: Long) {
        if (emergencyCall || callbackCall) lastEmergencyMs = nowMs
        emergencyCall = false
        callbackCall = false
    }

    fun update(): Change {
        val applies = (emergencyCall || callbackCall) && kernelBlocksMic && !switchUp
        if (!applies) dismissed = false
        val show = applies && !dismissed
        if (show == showing) return Change.NONE
        showing = show
        return if (show) Change.SHOW else Change.HIDE
    }

    /** The dialog closed. Only a close while it should show is the user's. */
    fun onDismissed() {
        if (!showing) return
        showing = false
        dismissed = true
    }

    override fun toString() =
        "emergencyCall=$emergencyCall callbackCall=$callbackCall kernelBlocksMic=$kernelBlocksMic " +
            "switchUp=$switchUp showing=$showing dismissed=$dismissed"

    companion object {
        // Android's default emergency callback mode length.
        const val CALLBACK_WINDOW_MS = 5L * 60L * 1000L
    }
}
