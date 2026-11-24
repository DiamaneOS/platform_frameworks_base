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

import java.util.HashMap;
import java.util.Map;

/**
 * The threads that open a URI or copy bytes into a pipe for the decode service (VmDecode's opens and pumps). A slot
 * is taken under this object's lock before such a thread starts and held until the thread itself ends, so the limits
 * are on living threads: at most {@code perOwner} of one owner and {@code total} in all. A thread whose caller gave up
 * while it runs (an open or a read that never returns) is stuck: an owner with {@code stuckPerOwner} stuck threads gets
 * no new ones, and once {@code stuckTotal} are stuck in all, only owners without a stuck thread get new ones, so a few
 * apps with hung providers cannot switch URI images off for everyone. Plain Java, tested on the host.
 */
final class RunSlots {
    private final int mPerOwner;
    private final int mTotal;
    private final int mStuckPerOwner;
    private final int mStuckTotal;
    private final Map<String, int[]> mOwners = new HashMap<>(); // {living, stuck} per owner, guarded by this
    Runnable beforeSlotForTest;
    private int mLiving; // guarded by this
    private int mStuck; // guarded by this

    /** One living thread's slot: ended exactly once, when the thread ends or never started. */
    final class Slot {
        final String owner;
        private boolean mStuckHere; // guarded by RunSlots.this
        private boolean mEnded; // guarded by RunSlots.this

        private Slot(String owner) {
            this.owner = owner;
        }

        /** The caller gave up while the thread runs: it counts as stuck until it ends. False when it ended already. */
        boolean abandon() {
            synchronized (RunSlots.this) {
                if (mEnded || mStuckHere) {
                    return !mEnded;
                }
                mStuckHere = true;
                mOwners.get(owner)[1]++;
                mStuck++;
                return true;
            }
        }

        /** The thread ended, or never started: the slot is free. Later calls do nothing. */
        void end() {
            synchronized (RunSlots.this) {
                if (mEnded) {
                    return;
                }
                mEnded = true;
                final int[] counts = mOwners.get(owner);
                counts[0]--;
                mLiving--;
                if (mStuckHere) {
                    counts[1]--;
                    mStuck--;
                }
                if (counts[0] == 0) {
                    mOwners.remove(owner);
                }
            }
        }
    }

    RunSlots(int perOwner, int total, int stuckPerOwner, int stuckTotal) {
        mPerOwner = perOwner;
        mTotal = total;
        mStuckPerOwner = stuckPerOwner;
        mStuckTotal = stuckTotal;
    }

    /** A slot for a new thread of {@code owner}, or null when the owner or the process has too many. */
    synchronized Slot take(String owner) {
        final String o = owner == null ? "" : owner;
        final int[] counts = mOwners.get(o);
        final int living = counts == null ? 0 : counts[0];
        final int stuck = counts == null ? 0 : counts[1];
        if (living >= mPerOwner || mLiving >= mTotal || stuck >= mStuckPerOwner
                || (stuck > 0 && mStuck >= mStuckTotal)) {
            return null;
        }
        if (beforeSlotForTest != null) beforeSlotForTest.run();
        final Slot slot = new Slot(o);
        if (counts == null) {
            mOwners.put(o, new int[] {1, 0});
        } else {
            counts[0]++;
        }
        mLiving++;
        return slot;
    }

    /** Living threads of {@code owner}, for tests. */
    synchronized int living(String owner) {
        final int[] counts = mOwners.get(owner == null ? "" : owner);
        return counts == null ? 0 : counts[0];
    }

    /** Living threads in all, for tests. */
    synchronized int living() {
        return mLiving;
    }

    /** Stuck threads in all, for tests. */
    synchronized int stuck() {
        return mStuck;
    }
}
