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

package de.diamaneos.decode.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** The waits of the recovery watch between its probes. */
@RunWith(JUnit4.class)
public class RecoveryWaitsTest {

    @Test
    public void waitsGrowToTheMaximum() {
        long wait = RecoveryWaits.FIRST_MS;
        for (int tries = 1; tries < RecoveryWaits.TRIES; tries++) {
            final long next = RecoveryWaits.next(tries, wait, true);
            assertEquals(Math.min(2 * wait, RecoveryWaits.MAX_MS), next);
            wait = next;
        }
        assertEquals(RecoveryWaits.MAX_MS, wait);
    }

    @Test
    public void aServiceStillDown_isWatchedOnAfterTheTries_slowly() {
        // A whole day of probes while the service stays down: the watch never ends, at most one probe per SLOW_MS
        // after the first tries.
        long wait = RecoveryWaits.FIRST_MS;
        long elapsed = 0;
        int tries = 0;
        while (elapsed < 24L * 3600 * 1000) {
            tries++;
            wait = RecoveryWaits.next(tries, wait, true);
            assertTrue("the watch ended after " + tries + " probes", wait != RecoveryWaits.END);
            elapsed += wait;
        }
        assertEquals(RecoveryWaits.SLOW_MS, wait);
    }

    @Test
    public void aServiceWithoutRoom_isNotWatchedForever() {
        assertEquals(RecoveryWaits.END, RecoveryWaits.next(RecoveryWaits.TRIES, RecoveryWaits.MAX_MS, false));
    }
}
