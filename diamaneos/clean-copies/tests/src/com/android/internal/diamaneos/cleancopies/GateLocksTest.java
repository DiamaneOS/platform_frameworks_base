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


package com.android.internal.diamaneos.cleancopies;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;

/** The system server's global locks: a required lock that is missing is never given up, and waits stay off. */
public class GateLocksTest {

    @Test
    public void aLockMadeLateIsFoundAndHeld() {
        final GateLocks locks = new GateLocks();
        final AtomicReference<Object> telecom = new AtomicReference<>();
        locks.require("Telecom's lock", telecom::get);
        assertFalse("missing: no system server thread waits", locks.complete());
        // Telecom starts only minutes after boot.
        for (long t = 0; t <= 10 * 60_000; t += 1_000) {
            locks.find(t);
        }
        assertFalse("still missing after ten minutes: never given up", locks.complete());
        assertEquals(1, locks.missing().size());
        final Object lock = new Object();
        telecom.set(lock);
        locks.find(10 * 60_000 + 1_000);
        assertTrue(locks.complete());
        synchronized (lock) {
            assertTrue("its holder is known", locks.heldHere());
        }
        assertFalse(locks.heldHere());
    }

    @Test
    public void lookedForAtMostOnceASecond() {
        final GateLocks locks = new GateLocks();
        final int[] asked = new int[1];
        locks.require("a lock", () -> {
            asked[0]++;
            return null;
        });
        final int atStart = asked[0];
        for (long t = 0; t < 1_000; t += 10) {
            locks.find(5_000 + t);
        }
        assertEquals(1, asked[0] - atStart);
    }

    @Test
    public void aLockKnownAtOnceIsRegistered() {
        final GateLocks locks = new GateLocks();
        final Object am = new Object();
        locks.require("the activity manager's lock", () -> am);
        assertTrue(locks.complete());
        synchronized (am) {
            assertTrue(locks.heldHere());
        }
    }

    @Test
    public void aSourceThatThrowsIsAskedAgain() {
        final GateLocks locks = new GateLocks();
        final Object pm = new Object();
        final boolean[] ready = new boolean[1];
        locks.require("the package manager's lock", () -> {
            if (!ready[0]) {
                throw new IllegalStateException("not yet");
            }
            return pm;
        });
        assertFalse(locks.complete());
        ready[0] = true;
        locks.find(2_000);
        assertTrue(locks.complete());
    }
}
