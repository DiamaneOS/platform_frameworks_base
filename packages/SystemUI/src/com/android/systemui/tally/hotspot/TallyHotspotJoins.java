/*
 * Copyright (C) 2026 The DiamaneOS Project
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

package com.android.systemui.tally.hotspot;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Decides when to tell the user that a device joined the Wi-Fi hotspot, and what to say.
 *
 * <p>A device is its MAC address. One that is new in this hotspot session is announced with
 * sound. A known one that comes back is announced silently, and only after
 * {@link #AWAY_MILLIS} away. A name is only what the device claims and never decides anything.
 * Not thread-safe.
 */
final class TallyHotspotJoins {
    /** No notice is due. */
    static final long NONE = -1;
    /** Lets the name arrive and devices that join together share one notice. */
    static final long SETTLE_MILLIS = 5_000;
    /** Shortest time between two notices. */
    static final long MIN_GAP_MILLIS = 60_000;
    /** A known device that comes back sooner is not announced: it may reconnect all the time. */
    static final long AWAY_MILLIS = 5 * 60_000;
    /** Bounds memory when a device keeps changing its address. */
    static final int MAX_REMEMBERED = 256;
    static final int MAX_NAMES = 3;
    static final int MAX_NAME_LENGTH = 40;

    static final class Notice {
        /** Devices that joined since the last notice. */
        final int joined;
        /** Whether one of them is new in this session. If so, the notice makes a sound. */
        final boolean alerts;
        /** Their claimed names, cleaned. May be empty. */
        final List<String> names;
        final int connected;

        Notice(int joined, boolean alerts, List<String> names, int connected) {
            this.joined = joined;
            this.alerts = alerts;
            this.names = names;
            this.connected = connected;
        }
    }

    private static final long CONNECTED = -1;

    /** Addresses seen in this hotspot session, each with the time it left or {@link #CONNECTED}. */
    private final Map<String, Long> mSeen = new HashMap<>();
    /** Joined and not announced yet, oldest first. */
    private final Set<String> mPending = new LinkedHashSet<>();
    /** Can be more than {@link #mPending} holds. */
    private int mJoined;
    private boolean mNewJoined;
    private boolean mBaselineDue;
    private int mConnected;
    private long mLastNoticeAt = NONE;
    private long mDueAt = NONE;

    /** The next list of connected devices counts as already known. */
    void start() {
        stop();
        mBaselineDue = true;
    }

    void stop() {
        mSeen.clear();
        mPending.clear();
        mJoined = 0;
        mNewJoined = false;
        mBaselineDue = false;
        mConnected = 0;
        mLastNoticeAt = NONE;
        mDueAt = NONE;
    }

    /** Ends the session. A join not announced yet still will be. */
    void onHotspotOff() {
        mSeen.clear();
        mBaselineDue = false;
        mConnected = 0;
    }

    /**
     * Takes the devices connected now. Returns when a notice is due, or {@link #NONE}.
     * {@code now} comes from a clock that never goes back.
     */
    long onClients(Collection<String> macs, long now) {
        final Set<String> current = new HashSet<>();
        if (macs != null) {
            for (String mac : macs) {
                if (mac != null && !mac.isEmpty()) current.add(normalize(mac));
            }
        }
        mConnected = current.size();
        if (mBaselineDue) {
            mBaselineDue = false;
            for (String mac : current) {
                mSeen.put(mac, CONNECTED);
            }
            return mDueAt;
        }
        for (String mac : current) {
            final Long leftAt = mSeen.put(mac, CONNECTED);
            if (leftAt == null) {
                mNewJoined = true;
            } else if (leftAt == CONNECTED || now - leftAt < AWAY_MILLIS
                    || mPending.contains(mac)) {
                continue;
            }
            if (mJoined < Integer.MAX_VALUE) mJoined++;
            if (mPending.size() < MAX_REMEMBERED) mPending.add(mac);
        }
        for (Map.Entry<String, Long> seen : mSeen.entrySet()) {
            if (seen.getValue() == CONNECTED && !current.contains(seen.getKey())) {
                seen.setValue(now);
            }
        }
        if (mSeen.size() > MAX_REMEMBERED) {
            // Too many to remember. An address that left and comes back counts as new.
            mSeen.keySet().retainAll(current);
        }
        if (mJoined > 0 && mDueAt == NONE) {
            mDueAt = now + SETTLE_MILLIS;
            if (mLastNoticeAt != NONE && mLastNoticeAt + MIN_GAP_MILLIS > mDueAt) {
                mDueAt = mLastNoticeAt + MIN_GAP_MILLIS;
            }
        }
        return mDueAt;
    }

    /** For tests. */
    int remembered() {
        return mSeen.size();
    }

    long dueAt() {
        return mDueAt;
    }

    /**
     * Returns the notice to show now, once, or null if none is due. {@code claimedNames} is
     * keyed by {@link #normalize}d address and may be null.
     */
    Notice takeNotice(long now, Map<String, String> claimedNames) {
        if (mDueAt == NONE || now < mDueAt) return null;
        final List<String> names = new ArrayList<>();
        if (claimedNames != null) {
            for (String mac : mPending) {
                if (names.size() >= MAX_NAMES) break;
                final String name = cleanName(claimedNames.get(mac));
                if (name != null && !names.contains(name)) names.add(name);
            }
        }
        final Notice notice =
                new Notice(mJoined, mNewJoined, Collections.unmodifiableList(names), mConnected);
        mPending.clear();
        mJoined = 0;
        mNewJoined = false;
        mLastNoticeAt = now;
        mDueAt = NONE;
        return notice;
    }

    static String normalize(String mac) {
        return mac.toLowerCase(Locale.ROOT);
    }

    /**
     * Makes a claimed name safe to show on one line, by the same rules as the hotspot page in
     * Settings: no characters that draw nothing or reorder text, such as bidi overrides, and at
     * most {@link #MAX_NAME_LENGTH}. Returns null if nothing is left.
     */
    static String cleanName(String claimed) {
        if (claimed == null) return null;
        final StringBuilder out = new StringBuilder();
        boolean spaceDue = false;
        for (int i = 0; i < claimed.length(); ) {
            final int codePoint = claimed.codePointAt(i);
            i += Character.charCount(codePoint);
            if (Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)) {
                spaceDue = out.length() > 0;
                continue;
            }
            if (isHidden(codePoint)) continue;
            if (spaceDue) {
                out.append(' ');
                spaceDue = false;
            }
            out.appendCodePoint(codePoint);
        }
        if (out.length() == 0) return null;
        if (out.codePointCount(0, out.length()) <= MAX_NAME_LENGTH) return out.toString();
        final String cut = out.substring(0, out.offsetByCodePoints(0, MAX_NAME_LENGTH));
        return cut.trim() + "…";
    }

    private static boolean isHidden(int codePoint) {
        switch (Character.getType(codePoint)) {
            case Character.CONTROL:
            case Character.FORMAT:
            case Character.SURROGATE:
            case Character.PRIVATE_USE:
            case Character.UNASSIGNED:
                return true;
            default:
                return false;
        }
    }
}
