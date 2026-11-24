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

    /** Handler-confined pending native IDs, including rollback without a live listener. */
    public static final class Pending<S extends ObbRecords.Record> {
        private final android.util.ArrayMap<String, S> mEntries = new android.util.ArrayMap<>();
        public int size() { return mEntries.size(); }
        public S valueAt(int index) { return mEntries.valueAt(index); }
        public S remove(String id) { return mEntries.remove(id); }
        public void retry(S state, NativeEnd nativeEnd, Delay delay, java.util.function.Consumer<S> confirmed) {
            String id = state.volId();
            if (id == null || mEntries.containsKey(id)) return;
            mEntries.put(id, state);
            delay.post(() -> {
                if (mEntries.get(id) != state) return;
                if (!end(nativeEnd, id, () -> { remove(id); confirmed.accept(state); })) {
                    remove(id);
                    retry(state, nativeEnd, delay, confirmed);
                }
            }, 3000);
        }
    }
}
