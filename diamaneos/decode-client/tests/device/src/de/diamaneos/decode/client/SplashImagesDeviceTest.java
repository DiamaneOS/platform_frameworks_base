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

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Process;
import android.os.SystemClock;
import android.os.UserHandle;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import de.diamaneos.decode.client.tests.R;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.RandomAccessFile;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/**
 * Splash screen images from clean copies (SplashImages, CleanImageStore): the app start never waits for the VM and
 * never decodes an app's image file, a drawable with a missing copy is left out, the copies the background make keeps
 * are taken on the next start, and a damaged or foreign copy is never shown. This test APK is under /data, so its
 * bitmap files are an app's. The test that makes copies needs the decode VM (debuggable builds) and is skipped
 * without it.
 */
@RunWith(AndroidJUnit4.class)
public class SplashImagesDeviceTest {
    private final Context mContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private final String mPackage = mContext.getPackageName();
    private final int mUser = UserHandle.myUserId();

    @Before
    public void emptyCaches() {
        CleanImageStore.get(mContext).clear();
        VmIcons.trim();
    }

    private static <T> T onThread(Callable<T> callable) throws Exception {
        final FutureTask<T> task = new FutureTask<>(callable);
        final Thread thread = new Thread(task, "splash-test");
        thread.start();
        return task.get(60, TimeUnit.SECONDS);
    }

    private Context splashContext(int theme) throws Exception {
        final Context packageContext = mContext.createPackageContextAsUser(mPackage, Context.CONTEXT_RESTRICTED,
                UserHandle.of(mUser));
        final Context gated = SplashImages.gate(mContext, packageContext, mPackage, mUser);
        assertNotNull("no gated splash context", gated);
        gated.setTheme(theme);
        return gated;
    }

    private static Drawable attr(Context context, int index) {
        final TypedArray a = context.obtainStyledAttributes(com.android.internal.R.styleable.Window);
        try {
            return SplashImages.complete(() -> a.getDrawable(index));
        } finally {
            a.recycle();
        }
    }

    /** Without clean copies: the bitmap images are left out at once, without the VM, and nothing is kept. */
    @Test
    public void noCopies_leftOut_noVmWait() throws Exception {
        final Context context = splashContext(R.style.VmTestSplash);
        final long start = SystemClock.elapsedRealtime();
        final boolean[] result = onThread(() -> {
            SplashImages.begin();
            final boolean icon = attr(context,
                    com.android.internal.R.styleable.Window_windowSplashScreenAnimatedIcon) == null;
            final boolean branding = attr(context,
                    com.android.internal.R.styleable.Window_windowSplashScreenBrandingImage) == null;
            final boolean background = attr(context,
                    com.android.internal.R.styleable.Window_windowBackground) == null;
            return new boolean[] {icon, branding, background, SplashImages.missed()};
        });
        final long took = SystemClock.elapsedRealtime() - start;
        assertTrue("icon left out", result[0]);
        assertTrue("branding (<bitmap>) left out", result[1]);
        assertTrue("background (layer list) left out", result[2]);
        assertTrue("splash missed", result[3]);
        assertTrue("took " + took + " ms: the start waited", took < 1_000);
        final File dir = new File(mContext.createDeviceProtectedStorageContext().getFilesDir(), "clean-images");
        final String[] files = dir.list();
        assertTrue("nothing kept without the VM", files == null || files.length == 0);
    }

    /** A vector splash icon is XML only: it shows as before, nothing missed. */
    @Test
    public void vectorIcon_loads() throws Exception {
        final Context context = splashContext(R.style.VmTestSplashVector);
        final Object[] result = onThread(() -> {
            SplashImages.begin();
            final Drawable icon = attr(context,
                    com.android.internal.R.styleable.Window_windowSplashScreenAnimatedIcon);
            return new Object[] {icon, SplashImages.missed()};
        });
        assertNotNull("vector icon", result[0]);
        assertFalse("nothing missed", (Boolean) result[1]);
    }

    /** The main thread never reads the store. */
    @Test
    public void store_notOnTheMainThread() throws Exception {
        final Bitmap red = solid(9, 5, 0xffff0000);
        CleanImageStore.get(mContext).save("rfile:/nowhere:1", red, null);
        final Object[] out = new Object[1];
        InstrumentationRegistry.getInstrumentation().runOnMainSync(
                () -> out[0] = CleanImageStore.get(mContext).load("rfile:/nowhere:1"));
        assertNull(out[0]);
    }

