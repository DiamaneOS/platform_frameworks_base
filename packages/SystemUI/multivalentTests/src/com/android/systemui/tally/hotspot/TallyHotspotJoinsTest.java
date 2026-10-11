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

import static com.android.systemui.tally.hotspot.TallyHotspotJoins.AWAY_MILLIS;
import static com.android.systemui.tally.hotspot.TallyHotspotJoins.MIN_GAP_MILLIS;
import static com.android.systemui.tally.hotspot.TallyHotspotJoins.NONE;
import static com.android.systemui.tally.hotspot.TallyHotspotJoins.SETTLE_MILLIS;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.android.systemui.tally.hotspot.TallyHotspotJoins.Notice;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Plain JUnit: {@link TallyHotspotJoins} uses no Android classes. */
public class TallyHotspotJoinsTest {
    private static final String A = "a2:00:00:00:00:01";
    private static final String B = "a2:00:00:00:00:02";
    private static final String C = "a2:00:00:00:00:03";
    private static final long T0 = 1_000_000;

    private TallyHotspotJoins mJoins;

    @Before
    public void setUp() {
        mJoins = new TallyHotspotJoins();
    }

    /** Listening starts with the hotspot on and nobody connected. */
    private void startOnEmptyHotspot() {
        mJoins.start();
        assertEquals(NONE, mJoins.onClients(Collections.<String>emptyList(), T0));
    }

    private static List<String> macs(String... macs) {
        return Arrays.asList(macs);
    }

    private static Map<String, String> names(String... macThenName) {
        final Map<String, String> names = new HashMap<>();
        for (int i = 0; i < macThenName.length; i += 2) {
            names.put(macThenName[i], macThenName[i + 1]);
        }
        return names;
    }

    @Test
    public void devicesConnectedWhenListeningStarts_areNotAnnounced() {
        mJoins.start();

        assertEquals(NONE, mJoins.onClients(macs(A, B), T0));

        assertEquals(NONE, mJoins.dueAt());
        assertNull(mJoins.takeNotice(T0 + MIN_GAP_MILLIS, null));
    }

    @Test
    public void hotspotOffWhenListeningStarts_firstDeviceIsAnnounced() {
        mJoins.start();
        mJoins.onHotspotOff();

        // No empty list came first: the first device must not be taken for one already there.
        assertEquals(T0 + SETTLE_MILLIS, mJoins.onClients(macs(A), T0));
    }

    @Test
    public void devicesJoiningTogether_shareOneNotice() {
        startOnEmptyHotspot();
        final long due = mJoins.onClients(macs(A), T0);

        // The second and third do not move the time.
        assertEquals(due, mJoins.onClients(macs(A, B), T0 + 2_000));
        assertEquals(due, mJoins.onClients(macs(A, B, C), T0 + 4_000));

        final Notice notice = mJoins.takeNotice(due, null);
        assertEquals(3, notice.joined);
        assertEquals(3, notice.connected);
    }

    @Test
    public void names_areCleanedGivenOnceAndAtMostThree() {
        startOnEmptyHotspot();
        final List<String> all = new ArrayList<>();
        final Map<String, String> names = new HashMap<>();
        for (int i = 0; i < 6; i++) {
            final String mac = "a2:00:00:00:01:0" + i;
            all.add(mac);
            mJoins.onClients(new ArrayList<>(all), T0 + i);
            // Two devices claim the same name; one claims a name with a line break in it.
            names.put(mac, i < 2 ? "Same" : i == 2 ? "Two\nlines" : "Device " + i);
        }

        final Notice notice = mJoins.takeNotice(T0 + SETTLE_MILLIS, names);

        assertEquals(6, notice.joined);
        assertEquals(Arrays.asList("Same", "Two lines", "Device 3"), notice.names);
    }

    @Test
    public void knownDeviceBackSooner_isNotAnnounced() {
        startOnEmptyHotspot();
        mJoins.onClients(macs(A), T0);
        mJoins.takeNotice(T0 + SETTLE_MILLIS, null);

        final long left = T0 + 10_000;
        mJoins.onClients(Collections.<String>emptyList(), left);

        assertEquals(NONE, mJoins.onClients(macs("A2:00:00:00:00:01"), left + AWAY_MILLIS - 1));
        // The time away counts from when it last left, not from when it first left.
        mJoins.onClients(Collections.<String>emptyList(), left + AWAY_MILLIS);
        assertEquals(NONE, mJoins.onClients(macs(A), left + 2 * AWAY_MILLIS - 1));
    }

