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

package com.android.server.storage;

/** DiamaneOS: only a confirmed native end permits removal of the user's record or a success callback. */
public final class ObbCleanup {
    public interface NativeEnd { void destroy(String id) throws Exception; }
    private ObbCleanup() {}
    public record Connection(Object identity, NativeEnd nativeEnd) {}

    /** True when a record made in session {@code recorded} is not of the live session: its cleanup is retried. */
    public static boolean stale(Connection recorded, Connection live) {
        return recorded == null || live == null || recorded.identity() != live.identity();
    }
    public static NativeEnd inSession(java.util.function.Supplier<Connection> current, Connection expected) {
        return id -> {
            Connection live = current.get();
            if (live == null || expected == null) throw new IllegalStateException("vold is disconnected");
            if (live.identity() != expected.identity()) return;
            live.nativeEnd().destroy(id);
        };
    }

    public static boolean end(NativeEnd nativeEnd, String id, Runnable confirmed) {
        if (id == null) return true;
        try {
            nativeEnd.destroy(id);
        } catch (Exception e) {
            return false;
        }
        confirmed.run();
        return true;
    }
    public interface Delay { void post(Runnable work, long millis); }

    /** The first retry's delay, doubled after each failure, and the tries before the retries rest. */
    static final long FIRST_RETRY_MS = 3000;
    static final int TRIES = 6;

    /**
     * Handler-confined pending native IDs, including rollback without a live listener. A cleanup that keeps failing
     * is tried {@link #TRIES} times, each wait twice the one before, then rests with its record kept until vold
     * connects again or the app unmounts the image again ({@link #retry} once more).
     */
    public static final class Pending<S extends ObbRecords.Record> {
        private final android.util.ArrayMap<String, S> mEntries = new android.util.ArrayMap<>();
        private final android.util.ArrayMap<String, Integer> mTries = new android.util.ArrayMap<>();
        public int size() { return mEntries.size(); }
        public S valueAt(int index) { return mEntries.valueAt(index); }
        public S remove(String id) {
            mTries.remove(id);
            return mEntries.remove(id);
        }
        public void retry(S state, NativeEnd nativeEnd, Delay delay, java.util.function.Consumer<S> confirmed) {
            String id = state.volId();
            if (id == null) return;
            final S known = mEntries.get(id);
            if (known != null && (known != state || mTries.containsKey(id))) return; // under way
            mEntries.put(id, state);
            schedule(id, state, 0, nativeEnd, delay, confirmed);
        }
        private void schedule(String id, S state, int tried, NativeEnd nativeEnd, Delay delay,
                java.util.function.Consumer<S> confirmed) {
            mTries.put(id, tried);
            delay.post(() -> {
                if (mEntries.get(id) != state) return;
                if (end(nativeEnd, id, () -> { remove(id); confirmed.accept(state); })) return;
                if (tried + 1 < TRIES) {
                    schedule(id, state, tried + 1, nativeEnd, delay, confirmed);
                } else {
                    mTries.remove(id); // resting: the record stays, the next retry starts again
                }
            }, FIRST_RETRY_MS << tried);
        }
    }
}
