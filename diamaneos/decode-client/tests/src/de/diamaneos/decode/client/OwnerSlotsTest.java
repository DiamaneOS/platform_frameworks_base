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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class OwnerSlotsTest {
    // Three threads for apps, as in the system server. "" is the system lane, "?x" an unknown owner.
    private static OwnerSlots slots() {
        return new OwnerSlots(3, 2, 4, String::isEmpty, lane -> lane.startsWith("?"), OwnerSlotsTest::now);
    }

    private static long now() {
        return System.nanoTime() / 1_000_000;
    }

    private static String claimNow(OwnerSlots slots, String lane) {
        return slots.claim(lane, () -> false, now());
    }

    // Claims on another thread, waiting up to waitMs.
    private static CompletableFuture<String> claimLater(OwnerSlots slots, String lane, long waitMs) {
        final long end = now() + waitMs;
        return CompletableFuture.supplyAsync(() -> slots.claim(lane, () -> false, end));
    }

    @Test
    public void anOwnerWithNothingInFlight_alwaysFindsAThread() {
        final OwnerSlots slots = slots();
        assertNull(claimNow(slots, "a"));
        assertNull(claimNow(slots, "a"));
        assertNull("the last thread is for a newcomer", claimNow(slots, "b"));
        assertNull("the system lane never counts against apps", claimNow(slots, ""));
    }

    @Test
    public void theLastThread_isKeptForOthers_whenNoneEnds() {
        final OwnerSlots slots = slots();
        assertNull(claimNow(slots, "a"));
        assertNull(claimNow(slots, "?gate:b"));
        final long start = now();
        final String why = slots.claim("a", () -> false, now() + 150);
        assertEquals("the last call thread is kept for others than a", why);
        assertTrue("waited for its turn first", now() - start >= 140);
        assertEquals(1, slots.inFlight("a"));
    }

    @Test
    public void anOwnersSecondCall_waitsForItsTurn() throws Exception {
        final OwnerSlots slots = slots();
        assertNull(claimNow(slots, "a"));
        assertNull(claimNow(slots, "?gate:b"));
        final CompletableFuture<String> second = claimLater(slots, "a", 5_000);
        Thread.sleep(100);
        assertTrue("waits while two calls are in flight", !second.isDone());
        slots.release("?gate:b");
        assertNull("taken once a call ended", second.get(2, TimeUnit.SECONDS));
        assertEquals(2, slots.inFlight("a"));
    }

    @Test
    public void anOwnerOverItsCap_waitsForOneOfItsCalls() throws Exception {
        final OwnerSlots slots = slots();
        assertNull(claimNow(slots, "a"));
        assertNull(claimNow(slots, "a"));
        assertEquals("too many decodes in flight for a", claimNow(slots, "a"));
        final CompletableFuture<String> third = claimLater(slots, "a", 5_000);
        Thread.sleep(100);
        assertTrue(!third.isDone());
        slots.release("a");
        assertNull(third.get(2, TimeUnit.SECONDS));
    }

    @Test
    public void anOwnerWithAStuckCall_isTurnedAwayAtOnce() throws Exception {
        final OwnerSlots slots = slots();
        assertNull(claimNow(slots, "a"));
        assertNull(claimNow(slots, "b"));
        final long start = now();
        assertEquals("the last call thread is kept for others than a",
                slots.claim("a", () -> true, now() + 5_000));
        assertTrue("no wait", now() - start < 1_000);
        // One that waits stops waiting once its call is stuck.
        final AtomicBoolean stuck = new AtomicBoolean();
        final long end = now() + 5_000;
        final CompletableFuture<String> waiting = CompletableFuture.supplyAsync(
                () -> slots.claim("a", stuck::get, end));
        Thread.sleep(100);
        assertTrue(!waiting.isDone());
        stuck.set(true);
        slots.wake();
        assertEquals("the last call thread is kept for others than a", waiting.get(2, TimeUnit.SECONDS));
    }
}
