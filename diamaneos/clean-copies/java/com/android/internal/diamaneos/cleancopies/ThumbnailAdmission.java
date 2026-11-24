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

import java.util.HashMap;
import java.util.Map;

/** Admission remains held until the actual open, copy or IPC worker exits. */
final class ThumbnailAdmission {
    private final int mGlobal;
    private final int mPerProvider;
    private final Map<String, Integer> mCounts = new HashMap<>();
    private int mCount;

    ThumbnailAdmission(int global, int perProvider) {
        mGlobal = global;
        mPerProvider = perProvider;
    }

    synchronized Ticket take(String provider) {
        if (mCount >= mGlobal || mCounts.getOrDefault(provider, 0) >= mPerProvider) return null;
        ++mCount;
        mCounts.merge(provider, 1, Integer::sum);
        return new Ticket(provider);
    }

    final class Ticket implements AutoCloseable {
        private final String mProvider;
        private boolean mClosed;
        Ticket(String provider) { mProvider = provider; }
        public void close() {
            synchronized (ThumbnailAdmission.this) {
                if (mClosed) return;
                mClosed = true;
                --mCount;
                final int left = mCounts.get(mProvider) - 1;
                if (left == 0) mCounts.remove(mProvider);
                else mCounts.put(mProvider, left);
            }
        }
    }
}
