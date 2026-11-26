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

import java.io.FileDescriptor;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Whether an open file holds bytes of the verified read-only system partitions (system, system_ext, product, vendor,
 * odm): decided from the open file itself, never from a path a caller names. The file's device (fstat of the
 * descriptor, so symlinks, bind mounts and /proc/self/fd paths resolve to the device that really holds the bytes) must
 * be a device-mapper block device mounted read-only at one of those mount points and mounted nowhere writable, and
 * verified boot must enforce dm-verity for the whole boot ({@code ro.boot.veritymode} enforcing). Anything else (a
 * pipe, a socket, a memory file, an overlay as adb remount makes it, a file of /data, an APEX image) is not.
 *
 * @hide
 */
public final class VerifiedSystemFiles {
    // Devices by number and until when the answer for them holds: a verified one for VERIFIED_MS (a device number can
    // be given to another device after a partition was taken down), another one for OTHER_MS, so a burst of files of
    // /data reads the mount table once.
    private static final ConcurrentHashMap<Long, Long> sVerifiedUntil = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Long, Long> sOtherUntil = new ConcurrentHashMap<>();
    private static final long VERIFIED_MS = 60_000;
    private static final long OTHER_MS = 2_000;
    private static final int MAX_DEVICES = 64;

    private VerifiedSystemFiles() {
    }

    /** True when {@code fd} is a regular file on a verified read-only system partition. Never throws. */
    public static boolean holds(FileDescriptor fd) {
        try {
            if (fd == null || !fd.valid()
                    || !"enforcing".equals(android.os.SystemProperties.get("ro.boot.veritymode"))) {
                return false;
            }
            final android.system.StructStat st = android.system.Os.fstat(fd);
            if (!android.system.OsConstants.S_ISREG(st.st_mode)) {
                return false;
            }
            final long now = android.os.SystemClock.uptimeMillis();
            if (now < sVerifiedUntil.getOrDefault(st.st_dev, 0L)) {
                return true;
            }
            if (now < sOtherUntil.getOrDefault(st.st_dev, 0L)) {
                return false;
            }
            final String mountinfo = new String(Files.readAllBytes(Paths.get("/proc/self/mountinfo")),
                    StandardCharsets.UTF_8);
            final boolean verified = VerifiedMounts.verifiedDevice(mountinfo, VerifiedMounts.major(st.st_dev),
                    VerifiedMounts.minor(st.st_dev));
            final ConcurrentHashMap<Long, Long> answers = verified ? sVerifiedUntil : sOtherUntil;
            if (answers.size() >= MAX_DEVICES) {
                answers.clear();
            }
            answers.put(st.st_dev, now + (verified ? VERIFIED_MS : OTHER_MS));
            (verified ? sOtherUntil : sVerifiedUntil).remove(st.st_dev);
            return verified;
        } catch (Exception | LinkageError e) {
            return false;
        }
    }
}
