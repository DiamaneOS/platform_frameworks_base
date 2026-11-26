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

/**
 * Direct readback is local to one user unless the caller is the verified server or debug root. Cross-profile
 * presentation requires a separate item grant and a current rendering role, checked by the maker on every access.
 * Plain Java, tested on the host.
 */
public final class CopyAudience {
    private CopyAudience() {
    }

    /** Direct readback for the same user or an independently verified server process. */
    public static boolean mayServe(int callerUser, boolean trustedServer, int user, int parentOfUser) {
        return user >= 0 && (trustedServer || user == callerUser);
    }

    /**
     * Candidate users whose launcher may be told that copies for {@code user} were made: that user and, for a profile, its
     * parent. The maker still checks the rendering role, subscription and profile state before delivery.
     */
    public static int[] toldUsers(int user, int parentOfUser) {
        if (user < 0) {
            return new int[0];
        }
        return parentOfUser >= 0 && parentOfUser != user ? new int[] {user, parentOfUser} : new int[] {user};
    }
    /** A watcher retains the registering process, including the server's exact PID. */
    public static final class Subscriber {
        public final int uid;
        private final int mPid;

        public Subscriber(int uid, int pid) {
            this.uid = uid;
            mPid = pid;
        }

        public boolean mayNotify(int serverPid, boolean admitted, boolean itemAllowed) {
            return admitted && ((uid == 1000 && mPid == serverPid && serverPid > 0) || itemAllowed);
        }
    }
}
