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

import android.app.IWallpaperManager;
import android.app.wallpaper.WallpaperDescription;
import android.app.WallpaperManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.system.Os;
import android.system.StructStat;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.*;

@RunWith(AndroidJUnit4.class)
public class WallpaperTransactionsDeviceTest {
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private final IWallpaperManager service = IWallpaperManager.Stub.asInterface(
            ServiceManager.getService(Context.WALLPAPER_SERVICE));

    private ParcelFileDescriptor begin() throws Exception {
        return service.setWallpaper(null, context.getOpPackageName(), new WallpaperDescription.Builder().build(),
                true, new Bundle(), WallpaperManager.FLAG_SYSTEM, null, context.getUserId());
    }

    private static void write(ParcelFileDescriptor fd, int color) throws Exception {
        final Bitmap image = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888);
        image.eraseColor(color);
        final ByteArrayOutputStream png = new ByteArrayOutputStream();
        assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, png));
        Os.lseek(fd.getFileDescriptor(), 0, android.system.OsConstants.SEEK_SET);
        final byte[] bytes = png.toByteArray();
        Os.ftruncate(fd.getFileDescriptor(), 0);
        Os.write(fd.getFileDescriptor(), bytes, 0, bytes.length);
        fd.getFileDescriptor().sync();
        image.recycle();
    }

    private static String shell(String cmd) throws Exception {
        return DeviceShell.run(cmd);
    }

    @After
    public void restore() throws Exception {
        WallpaperManager.getInstance(context).clear(WallpaperManager.FLAG_SYSTEM | WallpaperManager.FLAG_LOCK);
    }

    @Test
    public void anAlreadyIssuedFdCannotWriteTheNextSetAfterItsLastStat() throws Exception {
        ParcelFileDescriptor second = null;
        try (ParcelFileDescriptor first = begin()) {
            assertNotNull(first);
            write(first, 0xffff0000);
            final StructStat old = Os.fstat(first.getFileDescriptor());
            second = begin();
            assertNotNull(second);
            write(second, 0xff0000ff);
            final StructStat checked = Os.fstat(second.getFileDescriptor());
            assertTrue("the next set reused the writable inode", old.st_dev != checked.st_dev
                    || old.st_ino != checked.st_ino);
            // Write through the old descriptor after the new set's final stat.
            write(first, 0xff00ff00);
            second.close();
            second = null;
            final long end = SystemClock.uptimeMillis() + 40000;
            Bitmap crop = null;
            int descriptors = 0;
            int encodedBytes = 0;
            while (crop == null && SystemClock.uptimeMillis() < end) {
                try (ParcelFileDescriptor fd = WallpaperManager.getInstance(context)
                        .getWallpaperFile(WallpaperManager.FLAG_SYSTEM)) {
                    if (fd != null) {
                        descriptors++;
                        try (InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(fd)) {
                            final byte[] bytes = input.readNBytes(32 * 1024 * 1024 + 1);
                            assertTrue("synthetic crop exceeds the test read bound", bytes.length <= 32 * 1024 * 1024);
                            encodedBytes = bytes.length;
                            crop = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                        }
                    }
                }
                if (crop == null) SystemClock.sleep(250);
            }
            assertNotNull("new set never received a crop, descriptors=" + descriptors + " bytes=" + encodedBytes
                    + (crop == null ? " " + shell("logcat -d -v raw -s WallpaperVm:V") : ""), crop);
            assertEquals("old descriptor replaced the new set", 0xff0000ff,
                    crop.getPixel(crop.getWidth() / 2, crop.getHeight() / 2));
            crop.recycle();
        } finally {
            if (second != null) second.close();
        }
    }

    @Test
    public void overLimitInputIsTerminalBeforeSnapshotOrVmProbe() throws Exception {
        final String dir = "/data/system/users/" + context.getUserId() + "/";
        try (ParcelFileDescriptor input = begin()) {
            assertNotNull(input);
            // Sparse input beyond the VM's 256 MiB stream limit, without allocating those bytes on the phone.
            Os.ftruncate(input.getFileDescriptor(), 268435457L);
            input.getFileDescriptor().sync();
        }
        final long end = SystemClock.uptimeMillis() + 15000;
        boolean refused = false;
        while (!refused && SystemClock.uptimeMillis() < end) {
            final String log = shell("logcat -d -v raw -s WallpaperVm:V");
            refused = log.contains("Wallpaper input exceeds the VM input limit: wallpaper_orig");
            if (!refused) SystemClock.sleep(100);
        }
        assertTrue("over-limit source reached staging or VM without a terminal size refusal", refused);
        assertFalse("raw snapshot was staged", shell("su 0 test -f " + dir
                + "wallpaper_orig.vmsnap.tmp && echo staged").contains("staged"));
    }
}
