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

/**
 * Coalesced copy notices belong to the exact user token that produced their pixels.
 * Caller holds the maker lock.
 */
public final class CopyNotices {
    private final Map<String, CopyGenerations.Token> mPending = new HashMap<>();

    public boolean mark(String pkg, CopyGenerations.Token token) {
        final String key = pkg + '/' + token.user;
        if (mPending.get(key) == token) {
            return false;
        }
        mPending.put(key, token);
        return true;
    }

    public boolean take(String pkg, CopyGenerations.Token token) {
        return mPending.remove(pkg + '/' + token.user, token);
    }

    public void removeUser(int user) {
        mPending.values().removeIf(token -> token.user == user);
    }
}
