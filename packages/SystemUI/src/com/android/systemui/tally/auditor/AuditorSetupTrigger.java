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
 * When to offer Auditor: once, when the primary user's setup finishes while SystemUI runs. A boot
 * that starts with setup done (normal boots, updates) never offers it.
 */
final class AuditorSetupTrigger {
    private boolean mWatching;

    /** Returns whether to watch for setup to finish. */
    boolean start(boolean setUp) {
        mWatching = !setUp;
        return mWatching;
    }

    /** Returns whether to post the notice now. */
    boolean onSetupChanged(boolean setUp) {
        if (!mWatching || !setUp) return false;
        mWatching = false;
        return true;
    }
}
