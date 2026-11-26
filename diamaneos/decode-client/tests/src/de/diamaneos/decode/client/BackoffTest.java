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

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Retries of failed work. */
@RunWith(JUnit4.class)
public class BackoffTest {

    @Test
    public void waitsLongerAfterEachFailure() {
        final Backoff backoff = new Backoff(2_000, 60_000);
        assertTrue(backoff.start("a", 0));
        // Running: not started twice.
        assertFalse(backoff.start("a", 0));
        // The wait is returned, so the caller can schedule the next try.
        assertEquals(2_000, backoff.failed("a", 0));
        assertFalse(backoff.mayTry("a", 1_999));
        assertTrue(backoff.start("a", 2_000));
        assertEquals(4_000, backoff.failed("a", 2_000));
        assertFalse(backoff.mayTry("a", 5_999));
        assertTrue(backoff.mayTry("a", 6_000));
        // Never longer than the most.
        for (int i = 0; i < 20; i++) {
            backoff.failed("a", 10_000);
        }
        assertFalse(backoff.mayTry("a", 69_999));
        assertTrue(backoff.mayTry("a", 70_000));
        // Others are not held up.
        assertTrue(backoff.mayTry("b", 10_000));
    }

    @Test
    public void waitLeftCountsDownTheWait() {
        final Backoff backoff = new Backoff(2_000, 60_000);
        assertEquals(0, backoff.waitLeft("a", 0));
        assertTrue(backoff.start("a", 0));
        assertEquals("running, no wait to count", 0, backoff.waitLeft("a", 0));
        backoff.failed("a", 1_000);
        assertEquals(2_000, backoff.waitLeft("a", 1_000));
        assertEquals(500, backoff.waitLeft("a", 2_500));
        assertEquals(0, backoff.waitLeft("a", 3_000));
        backoff.succeeded("a");
        assertEquals(0, backoff.waitLeft("a", 1_000));
    }

    @Test
    public void successAndResetForget() {
        final Backoff backoff = new Backoff(2_000, 60_000);
        backoff.failed("a", 0);
        backoff.failed("b", 0);
        backoff.succeeded("a");
        assertTrue(backoff.mayTry("a", 0));
        assertFalse(backoff.mayTry("b", 0));
        backoff.reset();
        assertTrue(backoff.mayTry("b", 0));
    }
}
