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
    // wallpaper behind, on both screens (unless the run asks to keep it: -e keepWallpaper 1, for a check after a
    // restart).
    @After
    public void restoreTheDefaultWallpaper() throws Exception {
        if (InstrumentationRegistry.getArguments().getString("keepWallpaper") != null) {
            return;
        }
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

    /**
     * Review 2b M1: one image for both screens, then a home-only image. The service moves the shared image and its
     * crop over to the lock screen. The lock screen keeps a readable crop of the old image.
     */
    @Test
    public void homeOnlyOverAShared_theLockScreenKeepsItsCrop() throws Exception {
        assumeTrue("no decode service here", shell("pm path " + SERVICE).contains("package:"));
        final WallpaperManager wm = mContext.getSystemService(WallpaperManager.class);
        wm.setStream(new ByteArrayInputStream(huePng(256, 1)), null, true,
                WallpaperManager.FLAG_SYSTEM | WallpaperManager.FLAG_LOCK);
        assertEquals("the shared image's crop", 1, waitForHue(wm, WallpaperManager.FLAG_SYSTEM, 1, CROP_WAIT_MS));
        wm.setStream(new ByteArrayInputStream(huePng(256, 2)), null, true, WallpaperManager.FLAG_SYSTEM);
        assertEquals("the new home image", 2, waitForHue(wm, WallpaperManager.FLAG_SYSTEM, 2, CROP_WAIT_MS));
        assertEquals("the lock screen lost its image", 1,
                waitForHue(wm, WallpaperManager.FLAG_LOCK, 1, CROP_WAIT_MS));
        SystemClock.sleep(5_000);
        assertEquals("the lock screen's crop went after the move", 1, cropHue(wm, WallpaperManager.FLAG_LOCK));
    }

    /**
     * Review 2b M1, after a restart: run with -e lockHue and -e homeHue (0 red, 1 green, 2 blue) after
     * {@link #homeOnlyOverAShared_theLockScreenKeepsItsCrop} kept its wallpapers (-e keepWallpaper 1). Both screens
     * still have a readable crop of their image. Skipped without the arguments.
     */
    @Test
    public void afterARestart_bothScreensKeepTheirCrops() throws Exception {
        final String lock = InstrumentationRegistry.getArguments().getString("lockHue");
        final String home = InstrumentationRegistry.getArguments().getString("homeHue");
        assumeTrue("needs -e lockHue and -e homeHue", lock != null && home != null);
        final WallpaperManager wm = mContext.getSystemService(WallpaperManager.class);
        assertEquals("the home screen's crop", Integer.parseInt(home),
                waitForHue(wm, WallpaperManager.FLAG_SYSTEM, Integer.parseInt(home), CROP_WAIT_MS));
        assertEquals("the lock screen's crop", Integer.parseInt(lock),
                waitForHue(wm, WallpaperManager.FLAG_LOCK, Integer.parseInt(lock), CROP_WAIT_MS));
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

    // Opaque noise of one hue (red, green or blue strongest by far), new on every call, as a PNG.
    private static byte[] huePng(int size, int hue) {
        final int[] pixels = new int[size * size];
        final SecureRandom random = new SecureRandom();
        for (int i = 0; i < pixels.length; i++) {
            final int strong = 192 + random.nextInt(64);
            final int weak = random.nextInt(48);
            final int r = hue == 0 ? strong : weak;
            final int g = hue == 1 ? strong : weak;
            final int b = hue == 2 ? strong : random.nextInt(48);
            pixels[i] = 0xff000000 | (r << 16) | (g << 8) | b;
        }
        final java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
                .compress(Bitmap.CompressFormat.PNG, 100, out);
        return out.toByteArray();
    }

    // The strongest channel (0 red, 1 green, 2 blue) of the middle of which's crop, -1 while there is none.
    private static int cropHue(WallpaperManager wm, int which) throws IOException {
        try (ParcelFileDescriptor fd = wm.getWallpaperFile(which)) {
            if (fd == null) {
                return -1;
            }
            final Bitmap crop = android.graphics.BitmapFactory.decodeFileDescriptor(fd.getFileDescriptor());
            if (crop == null) {
                return -1;
            }
            long r = 0;
            long g = 0;
            long b = 0;
            for (int y = crop.getHeight() / 2 - 8; y < crop.getHeight() / 2 + 8; y++) {
                for (int x = crop.getWidth() / 2 - 8; x < crop.getWidth() / 2 + 8; x++) {
                    final int p = crop.getPixel(Math.max(0, x), Math.max(0, y));
                    r += (p >> 16) & 0xff;
                    g += (p >> 8) & 0xff;
                    b += p & 0xff;
                }
            }
            return r > g && r > b ? 0 : g > b ? 1 : 2;
        }
    }

    private static int waitForHue(WallpaperManager wm, int which, int hue, long ms) throws IOException {
        final long end = SystemClock.uptimeMillis() + ms;
        int seen = cropHue(wm, which);
        while (seen != hue && SystemClock.uptimeMillis() < end) {
            SystemClock.sleep(500);
            seen = cropHue(wm, which);
        }
        return seen;
    }

    /**
     * Review 2b H5: an app sets a large image, then another app a small one while the VM still makes the first clean.
     * The newer image is what stays: the older task's clean copy is never put over it. Rounds with the second write at
     * different times in the first one's VM step.
     */
    @Test
    public void twoQuickWrites_theNewerImageStays() throws Exception {
        assumeTrue("no decode service here", shell("pm path " + SERVICE).contains("package:"));
        final WallpaperManager wm = mContext.getSystemService(WallpaperManager.class);
        final int which = WallpaperManager.FLAG_SYSTEM;
        final StringBuilder rounds = new StringBuilder();
        int lost = 0;
        for (long delay = 200; delay <= 2_000; delay += 300) {
            final byte[] older = huePng(1600, 0);
            final byte[] newer = huePng(64, 2);
            final Thread first = new Thread(() -> {
                try {
                    wm.setStream(new ByteArrayInputStream(older), null, true, which);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }, "older-set");
            first.start();
            SystemClock.sleep(delay);
            wm.setStream(new ByteArrayInputStream(newer), null, true, which);
            first.join(60_000);
            final int hue = waitForHue(wm, which, 2, CROP_WAIT_MS);
            // The older task may still be on its way: what stays is what counts.
            SystemClock.sleep(5_000);
            final int last = cropHue(wm, which);
            rounds.append(delay).append(" ms: ").append(hue).append('/').append(last).append("; ");
            if (last != 2) {
                lost++;
            }
        }
        assertEquals("the older image replaced the newer one (hue 2 is the newer): " + rounds, 0, lost);
    }
}
