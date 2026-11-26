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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import android.app.IWallpaperManager;
import android.app.WallpaperManager;
import android.app.wallpaper.WallpaperDescription;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.DecodeGate;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.ServiceManager;
import android.os.SystemClock;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.internal.diamaneos.cleancopies.CleanFileStream;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.SecureRandom;
import java.util.Set;

/**
 * The wallpaper files the system server made from VM pixels (the clean copy and its crop) are named by their hash when
 * they are handed out, and a gated process decodes exactly those bytes without the VM. Bytes an app wrote are never
 * named, and a stream of them always goes to the VM. Changes the device's wallpaper and leaves the default. Needs the
 * decode service, and a debuggable build for the gate's test switch and the service's hold.
 */
@RunWith(AndroidJUnit4.class)
public class CleanWallpaperDeviceTest {
    private static final String SERVICE = "de.diamaneos.sanitizer";
    // The decode service answers every request as unavailable until this time (seconds since the epoch).
    private static final String DOWN_UNTIL = "debug.diamaneos.decoder.down_until";
    private static final long CROP_WAIT_MS = 40_000;
    private static final int W = 96;
    private static final int H = 64;

    private final Context mContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private final IWallpaperManager mService =
            IWallpaperManager.Stub.asInterface(ServiceManager.getService(Context.WALLPAPER_SERVICE));

    @Before
    public void gated() throws IOException {
        assumeTrue("no decode service here", shell("pm path " + SERVICE).contains("package:"));
        assumeTrue("needs a debuggable build", "1".equals(shell("getprop ro.debuggable").trim()));
        DecodeGate.setForcedForTest(true);
        DecodeGate.resetForTest();
    }

    @After
    public void asBefore() throws Exception {
        shell("setprop " + DOWN_UNTIL + " 0");
        DecodeGate.setForcedForTest(null);
        DecodeGate.resetForTest();
        WallpaperManager.getInstance(mContext).clear(WallpaperManager.FLAG_SYSTEM | WallpaperManager.FLAG_LOCK);
    }

