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

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Everyone who asked for an image that an image thread is making (or will try again): each is told when it is made,
 * not only the first. Only the key, the asker's app and tag and its callback are kept, never an image. Capped per app
 * (the app of each asker), so one app cannot push out another app's askers: at most
 * {@link #MAX_KEYS_PER_OWNER} images an app's askers wait for, {@link #MAX_PER_KEY} askers of one image, and
 * {@link #MAX_KEYS} images in all, where the app waiting for the most gives up its oldest until the bound holds again.
 * Askers let go past a cap are handed back to the caller, which tells them later so that they ask again. A tag that
 * is cancelled (a notification that went) drops its callbacks, also those already handed out: the askers of one tag
 * share a token until it is cancelled, and each keeps it until it is told or dropped. Thread-safe. Plain Java but for
 * the log line of a failed callback, tested on the host.
 */
final class Waiters {
    static final int MAX_KEYS = 512;
    static final int MAX_KEYS_PER_OWNER = 128;
    static final int MAX_PER_KEY = 64;
    /** How long the caller waits before it tells askers let go past a cap, so they ask again. */
    static final long EVICTED_RETRY_MS = 2_000;

    /** One asker. */
    static final class Waiter {
        final String owner;
        final String tag;
        final Runnable onMade;
        // Shared by the askers of the tag added since its last cancel (null without a tag).
        final Token token;

        Waiter(String owner, String tag, Runnable onMade, Token token) {
            this.owner = owner == null ? "" : owner;
            this.tag = tag;
            this.onMade = onMade;
            this.token = token;
        }
    }

    /** Cancellation of one tag's askers, shared by them. */
    static final class Token {
        boolean cancelled; // guarded by the Waiters
    }

    private final LinkedHashMap<String, List<Waiter>> mWaiting = new LinkedHashMap<>(); // guarded by this
    // The images each app's askers wait for, oldest first. Guarded by this.
    private final Map<String, LinkedHashSet<String>> mKeysOf = new HashMap<>();
    // The current token of each tag. Held weakly: the askers hold it, so a tag none of whose askers is left (waiting
    // or handed out) needs no entry, and such entries are pruned.
    private final Map<String, WeakReference<Token>> mTokens = new HashMap<>(); // guarded by this
    private static final int MIN_PRUNE = 256;
    private int mPruneAt = MIN_PRUNE; // guarded by this

    /**
     * Adds {@code onMade} (if not null) as a waiter for {@code key}. Returns the askers let go to make room (empty
     * mostly): the caller tells them later, so they ask again.
     */
    synchronized List<Waiter> add(String key, String owner, String tag, Runnable onMade) {
        final List<Waiter> evicted = new ArrayList<>();
        if (key == null || onMade == null) {
            return evicted;
        }
        final String o = owner == null ? "" : owner;
        List<Waiter> list = mWaiting.get(key);
        if (list != null) {
            // The same callback object is kept once.
            for (Waiter w : list) {
                if (w.onMade == onMade) {
                    return evicted;
                }
            }
        }
        final LinkedHashSet<String> keys = mKeysOf.computeIfAbsent(o, k -> new LinkedHashSet<>());
        if (!keys.contains(key) && keys.size() >= MAX_KEYS_PER_OWNER) {
            evicted.addAll(removeOwnerFrom(keys.iterator().next(), o));
        }
        list = mWaiting.get(key);
        if (list == null) {
            list = new ArrayList<>();
            mWaiting.put(key, list);
        }
        if (list.size() >= MAX_PER_KEY) {
            final Waiter gone = list.remove(0);
            evicted.add(gone);
            unlinkIfLast(gone.owner, key, list);
        }
        list.add(new Waiter(o, tag, onMade, tokenOf(tag)));
        mKeysOf.computeIfAbsent(o, k -> new LinkedHashSet<>()).add(key);
        while (mWaiting.size() > MAX_KEYS) {
            // The app waiting for the most images gives up its oldest. That image may still have askers of other
            // apps: then the next is given up, until the bound holds (each round lets at least one asker go).
            String largest = null;
            for (Map.Entry<String, LinkedHashSet<String>> e : mKeysOf.entrySet()) {
                if (!e.getValue().isEmpty()
                        && (largest == null || e.getValue().size() > mKeysOf.get(largest).size())) {
                    largest = e.getKey();
                }
            }
            if (largest == null) {
                break;
            }
            evicted.addAll(removeOwnerFrom(mKeysOf.get(largest).iterator().next(), largest));
        }
        return evicted;
    }

    // The token the askers of tag share now, made when there is none.
    private Token tokenOf(String tag) {
        if (tag == null) {
            return null;
        }
        final WeakReference<Token> ref = mTokens.get(tag);
        Token token = ref == null ? null : ref.get();
        if (token == null) {
            token = new Token();
            mTokens.put(tag, new WeakReference<>(token));
            if (mTokens.size() > mPruneAt) {
                mTokens.values().removeIf(r -> r.get() == null);
                mPruneAt = Math.max(MIN_PRUNE, 2 * mTokens.size());
            }
        }
        return token;
    }

    // Removes the askers of owner from key, and key when nobody is left. Returns them.
    private List<Waiter> removeOwnerFrom(String key, String owner) {
        final List<Waiter> removed = new ArrayList<>();
        final List<Waiter> list = mWaiting.get(key);
        if (list != null) {
            for (Iterator<Waiter> it = list.iterator(); it.hasNext(); ) {
                final Waiter w = it.next();
                if (w.owner.equals(owner)) {
                    it.remove();
                    removed.add(w);
                }
            }
            if (list.isEmpty()) {
                mWaiting.remove(key);
            }
        }
        unlink(owner, key);
        return removed;
    }

    private void unlinkIfLast(String owner, String key, List<Waiter> list) {
        for (Waiter w : list) {
            if (w.owner.equals(owner)) {
                return;
            }
        }
        unlink(owner, key);
    }

    private void unlink(String owner, String key) {
        final LinkedHashSet<String> keys = mKeysOf.get(owner);
        if (keys != null) {
            keys.remove(key);
            if (keys.isEmpty()) {
                mKeysOf.remove(owner);
            }
        }
    }

    /** Takes every waiter of {@code key}: the caller tells them. */
    synchronized List<Waiter> take(String key) {
        final List<Waiter> list = key == null ? null : mWaiting.remove(key);
        if (list == null) {
            return new ArrayList<>();
        }
        for (Waiter w : list) {
            unlink(w.owner, key);
        }
        return list;
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
        final WeakReference<Token> ref = mTokens.remove(tag);
        final Token token = ref == null ? null : ref.get();
        if (token != null) {
            // Every asker of the tag so far, waiting or handed out: none of their callbacks runs any more.
            token.cancelled = true;
        }
        final Iterator<Map.Entry<String, List<Waiter>>> it = mWaiting.entrySet().iterator();
        while (it.hasNext()) {
            final Map.Entry<String, List<Waiter>> e = it.next();
            final List<Waiter> gone = new ArrayList<>();
            for (Waiter w : e.getValue()) {
                if (tag.equals(w.tag)) {
                    gone.add(w);
                }
            }
            if (gone.isEmpty()) {
                continue;
            }
            e.getValue().removeAll(gone);
            for (Waiter w : gone) {
                unlinkIfLast(w.owner, e.getKey(), e.getValue());
            }
            if (e.getValue().isEmpty()) {
                it.remove();
                unwanted.add(e.getKey());
            }
        }
        return unwanted;
    }

    /** Main thread: runs the callbacks of the waiters still wanted, each guarded (see {@link #runSafely}). */
    void tell(List<Waiter> waiters) {
        for (Waiter w : waiters) {
            if (live(w)) {
                runSafely(w.onMade);
            }
        }
    }

    /**
     * Runs a caller's callback on the main thread so that one which throws ends neither the loop it is in nor
     * SystemUI: it is logged and the next one runs.
     */
    static void runSafely(Runnable callback) {
        try {
            callback.run();
        } catch (RuntimeException e) {
            android.util.Log.e("VmImages", "image callback failed", e);
        }
    }

    /** False for a waiter whose tag was cancelled after it was added: its callback is not run. */
    synchronized boolean live(Waiter w) {
        return w.token == null || !w.token.cancelled;
    }

    /** Images waited for in all, for tests. */
    synchronized int keys() {
        return mWaiting.size();
    }

    /** Askers waiting in all, for tests. */
    synchronized int size() {
        int n = 0;
        for (List<Waiter> list : mWaiting.values()) {
            n += list.size();
        }
        return n;
    }
}
