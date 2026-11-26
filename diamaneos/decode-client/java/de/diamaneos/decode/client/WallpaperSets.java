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

/** Service-owned set generations, independent of app-controlled file writes and timestamps. */
public final class WallpaperSets {
    private final Map<String, Long> mSets = new HashMap<>();
    private long mNext;

    public synchronized long begin(String path) {
        final long set = ++mNext;
        mSets.put(path, set);
        return set;
    }

    public synchronized long current(String path) {
        return mSets.getOrDefault(path, 0L);
    }

    public synchronized boolean current(String path, long set) {
        return current(path) == set;
    }
}
