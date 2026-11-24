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

import java.util.Collections;

/** How often what was shown with images left out is asked for again. */
@RunWith(JUnit4.class)
public class RetryTurnsTest {

    @Test
    public void turnsAreBounded_whileNothingChanges() {
        final RetryTurns<String> turns = new RetryTurns<>(3);
        turns.leftOut("row", 0);
        for (int i = 0; i < 3; i++) {
            assertEquals(Collections.singletonList("row"), turns.due(0));
            turns.leftOut("row", 0); // left out again by its rebind: the count stays
        }
        assertTrue(turns.due(0).isEmpty());
        assertTrue(turns.due(0).isEmpty());
        assertEquals(3, turns.turns("row"));
    }

    @Test
    public void aGenuineChange_givesEveryWaitingOneItsTurnsAgain() {
        final RetryTurns<String> turns = new RetryTurns<>(3);
        turns.leftOut("row", 0);
        for (int i = 0; i < 5; i++) {
            turns.due(0);
            turns.leftOut("row", 0);
        }
        // An outage ended (or the service had room again): the row still waiting is asked again, three more times.
        for (int i = 0; i < 3; i++) {
            assertEquals(Collections.singletonList("row"), turns.due(1));
            turns.leftOut("row", 1);
        }
        assertTrue(turns.due(1).isEmpty());
    }

    @Test
    public void whatGotItsImagesWaitsNoLonger() {
        final RetryTurns<String> turns = new RetryTurns<>(3);
        turns.leftOut("row", 0);
        turns.due(0);
        turns.done("row");
        assertEquals(-1, turns.turns("row"));
        assertTrue(turns.due(1).isEmpty());
        // Left out again later: a fresh count.
        turns.leftOut("row", 1);
        assertEquals(Collections.singletonList("row"), turns.due(1));
    }
    @Test
    public void unrelatedCapacityNeverSpendsOrResetsALanesTurns() {
        final RetryTurns<String> turns = new RetryTurns<>(3);
        final java.util.concurrent.atomic.AtomicInteger a = new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.atomic.AtomicInteger b = new java.util.concurrent.atomic.AtomicInteger();
        turns.leftOut("large", 0, a::get);
        turns.leftOut("small", 0, b::get);
        for (int i = 0; i < 20; i++) {
            b.incrementAndGet();
            assertEquals(Collections.singletonList("small"), turns.due(0));
        }
        assertEquals(0, turns.turns("large"));
        a.incrementAndGet();
        assertEquals(Collections.singletonList("large"), turns.due(0));
        assertTrue(turns.due(0).isEmpty());
        assertTrue("a health recovery is not capacity evidence", turns.due(1).isEmpty());
    }
    @Test
    public void aBindingChangeUsesTheCapacitySnapshotInsteadOfHealthRecovery() {
        final RetryTurns<String> turns = new RetryTurns<>(3);
        final java.util.concurrent.atomic.AtomicInteger binding = new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.atomic.AtomicInteger lane = new java.util.concurrent.atomic.AtomicInteger();
        CapacityLanes lanes = new CapacityLanes();
        CapacityLanes.Lane failed = lanes.refused("blocked", 800, 0);
        turns.leftOut("blocked", 0, CapacityLanes.snapshot(Collections.singletonList(failed), binding::get));
        assertTrue(turns.due(1).isEmpty());
        binding.incrementAndGet();
        assertEquals(Collections.singletonList("blocked"), turns.due(1));
        assertTrue(turns.due(2).isEmpty());
        lanes.completed("blocked", 800);
        assertEquals(Collections.singletonList("blocked"), turns.due(2));
    }
}
