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

import static org.junit.Assert.*;
import org.junit.Test;

public class CopyIsolationTest {
    @Test
    public void aStagedSessionNeverUsesTheInstalledOrAnotherSessionsAudience() {
        final String installed = CopyIsolation.identity("installer", "/data/app/installed/base.apk", false, 41);
        final String staged = CopyIsolation.identity("installer", "/data/app/vmdl1.tmp/base.apk", true, 41);
        assertNotNull(staged);
        assertNotEquals(installed, staged);
        assertNotEquals(staged, CopyIsolation.identity("installer", "/data/app/vmdl2.tmp/base.apk", true, 41));
        assertNotEquals(staged, CopyIsolation.identity("other", "/data/app/vmdl1.tmp/base.apk", true, 41));
        assertNotEquals(staged, CopyIsolation.identity("installer", "/data/app/vmdl1.tmp/base.apk", true, 42));
    }

    @Test
    public void aSessionNeedsItsAuthenticatedPathAndUserIncarnation() {
        assertNull(CopyIsolation.identity("installer", null, true, 41));
        assertNull(CopyIsolation.identity("installer", "/stage/base.apk", true, -1));
    }
}