    /** What is saved is loaded with its pixels and density, only under its own key, and a damaged file is removed. */
    @Test
    public void store_roundTrip_keyAndDamage() throws Exception {
        final String key = "rfile:" + mContext.getApplicationInfo().sourceDir + ":1:2:res/x.png:480:100x100";
        final Bitmap bitmap = solid(9, 5, 0xff336699);
        bitmap.setDensity(480);
        final CleanImageStore store = CleanImageStore.get(mContext);
        final CleanImageStore.Entry[] got = onThread(() -> {
            store.save(key, bitmap, null);
            return new CleanImageStore.Entry[] {store.load(key), store.load(key + "x")};
        });
        assertNotNull(got[0]);
        assertEquals(9, got[0].bitmap.getWidth());
        assertEquals(5, got[0].bitmap.getHeight());
        assertEquals(480, got[0].bitmap.getDensity());
        assertEquals(0xff336699, got[0].bitmap.getPixel(4, 2));
        assertFalse(got[0].bitmap.isMutable());
        assertNull("another key", got[1]);

        final File file = new File(new File(mContext.createDeviceProtectedStorageContext().getFilesDir(),
                "clean-images"), CleanImageFile.fileName(key));
        assertTrue(file.exists());
        try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
            raf.seek(raf.length() / 2);
            final int b = raf.read();
            raf.seek(raf.length() / 2);
            raf.write(b ^ 0x10);
        }
        assertNull("damaged copy", onThread(() -> store.load(key)));
        assertFalse("damaged copy removed", file.exists());
    }

    /** A nine-patch's data is kept with its copy. */
    @Test
    public void store_keepsNinePatch() throws Exception {
        final NinePatchChunk chunk = NinePatchChunk.fromChunks(ninePatchData(9, 5), null, 9, 5);
        assertNotNull(chunk);
        final String key = "rfile:" + mContext.getApplicationInfo().sourceDir + ":1:2:res/n.9.png:160:100x100";
        final CleanImageStore store = CleanImageStore.get(mContext);
        final CleanImageStore.Entry got = onThread(() -> {
            store.save(key, solid(9, 5, 0xff00ff00), chunk);
            return store.load(key);
        });
        assertNotNull(got);
        assertNotNull(got.chunk);
        assertArrayEquals(chunk.toFileFormat(), got.chunk.toFileFormat());
    }

    /**
     * With the VM: the background make keeps the copies, and the next splash takes them (memory cache emptied, as
     * after a restart) without the VM. Skipped when this process cannot reach the decode service.
     */
    @Test
    public void madeInTheVm_thenTakenFromDisk() throws Exception {
        assumeTrue("needs the decode VM (debuggable build)", vmReachable());
        final Configuration config = new Configuration(mContext.getResources().getConfiguration());
        onThread(() -> {
            SplashImages.make(mContext, new SplashImages.Request(mPackage, mUser, R.style.VmTestSplash, config));
            return null;
        });
        final File dir = new File(mContext.createDeviceProtectedStorageContext().getFilesDir(), "clean-images");
        final String[] files = dir.list();
        // vm_test_red (icon, and in the layer list), vm_test_green (<bitmap>), vm_test_blue (layer list).
        assertNotNull(files);
        assertTrue("copies kept: " + files.length, files.length >= 3);

        VmIcons.trim();
        final Context context = splashContext(R.style.VmTestSplash);
        final Object[] result = onThread(() -> {
            SplashImages.begin();
            final Drawable icon = attr(context,
                    com.android.internal.R.styleable.Window_windowSplashScreenAnimatedIcon);
            final Drawable branding = attr(context,
                    com.android.internal.R.styleable.Window_windowSplashScreenBrandingImage);
            final Drawable background = attr(context, com.android.internal.R.styleable.Window_windowBackground);
            return new Object[] {icon, branding, background, SplashImages.missed()};
        });
        assertTrue("icon from its copy", result[0] instanceof BitmapDrawable);
        final Bitmap icon = ((BitmapDrawable) result[0]).getBitmap();
        assertEquals(0xffff0000, icon.getPixel(icon.getWidth() / 2, icon.getHeight() / 2));
        assertNotNull("branding from its copy", result[1]);
        assertNotNull("background from its copies", result[2]);
        assertFalse("nothing missed", (Boolean) result[3]);
    }

    private boolean vmReachable() {
        try {
            final byte[] png = png(0x123456);
            final Bitmap decoded = onThread(() -> VmDecode.decodeBytes(mContext, png, 0, png.length, mPackage,
                    VmDecode.PRIORITY_USER_WAITING, 64, 64, VmDecode.deadlineIn(20_000)));
            return decoded != null;
        } catch (Exception e) {
            return false;
        }
    }

    private static Bitmap solid(int w, int h, int argb) {
        final Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(argb);
        return bitmap;
    }

    private static byte[] png(int rgb) {
        final Bitmap bitmap = solid(7, 3, 0xff000000 | rgb);
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        return out.toByteArray();
    }

    // npTc data of a w x h nine-patch with one stretch area each way, as a PNG file stores it (network order).
    private static byte[] ninePatchData(int w, int h) {
        final java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(32 + 4 * 4);
        b.put((byte) 0).put((byte) 2).put((byte) 2).put((byte) 0);
        b.putInt(32).putInt(40);
        b.putInt(1).putInt(1).putInt(1).putInt(1);
        b.putInt(48);
        b.putInt(1).putInt(w - 1).putInt(1).putInt(h - 1);
        return b.array();
    }
}
