/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 The DiamaneOS Project
 */

package com.android.server.ext;

/**
 * When NFC turns off by itself. The timer runs while NFC is on and no reader's field is near the
 * phone. It starts when NFC turns on and again when a field goes away. When it ends with the
 * screen off, NFC turns off. With the screen on, an app may be reading a tag or waiting for a
 * payment terminal, so NFC turns off once the screen goes off.
 */
final class NfcAutoOffRule {
    private boolean mNfcOn;
    private boolean mFieldPresent;
    private boolean mTimeUp;

    void setNfcOn(boolean on) {
        mNfcOn = on;
        mFieldPresent = false;
    }

    void setFieldPresent(boolean present) {
        mFieldPresent = present;
    }

    /** Called each time the timer is set again. Returns whether it should run. */
    boolean restartTimer() {
        mTimeUp = false;
        return mNfcOn && !mFieldPresent;
    }

    /** The timer ended. Returns whether NFC should turn off now. */
    boolean onTimeUp(boolean screenOn) {
        if (!mNfcOn || mFieldPresent) {
            return false;
        }
        mTimeUp = true;
        return !screenOn;
    }

    /** The screen went off. Returns whether NFC should turn off now. */
    boolean onScreenOff() {
        return mTimeUp && mNfcOn && !mFieldPresent;
    }
}
