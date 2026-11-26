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

public class CopyInstalledPathTest {
    @Test
    public void currentBaseAndSplitsAreRequiredEvenWhenThePackageNameMatches() {
        assertTrue(CopyPackages.selectedPath("/current/base.apk", "/current/base.apk",
                new String[] {"/current/split.apk"}, true));
        assertTrue(CopyPackages.selectedPath("/current/split.apk", "/current/base.apk",
                new String[] {"/current/split.apk"}, true));
        assertFalse(CopyPackages.selectedPath("/old/base.apk", "/current/base.apk",
                new String[] {"/current/split.apk"}, true));
    }

    @Test
    public void anUninstalledUserCannotUseAPathStillKnownForAnotherUser() {
        assertFalse(CopyPackages.selectedPath("/work/base.apk", "/work/base.apk", null, false));
        assertFalse(CopyPackages.selectedPath("/work/base.apk", null, null, false));
    }
}
