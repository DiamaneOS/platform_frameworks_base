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

import android.util.LruCache;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Everyone who asked for an image that an image thread is making (or will try again): each is told when it is made,
 * not only the first. Only the key, the asker's app and tag and its callback are kept, never an image. Capped per app,
 * so one app cannot push out another app's waiters: at most {@link #MAX_KEYS_PER_OWNER} images of one app (counted by
 * the app of the image's first asker) and {@link #MAX_PER_KEY} askers of one image, the oldest of that app or image let
 * go past that (they ask again on their next update), and {@link #MAX_KEYS} in all, where the app with the most images
 * gives up its oldest. A tag that is cancelled (a notification that went) drops its callbacks, also those already
 * handed out for a later retry. Thread-safe.
 */
final class Waiters {
    static final int MAX_KEYS = 512;
    static final int MAX_KEYS_PER_OWNER = 32;
    static final int MAX_PER_KEY = 64;

    /** One asker. */
    static final class Waiter {
        final String owner;
        final String tag;
        final Runnable onMade;
        final long seq;

        Waiter(String owner, String tag, Runnable onMade, long seq) {
            this.owner = owner == null ? "" : owner;
            this.tag = tag;
            this.onMade = onMade;
            this.seq = seq;
        }
    }

    private final LinkedHashMap<String, List<Waiter>> mWaiting = new LinkedHashMap<>(); // guarded by this
    // The sequence number at which each tag was last cancelled, for callbacks already handed out.
    private final LruCache<String, Long> mCancelled = new LruCache<>(512); // guarded by this
    private long mSeq; // guarded by this

    private static String ownerOf(List<Waiter> list) {
        return list.isEmpty() ? "" : list.get(0).owner;
    }

    /** Adds {@code onMade} (if not null) as a waiter for {@code key}. */
    synchronized void add(String key, String owner, String tag, Runnable onMade) {
        if (key == null || onMade == null) {
            return;
        }
        List<Waiter> list = mWaiting.remove(key);
        if (list == null) {
            list = new ArrayList<>();
            final String o = owner == null ? "" : owner;
            // A new image of this app: past its cap, its oldest image's waiters go.
            String oldestOfOwner = null;
            int ofOwner = 0;
            for (Map.Entry<String, List<Waiter>> e : mWaiting.entrySet()) {
                if (ownerOf(e.getValue()).equals(o)) {
                    if (oldestOfOwner == null) {
                        oldestOfOwner = e.getKey();
                    }
                    ofOwner++;
                }
            }
            if (ofOwner >= MAX_KEYS_PER_OWNER && oldestOfOwner != null) {
                mWaiting.remove(oldestOfOwner);
            }
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
        list.add(new Waiter(owner, tag, onMade, ++mSeq));
        mWaiting.put(key, list);
        if (mWaiting.size() > MAX_KEYS) {
            dropFromLargestOwner();
        }
    }

    // The app with the most images waited for gives up its oldest one.
    private void dropFromLargestOwner() {
        final Map<String, Integer> counts = new java.util.HashMap<>();
        for (List<Waiter> list : mWaiting.values()) {
            counts.merge(ownerOf(list), 1, Integer::sum);
        }
        String largest = null;
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            if (largest == null || e.getValue() > counts.get(largest)) {
                largest = e.getKey();
            }
        }
        for (Iterator<Map.Entry<String, List<Waiter>>> it = mWaiting.entrySet().iterator(); it.hasNext(); ) {
            if (ownerOf(it.next().getValue()).equals(largest)) {
                it.remove();
                return;
            }
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
        mCancelled.put(tag, ++mSeq);
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
        return at == null || at < w.seq;
    }
}
