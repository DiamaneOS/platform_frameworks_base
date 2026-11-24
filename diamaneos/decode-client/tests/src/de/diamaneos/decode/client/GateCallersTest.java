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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** The decode gate's principals, names and lanes, and the lifetime of its caller decisions. */
@RunWith(JUnit4.class)
public class GateCallersTest {
    // The system uid runs many packages: Settings among them, never first.
    private static final String[] SYSTEM = {"com.android.location.fused", "com.android.settings", "android"};

    @Test
    public void aSharedUidIsNamedByItsAttributedPackageNeverItsFirst() {
        assertEquals("com.android.settings", GateCallers.display(1000, "com.android.settings", SYSTEM));
        assertEquals("nothing attributed: the uid", "uid1000", GateCallers.display(1000, null, SYSTEM));
        assertEquals("a package of another uid named: the uid", "uid1000",
                GateCallers.display(1000, "com.example.other", SYSTEM));
    }

    @Test
    public void aUidWithOnePackageIsNamedByIt() {
        assertEquals("com.android.systemui",
                GateCallers.display(10_050, null, new String[] {"com.android.systemui"}));
        assertEquals("uid10050", GateCallers.display(10_050, null, new String[0]));
        assertEquals("uid10050", GateCallers.display(10_050, null, null));
    }

    @Test
    public void theSamePackageInTwoUsersIsTwoPrincipalsAndLanes() {
        final String[] settings0 = {"com.android.settings"};
        final String a = GateCallers.lane(1000, GateCallers.display(1000, "com.android.settings", SYSTEM), "bytes");
        final String b = GateCallers.lane(1_001_000,
                GateCallers.display(1_001_000, "com.android.settings", settings0), "bytes");
        assertNotEquals(a, b);
        assertNotEquals(GateCallers.principal(1000), GateCallers.principal(1_001_000));
    }

    @Test
    public void packagesOfOneUidShareOnePrincipal() {
        // Whatever package a caller of the system uid names, it is charged as the system uid.
        assertEquals(GateCallers.principal(1000), GateCallers.principal(1000));
        assertEquals("?gate:uid1000",
                GateCallers.lane(1000, GateCallers.display(1000, "com.android.settings", SYSTEM), "bytes"));
        assertTrue(GateCallers.lane(1000, "uid1000", "media").startsWith("?gate:"));
    }


    @Test
    public void displayAndOriginNeverCreateAnotherQuotaLane() {
        assertEquals(GateCallers.lane(1000, "com.android.settings", "bytes"),
                GateCallers.lane(1000, "android", "media"));
        assertNotEquals(GateCallers.lane(1000, "android", "media"),
                GateCallers.lane(1_001_000, "android", "media"));
    }

    @Test
    public void decisionsGoWithTheirPackagesAndUsers() {
        final GateCallers callers = new GateCallers();
        callers.allow(1_010_050, new String[] {"com.android.systemui"});
        callers.allow(10_050, new String[] {"com.android.systemui"});
        callers.refuse(10_200, 50_000);
        assertNotNull(callers.allowed(1_010_050));
        assertTrue(callers.refused(10_200, 1_000));
        assertFalse("refusals expire", callers.refused(10_200, 50_000));
        callers.refuse(10_200, 50_000);
        callers.forgetUid(10_200);
        assertFalse("a package of the uid changed", callers.refused(10_200, 1_000));
        callers.forgetUser(10, 100_000);
        assertNull("its user went", callers.allowed(1_010_050));
        assertNotNull("other users keep theirs", callers.allowed(10_050));
        callers.forgetAll();
        assertNull(callers.allowed(10_050));
    }
    @Test
    public void aSystemUidPeerCannotLabelItsDescriptorAsAnotherUsers() {
        assertEquals(0, GateCallers.unverifiedUser(1000, -1, 100_000));
        assertEquals(0, GateCallers.unverifiedUser(1000, 0, 100_000));
        assertEquals(-1, GateCallers.unverifiedUser(1000, 10, 100_000));
        assertEquals(-1, GateCallers.unverifiedUser(1000, 100_000, 100_000));
        assertEquals(10, GateCallers.unverifiedUser(1_001_000, 10, 100_000));
    }
}
