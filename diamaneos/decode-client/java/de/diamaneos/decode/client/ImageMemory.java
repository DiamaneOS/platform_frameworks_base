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

import android.content.ComponentCallbacks2;
import android.content.Context;
import android.content.res.Configuration;
import android.util.Log;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Lets go of the decoded app images SystemUI keeps for later (the icon cache and the gated resources of each app)
 * when the system runs low on memory. Images on screen stay with their views and are counted per app as before.
 */
final class ImageMemory {
    private static final String TAG = "VmDecode";
    private static final AtomicBoolean sWatching = new AtomicBoolean();

    private ImageMemory() {
    }

    /** Starts watching memory pressure for the process of {@code context}, once. */
    static void watch(Context context) {
        if (sWatching.get()) {
            return;
        }
        final Context app = context.getApplicationContext();
        if (app == null || sWatching.getAndSet(true)) {
            return;
        }
        app.registerComponentCallbacks(new ComponentCallbacks2() {
            @Override
            public void onTrimMemory(int level) {
                if (level == TRIM_MEMORY_RUNNING_LOW || level == TRIM_MEMORY_RUNNING_CRITICAL
                        || level >= TRIM_MEMORY_BACKGROUND) {
                    trim();
                }
            }

            @Override
            public void onConfigurationChanged(Configuration newConfig) {
            }

            @Override
            public void onLowMemory() {
                trim();
            }
        });
    }

    /** Drops what is kept for later. Images asked for again are made again. */
    static void trim() {
        Log.i(TAG, "memory low, kept app images released");
        VmIcons.trim();
        AppIcons.trim();
    }
}
