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

/**
 * When the recovery listeners run for answers that came too late for their callers and for "no room now" answers
 * (IDecodeService.STATUS_NO_ROOM): once none came for {@link #QUIET_MS}, or at the latest {@link #MAX_DELAY_MS} after
 * the first, and at most once per {@link #GAP_MS}. So a burst of them asks once for what was left out, after the
 * burst, events that never stop cannot hold it back for long, and refusals that come again can never ask more often
 * than that. Plain Java with the clock passed in, tested on the host. Thread-safe.
 */
final class LateRuns {
    /** A run waits until no event came for this long. */
    static final long QUIET_MS = 300;
    /** A run waits at most this long after the first event it settles, however many follow. */
    static final long MAX_DELAY_MS = 1_000;
    /** Runs are at least this far apart. */
    static final long GAP_MS = 2_000;

    /** From {@link #due}: run now. */
    static final long RUN = 0;
    /** From {@link #due}: run now, and a "no room now" answer is among what it settles. */
    static final long RUN_NO_ROOM = -1;

    private long mFirstAt;
    private long mLastAt;
    private long mRanAt = -GAP_MS;
    private boolean mWaiting;
    private boolean mNoRoom;

    /**
     * An event at {@code nowMs}: a late answer, or a "no room now" answer when {@code noRoom}. True when no run is
     * waiting yet: the caller starts the wait for one ({@link #due}).
     */
    synchronized boolean event(long nowMs, boolean noRoom) {
        mLastAt = nowMs;
        mNoRoom |= noRoom;
        if (mWaiting) {
            return false;
        }
        mWaiting = true;
        mFirstAt = nowMs;
        return true;
    }

    /** The wait could not be started: the next event starts it. */
    synchronized void notWaiting() {
        mWaiting = false;
    }

    /**
     * For the waiting run at {@code nowMs}: how many milliseconds it still waits, or {@link #RUN} or
     * {@link #RUN_NO_ROOM} when it runs now. Then the wait is over and the next event starts a new one.
     */
    synchronized long due(long nowMs) {
        final long wait = Math.max(Math.min(mLastAt + QUIET_MS, mFirstAt + MAX_DELAY_MS), mRanAt + GAP_MS) - nowMs;
        if (wait > 0) {
            return wait;
        }
        mRanAt = nowMs;
        mWaiting = false;
        final boolean noRoom = mNoRoom;
        mNoRoom = false;
        return noRoom ? RUN_NO_ROOM : RUN;
    }
}