    @Test
    public void knownDeviceBackAfterFiveMinutes_getsASilentNotice() {
        mJoins.start();
        mJoins.onClients(macs(A), T0);
        mJoins.onClients(Collections.<String>emptyList(), T0 + 10_000);

        final long back = T0 + 10_000 + AWAY_MILLIS;
        assertEquals(back + SETTLE_MILLIS, mJoins.onClients(macs(A), back));

        final Notice notice = mJoins.takeNotice(back + SETTLE_MILLIS, null);
        assertEquals(1, notice.joined);
        assertFalse(notice.alerts);
    }

    @Test
    public void noticeWithANewDevice_alerts() {
        mJoins.start();
        mJoins.onClients(macs(A), T0);
        mJoins.onClients(Collections.<String>emptyList(), T0 + 10_000);

        // A known device comes back together with a new one.
        final long back = T0 + 10_000 + AWAY_MILLIS;
        mJoins.onClients(macs(A, B), back);

        final Notice notice = mJoins.takeNotice(back + SETTLE_MILLIS, null);
        assertEquals(2, notice.joined);
        assertTrue(notice.alerts);
    }

    @Test
    public void joinsSoonAfterANotice_waitForTheGapAndShareOneNotice() {
        startOnEmptyHotspot();
        mJoins.onClients(macs(A), T0);
        final long first = T0 + SETTLE_MILLIS;
        mJoins.takeNotice(first, null);

        // One device that takes a new address for every connection.
        final long due = mJoins.onClients(macs(B), first + 10_000);
        assertEquals(first + MIN_GAP_MILLIS, due);
        assertEquals(due, mJoins.onClients(Collections.<String>emptyList(), first + 20_000));
        assertEquals(due, mJoins.onClients(macs(C), first + 30_000));

        assertNull(mJoins.takeNotice(due - 1, null));
        final Notice notice = mJoins.takeNotice(due, names(B, "Tablet", C, "Tablet"));
        assertEquals(2, notice.joined);
        assertEquals(1, notice.connected);
        assertEquals(Arrays.asList("Tablet"), notice.names);
    }

    @Test
    public void hotspotTurnedOnAgain_isANewSession() {
        startOnEmptyHotspot();
        mJoins.onClients(macs(A), T0);
        mJoins.takeNotice(T0 + SETTLE_MILLIS, null);
        mJoins.onHotspotOff();

        final long later = T0 + 10 * MIN_GAP_MILLIS;

        // No baseline this time: the hotspot started while listening, so everyone is new.
        assertEquals(later + SETTLE_MILLIS, mJoins.onClients(macs(A), later));
    }

    @Test
    public void manyAddresses_areAllCountedAndMemoryStaysBounded() {
        startOnEmptyHotspot();
        final int churned = 3 * TallyHotspotJoins.MAX_REMEMBERED;
        for (int i = 0; i < churned; i++) {
            final String mac = String.format("a2:00:00:%02x:%02x:%02x", i >> 16, (i >> 8) & 0xff,
                    i & 0xff);
            mJoins.onClients(macs(mac), T0 + i);
            assertTrue(mJoins.remembered() <= TallyHotspotJoins.MAX_REMEMBERED);
        }

        final Notice notice = mJoins.takeNotice(T0 + SETTLE_MILLIS, null);

        assertEquals(churned, notice.joined);
        assertEquals(1, notice.connected);
    }

    @Test
    public void cleanName_dropsWhatDrawsNothingOrReordersText() {
        assertEquals("evil", TallyHotspotJoins.cleanName("‮evil‬"));
        assertEquals("ab", TallyHotspotJoins.cleanName("a​b\u0000"));
        assertEquals("a b", TallyHotspotJoins.cleanName("  a \t\r\n b  "));
        assertEquals("a b", TallyHotspotJoins.cleanName("a b"));
        assertNull(TallyHotspotJoins.cleanName(null));
        assertNull(TallyHotspotJoins.cleanName(""));
        assertNull(TallyHotspotJoins.cleanName(" ‎\u0007 "));
    }

    @Test
    public void cleanName_cutsLongNamesWithoutSplittingACharacter() {
        final StringBuilder longName = new StringBuilder();
        for (int i = 0; i < 60; i++) {
            longName.append("😀");
        }

        final String cut = TallyHotspotJoins.cleanName(longName.toString());

        assertEquals(TallyHotspotJoins.MAX_NAME_LENGTH + 1, cut.codePointCount(0, cut.length()));
        assertTrue(cut.endsWith("😀…"));
        final String exact = "0123456789012345678901234567890123456789";
        assertEquals(exact, TallyHotspotJoins.cleanName(exact));
        assertEquals(exact + "…", TallyHotspotJoins.cleanName(exact + "x"));
    }
}
