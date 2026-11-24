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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

/**
 * The system server's global locks no thread may wait for the VM while holding (DecodeGate): the activity manager's,
 * the window manager's, the package manager's and Telecom's. A lock is registered when it is known. A lock that is
 * required but not found yet is looked for again (at most once a second) for as long as it is missing, never given up,
 * and while one is missing no system server thread waits for the VM at all: its decodes finish in the background
 * instead, so a holder of the unknown lock can never wait under it. Plain Java, tested on the host.
 *
 * @hide
 */
public final class GateLocks {
    private static final long LOOK_EVERY_MS = 1_000;

    private final List<Object> mLocks = new CopyOnWriteArrayList<>();
    private final Map<String, Supplier<Object>> mMissing = new ConcurrentHashMap<>();
    private volatile long mLookedAt = Long.MIN_VALUE / 2;

    /** A lock that is known. */
    public void register(Object lock) {
        if (lock != null && !mLocks.contains(lock)) {
            mLocks.add(lock);
        }
    }

    /** A required lock: {@code source} gives it once it exists (null before). */
    public void require(String name, Supplier<Object> source) {
        Object lock = null;
        try {
            lock = source.get();
        } catch (RuntimeException e) {
            // Not there yet.
        }
        if (lock != null) {
            register(lock);
        } else {
            mMissing.put(name, source);
        }
    }

    /** Looks for the missing locks again, at most once a second on {@code nowMs}'s clock. */
    public void find(long nowMs) {
        if (mMissing.isEmpty() || nowMs - mLookedAt < LOOK_EVERY_MS) {
            return;
        }
        mLookedAt = nowMs;
        for (Map.Entry<String, Supplier<Object>> source : mMissing.entrySet()) {
            Object lock = null;
            try {
                lock = source.getValue().get();
            } catch (RuntimeException e) {
                // Not there yet.
            }
            if (lock != null) {
                register(lock);
                mMissing.remove(source.getKey());
            }
        }
    }

    /** True when the calling thread holds a known lock. */
    public boolean heldHere() {
        for (Object lock : mLocks) {
            if (Thread.holdsLock(lock)) {
                return true;
            }
        }
        return false;
    }

    /** True when every required lock is known: only then may a system server thread wait for the VM. */
    public boolean complete() {
        return mMissing.isEmpty();
    }

    /** The names of the required locks still missing. */
    public List<String> missing() {
        return new ArrayList<>(mMissing.keySet());
    }
}