    private static String shell(String command) throws IOException {
        try (ParcelFileDescriptor out = InstrumentationRegistry.getInstrumentation().getUiAutomation()
                .executeShellCommand(command);
                InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(out)) {
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    // Opaque random noise, new on every call: never the bytes of a file made before.
    private static Bitmap noise() {
        final int[] pixels = new int[W * H];
        final SecureRandom random = new SecureRandom();
        for (int i = 0; i < pixels.length; i++) {
            pixels[i] = 0xff000000 | random.nextInt(0x1000000);
        }
        return Bitmap.createBitmap(pixels, W, H, Bitmap.Config.ARGB_8888);
    }

    private static byte[] png(Bitmap bitmap) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out));
        return out.toByteArray();
    }

    // The file the service hands out for FLAG_SYSTEM: the crop, or the stored source. Its out parameters in params.
    private ParcelFileDescriptor handedOut(boolean cropped, Bundle params) throws Exception {
        return mService.getWallpaperWithFeature(mContext.getOpPackageName(), null, null,
                WallpaperManager.FLAG_SYSTEM, params, mContext.getUserId(), cropped);
    }

    private ParcelFileDescriptor waitForCrop(Bundle params) throws Exception {
        final long end = SystemClock.uptimeMillis() + CROP_WAIT_MS;
        ParcelFileDescriptor made = null;
        while (made == null && SystemClock.uptimeMillis() < end) {
            SystemClock.sleep(1_000);
            params.clear();
            made = handedOut(true, params);
        }
        assertNotNull("no crop made", made);
        return made;
    }

    private static byte[] bytesOf(ParcelFileDescriptor pfd) throws IOException {
        try (InputStream in = new FileInputStream(pfd.getFileDescriptor())) {
            return in.readAllBytes();
        }
    }

    // Decodes, probes and failures of the gate so far: any change means the bytes went to the VM.
    private static int vmCalls() {
        final int[] c = DecodeGate.countsForTest();
        return c[0] + c[1] + c[2] + c[3] + c[4];
    }

    /**
     * An image the system server made: its crop is named by the hash of its bytes, and those bytes are decoded in
     * this gated process without any call to the VM, through the same calls WallpaperManager uses (bounds and image).
     */
    @Test
    public void cleanCrop_isNamed_andDecodedWithoutTheVm() throws Exception {
        final WallpaperManager wm = mContext.getSystemService(WallpaperManager.class);
        wm.setBitmap(noise(), null, true, WallpaperManager.FLAG_SYSTEM);
        final Bundle params = new Bundle();
        final ParcelFileDescriptor crop = waitForCrop(params);
        final String hash = params.getString(CleanFileStream.EXTRA_SHA256);
        assertNotNull("the crop the system server made is not named", hash);
        assertEquals("the named hash is not the crop's", CleanFileStream.sha256(bytesOf(crop)), hash);

        DecodeGate.resetForTest();
        final int before = vmCalls();
        try (InputStream in = CleanFileStream.openWallpaper(crop, params)) {
            assertTrue("not read as the system server's own file", in instanceof CleanFileStream);
            assertFalse("the gate takes the system server's own file to the VM", DecodeGate.gatesStream(in));
            final Bitmap bitmap = BitmapFactory.decodeStream(in);
            assertNotNull("the crop was not decoded", bitmap);
        }
        // WallpaperManager's own path: bounds and the image, from a fresh load.
        wm.forgetLoadedWallpaper();
        assertNotNull(wm.peekBitmapDimensions(WallpaperManager.FLAG_SYSTEM, false));
        assertNotNull(wm.getBitmap());
        assertEquals("the system server's own file went to the VM", before, vmCalls());

        // The same crop handed out without its name goes to the VM like any other file.
        final ParcelFileDescriptor unnamed = handedOut(true, new Bundle());
        try (InputStream in = CleanFileStream.openWallpaper(unnamed, null)) {
            assertFalse(in instanceof CleanFileStream);
            assertTrue("an unnamed file is not gated", DecodeGate.gatesStream(in));
        }
    }

    /** An unchecked source stays unavailable until the VM publishes its checked copy. */
    @Test
    public void appBytes_areUnavailableUntilChecked() throws Exception {
        final WallpaperManager wm = mContext.getSystemService(WallpaperManager.class);
        wm.setBitmap(noise(), null, true, WallpaperManager.FLAG_SYSTEM);
        waitForCrop(new Bundle()).close();
        final Bundle cleanParams = new Bundle();
        final ParcelFileDescriptor cleanSource = handedOut(false, cleanParams);
        assertNotNull(cleanSource);
        final byte[] clean = bytesOf(cleanSource);
        cleanSource.close();
        assertEquals("the clean copy is not named", CleanFileStream.sha256(clean),
                cleanParams.getString(CleanFileStream.EXTRA_SHA256));

        // New bytes, the VM held unavailable.
        final byte[] app = png(noise());
        final long until = System.currentTimeMillis() / 1000 + 20;
        shell("setprop " + DOWN_UNTIL + " " + until);
        wm.setStream(new ByteArrayInputStream(app), null, true, WallpaperManager.FLAG_SYSTEM);
        SystemClock.sleep(2_000);
        assertTrue("the hold ran out", System.currentTimeMillis() / 1000 < until - 1);
        Bundle params = new Bundle();
        assertNull("a crop made from an app's bytes", handedOut(true, params));
        assertNull("an unchecked source was returned", handedOut(false, params));
        assertNull(params.getString(CleanFileStream.EXTRA_SHA256));

        // The clean copy's own bytes through a descriptor the app keeps open (the hold still on, so no retry of the
        // VM step moves a file into place meanwhile).
        final ParcelFileDescriptor writable = mService.setWallpaper(null, mContext.getOpPackageName(),
                new WallpaperDescription.Builder().build(), true, new Bundle(), WallpaperManager.FLAG_SYSTEM, null,
                mContext.getUserId());
        assertNotNull("no descriptor to write into", writable);
        try {
            final FileOutputStream out = new FileOutputStream(writable.getFileDescriptor());
            out.write(clean);
            out.flush();
            writable.getFileDescriptor().sync();
            params = new Bundle();
            assertNull("an app-writable source was returned", handedOut(false, params));
            assertNull(params.getString(CleanFileStream.EXTRA_SHA256));
            assertTrue("the hold ran out", System.currentTimeMillis() / 1000 < until - 1);
        } finally {
            writable.close();
        }
    }

    /**
     * Only bytes with the named hash are read as the system server's own: a file whose bytes differ from the hash it
     * is handed out with (changed after the system server named it) goes to the VM.
     */
    @Test
    public void bytesThatDifferFromTheName_goToTheVm() throws Exception {
        final byte[] made = png(noise());
        final byte[] other = png(noise());
        final File file = new File(mContext.getCacheDir(), "clean-file-test.png");
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(other);
        }
        try (ParcelFileDescriptor pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)) {
            assertNull(CleanFileStream.read(pfd.getFileDescriptor(), Set.of(CleanFileStream.sha256(made))));
            assertNull(CleanFileStream.read(pfd.getFileDescriptor(), Set.of()));
            assertNotNull(CleanFileStream.read(pfd.getFileDescriptor(), Set.of(CleanFileStream.sha256(other))));
        }
        final Bundle params = new Bundle();
        params.putString(CleanFileStream.EXTRA_SHA256, CleanFileStream.sha256(made));
        final int before = vmCalls();
        try (InputStream in = CleanFileStream.openWallpaper(
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY), params)) {
            assertFalse(in instanceof CleanFileStream);
            assertTrue(DecodeGate.gatesStream(in));
            BitmapFactory.decodeStream(in);
        }
        assertTrue("bytes that differ from the name were not taken to the VM", vmCalls() > before);
        file.delete();
    }
}
