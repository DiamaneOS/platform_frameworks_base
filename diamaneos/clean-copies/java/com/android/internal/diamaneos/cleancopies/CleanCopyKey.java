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

import java.io.File;

/**
 * The key a clean copy of an app's image file is kept under, the same in every process: the APK (path, time and size,
 * so an update is another key), the file in it, its density and the box it is made in. And which APKs are the
 * verified system image, whose files every process decodes as before. Plain Java, tested on the host.
 */
public final class CleanCopyKey {
    /** How a key starts, followed by the APK's path. */
    public static final String PREFIX = "rfile:";

    private static final String[] SYSTEM_IMAGE = {
        "/system/", "/system_ext/", "/product/", "/vendor/", "/odm/", "/apex/",
    };

    private CleanCopyKey() {
    }

    /**
     * The key of the file {@code file} of the APK at {@code apkPath}, decoded at {@code density} in a box of
     * {@code maxWidth} x {@code maxHeight}. Null when the APK is not known: such a file is never kept, so two
     * packages can never share a copy.
     */
    public static String of(String apkPath, String file, int density, int maxWidth, int maxHeight) {
        if (apkPath == null || file == null) {
            return null;
        }
        final File apk = new File(apkPath);
        return PREFIX + apkPath + ':' + apk.lastModified() + ':' + apk.length() + ':' + file + ':' + density
                + ':' + maxWidth + 'x' + maxHeight;
    }

    /**
     * What a copy is a copy of, the same across app updates and users: the app the APK belongs to, the resource (as
     * {@code Resources.getResourceName} names it), its variant ({@link #variant}) and the box. Null when one is not
     * known or the name is not a drawable's or a mipmap's. Only the system server makes identities, from the package
     * it finds the APK installed for, never from what an asker or the APK's own resource table says the package is.
     * A copy whose exact key is not made yet (an update brought a new APK, or Android picks another file for the
     * resource) shows the newest copy of the same identity meanwhile, which is made in the VM and safe, just older.
     */
    public static String identity(String ownerPackage, String resourceName, String file, int maxWidth,
            int maxHeight) {
        if (ownerPackage == null || ownerPackage.isEmpty() || ownerPackage.length() > 255
                || !resourceName(resourceName) || file == null) {
            return null;
        }
        return "res:" + ownerPackage + '/' + resourceName + '/' + variant(file) + '/' + maxWidth + 'x' + maxHeight;
    }

    private static final java.util.regex.Pattern RESOURCE_NAME =
            java.util.regex.Pattern.compile("[A-Za-z0-9_.]+:(drawable|mipmap)/[A-Za-z0-9_.$-]+");
    private static final java.util.regex.Pattern DENSITY_OR_VERSION =
            java.util.regex.Pattern.compile("(ldpi|mdpi|tvdpi|hdpi|xhdpi|xxhdpi|xxxhdpi|nodpi|anydpi|[0-9]+dpi|v[0-9]+)");

    /** True for a drawable's or a mipmap's name as {@code Resources.getResourceName} gives it ({@code pkg:type/entry}). */
    public static boolean resourceName(String name) {
        return name != null && name.length() <= 256 && RESOURCE_NAME.matcher(name).matches();
    }

    /**
     * The configuration a file of a resource is for, from its folder's qualifiers without density and version
     * ({@code res/mipmap-night-xxhdpi-v26/a.png} is {@code night}), so a night file never stands in for a day one.
     * Empty for a file without qualifiers (and for renamed paths, which carry none).
     */
    public static String variant(String file) {
        final int slash = file.lastIndexOf('/');
        if (slash <= 0) {
            return "";
        }
        final String dir = file.substring(file.lastIndexOf('/', slash - 1) + 1, slash);
        final String[] parts = dir.split("-");
        final StringBuilder variant = new StringBuilder();
        for (int i = 1; i < parts.length; i++) {
            if (!DENSITY_OR_VERSION.matcher(parts[i]).matches()) {
                variant.append(variant.length() == 0 ? "" : "-").append(parts[i]);
            }
        }
        return variant.toString();
    }

    /**
     * True for a zip entry name a gate may ask for: relative, without {@code ..}, NUL or backslash, and not a folder.
     * Any name the resource table names is allowed (resource obfuscators write {@code r/a.png}): the entry is only
     * read from the APK by its exact name and decoded in the VM.
     */
    public static boolean entryName(String file) {
        return file != null && !file.isEmpty() && file.length() < 1024 && file.charAt(0) != '/'
                && !file.endsWith("/") && !file.contains("..") && file.indexOf('\0') < 0
                && file.indexOf('\\') < 0;
    }

    /** The app of an identity, or null. */
    public static String ownerOf(String identity) {
        if (identity == null || !identity.startsWith("res:")) {
            return null;
        }
        final int end = identity.indexOf('/', 4);
        return end > 4 ? identity.substring(4, end) : null;
    }

    /** The APK path of a key, or null. */
    public static String apkOf(String key) {
        if (key == null || !key.startsWith(PREFIX)) {
            return null;
        }
        final int end = key.indexOf(':', PREFIX.length());
        return end > PREFIX.length() ? key.substring(PREFIX.length(), end) : null;
    }

    /**
     * True when {@code path} is an APK of the verified system image (signed partitions, verified at boot, or an APEX
     * verified by apexd), plain absolute paths only. Apps, updated system apps, and overlays and shared libraries
     * installed under /data are not. The same rule as the decode client's {@code SystemImagePaths}.
     */
    public static boolean isSystemImage(String path) {
        if (path == null || path.isEmpty() || path.charAt(0) != '/' || path.indexOf('\0') >= 0) {
            return false;
        }
        if (path.contains("//") || path.contains("/./") || path.contains("/../") || path.endsWith("/.")
                || path.endsWith("/..")) {
            return false;
        }
        for (String prefix : SYSTEM_IMAGE) {
            if (path.startsWith(prefix) && path.length() > prefix.length()) {
                return true;
            }
        }
        return false;
    }
}
