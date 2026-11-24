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
            final String key = CleanCopyKey.of(apk.getPath(), "res/a.png", 480, 1080, 2400);
            assertTrue(key.startsWith(CleanCopyKey.PREFIX + apk.getPath() + ':'));
            assertTrue(key.endsWith(":3:res/a.png:480:1080x2400"));
            assertEquals(apk.getPath(), CleanCopyKey.apkOf(key));
            assertNotEquals(key, CleanCopyKey.of(apk.getPath(), "res/a.png", 640, 1080, 2400));
            assertNotEquals(key, CleanCopyKey.of(apk.getPath(), "res/b.png", 480, 1080, 2400));
            Files.write(apk.toPath(), new byte[] {1, 2, 3, 4});
            assertNotEquals("an update is another key", key,
                    CleanCopyKey.of(apk.getPath(), "res/a.png", 480, 1080, 2400));
        } finally {
            apk.delete();
        }
        assertNull(CleanCopyKey.of(null, "res/a.png", 480, 1, 1));
        assertNull(CleanCopyKey.apkOf("other:/x"));
    }

    @Test
    public void identity_namesTheAppTheResourceAndItsVariant() {
        assertEquals("res:p.q/p.q:mipmap/ic_launcher/night/1080x2400", CleanCopyKey.identity("p.q",
                "p.q:mipmap/ic_launcher", "res/mipmap-night-xxhdpi-v26/ic_launcher.png", 1080, 2400));
        assertEquals("p.q", CleanCopyKey.ownerOf(CleanCopyKey.identity("p.q", "x.y:drawable/a", "r/a.png", 1, 1)));
        assertNull("not a drawable", CleanCopyKey.identity("p.q", "p.q:raw/a", "res/raw/a.png", 1, 1));
        assertNull("no app", CleanCopyKey.identity(null, "p.q:drawable/a", "res/a.png", 1, 1));
        assertNull("odd name", CleanCopyKey.identity("p.q", "p.q:drawable/a/../b", "res/a.png", 1, 1));
        assertNull("odd name", CleanCopyKey.identity("p.q", "p.q:drawable/a\nb", "res/a.png", 1, 1));
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
        assertTrue(CleanCopyKey.isSystemImage("/apex/com.android.permission/priv-app/P/P.apk"));
        assertFalse(CleanCopyKey.isSystemImage("/data/app/~~x/p-y/base.apk"));
        assertFalse(CleanCopyKey.isSystemImage("/system/../data/app/a.apk"));
        assertFalse(CleanCopyKey.isSystemImage("/system//x.apk"));
        assertFalse(CleanCopyKey.isSystemImage("system/x.apk"));
        assertFalse(CleanCopyKey.isSystemImage("/system/"));
        assertFalse(CleanCopyKey.isSystemImage(null));
    }
}
