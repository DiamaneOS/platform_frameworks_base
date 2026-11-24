/*
 * Copyright (C) 2026 DiamaneOS
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

package com.android.wm.shell.bubbles;

/**
 * DiamaneOS: tells a bubble that its shortcut icon was made, also when it was made before the bubble's views listen
 * for it: that news is kept and given to the listener once it is set.
 */
final class BubbleIconMade {
    private Runnable mListener; // guarded by this
    private boolean mRetired;
    private boolean mMade; // guarded by this

    /** The icon was made: the listener runs now, or once it is set. */
    void made() {
        final Runnable listener;
        synchronized (this) {
            if (mRetired) return;
            listener = mListener;
            if (listener == null) {
                mMade = true;
                return;
            }
        }
        listener.run();
    }

    /** Sets what draws the bubble again. It runs at once when the icon was made before. */
    void listen(Runnable listener) {
        synchronized (this) {
            if (mRetired) return;
            mListener = listener;
            if (!mMade) {
                return;
            }
            mMade = false;
        }
        listener.run();
    }
    /** A newer view info replaced this callback. */
    synchronized void retire() {
        mRetired = true;
        mMade = false;
        mListener = null;
    }

}
