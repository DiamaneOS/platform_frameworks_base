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

/** Installed base and split paths, each bound to the users that actually have that path installed. */
public final class CopyPackages {
    private final Map<String, Map<Integer, String>> mPaths = new HashMap<>();

    public void add(String apk, String packageName, int user) {
        if (apk != null && user >= 0) {
            mPaths.computeIfAbsent(apk, k -> new HashMap<>()).put(user, packageName);
        }
    }

    public String packageOf(String apk, int user) {
        final Map<Integer, String> users = mPaths.get(apk);
        return users == null ? null : users.get(user);
    }

    public String anyPackageOf(String apk) {
        final Map<Integer, String> users = mPaths.get(apk);
        return users == null || users.isEmpty() ? null : users.values().iterator().next();
    }

    public boolean contains(String apk) {
        return mPaths.containsKey(apk);
    }
    /** The actual ApplicationInfo for the requested user still selects this installed base or split. */
    public static boolean selectedPath(String apk, String base, String[] splits, boolean installed) {
        return installed && apk != null && (apk.equals(base)
                || (splits != null && java.util.Arrays.asList(splits).contains(apk)));
    }

}
