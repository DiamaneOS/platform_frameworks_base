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

/** When the recovery listeners run after late and "no room now" answers. */
@RunWith(JUnit4.class)
public class LateRunsTest {

    // The waiting thread: sleeps as told until the run is due. Returns {when it ran, what due answered}.
    private static long[] waitForRun(LateRuns runs, long fromMs) {
        long now = fromMs;
        long due;
        while ((due = runs.due(now)) > 0) {
            now += due;
        }
        return new long[] {now, due};
    }

    @Test
    public void aBurstOfRefusalsRunsOnceAfterIt() {
        final LateRuns runs = new LateRuns();
        assertTrue("the first event starts the wait", runs.event(10_000, true));
        for (long t = 10_100; t <= 10_600; t += 100) {
            assertFalse("a wait runs already", runs.event(t, true));
        }
        final long[] run = waitForRun(runs, 10_000);
        assertEquals("quiet after the last refusal", 10_600 + LateRuns.QUIET_MS, run[0]);
        assertEquals(LateRuns.RUN_NO_ROOM, run[1]);
    }

    @Test
    public void eventsThatNeverStop_runAtTheLatestTheMaximumDelayAfterTheFirst() {
        // Late answers or refusals of other images every 200 ms, never quiet for 300 ms.
        final LateRuns runs = new LateRuns();
        assertTrue(runs.event(0, false));
        long ranAt = -1;
        for (long t = 100; t <= 10_000 && ranAt < 0; t += 100) {
            if (t % 200 == 0) {
                runs.event(t, true);
            }
            if (runs.due(t) <= 0) {
                ranAt = t;
            }
        }
        assertTrue("ran at " + ranAt, ranAt >= 0 && ranAt <= LateRuns.MAX_DELAY_MS);
        // The next run waits for the gap, then again at most the maximum delay after its first event.
        assertTrue(runs.event(ranAt + 100, true));
        long next = -1;
        for (long t = ranAt + 200; t <= ranAt + 10_000 && next < 0; t += 100) {
            if (t % 200 == 0) {
                runs.event(t, true);
            }
            if (runs.due(t) <= 0) {
                next = t;
            }
        }
        assertEquals(ranAt + LateRuns.GAP_MS, next);
    }

    @Test
    public void runsAreApartByTheGap() {
        final LateRuns runs = new LateRuns();
        assertTrue(runs.event(5_000, true));
        final long first = waitForRun(runs, 5_000)[0];
        assertEquals(5_000 + LateRuns.QUIET_MS, first);
        // Refused again at once by the retry: the next run waits for the gap, not only the quiet time.
        assertTrue("a new wait after the run", runs.event(first + 50, true));
        assertEquals(first + LateRuns.GAP_MS, waitForRun(runs, first + 50)[0]);
    }

    @Test
    public void lateAnswersAloneTellNoRefusal() {
        final LateRuns runs = new LateRuns();
        assertTrue(runs.event(1_000, false));
        assertEquals(LateRuns.RUN, waitForRun(runs, 1_000)[1]);
        assertTrue(runs.event(4_000, false));
        assertFalse(runs.event(4_100, true));
        assertEquals(LateRuns.RUN_NO_ROOM, waitForRun(runs, 4_000)[1]);
        // The refusal was settled by that run.
        assertTrue(runs.event(8_000, false));
        assertEquals(LateRuns.RUN, waitForRun(runs, 8_000)[1]);
    }

    @Test
    public void refusalsThatComeBackAtEveryRunCannotStorm() {
        // Every run asks again for what was left out and the service refuses it again 100 ms later, for a minute.
        final LateRuns runs = new LateRuns();
        long now = 0;
        int count = 0;
        assertTrue(runs.event(now, true));
        while (now < 60_000) {
            now = waitForRun(runs, now)[0];
            count++;
            now += 100;
            assertTrue(runs.event(now, true));
        }
        assertTrue("runs " + count + " in a minute", count <= 60_000 / LateRuns.GAP_MS + 1);
    }

    @Test
    public void aWaitThatCouldNotStartIsStartedByTheNextEvent() {
        final LateRuns runs = new LateRuns();
        assertTrue(runs.event(1_000, true));
        runs.notWaiting();
        assertTrue(runs.event(1_200, true));
        assertEquals(1_200 + LateRuns.QUIET_MS, waitForRun(runs, 1_200)[0]);
    }
}
