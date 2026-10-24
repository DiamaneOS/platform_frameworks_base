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

package com.android.systemui.tally.auditor;

/**
 * When to offer Auditor: when the primary user's setup finishes while SystemUI runs, and again at
 * each start until the user taps the notice, swipes it away or pairs the phone in Auditor. A boot
 * that starts with setup done and nothing offered (normal boots, updates) never offers it.
 */
final class AuditorSetupTrigger {
    static final String AUDITOR = "de.diamaneos.auditor";
    // Auditor's RemoteVerifyJob NOTIFICATION_CHANNEL_SUCCESS_ID. Keep it in step on rebases.
    static final String REMOTE_VERIFIED_CHANNEL = "remote_verification";

    /** Stored states. A factory reset clears them, so the next setup offers Auditor again. */
    static final int NEW = 0;
    static final int OFFERED = 1;
    static final int DONE = 2;

    enum Start { NOTHING, WATCH, POST }

    private int mState = NEW;
    private boolean mWatching;

    /** Returns what to do at start: watch for setup to finish, or post the notice again. */
    Start start(int state, boolean setUp) {
        mState = state;
        mWatching = state == NEW && !setUp;
        if (state == OFFERED) return Start.POST;
        return mWatching ? Start.WATCH : Start.NOTHING;
    }

    /** Returns whether to post the notice now. The state is then {@link #OFFERED}. */
    boolean onSetupChanged(boolean setUp) {
        if (!mWatching || !setUp) return false;
        mWatching = false;
        mState = OFFERED;
        return true;
    }

    /**
     * The user tapped or swiped away the notice, or paired the phone in Auditor. Returns whether
     * the state became DONE.
     */
    boolean onUserDone() {
        if (mState != OFFERED) return false;
        mState = DONE;
        return true;
    }

    int state() {
        return mState;
    }

    /** Whether a notification is Auditor's note of a successful remote pairing or verification. */
    static boolean isRemoteVerified(String packageName, String channelId) {
        return AUDITOR.equals(packageName) && REMOTE_VERIFIED_CHANNEL.equals(channelId);
    }
}
