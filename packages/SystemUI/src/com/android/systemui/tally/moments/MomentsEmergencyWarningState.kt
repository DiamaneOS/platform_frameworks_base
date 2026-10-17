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
 * When the emergency call warning shows: during an outgoing emergency call, while the kernel keeps
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
    var kernelBlocksMic = false
    var switchUp = false
    private var showing = false
    private var dismissed = false

    fun update(): Change {
        val applies = emergencyCall && kernelBlocksMic && !switchUp
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
        "emergencyCall=$emergencyCall kernelBlocksMic=$kernelBlocksMic switchUp=$switchUp " +
            "showing=$showing dismissed=$dismissed"
}
