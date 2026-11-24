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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.io.File;
import java.nio.file.Files;

/** The key of a clean copy and the system image rule. */
@RunWith(JUnit4.class)
public class CleanCopyKeyTest {
    @Test
    public void key_carriesApkFileDensityAndBox_andChangesWithTheApk() throws Exception {
        final File apk = File.createTempFile("app", ".apk");
        try {
            Files.write(apk.toPath(), new byte[] {1, 2, 3});
            final String key = CleanCopyKey.of(apk.getPath(), "res/a.png", 480, 1080, 2400, 0);
            assertTrue(key.startsWith(CleanCopyKey.PREFIX + apk.getPath() + ':'));
            assertTrue(key.endsWith(":3:res/a.png:480:1080x2400:u0"));
            assertEquals(apk.getPath(), CleanCopyKey.apkOf(key));
            assertNotEquals(key, CleanCopyKey.of(apk.getPath(), "res/a.png", 640, 1080, 2400, 0));
            assertNotEquals(key, CleanCopyKey.of(apk.getPath(), "res/b.png", 480, 1080, 2400, 0));
            Files.write(apk.toPath(), new byte[] {1, 2, 3, 4});
            assertNotEquals("an update is another key", key,
                    CleanCopyKey.of(apk.getPath(), "res/a.png", 480, 1080, 2400, 0));
        } finally {
            apk.delete();
        }
        assertNull(CleanCopyKey.of(null, "res/a.png", 480, 1, 1, 0));
        assertNull(CleanCopyKey.apkOf("other:/x"));
    }

    @Test
    public void key_ofOneUser_isNeverAnotherUsersKey() {
        // The same APK installed for the user and for a work profile: one copy each, never shared.
        final String personal = CleanCopyKey.of("/data/app/p/base.apk", "res/a.png", 480, 1080, 2400, 0);
        final String work = CleanCopyKey.of("/data/app/p/base.apk", "res/a.png", 480, 1080, 2400, 10);
        assertNotEquals(personal, work);
        assertEquals(0, CleanCopyKey.userOf(personal));
        assertEquals(10, CleanCopyKey.userOf(work));
        assertEquals("/data/app/p/base.apk", CleanCopyKey.apkOf(work));
        assertNull("no user, no key", CleanCopyKey.of("/data/app/p/base.apk", "res/a.png", 480, 1, 1, -1));
        // A file name that holds the separator does not change whose copy it is.
        assertEquals(11, CleanCopyKey.userOf(CleanCopyKey.of("/data/app/p/base.apk", "r/:u3.png", 1, 1, 1, 11)));
        assertEquals(-1, CleanCopyKey.userOf("rfile:/x:1:2:a.png:0:1x1"));
        assertEquals(-1, CleanCopyKey.userOf("rfile:/x:1:2:a.png:0:1x1:u"));
        assertEquals(-1, CleanCopyKey.userOf("rfile:/x:1:2:a.png:0:1x1:u1x"));
        assertEquals(-1, CleanCopyKey.userOf("rfile:/x:1:2:a.png:0:1x1:u9999999999"));
        assertEquals(-1, CleanCopyKey.userOf(null));
    }

    @Test
    public void identity_ofOneUser_isNeverAnotherUsersStandIn() {
        final String personal = CleanCopyKey.identity("p.q", 0, "p.q:mipmap/a", "res/a.png", 1080, 2400);
        final String work = CleanCopyKey.identity("p.q", 10, "p.q:mipmap/a", "res/a.png", 1080, 2400);
        assertNotEquals(personal, work);
        assertEquals(10, CleanCopyKey.userOf(work));
        assertEquals("p.q", CleanCopyKey.ownerOf(work));
        assertNull(CleanCopyKey.identity("p.q", -1, "p.q:mipmap/a", "res/a.png", 1, 1));
    }

    @Test
    public void identity_namesTheAppTheResourceAndItsVariant() {
        assertEquals("res:p.q/p.q:mipmap/ic_launcher/night/1080x2400:u0", CleanCopyKey.identity("p.q", 0,
                "p.q:mipmap/ic_launcher", "res/mipmap-night-xxhdpi-v26/ic_launcher.png", 1080, 2400));
        assertEquals("p.q", CleanCopyKey.ownerOf(CleanCopyKey.identity("p.q", 0, "x.y:drawable/a", "r/a.png", 1, 1)));
        assertNull("not a drawable", CleanCopyKey.identity("p.q", 0, "p.q:raw/a", "res/raw/a.png", 1, 1));
        assertNull("no app", CleanCopyKey.identity(null, 0, "p.q:drawable/a", "res/a.png", 1, 1));
        assertNull("odd name", CleanCopyKey.identity("p.q", 0, "p.q:drawable/a/../b", "res/a.png", 1, 1));
        assertNull("odd name", CleanCopyKey.identity("p.q", 0, "p.q:drawable/a\nb", "res/a.png", 1, 1));
    }

