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
 * Whose image a request of the decode gate is (DecodeGate), captured once on the thread that makes the request and
 * carried unchanged through the gate's sender, its answers kept and its requests under way, to the system server's
 * provider: {@link #user}, the Android user whose image it is, and {@link #forUid}, the process the system server
 * decodes it for. A component that knows the user names it in a scope (the user switching dialog, another user's
 * icon): that wins. In the system server an incoming binder call names its caller's user and uid. Else nothing is
 * known here ({@link #UNKNOWN}): the provider decides from the binder identity it sees, or, for the system server's own
 * work, keeps it apart from every user's. Outside the system server an image of another user also names its
 * {@link #source}, which the provider acquires itself. Plain Java, tested on the host.
 *
 * @hide
 */
public final class GateOwner {
    /** Not known here. */
    public static final int UNKNOWN = -1;

    /** The Android user whose image it is, or {@link #UNKNOWN}. */
    public final int user;
    /** The uid of the process the system server decodes it for, or {@link #UNKNOWN} (its own work). */
    public final int forUid;
    /**
     * Where the system server acquires the image of another user itself ({@code uri:}, {@code uri-image:} or
     * {@code usericon:}), or null. A remote caller's own bytes are never taken for another user.
     */
    public final String source;

    private GateOwner(int user, int forUid) {
        this(user, forUid, null);
    }

    private GateOwner(int user, int forUid, String source) {
        this.user = user;
        this.forUid = forUid;
        this.source = source;
    }

    /** The same owner, its image acquired by the system server from {@code source} (null: none). */
    public GateOwner withSource(String source) {
        return new GateOwner(user, forUid, source);
    }

    /**
     * The owner of a request: {@code scopedUser} when a scope names one (at least 0), else the user of
     * {@code callingUid} (the incoming binder call's uid, {@link #UNKNOWN} outside one), with {@code perUserRange} uids
     * per user. The delegate is the binder caller whatever the scope.
     */
    public static GateOwner of(int scopedUser, int callingUid, int perUserRange) {
        final int user = scopedUser >= 0 ? scopedUser
                : callingUid >= 0 ? callingUid / perUserRange : UNKNOWN;
        return new GateOwner(user, callingUid >= 0 ? callingUid : UNKNOWN);
    }

    /** The user encoded by a resolver-backed source, or UNKNOWN for a process file. */
    public static int uriUser(String scheme, int resolvedUser) {
        if (!"content".equals(scheme) && !"android.resource".equals(scheme)) return UNKNOWN;
        if (resolvedUser < 0 || resolvedUser >= 100_000) {
            throw new IllegalArgumentException("the source URI has no concrete Android user");
        }
        return resolvedUser;
    }

    /** Retains the source user and binds a subsequent operation to its current delegate. */
    public GateOwner forSource(int callingUid, int perUserRange) {
        return new GateOwner(user, callingUid >= 0 ? callingUid : forUid, source);
    }

    /** The part of every cache and request key: answers are never shared between owners. */
    public String key() {
        return "u" + user + ",f" + forUid + (source == null ? "" : ",s" + source);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof GateOwner && ((GateOwner) o).user == user && ((GateOwner) o).forUid == forUid
                && java.util.Objects.equals(((GateOwner) o).source, source);
    }

    @Override
    public int hashCode() {
        return 31 * (31 * user + forUid) + java.util.Objects.hashCode(source);
    }

    @Override
    public String toString() {
        return key();
    }
}
