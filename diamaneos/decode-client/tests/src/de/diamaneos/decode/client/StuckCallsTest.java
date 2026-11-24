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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** The decode client's stuck calls: a give-up racing the call's end, in either order. */
@RunWith(JUnit4.class)
public class StuckCallsTest {

    @Test
    public void anEndBeforeTheGiveUpCountsNothing() {
        final StuckCalls calls = new StuckCalls();
        final StuckCalls.Call call = calls.begin(1, "app");
        assertFalse("answered in time: no late answer", calls.end(call, true));
        assertEquals("ended first: nothing counted", -1, calls.giveUp(call));
        assertEquals(0, calls.stuck());
        assertFalse(calls.hasStuck("app"));
    }

    @Test
    public void completedSuccessDiscardedByTheFutureIsDeliveredOnce() {
        final StuckCalls calls = new StuckCalls();
        final StuckCalls.Call call = calls.begin(1, "app");
        assertFalse(calls.end(call, true));
        // The worker ended before FutureTask published, then the waiter timed out and cancelled.
        assertEquals(-1, calls.giveUp(call));
        assertTrue("the discarded success still needs its late notice", calls.end(call, true));
        assertFalse("the notice is delivered once", calls.end(call, true));
        assertEquals(0, calls.stuck());
    }

    @Test
    public void aGiveUpBeforeTheEndCountsUntilTheEnd() {
        final StuckCalls calls = new StuckCalls();
        final StuckCalls.Call call = calls.begin(1, "app");
        assertEquals(1, calls.giveUp(call));
        assertTrue(calls.hasStuck("app"));
        assertTrue("answered after the caller gave up: a late answer", calls.end(call, true));
        assertEquals(0, calls.stuck());
        assertFalse(calls.hasStuck("app"));
    }

    @Test
    public void aFailedCallAfterTheGiveUpIsNoLateAnswer() {
        final StuckCalls calls = new StuckCalls();
        final StuckCalls.Call call = calls.begin(1, "app");
        calls.giveUp(call);
        assertFalse(calls.end(call, false));
        assertEquals(0, calls.stuck());
    }

    @Test
    public void anEndBetweenTheGiveUpAndItsCountLeavesNothingCounted() throws Exception {
        // The call's thread ends right after its caller marked it given up, before the give-up counted it: the end
        // must not miss the count, else it stays for good and turns the owner away.
        final StuckCalls calls = new StuckCalls();
        final StuckCalls.Call call = calls.begin(1, "app");
        final AtomicBoolean wasLate = new AtomicBoolean();
        final Thread[] worker = new Thread[1];
        StuckCalls.sGivenUpForTest = () -> {
            worker[0] = new Thread(() -> wasLate.set(calls.end(call, true)));
            worker[0].start();
            try {
                worker[0].join(300); // it may not get in before the count
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        };
        try {
            calls.giveUp(call);
        } finally {
            StuckCalls.sGivenUpForTest = null;
        }
        worker[0].join(10_000);
        assertTrue("answered after the give-up: a late answer", wasLate.get());
        assertEquals("nothing left counted", 0, calls.stuck());
        assertFalse(calls.hasStuck("app"));
    }

    @Test
    public void raceOfGiveUpAndEndLeavesTheCountsRight() throws Exception {
        // The caller's give-up (timeout or interrupt) and the call's end on its thread, released together many
        // times. Every call ends: nothing may stay counted, and a late answer is told exactly when the give-up won.
        final StuckCalls calls = new StuckCalls();
        int late = 0;
        int counted = 0;
        for (int round = 0; round < 5_000; round++) {
            final StuckCalls.Call call = calls.begin(1, "app" + (round % 3));
            final CyclicBarrier together = new CyclicBarrier(2);
            final AtomicBoolean wasLate = new AtomicBoolean();
            final Thread worker = new Thread(() -> {
                try {
                    together.await();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                wasLate.set(calls.end(call, true));
            });
            worker.start();
            together.await();
            final int stuck = calls.giveUp(call);
            worker.join(10_000);
            if (stuck > 0) {
                counted++;
                assertTrue("given up first: its answer is late (round " + round + ")", wasLate.get());
                late++;
            } else {
                assertFalse("ended first: no late answer (round " + round + ")", wasLate.get());
            }
            assertEquals("stuck after round " + round, 0, calls.stuck());
        }
        assertEquals(counted, late);
        for (int i = 0; i < 3; i++) {
            assertFalse(calls.hasStuck("app" + i));
        }
    }

    @Test
    public void aNewerBindingDropsTheOldCounts() {
        final StuckCalls calls = new StuckCalls();
        final StuckCalls.Call old = calls.begin(1, "app");
        assertEquals(1, calls.giveUp(old));
        final StuckCalls.Call fresh = calls.begin(2, "other");
        assertEquals("the newer binding counts afresh", 1, calls.giveUp(fresh));
        assertFalse(calls.hasStuck("app"));
        assertEquals("the old call ending touches nothing of the new binding", true, calls.end(old, true));
        assertEquals(1, calls.stuck());
        assertTrue(calls.hasStuck("other"));
        final StuckCalls.Call older = calls.begin(1, "app");
        assertEquals("a call of the old binding given up now is not counted", 0, calls.giveUp(older));
        assertEquals(1, calls.stuck());
        calls.end(older, false);
        calls.end(fresh, false);
        assertEquals(0, calls.stuck());
        assertEquals(0, calls.owners(2).size());
    }
}
