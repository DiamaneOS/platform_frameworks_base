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

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assume.assumeTrue;

import android.app.WallpaperManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;

/**
 * A wallpaper set while the decode service is down shows the default (no crop is made from a source that is not a
 * clean copy from the VM), and gets its crop and its image wallpaper once the service is back, without a reboot:
 * for the home screen, the lock screen and both. Changes the device's wallpaper and turns the decode service off for
 * a while: a test device only. Skipped where the service is not installed.
 */
@RunWith(AndroidJUnit4.class)
public class WallpaperVmDeviceTest {
    private static final String SERVICE = "de.diamaneos.sanitizer";
    // The first retry of the VM step comes after 5 s, the second after 15 s more.
    private static final long CROP_WAIT_MS = 40_000;

    private final Context mContext = InstrumentationRegistry.getInstrumentation().getTargetContext();

    private static String shell(String command) throws IOException {
        try (ParcelFileDescriptor out = InstrumentationRegistry.getInstrumentation().getUiAutomation()
                .executeShellCommand(command);
                java.io.InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(out)) {
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    // The crop of which (FLAG_SYSTEM or FLAG_LOCK), or null while there is none.
    private ParcelFileDescriptor crop(WallpaperManager wm, int which) {
        return wm.getWallpaperFile(which);
    }

    private static Bitmap picture(int color) {
        final Bitmap bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(color);
        return bitmap;
    }

    private void setWhileDownThenComeBack(int which, int color) throws Exception {
        assumeTrue("no decode service here", shell("pm path " + SERVICE).contains("package:"));
        final WallpaperManager wm = mContext.getSystemService(WallpaperManager.class);
        shell("pm disable-user --user 0 " + SERVICE);
        try {
            wm.setBitmap(picture(color), null, true, which);
            SystemClock.sleep(3_000);
            // Not a clean copy yet: no crop, the default shows.
            assertNull(crop(wm, which == WallpaperManager.FLAG_LOCK ? WallpaperManager.FLAG_LOCK
                    : WallpaperManager.FLAG_SYSTEM));
        } finally {
            shell("pm enable --user 0 " + SERVICE);
        }
        final long end = SystemClock.uptimeMillis() + CROP_WAIT_MS;
        for (int flag : new int[] {WallpaperManager.FLAG_SYSTEM, WallpaperManager.FLAG_LOCK}) {
            if ((which & flag) == 0) {
                continue;
            }
            ParcelFileDescriptor made = null;
            while (made == null && SystemClock.uptimeMillis() < end) {
                SystemClock.sleep(1_000);
                made = crop(wm, flag);
            }
            assertNotNull("no crop after the decode service came back, which " + flag, made);
            made.close();
        }
    }

    @Test
    public void homeScreen_setWhileTheServiceIsDown_getsItsCropWhenItIsBack() throws Exception {
        setWhileDownThenComeBack(WallpaperManager.FLAG_SYSTEM, 0xff336699);
    }

    @Test
    public void lockScreen_setWhileTheServiceIsDown_getsItsCropWhenItIsBack() throws Exception {
        setWhileDownThenComeBack(WallpaperManager.FLAG_LOCK, 0xff993366);
    }

    @Test
    public void both_setWhileTheServiceIsDown_getCropsWhenItIsBack() throws Exception {
        setWhileDownThenComeBack(WallpaperManager.FLAG_SYSTEM | WallpaperManager.FLAG_LOCK, 0xff669933);
    }
}
