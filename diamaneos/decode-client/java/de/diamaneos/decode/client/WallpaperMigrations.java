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

import java.util.concurrent.ConcurrentHashMap;

/** A legacy migration suppresses retries only for its own source generation. */
public final class WallpaperMigrations {
    private final ConcurrentHashMap<String, String> mPending = new ConcurrentHashMap<>();

    public boolean begin(String path, String generation) {
        return generation != null && !generation.equals(mPending.put(path, generation));
    }

    public void retire(String path, String generation) {
        mPending.remove(path, generation);
    }

    public void clear(String path) {
        mPending.remove(path);
    }
}
