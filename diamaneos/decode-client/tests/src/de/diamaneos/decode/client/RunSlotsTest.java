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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** The slots of VmDecode's open and pump threads: hard limits on living threads, taken before a thread starts. */
@RunWith(JUnit4.class)
public class RunSlotsTest {

    // As VmDecode makes it.
    private static RunSlots slots() {
        return new RunSlots(4, 64, 2, 16);
    }

    @Test
    public void callersAtOnceNeverPassTheOwnersLimit() throws Exception {
        // Twelve callers of one owner reach admission together, before any of their threads is stuck: at most four
        // threads may live at once, however the callers interleave.
        for (int round = 0; round < 200; round++) {
            final RunSlots slots = slots();
            final int callers = 12;
            final CyclicBarrier together = new CyclicBarrier(callers);
            final CountDownLatch admitted = new CountDownLatch(callers);
            final CountDownLatch release = new CountDownLatch(1);
            final AtomicInteger living = new AtomicInteger();
            final AtomicInteger most = new AtomicInteger();
            final List<Thread> threads = new ArrayList<>();
            for (int i = 0; i < callers; i++) {
                final Thread t = new Thread(() -> {
                    try {
                        together.await();
                        final RunSlots.Slot slot = slots.take("app");
                        admitted.countDown();
                        if (slot == null) {
                            return;
                        }
                        most.accumulateAndGet(living.incrementAndGet(), Math::max);
                        release.await();
                        living.decrementAndGet();
                        slot.end();
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
                t.start();
                threads.add(t);
            }
            assertTrue(admitted.await(10, TimeUnit.SECONDS));
            assertEquals("living threads of one owner", 4, slots.living("app"));
            release.countDown();
            for (Thread t : threads) {
                t.join(10_000);
            }
            assertTrue("never more than four at once, was " + most.get(), most.get() <= 4);
            assertEquals(0, slots.living());
        }
    }

    @Test
    public void theProcessLimitHolds() {
        final RunSlots slots = new RunSlots(4, 10, 2, 16);
        int taken = 0;
        for (int owner = 0; owner < 20; owner++) {
            for (int i = 0; i < 2; i++) {
                if (slots.take("app" + owner) != null) {
                    taken++;
                }
            }
        }
        assertEquals("all owners together", 10, taken);
        assertEquals(10, slots.living());
    }

    @Test
    public void aStuckThreadHoldsItsSlotUntilItEnds() {
        final RunSlots slots = slots();
        final RunSlots.Slot a = slots.take("app");
        final RunSlots.Slot b = slots.take("app");
        assertTrue("given up while it runs", a.abandon());
        assertTrue(b.abandon());
        assertEquals(2, slots.stuck());
        assertNull("two stuck: no new thread for the owner", slots.take("app"));
        assertNotNull("another owner still gets one", slots.take("other"));
        a.end();
        assertEquals("ended: no longer stuck", 1, slots.stuck());
        assertNotNull("one stuck: the owner gets a thread again", slots.take("app"));
    }

    @Test
    public void anEndedThreadIsNotCountedWhenItsCallerGivesUpLater() {
        final RunSlots slots = slots();
        final RunSlots.Slot a = slots.take("app");
        a.end();
        assertFalse("ended before the caller gave up: not stuck", a.abandon());
        assertEquals(0, slots.stuck());
        assertEquals(0, slots.living());
        a.end();
        assertEquals("a second end changes nothing", 0, slots.living());
    }

    @Test
    public void stuckOwnersAreTurnedAwayPastTheStuckTotal() {
        final RunSlots slots = new RunSlots(4, 64, 2, 3);
        for (int i = 0; i < 3; i++) {
            assertTrue(slots.take("hung" + i).abandon());
        }
        assertNull("an owner with a stuck thread, past the total", slots.take("hung0"));
        assertNotNull("an owner without one still gets its thread", slots.take("fine"));
    }

    @Test
    public void giveUpAndEndRacingLeaveNothingCounted() throws Exception {
        // The caller's give-up and the thread's end released together, many times: whichever wins, nothing stays.
        final RunSlots slots = slots();
        for (int round = 0; round < 2_000; round++) {
            final RunSlots.Slot slot = slots.take("app");
            final CyclicBarrier together = new CyclicBarrier(2);
            final Thread ender = new Thread(() -> {
                try {
                    together.await();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                slot.end();
            });
            ender.start();
            together.await();
            slot.abandon();
            ender.join(10_000);
            assertEquals("living after round " + round, 0, slots.living());
            assertEquals("stuck after round " + round, 0, slots.stuck());
        }
    }
    @Test
    public void allocationFailureCannotPublishAPhantomSlot() {
        final RunSlots slots = slots();
        final OutOfMemoryError allocation = new OutOfMemoryError("slot construction");
        slots.beforeSlotForTest = () -> { throw allocation; };
        try {
            slots.take("app");
            throw new AssertionError("construction did not fail");
        } catch (OutOfMemoryError expected) { assertTrue(expected == allocation); }
        assertEquals(0, slots.living());
        assertEquals(0, slots.stuck());
        slots.beforeSlotForTest = null;
        for (int n = 0; n < 4; n++) assertNotNull(slots.take("app"));
        assertNull(slots.take("app"));
    }
}
