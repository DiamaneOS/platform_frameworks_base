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

package com.android.server.cleanimages;

import android.content.Context;

import com.android.internal.diamaneos.cleancopies.CleanCopyClient;
import com.android.server.SystemService;

/**
 * DiamaneOS: the clean icon cache's place in the system server's start. Once the phone has booted, the warm-up
 * ({@link CleanImageWarmup}) starts: copies of every installed app's launcher icons and splash images are made at
 * install and update, and in one pass after an OS update.
 */
public final class CleanImageService extends SystemService {
    public CleanImageService(Context context) {
        super(context);
    }

    @Override
    public void onStart() {
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase == PHASE_SYSTEM_SERVICES_READY) {
            // The box every process keys copies by, found once here, off any lock (the system server's own gate
            // never asks the display itself).
            new Thread(() -> CleanCopyClient.box(getContext()), "CleanImagesBox").start();
            // The store's index, read on the maker's thread before any binder thread asks for a stand-in.
            CleanImageMaker.get(getContext()).indexStore();
        } else if (phase == PHASE_BOOT_COMPLETED) {
            CleanImageMaker.get(getContext()).prepareStore();
            CleanImageWarmup.start(getContext());
        }
    }
}
