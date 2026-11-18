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

import android.os.SystemClock;
import android.util.LruCache;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Everyone who asked for an image that an image thread is making (or will try again): each is told when it is made,
 * not only the first. Only the key, the asker's app and tag and its callback are kept, never an image. Capped: past
 * {@link #MAX_PER_KEY} for one image or {@link #MAX_KEYS} images, the oldest are let go (they ask again on their
 * next update). A tag that is cancelled (a notification that went) drops its callbacks, also those already handed
 * out for a later retry. Thread-safe.
 */
final class Waiters {
    static final int MAX_KEYS = 256;
    static final int MAX_PER_KEY = 16;

    /** One asker. */
    static final class Waiter {
        final String owner;
        final String tag;
        final Runnable onMade;
        final long since;

        Waiter(String owner, String tag, Runnable onMade, long since) {
            this.owner = owner == null ? "" : owner;
            this.tag = tag;
            this.onMade = onMade;
            this.since = since;
        }
    }

    private final LinkedHashMap<String, List<Waiter>> mWaiting = new LinkedHashMap<>(); // guarded by this
    // When each tag was last cancelled (uptime), for callbacks already handed out.
    private final LruCache<String, Long> mCancelled = new LruCache<>(512); // guarded by this

    /** Adds {@code onMade} (if not null) as a waiter for {@code key}. */
    synchronized void add(String key, String owner, String tag, Runnable onMade) {
        if (key == null || onMade == null) {
            return;
        }
        List<Waiter> list = mWaiting.remove(key);
        if (list == null) {
            list = new ArrayList<>();
        }
        // The same callback object is kept once.
        for (Waiter w : list) {
            if (w.onMade == onMade) {
                mWaiting.put(key, list);
                return;
            }
        }
        if (list.size() >= MAX_PER_KEY) {
            list.remove(0);
        }
        list.add(new Waiter(owner, tag, onMade, SystemClock.uptimeMillis()));
        mWaiting.put(key, list);
        if (mWaiting.size() > MAX_KEYS) {
            final Iterator<Map.Entry<String, List<Waiter>>> it = mWaiting.entrySet().iterator();
            it.next();
            it.remove();
        }
    }

    /** Takes every waiter of {@code key}: the caller tells them. */
    synchronized List<Waiter> take(String key) {
        final List<Waiter> list = key == null ? null : mWaiting.remove(key);
        return list != null ? list : new ArrayList<>();
    }

    /** True when someone waits for {@code key}. */
    synchronized boolean has(String key) {
        return key != null && mWaiting.containsKey(key);
    }

    /**
     * Drops the waiters with {@code tag}. Returns the keys nobody waits for any more because of it: their work is
     * no longer wanted.
     */
    synchronized List<String> cancel(String tag) {
        final List<String> unwanted = new ArrayList<>();
        if (tag == null) {
            return unwanted;
        }
        mCancelled.put(tag, SystemClock.uptimeMillis());
        final Iterator<Map.Entry<String, List<Waiter>>> it = mWaiting.entrySet().iterator();
        while (it.hasNext()) {
            final Map.Entry<String, List<Waiter>> e = it.next();
            if (e.getValue().removeIf(w -> tag.equals(w.tag)) && e.getValue().isEmpty()) {
                it.remove();
                unwanted.add(e.getKey());
            }
        }
        return unwanted;
    }

    /** False for a waiter whose tag was cancelled after it was added: its callback is not run. */
    synchronized boolean live(Waiter w) {
        if (w.tag == null) {
            return true;
        }
        final Long at = mCancelled.get(w.tag);
        return at == null || at < w.since;
    }
}
