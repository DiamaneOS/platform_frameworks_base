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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Plain JUnit: {@link AuditorSetupTrigger} uses no Android classes. */
public class AuditorSetupTriggerTest {
    @Test
    public void bootWithSetupDoneNeverOffers() {
        final AuditorSetupTrigger trigger = new AuditorSetupTrigger();
        assertFalse(trigger.start(true));
        assertFalse(trigger.onSetupChanged(true));
    }

    @Test
    public void offersOnceWhenSetupFinishes() {
        final AuditorSetupTrigger trigger = new AuditorSetupTrigger();
        assertTrue(trigger.start(false));
        assertFalse(trigger.onSetupChanged(false));
        assertTrue(trigger.onSetupChanged(true));
        assertFalse(trigger.onSetupChanged(true));
        assertFalse(trigger.onSetupChanged(false));
        assertFalse(trigger.onSetupChanged(true));
    }

    @Test
    public void nothingBeforeStart() {
        assertFalse(new AuditorSetupTrigger().onSetupChanged(true));
    }
}
