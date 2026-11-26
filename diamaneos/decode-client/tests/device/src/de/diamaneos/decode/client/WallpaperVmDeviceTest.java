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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import android.app.WallpaperManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.SecureRandom;

/**
 * A wallpaper set while the decode service does not answer shows the default (no crop is made from a source that is
 * not a clean copy from the VM), and gets its crop and its image wallpaper once the service answers again, without a
 * reboot: for the home screen, the lock screen and both. Changes the device's wallpaper and stops the decode service's
 * process for a while (SIGSTOP, then SIGCONT: it is a persistent app, so disabling it neither stops nor restarts it):
 * a test device with root only. Skipped elsewhere.
 *
 * <p>Each image is new random noise, so it can never equal a clean copy the VM made before (whose hash would be in the
 * directory's clean list). Where root is available the test checks that too, and reads the stored files.
 */
@RunWith(AndroidJUnit4.class)
public class WallpaperVmDeviceTest {
    private static final String SERVICE = "de.diamaneos.sanitizer";
    // The first retry of the VM step comes after 5 s, the second after 15 s more.
    private static final long CROP_WAIT_MS = 40_000;

    private final Context mContext = InstrumentationRegistry.getInstrumentation().getTargetContext();

    // The test pictures are tiny and look broken on a screen. Each test leaves the default
    // wallpaper behind, on both screens.
    @After
    public void restoreTheDefaultWallpaper() throws Exception {
        WallpaperManager.getInstance(mContext)
                .clear(WallpaperManager.FLAG_SYSTEM | WallpaperManager.FLAG_LOCK);
    }

    private static String shell(String command) throws IOException {
        return new String(shellBytes(command), java.nio.charset.StandardCharsets.UTF_8);
    }

    private static byte[] shellBytes(String command) throws IOException {
        try (ParcelFileDescriptor out = InstrumentationRegistry.getInstrumentation().getUiAutomation()
                .executeShellCommand(command);
                java.io.InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(out)) {
            return in.readAllBytes();
        }
    }

    private static boolean root() throws IOException {
        return shell("su 0 id").contains("uid=0");
    }

    // Stops the decode service's process: calls to it wait until resume. Returns its pid.
    private static String pause() throws IOException {
        final String pid = shell("pidof " + SERVICE).trim();
        assertTrue("decode service not running: " + pid, pid.matches("[0-9]+"));
        shell("su 0 kill -STOP " + pid);
        return pid;
    }

    private static void resume(String pid) throws IOException {
        shell("su 0 kill -CONT " + pid);
    }

    // The stored source of which (FLAG_SYSTEM or FLAG_LOCK) in this user's wallpaper directory.
    private String sourcePath(int which) {
        return "/data/system/users/" + mContext.getUserId() + "/"
                + (which == WallpaperManager.FLAG_LOCK ? "wallpaper_lock_orig" : "wallpaper_orig");
    }

    private String sourceHash(int which) throws IOException {
        final String out = shell("su 0 sha256sum " + sourcePath(which)).trim();
        final int space = out.indexOf(' ');
        return space == 64 ? out.substring(0, 64) : "";
    }

    private String cleanList() throws IOException {
        return shell("su 0 cat /data/system/users/" + mContext.getUserId() + "/wallpaper_vm_clean");
    }

    // The crop of which (FLAG_SYSTEM or FLAG_LOCK), or null while there is none.
    private ParcelFileDescriptor crop(WallpaperManager wm, int which) {
        return wm.getWallpaperFile(which);
    }

    // Opaque random noise, new on every call: never the same bytes as a clean copy made before.
    private static Bitmap picture() {
        final int[] pixels = new int[64 * 64];
        final SecureRandom random = new SecureRandom();
        for (int i = 0; i < pixels.length; i++) {
            pixels[i] = 0xff000000 | random.nextInt(0x1000000);
        }
        return Bitmap.createBitmap(pixels, 64, 64, Bitmap.Config.ARGB_8888);
    }

    private ParcelFileDescriptor waitForCrop(WallpaperManager wm, int flag, long end) {
        ParcelFileDescriptor made = null;
        while (made == null && SystemClock.uptimeMillis() < end) {
            SystemClock.sleep(1_000);
            made = crop(wm, flag);
        }
        return made;
    }

    private void setWhileDownThenComeBack(int which) throws Exception {
        assumeTrue("no decode service here", shell("pm path " + SERVICE).contains("package:"));
        assumeTrue("needs root to stop the decode service", root());
        final WallpaperManager wm = mContext.getSystemService(WallpaperManager.class);
        final String pid = pause();
        try {
            wm.setBitmap(picture(), null, true, which);
            // The case under test: a source the VM never made, so not in the clean list.
            final int stored = which == WallpaperManager.FLAG_LOCK ? WallpaperManager.FLAG_LOCK
                    : WallpaperManager.FLAG_SYSTEM;
            final String hash = sourceHash(stored);
            assertEquals("source hash not read", 64, hash.length());
            assertFalse("the new image is already in the clean list", cleanList().contains(hash));
            SystemClock.sleep(3_000);
            // Not a clean copy yet: no crop, the default shows.
            assertNull(crop(wm, which == WallpaperManager.FLAG_LOCK ? WallpaperManager.FLAG_LOCK
                    : WallpaperManager.FLAG_SYSTEM));
        } finally {
            resume(pid);
        }
        final long end = SystemClock.uptimeMillis() + CROP_WAIT_MS;
        // Set for both, the lock screen shows the home screen's wallpaper and has no file of its own.
        final int[] flags = which == WallpaperManager.FLAG_LOCK ? new int[] {WallpaperManager.FLAG_LOCK}
                : new int[] {WallpaperManager.FLAG_SYSTEM};
        for (int flag : flags) {
            final ParcelFileDescriptor made = waitForCrop(wm, flag, end);
            assertNotNull("no crop after the decode service answered again, which " + flag, made);
            made.close();
        }
    }

