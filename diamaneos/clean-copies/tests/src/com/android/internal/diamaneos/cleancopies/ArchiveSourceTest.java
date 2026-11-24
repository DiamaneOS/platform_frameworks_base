// Copyright (C) 2026 DiamaneOS
package com.android.internal.diamaneos.cleancopies;

import static org.junit.Assert.*;
import org.junit.Test;

public class ArchiveSourceTest {
    @Test public void fallbackCarriesTheSelectedWorkUserToTheGateKey() {
        ArchiveSource<String> source = ArchiveSource.select(0, new int[] {0, 10},
                user -> user == 10 ? "work icon" : null);
        assertNotNull(source);
        assertEquals("work icon", source.state);
        assertEquals(10, source.user);
        assertEquals("u10,f1000", GateOwner.of(source.user, 1000, 100_000).key());
    }
    @Test public void theRequestedUsersStateWins() {
        ArchiveSource<String> source = ArchiveSource.select(0, new int[] {10, 0}, user -> "icon " + user);
        assertEquals(0, source.user);
        assertEquals("icon 0", source.state);
    }
    @Test public void noStateHasNoSource() {
        assertNull(ArchiveSource.select(0, new int[] {10}, user -> null));
    }
}
