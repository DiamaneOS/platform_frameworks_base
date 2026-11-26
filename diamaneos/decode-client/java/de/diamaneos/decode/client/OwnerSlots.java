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
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

/**
 * The calls a process has in flight to the decode service, per owner (queued, running, or stuck past a deadline), so
 * one app's slow or large files cannot fill the queue or hold every call thread. An owner has at most
 * {@code maxPerOwner} calls in flight ({@code maxUnknownOwner} for requests without a known owner), and an owner that
 * already has one may not take the last of the apps' call threads: an owner with nothing in flight always finds one.
 * An owner over its share waits for one of the calls in flight to end, at most until the time its call has, so a share
 * sheet's second preview comes a moment later instead of not at all. An owner whose calls are stuck in the service is
 * turned away at once. The process's own calls (the system lane) have a thread of their own and never count against
 * the apps. Plain Java, tested on the host.
 */
final class OwnerSlots {
    private final int mAppThreads;
    private final int mMaxPerOwner;
    private final int mMaxUnknownOwner;
    private final Predicate<String> mSystemLane;
    private final Predicate<String> mUnknownOwner;
    private final LongSupplier mClock;
    private final Map<String, Integer> mCalls = new HashMap<>(); // guarded by this

    /**
     * {@code systemLane} tells the process's own lane, {@code unknownOwner} a lane of requests without a known owner,
     * and {@code clock} gives the time {@link #claim}'s end is given in, in milliseconds.
     */
    OwnerSlots(int appThreads, int maxPerOwner, int maxUnknownOwner, Predicate<String> systemLane,
            Predicate<String> unknownOwner, LongSupplier clock) {
        mAppThreads = appThreads;
        mMaxPerOwner = maxPerOwner;
        mMaxUnknownOwner = maxUnknownOwner;
        mSystemLane = systemLane;
        mUnknownOwner = unknownOwner;
        mClock = clock;
    }

    /**
     * Takes a slot for {@code lane}, waiting while the owner is over its share, at most until {@code waitEnd} on the
     * clock and not at all while {@code stuck} says a call of the owner is stuck. Null when the slot is taken, else
     * why not (nothing taken).
     */
    synchronized String claim(String lane, BooleanSupplier stuck, long waitEnd) {
        while (true) {
            final String why = overShare(lane);
            if (why == null) {
                mCalls.merge(lane, 1, Integer::sum);
                return null;
            }
            if (stuck.getAsBoolean()) {
                return why;
            }
            final long left = waitEnd - mClock.getAsLong();
            if (left <= 0) {
                return why;
            }
            try {
                wait(left);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return why;
            }
        }
    }

    /** Gives back a slot {@link #claim} took for {@code lane}. */
    synchronized void release(String lane) {
        final int n = mCalls.getOrDefault(lane, 1) - 1;
        if (n <= 0) {
            mCalls.remove(lane);
        } else {
            mCalls.put(lane, n);
        }
        notifyAll();
    }

    /** Owners that wait look again (a call became stuck). */
    synchronized void wake() {
        notifyAll();
    }

    /** Calls in flight for {@code lane}. */
    synchronized int inFlight(String lane) {
        return mCalls.getOrDefault(lane, 0);
    }

    // Why the owner may not have another call now, or null. Under this.
    private String overShare(String lane) {
        final int n = mCalls.getOrDefault(lane, 0);
        if (n >= (mUnknownOwner.test(lane) ? mMaxUnknownOwner : mMaxPerOwner)) {
            return "too many decodes in flight for " + lane;
        }
        if (n > 0 && !mSystemLane.test(lane)) {
            int total = 0;
            for (Map.Entry<String, Integer> calls : mCalls.entrySet()) {
                if (!mSystemLane.test(calls.getKey())) {
                    total += calls.getValue();
                }
            }
            if (total >= mAppThreads - 1) {
                return "the last call thread is kept for others than " + lane;
            }
        }
        return null;
    }
}
