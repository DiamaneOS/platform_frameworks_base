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

import static com.android.systemui.tally.auditor.AuditorSetupTrigger.DONE;
import static com.android.systemui.tally.auditor.AuditorSetupTrigger.NEW;
import static com.android.systemui.tally.auditor.AuditorSetupTrigger.OFFERED;
import static com.android.systemui.tally.auditor.AuditorSetupTrigger.Start.NOTHING;
import static com.android.systemui.tally.auditor.AuditorSetupTrigger.Start.POST;
import static com.android.systemui.tally.auditor.AuditorSetupTrigger.Start.WATCH;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Plain JUnit: {@link AuditorSetupTrigger} uses no Android classes. */
public class AuditorSetupTriggerTest {
    @Test
    public void bootWithSetupDoneNeverOffers() {
        final AuditorSetupTrigger trigger = new AuditorSetupTrigger();
        assertEquals(NOTHING, trigger.start(NEW, true));
        assertFalse(trigger.onSetupChanged(true));
        assertFalse(trigger.onUserDone());
        assertEquals(NEW, trigger.state());
    }

    @Test
    public void offersOnceWhenSetupFinishes() {
        final AuditorSetupTrigger trigger = new AuditorSetupTrigger();
        assertEquals(WATCH, trigger.start(NEW, false));
        assertFalse(trigger.onSetupChanged(false));
        assertEquals(NEW, trigger.state());
        assertTrue(trigger.onSetupChanged(true));
        assertEquals(OFFERED, trigger.state());
        assertFalse(trigger.onSetupChanged(true));
        assertFalse(trigger.onSetupChanged(false));
        assertFalse(trigger.onSetupChanged(true));
    }

    @Test
    public void offersAgainAfterRestartsUntilTheUserIsDone() {
        final AuditorSetupTrigger trigger = new AuditorSetupTrigger();
        assertEquals(POST, trigger.start(OFFERED, true));
        assertFalse(trigger.onSetupChanged(true));
        assertTrue(trigger.onUserDone());
        assertEquals(DONE, trigger.state());
        assertFalse(trigger.onUserDone());
    }

    @Test
    public void doneIsFinal() {
        final AuditorSetupTrigger trigger = new AuditorSetupTrigger();
        assertEquals(NOTHING, trigger.start(DONE, true));
        assertEquals(NOTHING, trigger.start(DONE, false));
        assertFalse(trigger.onSetupChanged(true));
        assertFalse(trigger.onUserDone());
        assertEquals(DONE, trigger.state());
    }

    @Test
    public void userDoneAfterOfferInTheSameRun() {
        final AuditorSetupTrigger trigger = new AuditorSetupTrigger();
        trigger.start(NEW, false);
        assertFalse(trigger.onUserDone());
        trigger.onSetupChanged(true);
        assertTrue(trigger.onUserDone());
        assertEquals(DONE, trigger.state());
    }

    @Test
    public void onlyAuditorsSuccessNoteCountsAsPaired() {
        assertTrue(AuditorSetupTrigger.isRemoteVerified("de.diamaneos.auditor",
                "remote_verification"));
        assertFalse(AuditorSetupTrigger.isRemoteVerified("de.diamaneos.auditor",
                "remote_verification_failure_2"));
        assertFalse(AuditorSetupTrigger.isRemoteVerified("app.attestation.auditor",
                "remote_verification"));
        assertFalse(AuditorSetupTrigger.isRemoteVerified("de.diamaneos.auditor", null));
    }

    @Test
    public void nothingBeforeStart() {
        final AuditorSetupTrigger trigger = new AuditorSetupTrigger();
        assertFalse(trigger.onSetupChanged(true));
        assertFalse(trigger.onUserDone());
    }
}
