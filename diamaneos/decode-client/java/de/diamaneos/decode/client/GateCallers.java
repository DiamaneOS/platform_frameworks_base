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
import java.util.Iterator;
import java.util.Map;

/**
 * Who the system server's decode gate (VmDecodeProvider) serves and charges. The principal of a request is the full
 * calling uid: packages that share a uid are one principal, the same package in two users is two. A display name
 * uses a validated transport attribution or an unambiguous package. The quota lane always uses the full UID alone.
 * An origin only orders work inside the provider's UID queue. The callers allowed or refused
 * are kept per uid until a package of that uid or its user changes ({@link #forgetUid}, {@link #forgetUser}). Plain
 * Java, tested on the host.
 */
public final class GateCallers {
    private final Map<Integer, String[]> mAllowed = new HashMap<>(); // guarded by this
    private final Map<Integer, Long> mRefusedUntil = new HashMap<>(); // guarded by this

    /** The principal's key of the calling uid: the provider's outer queue. */
    public static String principal(int uid) {
        return "uid" + uid;
    }

    /**
     * The name shown for {@code uid}: {@code attributed} (the transport's package) when the uid runs it, the uid's
     * only package, else "uid" and the number.
     */
    public static String display(int uid, String attributed, String[] packages) {
        if (packages != null) {
            if (attributed != null) {
                for (String p : packages) {
                    if (attributed.equals(p)) {
                        return attributed;
                    }
                }
            }
            if (packages.length == 1 && packages[0] != null && !packages[0].isEmpty()) {
                return packages[0];
            }
        }
        return principal(uid);
    }

    /**
     * The decode service's lane of a gate request: a lane of no app of its own (VmDecode.unknownOwner), named by the
     * full UID alone. Display names and origins never divide its quota.
     */
    public static String lane(int uid, String display, String origin) {
        return "?gate:" + principal(uid);
    }

    /** The source user allowed for a descriptor the remote caller supplies itself. */
    public static int unverifiedUser(int uid, int named, int perUserRange) {
        final int user = uid / perUserRange;
        return named < 0 || named == user ? user : -1;
    }

    /** The packages of an allowed uid, or null when it is not known (or no longer known). */
    public synchronized String[] allowed(int uid) {
        return mAllowed.get(uid);
    }

    /** True while {@code uid} is refused without a lookup. */
    public synchronized boolean refused(int uid, long nowMs) {
        final Long until = mRefusedUntil.get(uid);
        if (until == null) {
            return false;
        }
        if (nowMs >= until) {
            mRefusedUntil.remove(uid);
            return false;
        }
        return true;
    }

    /** {@code uid} is allowed, running {@code packages}. */
    public synchronized void allow(int uid, String[] packages) {
        mRefusedUntil.remove(uid);
        mAllowed.put(uid, packages);
    }

    /** {@code uid} is refused until {@code untilMs}. */
    public synchronized void refuse(int uid, long untilMs) {
        mAllowed.remove(uid);
        mRefusedUntil.put(uid, untilMs);
    }

    /** A package of {@code uid} was added, removed, replaced or changed: it is looked up again. */
    public synchronized void forgetUid(int uid) {
        mAllowed.remove(uid);
        mRefusedUntil.remove(uid);
    }

    /** User {@code userId} was removed (or changed): every uid of it is looked up again. */
    public synchronized void forgetUser(int userId, int perUserRange) {
        forgetUser(mAllowed, userId, perUserRange);
        forgetUser(mRefusedUntil, userId, perUserRange);
    }

    /** Everything is looked up again. */
    public synchronized void forgetAll() {
        mAllowed.clear();
        mRefusedUntil.clear();
    }

    private static void forgetUser(Map<Integer, ?> map, int userId, int perUserRange) {
        for (Iterator<Integer> it = map.keySet().iterator(); it.hasNext(); ) {
            if (it.next() / perUserRange == userId) {
                it.remove();
            }
        }
    }
}