    @Test
    public void homeScreen_setWhileTheServiceIsDown_getsItsCropWhenItIsBack() throws Exception {
        setWhileDownThenComeBack(WallpaperManager.FLAG_SYSTEM);
    }

    @Test
    public void lockScreen_setWhileTheServiceIsDown_getsItsCropWhenItIsBack() throws Exception {
        setWhileDownThenComeBack(WallpaperManager.FLAG_LOCK);
    }

    @Test
    public void both_setWhileTheServiceIsDown_getCropsWhenItIsBack() throws Exception {
        setWhileDownThenComeBack(WallpaperManager.FLAG_SYSTEM | WallpaperManager.FLAG_LOCK);
    }

    // The decode service answers every request as unavailable until this time (seconds since the epoch), and keeps
    // its VM stopped. Honoured on debuggable builds only.
    private static final String DOWN_UNTIL = "debug.diamaneos.decoder.down_until";

    /**
     * The VM step's retry path: while the service answers that the VM is unavailable, the default shows and no crop
     * is made. Once it is available again, a later try of the same task makes the clean copy and its crop, with no new
     * file event and no reboot. Needs a debuggable build, where the service honours {@link #DOWN_UNTIL}.
     */
    @Test
    public void homeScreen_setWhileTheVmIsUnavailable_getsItsCropOnARetry() throws Exception {
        assumeTrue("no decode service here", shell("pm path " + SERVICE).contains("package:"));
        assumeTrue("needs a debuggable build", "1".equals(shell("getprop ro.debuggable").trim()));
        final WallpaperManager wm = mContext.getSystemService(WallpaperManager.class);
        final int which = WallpaperManager.FLAG_SYSTEM;
        // Long enough that the first try and the retry 5 s later both find the VM unavailable.
        final long holdS = 12;
        final long until = System.currentTimeMillis() / 1000 + holdS;
        shell("setprop " + DOWN_UNTIL + " " + until);
        try {
            assertEquals("the hold was not set", String.valueOf(until), shell("getprop " + DOWN_UNTIL).trim());
            wm.setBitmap(picture(), null, true, which);
            // Not a clean copy while the hold lasts: no crop, the default shows.
            while (System.currentTimeMillis() / 1000 < until - 1) {
                assertNull("a crop while the VM was unavailable", crop(wm, which));
                SystemClock.sleep(1_000);
            }
        } finally {
            shell("setprop " + DOWN_UNTIL + " 0");
        }
        final ParcelFileDescriptor made = waitForCrop(wm, which, SystemClock.uptimeMillis() + CROP_WAIT_MS);
        assertNotNull("no crop on a retry after the VM was available again", made);
        made.close();
    }

    /**
     * An app writes, through the descriptor it is handed, the very bytes of the clean copy the VM made before (their
     * hash is in the clean list): the file is still not clean, because the app may keep the descriptor and change
     * the bytes after any check. No crop until the VM has made a new copy.
     */
    @Test
    public void cleanCopysOwnBytes_setWhileTheServiceIsDown_areNotTrusted() throws Exception {
        assumeTrue("no decode service here", shell("pm path " + SERVICE).contains("package:"));
        assumeTrue("needs root to read the stored source", root());
        final WallpaperManager wm = mContext.getSystemService(WallpaperManager.class);
        final int which = WallpaperManager.FLAG_SYSTEM;
        wm.setBitmap(picture(), null, true, which);
        final ParcelFileDescriptor first = waitForCrop(wm, which, SystemClock.uptimeMillis() + CROP_WAIT_MS);
        assertNotNull("no crop with the decode service up", first);
        first.close();
        final String cleanHash = sourceHash(which);
        assertTrue("the VM's copy is not in the clean list", cleanList().contains(cleanHash));
        final byte[] clean = shellBytes("su 0 cat " + sourcePath(which));
        final String pid = pause();
        try {
            wm.setStream(new ByteArrayInputStream(clean), null, true, which);
            assertEquals("the stored bytes differ from the clean copy", cleanHash, sourceHash(which));
            SystemClock.sleep(3_000);
            assertNull("bytes an app wrote were taken as a clean copy", crop(wm, which));
        } finally {
            resume(pid);
        }
        final ParcelFileDescriptor made = waitForCrop(wm, which, SystemClock.uptimeMillis() + CROP_WAIT_MS);
        assertNotNull("no crop after the decode service came back", made);
        made.close();
    }
}
