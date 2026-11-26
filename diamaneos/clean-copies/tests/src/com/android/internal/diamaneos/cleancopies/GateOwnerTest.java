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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Whose image a decode gate request is: what is captured, and that owners never share a key. */
public class GateOwnerTest {
    private static final int RANGE = 100_000;

    @Test
    public void aScopeNamesTheUserWhateverTheThread() {
        // The user switching dialog on the system server's UI thread, switching to user 11.
        final GateOwner dialog = GateOwner.of(11, GateOwner.UNKNOWN, RANGE);
        assertEquals(11, dialog.user);
        assertEquals("its own work: no app it decodes for", GateOwner.UNKNOWN, dialog.forUid);
    }

    @Test
    public void aScopeWinsOverTheBinderCallersUserAndKeepsTheDelegate() {
        // SystemUI (user 0) asks the system server for a work profile task's snapshot.
        final GateOwner owner = GateOwner.of(10, 10_123, RANGE);
        assertEquals(10, owner.user);
        assertEquals("the delegate stays the binder caller", 10_123, owner.forUid);
    }

    @Test
    public void anIncomingCallNamesItsCallersUserAndUid() {
        final GateOwner owner = GateOwner.of(GateOwner.UNKNOWN, 1_010_057, RANGE);
        assertEquals(10, owner.user);
        assertEquals(1_010_057, owner.forUid);
    }

    @Test
    public void nothingKnownStaysUnknown() {
        final GateOwner owner = GateOwner.of(GateOwner.UNKNOWN, GateOwner.UNKNOWN, RANGE);
        assertEquals(GateOwner.UNKNOWN, owner.user);
        assertEquals(GateOwner.UNKNOWN, owner.forUid);
    }

    @Test
    public void ownersNeverShareAKey() {
        final GateOwner[] owners = {
            GateOwner.of(0, GateOwner.UNKNOWN, RANGE),
            GateOwner.of(10, GateOwner.UNKNOWN, RANGE),
            GateOwner.of(GateOwner.UNKNOWN, 10_057, RANGE),
            GateOwner.of(GateOwner.UNKNOWN, 1_010_057, RANGE),
            GateOwner.of(10, 10_057, RANGE),
            GateOwner.of(GateOwner.UNKNOWN, GateOwner.UNKNOWN, RANGE),
        };
        for (int i = 0; i < owners.length; i++) {
            for (int j = 0; j < owners.length; j++) {
                if (i != j) {
                    assertNotEquals(owners[i].key(), owners[j].key());
                    assertFalse(owners[i].equals(owners[j]));
                }
            }
        }
        assertTrue(GateOwner.of(10, 5, RANGE).equals(GateOwner.of(10, 5, RANGE)));
        assertEquals(GateOwner.of(10, 5, RANGE).key(), GateOwner.of(10, 5, RANGE).key());
    }
    @org.junit.Test
    public void aRegionKeepsItsSourceUserWhenTheScopeOrConsumerChanges() {
        final GateOwner source = GateOwner.of(10, 1000, 100_000);
        org.junit.Assert.assertEquals(10, source.forSource(2000, 100_000).user);
        org.junit.Assert.assertEquals(2000, source.forSource(2000, 100_000).forUid);
        org.junit.Assert.assertEquals(10, source.forSource(-1, 100_000).user);
        org.junit.Assert.assertEquals(1000, source.forSource(-1, 100_000).forUid);
    }
    @org.junit.Test
    public void onlyUserAwareUriSchemesSelectTheResolversUser() {
        org.junit.Assert.assertEquals(10, GateOwner.uriUser("content", 10));
        org.junit.Assert.assertEquals(10, GateOwner.uriUser("android.resource", 10));
        org.junit.Assert.assertEquals(GateOwner.UNKNOWN, GateOwner.uriUser("file", 10));
        org.junit.Assert.assertEquals(GateOwner.UNKNOWN, GateOwner.uriUser(null, 10));
    }

    @org.junit.Test
    public void unresolvedContentUsersNeverFallBackToTheCaller() {
        for (int user : new int[] {-2, -10000, 100_000}) {
            try {
                GateOwner.uriUser("content", user);
                org.junit.Assert.fail("an unresolved source user cannot become the caller's user");
            } catch (IllegalArgumentException expected) {
                // No source identity was established.
            }
        }
    }
}
