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

import java.util.LinkedHashMap;
import java.util.Map;

/** Bounded item grants and package subscriptions, issued only after a resource request is authorized. */
public final class CopyRequests {
    public static final class Grant {
        public final String apk;
        public final String packageName;
        public final int user;
        public final long serial;
        public final String installer;
        public final int sessionId;

        public Grant(String apk, String packageName, int user, long serial) {
            this(apk, packageName, user, serial, null, -1);
        }

        public Grant(String apk, String packageName, int user, long serial, String installer, int sessionId) {
            this.installer = installer;
            this.sessionId = sessionId;
            this.apk = apk;
            this.packageName = packageName;
            this.user = user;
            this.serial = serial;
        }
    }

    private final LinkedHashMap<Integer, LinkedHashMap<String, Grant>> mCallers = new LinkedHashMap<>();

    public synchronized void add(int uid, String key, Grant grant) {
        if (key == null || grant == null) {
            return;
        }
        final LinkedHashMap<String, Grant> items = mCallers.computeIfAbsent(uid, k -> new LinkedHashMap<>());
        items.remove(key);
        items.put(key, grant);
        if (items.size() > 1024) {
            items.remove(items.keySet().iterator().next());
        }
        if (mCallers.size() > 128) {
            mCallers.remove(mCallers.keySet().iterator().next());
        }
    }

    public synchronized Grant get(int uid, String key) {
        final Map<String, Grant> items = mCallers.get(uid);
        return items == null ? null : items.get(key);
    }

    public synchronized boolean interested(int uid, String pkg, int user, long serial) {
        final Map<String, Grant> items = mCallers.get(uid);
        if (items == null) {
            return false;
        }
        for (Grant grant : items.values()) {
            if (grant.user == user && grant.serial == serial && grant.packageName.equals(pkg)) {
                return true;
            }
        }
        return false;
    }

    /** Snapshot of the exact grants whose current authority can justify one named notice. */
    public synchronized java.util.List<Grant> grants(int uid, String pkg, int user, long serial) {
        final java.util.List<Grant> matches = new java.util.ArrayList<>();
        final Map<String, Grant> items = mCallers.get(uid);
        if (items != null) {
            for (Grant grant : items.values()) {
                if (grant.user == user && grant.serial == serial && grant.packageName.equals(pkg)) matches.add(grant);
            }
        }
        return matches;
    }

    public synchronized void removeUser(int user) {
        for (Map<String, Grant> items : mCallers.values()) {
            items.values().removeIf(grant -> grant.user == user);
        }
    }

    public synchronized void clear() {
        mCallers.clear();
    }
}
