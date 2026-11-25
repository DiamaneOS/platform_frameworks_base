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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.CancellationSignal;
import android.os.OperationCanceledException;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.util.Size;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.internal.diamaneos.cleancopies.VmThumbnails;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * ContentResolver.loadThumbnail in a system process (forced here with {@link VmThumbnails#setForcedForTest}, as this
 * test APK is no system app): the provider's reply is decoded in the VM by the system server, whole files, parts of
 * files and pipes alike, the provider's orientation extra and the file's EXIF orientation come out as Android's own
 * decode gives them, and with the VM unavailable or the decode service stopped the load gives no thumbnail (it waits
 * for the service until the caller cancels it) and nothing is decoded here. In any other process the load decodes as
 * before, VM or not. SystemUI's clipboard preview (VmDecode.loadThumbnail) turns its thumbnails as stock does, EXIF
 * orientations 1 to 8 and the provider's extra.
 * Needs a debuggable build with the decode service: the system server and the service take this package as a caller
 * on debuggable builds only, and the VM is held down with a debug property.
 */
@RunWith(AndroidJUnit4.class)
public class ThumbnailsDeviceTest {
    private static final String SERVICE = "de.diamaneos.sanitizer";
    // The decode service answers every request as unavailable until this time (seconds since the epoch).
    private static final String DOWN_UNTIL = "debug.diamaneos.decoder.down_until";
    private static final Size BOX = new Size(256, 256);
    // The clipboard preview's box shape: a width and four times that height (overlay_x_scale).
    private static final Size CLIPBOARD = new Size(96, 384);
    // The system server gives a thumbnail 5 s (VmThumbnailDecoder.THUMBNAIL_MS), a stopped service included.
    private static final long FAIL_WITHIN_MS = 12_000;
    // A caller that leaves: the load waits for the decode service to answer again only until it is cancelled.
    private static final long CANCEL_AFTER_MS = 3_000;

    private final Context mContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private final ContentResolver mResolver = mContext.getContentResolver();

    @After
    public void asBefore() throws Exception {
        VmThumbnails.setForcedForTest(false);
        shell("setprop " + DOWN_UNTIL + " 0");
    }

    private static String shell(String command) throws IOException {
        try (ParcelFileDescriptor out = InstrumentationRegistry.getInstrumentation().getUiAutomation()
                .executeShellCommand(command);
                InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(out)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void assumeDecodeService() throws IOException {
        assumeTrue("no decode service here", shell("pm path " + SERVICE).contains("package:"));
        assumeTrue("needs a debuggable build", "1".equals(shell("getprop ro.debuggable").trim()));
    }

    private static void holdVmDown(long seconds) throws IOException {
        final long until = System.currentTimeMillis() / 1000 + seconds;
        shell("setprop " + DOWN_UNTIL + " " + until);
        assertEquals("the hold was not set", String.valueOf(until), shell("getprop " + DOWN_UNTIL).trim());
    }

    private Bitmap load(Uri uri) throws IOException {
        return mResolver.loadThumbnail(uri, BOX, null);
    }

    // The VM's thumbnail, asked again for up to 30 s while the service comes back from an earlier hold.
    private Bitmap loadFromVm(Uri uri) throws Exception {
        final long end = SystemClock.uptimeMillis() + 30_000;
        IOException last = null;
        while (SystemClock.uptimeMillis() < end) {
            final int made = VmThumbnails.madeForTest();
            try {
                final Bitmap bitmap = load(uri);
                assertEquals("not from the VM", made + 1, VmThumbnails.madeForTest());
                return bitmap;
            } catch (IOException e) {
                last = e;
                SystemClock.sleep(1_000);
            }
        }
        throw new AssertionError("no thumbnail from the VM: " + last, last);
    }

    private void assertFailsClosed(Uri uri) {
        final int made = VmThumbnails.madeForTest();
        final CancellationSignal signal = new CancellationSignal();
        final Thread leave = new Thread(() -> {
            SystemClock.sleep(CANCEL_AFTER_MS);
            signal.cancel();
        }, "thumbnail-caller-leaves");
        leave.setDaemon(true);
        final long start = SystemClock.uptimeMillis();
        leave.start();
        try {
            final Bitmap bitmap = mResolver.loadThumbnail(uri, BOX, signal);
            fail("a thumbnail without the VM: " + bitmap.getWidth() + "x" + bitmap.getHeight());
        } catch (IOException | OperationCanceledException expected) {
            // The generic icon shows.
        }
        final long took = SystemClock.uptimeMillis() - start;
        assertTrue("the failure took " + took + " ms", took < CANCEL_AFTER_MS + FAIL_WITHIN_MS);
        assertEquals("counted as made", made, VmThumbnails.madeForTest());
    }

    // A colour no earlier load asked for, so its PNG holds new bytes: the decode service answers bytes it decoded
    // before from its cache, before it asks the VM and so while the VM is held down too.
    private static String newColour() {
        return String.format("%06x", new java.util.Random().nextInt(0x1000000));
    }

    private static void assertSolid(String what, Bitmap bitmap, int argb) {
        assertNotNull(what, bitmap);
        assertEquals(what + " width", ThumbnailTestProvider.WIDTH, bitmap.getWidth());
        assertEquals(what + " height", ThumbnailTestProvider.HEIGHT, bitmap.getHeight());
        assertEquals(what + " colour", argb, bitmap.getPixel(ThumbnailTestProvider.WIDTH / 2, 1));
    }

    private static int[] pixels(Bitmap bitmap) {
        final int[] p = new int[bitmap.getWidth() * bitmap.getHeight()];
        bitmap.getPixels(p, 0, bitmap.getWidth(), 0, 0, bitmap.getWidth(), bitmap.getHeight());
        return p;
    }

    private static void assertClose(String what, int expected, int actual) {
        final int d = Math.max(Math.abs(Color.red(expected) - Color.red(actual)),
                Math.max(Math.abs(Color.green(expected) - Color.green(actual)),
                        Math.abs(Color.blue(expected) - Color.blue(actual))));
        assertTrue(what + ": " + Integer.toHexString(actual) + " is not close to " + Integer.toHexString(expected),
                d <= 48);
    }

    // The middles of the four quarters of a JPEG thumbnail match within JPEG's error.
    private static void assertQuartersClose(String what, Bitmap stock, Bitmap vm) {
        final int w = vm.getWidth();
        final int h = vm.getHeight();
        for (int[] at : new int[][] {{w / 4, h / 4}, {3 * w / 4, h / 4}, {w / 4, 3 * h / 4}, {3 * w / 4, 3 * h / 4}}) {
            assertClose(what + " at " + at[0] + "," + at[1], stock.getPixel(at[0], at[1]), vm.getPixel(at[0], at[1]));
        }
    }

    @Test
    public void gatedCaller_decodesInTheVm() throws Exception {
        assumeDecodeService();
        VmThumbnails.setForcedForTest(true);
        assertSolid("whole file", loadFromVm(ThumbnailTestProvider.uri("png", "123456")), 0xff123456);
        assertSolid("part of a file", loadFromVm(ThumbnailTestProvider.uri("part", "00ff00")), 0xff00ff00);
        assertSolid("pipe", loadFromVm(ThumbnailTestProvider.uri("pipe", "0000ff")), 0xff0000ff);
        // New images of each kind while the VM is held down: no thumbnail, so nothing is decoded here. (The images
        // above would come back from the decode service's cache of what the VM made.)
        holdVmDown(60);
        assertFailsClosed(ThumbnailTestProvider.uri("png", newColour()));
        assertFailsClosed(ThumbnailTestProvider.uri("part", newColour()));
        assertFailsClosed(ThumbnailTestProvider.uri("pipe", newColour()));
    }

    @Test
    public void gatedCaller_refusedBytes_failClosed() throws Exception {
        assumeDecodeService();
        VmThumbnails.setForcedForTest(true);
        loadFromVm(ThumbnailTestProvider.uri("png", "123456"));
        assertFailsClosed(ThumbnailTestProvider.uri("junk", null));
    }

    @Test
    public void gatedCaller_failsClosed_whileTheDecodeServiceIsStopped() throws Exception {
        assumeDecodeService();
        assumeTrue("needs root to stop the decode service", shell("su 0 id").contains("uid=0"));
        VmThumbnails.setForcedForTest(true);
        loadFromVm(ThumbnailTestProvider.uri("png", "123456"));
        final String pid = shell("pidof " + SERVICE).trim();
        assertTrue("decode service not running: " + pid, pid.matches("[0-9]+"));
        shell("su 0 kill -STOP " + pid);
        try {
            assertFailsClosed(ThumbnailTestProvider.uri("png", newColour()));
            assertFailsClosed(ThumbnailTestProvider.uri("pipe", newColour()));
        } finally {
            shell("su 0 kill -CONT " + pid);
        }
    }

    @Test
    public void gatedCaller_orientationExtra_appliedAsAndroidDoes() throws Exception {
        // A whole PNG and a JPEG handed out as a part of a file (an EXIF thumbnail's way), with the extra.
        assumeDecodeService();
        for (String kind : new String[] {"turned/90", "turned/180", "turned/270", "exifturned/90",
                "exifturned/180", "exifturned/270"}) {
            final Uri uri = ThumbnailTestProvider.uri(kind.split("/")[0], kind.split("/")[1]);
            final Bitmap stock = load(uri);
            VmThumbnails.setForcedForTest(true);
            final Bitmap vm = loadFromVm(uri);
            VmThumbnails.setForcedForTest(false);
            assertEquals(kind + " width", stock.getWidth(), vm.getWidth());
            assertEquals(kind + " height", stock.getHeight(), vm.getHeight());
            if (kind.startsWith("turned")) {
                assertArrayEquals(kind + " pixels", pixels(stock), pixels(vm));
            } else {
                assertQuartersClose(kind, stock, vm);
            }
        }
        // A quarter turn clockwise: the left half (red) is on top.
        VmThumbnails.setForcedForTest(true);
        final Bitmap turned = loadFromVm(ThumbnailTestProvider.uri("turned", "90"));
        assertEquals(4, turned.getWidth());
        assertEquals(8, turned.getHeight());
        assertEquals(0xffff0000, turned.getPixel(2, 1));
        assertEquals(0xff0000ff, turned.getPixel(2, 6));
    }

    @Test
    public void gatedCaller_exifOrientation_asAndroidDecodes() throws Exception {
        assumeDecodeService();
        for (String orientation : new String[] {"1", "2", "3", "4", "5", "6", "7", "8"}) {
            final Uri uri = ThumbnailTestProvider.uri("exif", orientation);
            final Bitmap stock = load(uri);
            VmThumbnails.setForcedForTest(true);
            final Bitmap vm = loadFromVm(uri);
            VmThumbnails.setForcedForTest(false);
            final String what = "EXIF orientation " + orientation;
            assertEquals(what + " width", stock.getWidth(), vm.getWidth());
            assertEquals(what + " height", stock.getHeight(), vm.getHeight());
            assertQuartersClose(what, stock, vm);
        }
    }

    // The clipboard's way (SystemUI calls VmDecode.loadThumbnail itself, as a caller of the decode service): the
    // VM's thumbnail of {@code uri} in the clipboard's box, retried for up to 30 s while the VM comes back.
    private Bitmap clipboardThumbnail(Uri uri) throws Exception {
        final long end = SystemClock.uptimeMillis() + 30_000;
        Exception last = null;
        while (SystemClock.uptimeMillis() < end) {
            try {
                return VmDecode.loadThumbnail(mContext, mResolver, uri, VmDecode.unknownOwner("clipboard"),
                        mContext.getUserId(), VmDecode.PRIORITY_USER_WAITING, CLIPBOARD.getWidth(),
                        CLIPBOARD.getHeight(), VmDecode.deadlineIn(20_000));
            } catch (VmDecode.UnavailableException | VmDecode.RejectedException e) {
                last = e;
                SystemClock.sleep(1_000);
            }
        }
        throw new AssertionError("no thumbnail from the VM: " + last, last);
    }

    /**
     * SystemUI's clipboard preview (VmDecode.loadThumbnail) against stock ContentResolver.loadThumbnail in the same
     * box: EXIF orientations 1 to 8 of the file, the provider's orientation extra, and both, as a provider hands out
     * an EXIF thumbnail.
     */
    @Test
    public void clipboardThumbnail_turnedAsStock() throws Exception {
        assumeDecodeService();
        VmThumbnails.setForcedForTest(false);
        final java.util.List<Uri> uris = new java.util.ArrayList<>();
        for (int orientation = 1; orientation <= 8; orientation++) {
            uris.add(ThumbnailTestProvider.uri("exif", String.valueOf(orientation)));
        }
        for (int degrees : new int[] {90, 180, 270}) {
            uris.add(ThumbnailTestProvider.uri("turned", String.valueOf(degrees)));
            uris.add(ThumbnailTestProvider.uri("exifturned", String.valueOf(degrees)));
        }
        for (Uri uri : uris) {
            final Bitmap stock = mResolver.loadThumbnail(uri, CLIPBOARD, null);
            final Bitmap vm = clipboardThumbnail(uri);
            final String what = uri.getPath();
            assertEquals(what + " width", stock.getWidth(), vm.getWidth());
            assertEquals(what + " height", stock.getHeight(), vm.getHeight());
            assertQuartersClose(what, stock, vm);
        }
    }

    @Test
    public void ungatedCaller_keepsStockBehaviour() throws Exception {
        assumeDecodeService();
        VmThumbnails.setForcedForTest(false);
        assumeTrue("this process is a gated one", !VmThumbnails.applies());
        holdVmDown(30);
        final int made = VmThumbnails.madeForTest();
        // Decoded in this process as before, with the VM down, and the orientation extra applied.
        assertSolid("whole file", load(ThumbnailTestProvider.uri("png", "abcdef")), 0xffabcdef);
        assertSolid("part of a file", load(ThumbnailTestProvider.uri("part", "fedcba")), 0xfffedcba);
        final Bitmap turned = load(ThumbnailTestProvider.uri("turned", "90"));
        assertEquals(4, turned.getWidth());
        assertEquals(0xffff0000, turned.getPixel(2, 1));
        assertEquals("the VM was asked", made, VmThumbnails.madeForTest());
    }
}
