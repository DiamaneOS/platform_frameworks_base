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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Who may see a user's clean copies: the store's reads, asks, watchers and the shortcut broker go by this. */
@RunWith(JUnit4.class)
public class CopyAudienceTest {
    private static final int PERSONAL = 0;
    private static final int WORK = 10; // a work profile
    private static final int SECONDARY = 11; // another full user
    private static final int NO_PARENT = -1;

    @Test
    public void ownUserDoesNotImplyItsProfilesStore() {
        assertTrue(CopyAudience.mayServe(PERSONAL, false, PERSONAL, NO_PARENT));
        assertFalse("parentage is not a readback grant", CopyAudience.mayServe(PERSONAL, false, WORK, PERSONAL));
        assertTrue(CopyAudience.mayServe(WORK, false, WORK, PERSONAL));
    }

    @Test
    public void neverTheParent_neverAnotherUser() {
        assertFalse("a work process never sees the parent's copies",
                CopyAudience.mayServe(WORK, false, PERSONAL, NO_PARENT));
        assertFalse(CopyAudience.mayServe(PERSONAL, false, SECONDARY, NO_PARENT));
        assertFalse(CopyAudience.mayServe(SECONDARY, false, PERSONAL, NO_PARENT));
        assertFalse("a profile of another user", CopyAudience.mayServe(SECONDARY, false, WORK, PERSONAL));
        assertFalse("no user", CopyAudience.mayServe(PERSONAL, false, -1, NO_PARENT));
    }

    @Test
    public void onlyTheIndependentlyVerifiedServerHasAllUserReadback() {
        assertTrue(CopyAudience.mayServe(PERSONAL, true, SECONDARY, NO_PARENT));
        assertTrue(CopyAudience.mayServe(PERSONAL, true, WORK, PERSONAL));
        assertFalse("still no copy without a user", CopyAudience.mayServe(PERSONAL, true, -1, NO_PARENT));
    }

    @Test
    public void launchersTold_theUserAndItsParentOnly() {
        assertArrayEquals(new int[] {PERSONAL}, CopyAudience.toldUsers(PERSONAL, NO_PARENT));
        assertArrayEquals(new int[] {WORK, PERSONAL}, CopyAudience.toldUsers(WORK, PERSONAL));
        assertArrayEquals(new int[] {SECONDARY}, CopyAudience.toldUsers(SECONDARY, NO_PARENT));
        assertArrayEquals(new int[0], CopyAudience.toldUsers(-1, NO_PARENT));
    }
}
