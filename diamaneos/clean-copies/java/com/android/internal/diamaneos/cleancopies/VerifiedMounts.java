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

import java.util.Arrays;
import java.util.List;

/**
 * The mount table rule of {@link VerifiedSystemFiles}, plain Java (tested on the host): which devices are verified
 * read-only system partitions by the text of /proc/self/mountinfo.
 *
 * @hide
 */
public final class VerifiedMounts {
    /** Mount points of the verified read-only partitions. "/" is the system partition (system as root). */
    static final List<String> MOUNT_POINTS = Arrays.asList("/", "/system", "/system_ext", "/product",
            "/vendor", "/odm");
    private static final List<String> FILE_SYSTEMS = Arrays.asList("ext4", "erofs", "f2fs", "squashfs");

    private VerifiedMounts() {
    }

    /** The major number of a Linux device number (as the kernel's new_encode_dev packs it). */
    public static int major(long dev) {
        return (int) (((dev >>> 8) & 0xfff) | ((dev >>> 32) & ~0xfffL));
    }

    /** The minor number of a Linux device number. */
    public static int minor(long dev) {
        return (int) ((dev & 0xff) | ((dev >>> 12) & ~0xffL));
    }

    /**
     * True when the device {@code major}:{@code minor} is, by {@code mountinfo} (the text of /proc/self/mountinfo),
     * a device-mapper block device mounted read-only at one of {@link #MOUNT_POINTS} with a disk file system, and
     * mounted writable nowhere.
     */
    public static boolean verifiedDevice(String mountinfo, int major, int minor) {
        final String device = major + ":" + minor;
        boolean atPartition = false;
        for (String line : mountinfo.split("\n")) {
            // id parent major:minor root mount-point options [optional fields] - type source super-options
            final String[] f = line.trim().split(" ");
            if (f.length < 10 || !device.equals(f[2])) {
                continue;
            }
            int dash = -1;
            for (int i = 6; i < f.length; i++) {
                if ("-".equals(f[i])) {
                    dash = i;
                    break;
                }
            }
            if (dash < 0 || dash + 3 >= f.length) {
                return false; // Not a line this check can read: not verified.
            }
            final List<String> options = Arrays.asList(f[5].split(","));
            final List<String> superOptions = Arrays.asList(f[dash + 3].split(","));
            if (options.contains("rw") || superOptions.contains("rw")) {
                return false; // Writable somewhere: never a verified partition.
            }
            final String mountPoint = unescape(f[4]);
            if (MOUNT_POINTS.contains(mountPoint) && options.contains("ro")
                    && FILE_SYSTEMS.contains(f[dash + 1]) && f[dash + 2].startsWith("/dev/block/dm-")) {
                atPartition = true;
            }
        }
        return atPartition;
    }

    // Mount points escape space, tab, newline and backslash as octal.
    private static String unescape(String s) {
        if (s.indexOf('\\') < 0) {
            return s;
        }
        final StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            final char c = s.charAt(i);
            if (c == '\\' && i + 3 < s.length()) {
                try {
                    out.append((char) Integer.parseInt(s.substring(i + 1, i + 4), 8));
                    i += 3;
                    continue;
                } catch (NumberFormatException e) {
                    // Kept as it is.
                }
            }
            out.append(c);
        }
        return out.toString();
    }
}
