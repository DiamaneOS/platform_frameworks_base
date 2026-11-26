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

package com.android.server.om;

import android.annotation.NonNull;
import android.os.FabricatedOverlayInternal;
import android.os.FabricatedOverlayInternalEntry;
import android.os.Process;

/**
 * DiamaneOS: fabricated overlay values backed by a file come from the system server only. idmap2d, which runs as the
 * system uid, reads a nine-patch value with libpng. The bytes of any other file value go into the overlay unread
 * and reach the image and XML parsers of every process that loads the target's resources, the system server too
 * when the target is "android". Nothing outside tests sets such a value through this service (self-targeting
 * overlays are made in the app's own process and never come here), so a request from any other process is refused
 * before idmap2d gets the file, apps that run as the system uid (Settings among them) too.
 */
final class FabricatedFileValues {
    private FabricatedFileValues() {
    }

    /**
     * Throws IllegalArgumentException, the commit path's answer to an invalid request, when a caller other than the
     * system server's own process hands in a file value. {@code callingUid} and {@code callingPid} are the caller's,
     * read on the binder thread before its identity is cleared.
     */
    static void enforceCaller(int callingUid, int callingPid, @NonNull FabricatedOverlayInternal overlay) {
        if ((callingUid == Process.SYSTEM_UID && callingPid == Process.myPid())
                || overlay.entries == null) {
            return;
        }
        for (FabricatedOverlayInternalEntry entry : overlay.entries) {
            if (entry != null && (entry.isNinePatch || entry.binaryData != null)) {
                throw new IllegalArgumentException("UID " + callingUid
                        + " may not set file values in fabricated overlays");
            }
        }
    }
}
