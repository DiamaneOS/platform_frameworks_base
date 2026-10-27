/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 The DiamaneOS Project
 */

package com.android.server.ext;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class NfcAutoOffRuleTest {
    private final NfcAutoOffRule mRule = new NfcAutoOffRule();

    private void nfcOn() {
        mRule.setNfcOn(true);
        assertTrue(mRule.restartTimer());
    }

    @Test
    public void noTimerWhileNfcIsOff() {
        mRule.setNfcOn(false);
        assertFalse(mRule.restartTimer());
        assertFalse(mRule.onTimeUp(false));
        assertFalse(mRule.onScreenOff());
    }

    @Test
    public void turnsOffWhenTimeIsUpWithTheScreenOff() {
        nfcOn();
        assertTrue(mRule.onTimeUp(false));
    }

    @Test
    public void waitsForTheScreenToGoOff() {
        nfcOn();
        assertFalse(mRule.onTimeUp(true));
        assertTrue(mRule.onScreenOff());
    }

    @Test
    public void screenOffBeforeTheTimeIsUpKeepsNfcOn() {
        nfcOn();
        assertFalse(mRule.onScreenOff());
    }

    @Test
    public void readerFieldHoldsTheTimer() {
        nfcOn();
        mRule.setFieldPresent(true);
        assertFalse(mRule.restartTimer());
        assertFalse(mRule.onTimeUp(false));
        assertFalse(mRule.onScreenOff());
    }

    @Test
    public void timerStartsAgainWhenTheFieldGoesAway() {
        nfcOn();
        assertFalse(mRule.onTimeUp(true));
        mRule.setFieldPresent(true);
        assertFalse(mRule.restartTimer());
        mRule.setFieldPresent(false);
        assertTrue(mRule.restartTimer());
        assertFalse(mRule.onScreenOff());
    }

    @Test
    public void fieldDuringTheWaitCancelsTheTurnOff() {
        nfcOn();
        assertFalse(mRule.onTimeUp(true));
        mRule.setFieldPresent(true);
        assertFalse(mRule.onScreenOff());
    }

    @Test
    public void turningNfcOffClearsTheWait() {
        nfcOn();
        assertFalse(mRule.onTimeUp(true));
        mRule.setNfcOn(false);
        mRule.restartTimer();
        assertFalse(mRule.onScreenOff());
    }

    @Test
    public void turningNfcOffClearsAStaleField() {
        nfcOn();
        mRule.setFieldPresent(true);
        mRule.setNfcOn(false);
        mRule.setNfcOn(true);
        assertTrue(mRule.restartTimer());
    }

    @Test
    public void settingChangeStartsTheTimerAgain() {
        nfcOn();
        assertFalse(mRule.onTimeUp(true));
        assertTrue(mRule.restartTimer());
        assertFalse(mRule.onScreenOff());
    }
}
