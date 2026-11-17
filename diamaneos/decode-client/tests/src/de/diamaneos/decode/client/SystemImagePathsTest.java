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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Which APK paths count as the verified system image. */
@RunWith(JUnit4.class)
public class SystemImagePathsTest {

    @Test
    public void systemImagePartitions() {
        assertTrue(SystemImagePaths.isSystemImage("/system/framework/framework-res.apk"));
        assertTrue(SystemImagePaths.isSystemImage("/system_ext/priv-app/SystemUI/SystemUI.apk"));
        assertTrue(SystemImagePaths.isSystemImage("/product/overlay/Theme.apk"));
        assertTrue(SystemImagePaths.isSystemImage("/vendor/overlay/Device.apk"));
        assertTrue(SystemImagePaths.isSystemImage("/odm/overlay/Odm.apk"));
        assertTrue(SystemImagePaths.isSystemImage("/apex/com.android.permission/priv-app/A/A.apk"));
    }

    @Test
    public void everythingElse() {
        assertFalse(SystemImagePaths.isSystemImage(null));
        assertFalse(SystemImagePaths.isSystemImage(""));
        assertFalse(SystemImagePaths.isSystemImage("/data/app/~~a==/de.example-b==/base.apk"));
        assertFalse(SystemImagePaths.isSystemImage("/data/resource-cache/x.frro"));
        assertFalse(SystemImagePaths.isSystemImage("/mnt/expand/uuid/app/x/base.apk"));
        assertFalse(SystemImagePaths.isSystemImage("system/app/A.apk"));
        assertFalse(SystemImagePaths.isSystemImage("/system"));
        assertFalse(SystemImagePaths.isSystemImage("/system/"));
        assertFalse(SystemImagePaths.isSystemImage("/systemx/app/A.apk"));
        // Paths that only look like the system image.
        assertFalse(SystemImagePaths.isSystemImage("/system/../data/app/x/base.apk"));
        assertFalse(SystemImagePaths.isSystemImage("/system/./app/A.apk"));
        assertFalse(SystemImagePaths.isSystemImage("/system//app/A.apk"));
        assertFalse(SystemImagePaths.isSystemImage("/system/app/.."));
        assertFalse(SystemImagePaths.isSystemImage("/system/app/A.apk\0/data"));
    }
}
