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
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * The work queue of the system server's clean copy maker, by key: an urgent lane (files a system process waits for),
 * a background lane (the warm-up's) and the files to try again later. Each holds at most {@code max} entries, the
 * oldest going first, and each lane has at most one drain scheduled at a time: however many files are added, the
 * thread that makes them has one task per lane waiting, and a file dropped from a full lane leaves nothing behind.
 * Not thread-safe: the maker calls it under its lock. Plain Java, tested on the host.
 */
public final class MakeQueue<T> {
    private final int mMax;
    private final LinkedHashMap<String, T> mUrgent = new LinkedHashMap<>();
    private final LinkedHashMap<String, T> mBackground = new LinkedHashMap<>();
    private final LinkedHashMap<String, T> mRetry = new LinkedHashMap<>();
    private final Map<String, Long> mDue = new LinkedHashMap<>();
    private final java.util.Set<String> mRunning = new java.util.HashSet<>();
    private boolean mUrgentDrain;
    private boolean mBackgroundDrain;
    private boolean mRetryTimer;

    /** A queue of at most {@code max} files per lane and as many waiting to be tried again. */
    public MakeQueue(int max) {
        mMax = Math.max(1, max);
    }

    /** The file under {@code key} that waits to be tried again, or null. */
    public T retrying(String key) {
        return mRetry.get(key);
    }

    /** Takes a retry for promotion only after the running make has released the key. */
    public T promoteRetry(String key) {
        if (mRunning.contains(key)) {
            return null;
        }
        final T retry = mRetry.get(key);
        dropRetry(key);
        return retry;
    }

    /** Takes the file under {@code key} off the files to try again. */
    public void dropRetry(String key) {
        mRetry.remove(key);
        mDue.remove(key);
    }

    /**
     * Queues {@code item} under {@code key} in its lane: an urgent file leaves the background lane, a file already
     * queued is not queued twice (an urgent one stays urgent). True when the caller must schedule the lane's drain
     * ({@link #next}): none is scheduled yet.
     */
    public boolean add(String key, T item, boolean urgent) {
        if (mRunning.contains(key)) {
            return false;
        }
        if (urgent) {
            mBackground.remove(key);
            if (mUrgent.containsKey(key)) {
                return false;
            }
            mUrgent.put(key, item);
            trim(mUrgent);
            if (mUrgentDrain) {
                return false;
            }
            mUrgentDrain = true;
            return true;
        }
        if (mUrgent.containsKey(key) || mBackground.containsKey(key)) {
            return false;
        }
        mBackground.put(key, item);
        trim(mBackground);
        if (mBackgroundDrain) {
            return false;
        }
        mBackgroundDrain = true;
        return true;
    }

    /**
     * The next file of a lane, taken off it, for the lane's drain. Null when the lane is empty: its drain ends, and
     * the next {@link #add} schedules a new one. The drain schedules itself again after a file.
     */
    public T next(boolean urgent) {
        final Iterator<Map.Entry<String, T>> it = (urgent ? mUrgent : mBackground).entrySet().iterator();
        if (!it.hasNext()) {
            if (urgent) {
                mUrgentDrain = false;
            } else {
                mBackgroundDrain = false;
            }
            return null;
        }
        final Map.Entry<String, T> item = it.next();
        mRunning.add(item.getKey());
        it.remove();
        return item.getValue();
    }

    /** The maker finished this key, including verification of an existing file. */
    public void finished(String key) {
        mRunning.remove(key);
    }

    /** Drops pending work for a removed user or package. Running work needs a publication fence too. */
    public void removeIf(Predicate<T> test) {
        mUrgent.values().removeIf(test);
        mBackground.values().removeIf(test);
        final Iterator<Map.Entry<String, T>> it = mRetry.entrySet().iterator();
        while (it.hasNext()) {
            final Map.Entry<String, T> entry = it.next();
            if (test.test(entry.getValue())) {
                mDue.remove(entry.getKey());
                it.remove();
            }
        }
    }

    /** True when a queued file (in either lane) matches {@code test}. */
    public boolean anyQueued(Predicate<T> test) {
        for (T item : mUrgent.values()) {
            if (test.test(item)) {
                return true;
            }
        }
        for (T item : mBackground.values()) {
            if (test.test(item)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Keeps {@code item} to be tried again at {@code due}. A file waiting already is replaced (one entry per key).
     * When more than {@code max} files wait, the oldest goes: it is returned (null otherwise), so the caller can
     * count it as given up.
     */
    public T retry(String key, T item, long due) {
        mRetry.remove(key);
        mRetry.put(key, item);
        mDue.remove(key);
        mDue.put(key, due);
        if (mRetry.size() <= mMax) {
            return null;
        }
        final Iterator<Map.Entry<String, T>> it = mRetry.entrySet().iterator();
        final Map.Entry<String, T> eldest = it.next();
        it.remove();
        mDue.remove(eldest.getKey());
        return eldest.getValue();
    }

    /** True when the caller must schedule the retry timer: files wait and none is scheduled. Marks it scheduled. */
    public boolean needsRetryTimer() {
        if (mRetryTimer || mRetry.isEmpty()) {
            return false;
        }
        mRetryTimer = true;
        return true;
    }

    /** The retry timer ran: the files due by {@code now}, taken off. Call {@link #needsRetryTimer} after. */
    public List<T> due(long now) {
        mRetryTimer = false;
        final List<T> due = new ArrayList<>();
        for (Iterator<Map.Entry<String, Long>> it = mDue.entrySet().iterator(); it.hasNext(); ) {
            final Map.Entry<String, Long> entry = it.next();
            if (entry.getValue() <= now && !mRunning.contains(entry.getKey())) {
                due.add(mRetry.remove(entry.getKey()));
                it.remove();
            }
        }
        return due;
    }

    /** Takes retries whose running make has ended when the decode service answers after an outage. */
    public List<T> takeRetries() {
        final List<T> all = new ArrayList<>();
        final Iterator<Map.Entry<String, T>> it = mRetry.entrySet().iterator();
        while (it.hasNext()) {
            final Map.Entry<String, T> entry = it.next();
            if (!mRunning.contains(entry.getKey())) {
                all.add(entry.getValue());
                mDue.remove(entry.getKey());
                it.remove();
            }
        }
        return all;
    }

    /** Every file waiting to be tried again, taken off, for an APK's copies that were removed. */
    public void clearRetries() {
        mRetry.clear();
        mDue.clear();
    }

    /** How many files a lane holds, for tests and logs. */
    public int size(boolean urgent) {
        return (urgent ? mUrgent : mBackground).size();
    }

    /** How many files wait to be tried again. */
    public int retries() {
        return mRetry.size();
    }

    private void trim(LinkedHashMap<String, T> lane) {
        if (lane.size() > mMax) {
            final Iterator<String> it = lane.keySet().iterator();
            it.next();
            it.remove();
        }
    }
}
