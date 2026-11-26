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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The decode client's calls to the service while they run on a call thread: each call ends exactly once, and a call
 * whose caller gave up first (its deadline passed, or the caller was interrupted) is stuck until it ends. Stuck calls
 * are counted per binding generation and owner: the counts of an older binding are dropped when a newer one has one.
 * A success whose waiter gives up is delivered by one late notice, including a completion whose future was then
 * cancelled. State, notification delivery and counts use one lock in either completion order. Plain Java, tested on the host.
 */
final class StuckCalls {
    private static final int RUNNING = 0;
    private static final int ENDED = 1;
    private static final int GIVEN_UP = 2;

    // Tests: run right after a call is marked given up, before it is counted (a thread switch there).
    static volatile Runnable sGivenUpForTest;

    private int mGeneration = -1; // guarded by this
    private int mStuck; // guarded by this
    private final Map<String, Integer> mOwners = new HashMap<>(); // guarded by this

    /** One call on a call thread. */
    final class Call {
        final int generation;
        final String owner;
        private int mState = RUNNING; // guarded by StuckCalls.this
        private boolean mCounted; // guarded by StuckCalls.this
        private boolean mAbandoned;
        private boolean mAnswered;
        private boolean mLateDelivered;

        private Call(int generation, String owner) {
            this.generation = generation;
            this.owner = owner == null ? "" : owner;
        }
    }

    /** A call of binding {@code generation} for {@code owner}, about to run. */
    Call begin(int generation, String owner) {
        return new Call(generation, owner);
    }

    /**
     * The caller gave up while {@code call} runs. Returns the stuck calls of its binding counting this one, or -1
     * when the call ended first (nothing counted, its result went to nobody). A call of a binding older than the
     * newest with a stuck call is marked given up but not counted.
     */
    synchronized int giveUp(Call call) {
        call.mAbandoned = true;
        if (call.mState != RUNNING) {
            return call.mState == ENDED ? -1 : mStuck;
        }
        call.mState = GIVEN_UP;
        final Runnable hook = sGivenUpForTest;
        if (hook != null) {
            hook.run();
        }
        if (call.generation < mGeneration) {
            return 0;
        }
        if (call.generation > mGeneration) {
            mGeneration = call.generation;
            mStuck = 0;
            mOwners.clear();
        }
        call.mCounted = true;
        mOwners.merge(call.owner, 1, Integer::sum);
        return ++mStuck;
    }

    /**
     * {@code call} ended on its call thread ({@code answered}: with an answer of the service). True when its caller
     * had given up and it was answered: a late answer, the service keeps it for the next ask.
     */
    synchronized boolean end(Call call, boolean answered) {
        if (call.mState == ENDED) {
            return takeLateAnswer(call);
        }
        call.mAnswered = answered;
        call.mState = ENDED;
        if (call.mCounted && call.generation == mGeneration) {
            mStuck--;
            final int left = mOwners.getOrDefault(call.owner, 1) - 1;
            if (left <= 0) {
                mOwners.remove(call.owner);
            } else {
                mOwners.put(call.owner, left);
            }
        }
        call.mCounted = false;
        return takeLateAnswer(call);
    }

    /** Claims a completed success lost by its waiter, including completion before cancellation. */
    synchronized boolean takeLateAnswer(Call call) {
        if (call.mState != ENDED || !call.mAbandoned || !call.mAnswered || call.mLateDelivered) {
            return false;
        }
        call.mLateDelivered = true;
        return true;
    }

    /** Whether a call of {@code owner} is stuck in the newest binding with stuck calls. */
    synchronized boolean hasStuck(String owner) {
        return mOwners.getOrDefault(owner == null ? "" : owner, 0) > 0;
    }

    /** The owners of the stuck calls of binding {@code generation}, empty for any other binding. */
    synchronized List<String> owners(int generation) {
        return generation == mGeneration ? new ArrayList<>(mOwners.keySet()) : new ArrayList<>();
    }

    /** Stuck calls of the newest binding with stuck calls, for tests. */
    synchronized int stuck() {
        return mStuck;
    }
}