    @Test
    public void variant_keepsConfigurationButNotDensityOrVersion() {
        assertEquals("", CleanCopyKey.variant("res/mipmap-xxhdpi-v4/a.png"));
        assertEquals("night", CleanCopyKey.variant("res/mipmap-night-xxxhdpi-v26/a.png"));
        assertEquals("ldrtl-night", CleanCopyKey.variant("res/drawable-ldrtl-night-480dpi/a.png"));
        assertEquals("", CleanCopyKey.variant("r/a.png"));
        assertEquals("", CleanCopyKey.variant("a.png"));
    }

    @Test
    public void entryName_anyRelativeFile() {
        assertTrue(CleanCopyKey.entryName("res/mipmap-xxhdpi/a.png"));
        assertTrue("renamed by an obfuscator", CleanCopyKey.entryName("r/a.png"));
        assertFalse(CleanCopyKey.entryName("/res/a.png"));
        assertFalse(CleanCopyKey.entryName("res/../a.png"));
        assertFalse(CleanCopyKey.entryName("res/"));
        assertFalse(CleanCopyKey.entryName("res/a\0.png"));
        assertFalse(CleanCopyKey.entryName(""));
        assertFalse(CleanCopyKey.entryName(null));
    }

    @Test
    public void systemImage_onlyVerifiedPartitions() {
        assertTrue(CleanCopyKey.isSystemImage("/system/app/A/A.apk"));
        assertTrue(CleanCopyKey.isSystemImage("/system/framework/framework-res.apk"));
        assertTrue(CleanCopyKey.isSystemImage("/system_ext/priv-app/SystemUI/SystemUI.apk"));
        assertTrue(CleanCopyKey.isSystemImage("/product/overlay/Theme.apk"));
        assertTrue(CleanCopyKey.isSystemImage("/vendor/overlay/Device.apk"));
        assertTrue(CleanCopyKey.isSystemImage("/odm/overlay/Odm.apk"));
        assertTrue(CleanCopyKey.isSystemImage("/apex/com.android.permission/priv-app/P/P.apk"));
        assertFalse(CleanCopyKey.isSystemImage("/data/app/~~x/p-y/base.apk"));
        assertFalse(CleanCopyKey.isSystemImage("/data/resource-cache/x.frro"));
        assertFalse(CleanCopyKey.isSystemImage("/mnt/expand/uuid/app/x/base.apk"));
        assertFalse(CleanCopyKey.isSystemImage("/system/../data/app/a.apk"));
        assertFalse(CleanCopyKey.isSystemImage("/system/./app/A.apk"));
        assertFalse(CleanCopyKey.isSystemImage("/system//x.apk"));
        assertFalse(CleanCopyKey.isSystemImage("/system/app/.."));
        assertFalse(CleanCopyKey.isSystemImage("/system/app/A.apk\0/data"));
        assertFalse(CleanCopyKey.isSystemImage("system/x.apk"));
        assertFalse(CleanCopyKey.isSystemImage("/system"));
        assertFalse(CleanCopyKey.isSystemImage("/system/"));
        assertFalse(CleanCopyKey.isSystemImage("/systemx/app/A.apk"));
        assertFalse(CleanCopyKey.isSystemImage(""));
        assertFalse(CleanCopyKey.isSystemImage(null));
    }
    @org.junit.Test
    public void exactCopyWithoutResourceName_stillNamesItsPackage() {
        org.junit.Assert.assertEquals("p.app", CleanCopyKey.ownerOf(CleanCopyKey.ownership("p.app", 10)));
        org.junit.Assert.assertEquals(10, CleanCopyKey.userOf(CleanCopyKey.ownership("p.app", 10)));
    }

    @org.junit.Test
    public void serialsSeparateReusedUserIdsInExactAndFallbackKeys() {
        final String key = CleanCopyKey.of("/data/app/p/base.apk", "res/a.png", 0, 100, 100, 10);
        final String first = CleanCopyKey.forSerial(key, 41);
        final String next = CleanCopyKey.forSerial(key, 42);
        org.junit.Assert.assertNotEquals(first, next);
        org.junit.Assert.assertEquals(10, CleanCopyKey.userOf(first));
        org.junit.Assert.assertEquals(41, CleanCopyKey.serialOf(first));
        org.junit.Assert.assertEquals("p.app", CleanCopyKey.ownerOf(
                CleanCopyKey.forSerial(CleanCopyKey.ownership("p.app", 10), 41)));
        org.junit.Assert.assertEquals(-1, CleanCopyKey.serialOf(key));
        org.junit.Assert.assertNull(CleanCopyKey.forSerial(key, -1));
    }

}
