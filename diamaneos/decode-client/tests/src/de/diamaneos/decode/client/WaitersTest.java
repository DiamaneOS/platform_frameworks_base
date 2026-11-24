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

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** The askers waiting for images: the bound on images after an overflow, and cancels of handed-out callbacks. */
@RunWith(JUnit4.class)
public class WaitersTest {

    private static final Runnable NOTHING = () -> { };

    @Test
    public void theImageBoundHoldsWhenTheOldestImageIsShared() {
        final Waiters waiters = new Waiters();
        // The busiest app's oldest image is one another app waits for too.
        waiters.add("shared", "busy", null, () -> { });
        waiters.add("shared", "other", null, () -> { });
        for (int i = 1; i < Waiters.MAX_KEYS_PER_OWNER; i++) {
            waiters.add("busy" + i, "busy", null, () -> { });
        }
        int keys = Waiters.MAX_KEYS_PER_OWNER;
        for (int owner = 0; keys < Waiters.MAX_KEYS; owner++) {
            for (int i = 0; i < Waiters.MAX_KEYS_PER_OWNER - 1 && keys < Waiters.MAX_KEYS; i++, keys++) {
                waiters.add("o" + owner + "k" + i, "o" + owner, null, () -> { });
            }
        }
        assertEquals(Waiters.MAX_KEYS, waiters.keys());
        final List<Waiters.Waiter> evicted = waiters.add("one more", "late", null, () -> { });
        assertTrue("images waited for: " + waiters.keys(), waiters.keys() <= Waiters.MAX_KEYS);
        assertTrue("the askers let go are handed back", evicted.size() >= 2);
        for (int round = 0; round < 50; round++) {
            waiters.add("more" + round, "late" + round, null, () -> { });
            assertTrue("images waited for: " + waiters.keys(), waiters.keys() <= Waiters.MAX_KEYS);
        }
    }

    @Test
    public void aCancelReachesCallbacksHandedOutLongBefore() {
        final Waiters waiters = new Waiters();
        final AtomicInteger ran = new AtomicInteger();
        waiters.add("image", "app", "notification", ran::incrementAndGet);
        final List<Waiters.Waiter> handedOut = waiters.take("image");
        assertEquals(1, handedOut.size());
        waiters.cancel("notification");
        // Many other notifications go before the handed-out callback is due.
        for (int i = 0; i < 2_000; i++) {
            waiters.add("image" + i, "app" + (i % 7), "gone" + i, NOTHING);
            waiters.cancel("gone" + i);
        }
        assertFalse("cancelled before it was due", waiters.live(handedOut.get(0)));
        waiters.tell(handedOut);
        assertEquals("a cancelled callback never runs", 0, ran.get());
    }

    @Test
    public void anAskerAddedAfterACancelIsLive() {
        final Waiters waiters = new Waiters();
        waiters.add("image", "app", "notification", NOTHING);
        final List<Waiters.Waiter> before = waiters.take("image");
        waiters.cancel("notification");
        waiters.add("image", "app", "notification", NOTHING);
        final List<Waiters.Waiter> after = waiters.take("image");
        assertFalse(waiters.live(before.get(0)));
        assertTrue("posted again after its cancel", waiters.live(after.get(0)));
    }
}
