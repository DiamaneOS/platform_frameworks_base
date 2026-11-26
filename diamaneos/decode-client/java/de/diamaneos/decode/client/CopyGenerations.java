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

/** User incarnations for copy publication. Called only under the store's publication lock. */
public final class CopyGenerations {
    public static final class Token {
        public final int user;
        public final long serial;
        private boolean retired;

        private Token(int user, long serial) {
            this.user = user;
            this.serial = serial;
        }
    }

    private final Map<Integer, Token> mUsers = new HashMap<>();

    public Token begin(int user, long serial) {
        if (user < 0 || serial < 0) {
            return null;
        }
        Token token = mUsers.get(user);
        if (token == null || token.serial != serial) {
            if (token != null) {
                token.retired = true;
            }
            token = new Token(user, serial);
            mUsers.put(user, token);
        }
        return token.retired ? null : token;
    }

    public boolean current(Token token) {
        return token != null && !token.retired && mUsers.get(token.user) == token;
    }

    public void remove(int user) {
        final Token token = mUsers.get(user);
        if (token != null) {
            token.retired = true;
        }
    }
}
