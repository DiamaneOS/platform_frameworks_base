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

/**
 * Which APK paths belong to the system image, whose files are verified at boot and may be decoded on Android. Plain
 * Java, tested on the host.
 */
public final class SystemImagePaths {
    // Read-only partitions checked by verified boot. APEX payloads are signed images too, checked by apexd before
    // they are mounted under /apex.
    private static final String[] PREFIXES = {
            "/system/", "/system_ext/", "/product/", "/vendor/", "/odm/", "/apex/",
    };

    private SystemImagePaths() {
    }

    /**
     * True for an absolute, plain path on a partition of the system image. False for anything else: apps and updated
     * system apps under /data, overlays and shared libraries installed there, unknown ({@code null}) or odd paths.
     */
    public static boolean isSystemImage(String path) {
        if (path == null || path.isEmpty() || path.charAt(0) != '/' || path.indexOf('\0') >= 0) {
            return false;
        }
        if (path.contains("//") || path.contains("/./") || path.contains("/../") || path.endsWith("/.")
                || path.endsWith("/..")) {
            return false;
        }
        for (String prefix : PREFIXES) {
            if (path.startsWith(prefix) && path.length() > prefix.length()) {
                return true;
            }
        }
        return false;
    }
}
