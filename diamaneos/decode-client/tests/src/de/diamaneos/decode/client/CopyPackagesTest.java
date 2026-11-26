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
import static org.junit.Assert.assertNull;
import org.junit.Test;

public class CopyPackagesTest {
    @Test
    public void workOnlyBaseAndSplit_cannotBeRelabeledPersonal() {
        final CopyPackages packages = new CopyPackages();
        for (String apk : new String[] {"/data/app/work/base.apk", "/data/app/work/split.apk"}) {
            packages.add(apk, "p", 10);
            assertNull(packages.packageOf(apk, 0));
            assertEquals("p", packages.packageOf(apk, 10));
        }
    }

    @Test
    public void samePackage_differentUserPath_isNotTheRequestedInstallation() {
        final CopyPackages packages = new CopyPackages();
        packages.add("/data/app/v1/base.apk", "p", 0);
        packages.add("/data/app/v2/base.apk", "p", 10);
        assertNull(packages.packageOf("/data/app/v1/base.apk", 10));
        assertNull(packages.packageOf("/data/app/v2/base.apk", 0));
    }
}
