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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.List;

/** The clean copy maker's queue: hard caps on every lane and on the files to try again, one drain per lane. */
@RunWith(JUnit4.class)
public class MakeQueueTest {
    @Test
    public void manyFiles_oneDrainPerLane_andTheCapHolds() {
        final MakeQueue<String> queue = new MakeQueue<>(8);
        int drains = 0;
        for (int i = 0; i < 1000; i++) {
            if (queue.add("u" + i, "u" + i, true)) {
                drains++;
            }
            if (queue.add("b" + i, "b" + i, false)) {
                drains++;
            }
        }
        assertEquals("one drain scheduled for each lane", 2, drains);
        assertEquals(8, queue.size(true));
        assertEquals(8, queue.size(false));
        assertEquals("the newest stay", "u992", queue.next(true));
    }

    @Test
    public void drain_endsWhenItsLaneIsEmpty_thenANewOne() {
        final MakeQueue<String> queue = new MakeQueue<>(8);
        assertTrue(queue.add("a", "a", true));
        assertFalse(queue.add("b", "b", true));
        assertEquals("a", queue.next(true));
        assertEquals("b", queue.next(true));
        assertFalse("the drain still runs", queue.add("c", "c", true));
        assertEquals("c", queue.next(true));
        assertNull(queue.next(true));
        assertTrue("ended: the next file schedules a drain", queue.add("d", "d", true));
    }

    @Test
    public void urgent_takesAFileOutOfTheBackground_once() {
        final MakeQueue<String> queue = new MakeQueue<>(8);
        queue.add("k", "background", false);
        queue.add("k", "urgent", true);
        assertEquals(0, queue.size(false));
        assertEquals(1, queue.size(true));
        assertFalse(queue.add("k", "again", false));
        assertEquals("urgent", queue.next(true));
        assertFalse(queue.anyQueued(s -> true));
    }

    @Test
    public void retries_capped_oldestGivenUp_oneTimer() {
        final MakeQueue<String> queue = new MakeQueue<>(8);
        String evicted = null;
        int timers = 0;
        for (int i = 0; i < 100; i++) {
            final String out = queue.retry("r" + i, "r" + i, 1000 + i);
            if (out != null) {
                evicted = out;
            }
            if (queue.needsRetryTimer()) {
                timers++;
            }
        }
        assertEquals(8, queue.retries());
        assertEquals("the oldest went", "r91", evicted);
        assertEquals("one timer", 1, timers);
        assertNull("one entry per key", queue.retry("r99", "r99 again", 2000));
        assertEquals(8, queue.retries());
        final List<String> due = queue.due(1095);
        assertEquals(List.of("r92", "r93", "r94", "r95"), due);
        assertTrue("files left: the timer again", queue.needsRetryTimer());
        assertEquals(4, queue.takeRetries().size());
        assertEquals(0, queue.retries());
    }

    @Test
    public void retrying_isFoundAndDropped() {
        final MakeQueue<String> queue = new MakeQueue<>(8);
        queue.retry("k", "v", 5);
        assertEquals("v", queue.retrying("k"));
        queue.dropRetry("k");
        assertNull(queue.retrying("k"));
        assertEquals(0, queue.due(10).size());
    }
    @Test
    public void presentFile_requestsWhileVerificationRuns_areCoalesced() {
        final MakeQueue<String> queue = new MakeQueue<>(8);
        queue.add("present", "verify", false);
        assertEquals("verify", queue.next(false));
        for (int i = 0; i < 10000; i++) {
            queue.add("present", "verify again", false);
        }
        assertEquals("verification already runs", 0, queue.size(false));
    }

    @Test
    public void dueTimer_keepsARetryUntilTheRunningMakeReleasesIt() {
        final MakeQueue<String> queue = runningWithRetry();
        assertTrue(queue.due(10).isEmpty());
        assertEquals("retry", queue.retrying("k"));
        queue.finished("k");
        assertEquals(List.of("retry"), queue.due(10));
        assertTrue(queue.due(10).isEmpty());
    }

    @Test
    public void serviceRecovery_keepsARetryUntilTheRunningMakeReleasesIt() {
        final MakeQueue<String> queue = runningWithRetry();
        assertTrue(queue.takeRetries().isEmpty());
        assertEquals("retry", queue.retrying("k"));
        queue.finished("k");
        assertEquals(List.of("retry"), queue.takeRetries());
        assertTrue(queue.takeRetries().isEmpty());
    }

    @Test
    public void urgentPromotion_keepsARetryUntilTheRunningMakeReleasesIt() {
        final MakeQueue<String> queue = runningWithRetry();
        assertNull(queue.promoteRetry("k"));
        assertEquals("retry", queue.retrying("k"));
        queue.finished("k");
        assertEquals("retry", queue.promoteRetry("k"));
        assertNull(queue.promoteRetry("k"));
        queue.add("k", "urgent retry", true);
        assertEquals("urgent retry", queue.next(true));
        assertNull(queue.next(true));
    }

    private static MakeQueue<String> runningWithRetry() {
        final MakeQueue<String> queue = new MakeQueue<>(8);
        queue.add("k", "running", true);
        assertEquals("running", queue.next(true));
        queue.retry("k", "retry", 5);
        return queue;
    }

}
