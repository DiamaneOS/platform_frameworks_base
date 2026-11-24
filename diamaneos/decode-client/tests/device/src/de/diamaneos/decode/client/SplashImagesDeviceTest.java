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
import static org.junit.Assert.fail;

import android.content.Context;
import com.android.internal.diamaneos.cleancopies.CleanCopyClient;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.SystemClock;
import android.os.UserHandle;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import de.diamaneos.decode.client.tests.R;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.RandomAccessFile;
import java.util.Collections;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/**
 * Splash screen images from the system server's clean copies (SplashImages, CleanImages, CleanImageStore): the app
 * start never waits for the VM and never decodes an app's image file, a drawable with a missing copy is left out and
 * its files are asked for, the system server makes them in the VM and the next start takes them, the system server
 * refuses a file of an APK the app does not load from, and a damaged or foreign copy is never shown. This test APK is
 * under /data, so its bitmap files are an app's. It is platform-signed, so the system server serves it like SystemUI.
 */
@RunWith(AndroidJUnit4.class)
public class SplashImagesDeviceTest {
    private static final long MADE_WITHIN_MS = 60_000;

    private final Context mContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private final String mPackage = mContext.getPackageName();
    private final int mUser = UserHandle.myUserId();
    private File mTempStore;

    @Before
    public void emptyCaches() {
        CleanImages.get(mContext).forget(mContext.getApplicationInfo().sourceDir);
        VmIcons.trim();
        mTempStore = new File(mContext.getCacheDir(), "store-" + SystemClock.elapsedRealtimeNanos());
    }

    @After
    public void removeTempStore() {
        new CleanImageStore(mTempStore).clear();
        mTempStore.delete();
    }

    private static <T> T onThread(Callable<T> callable) throws Exception {
        final FutureTask<T> task = new FutureTask<>(callable);
        final Thread thread = new Thread(task, "splash-test");
        thread.start();
        return task.get(90, TimeUnit.SECONDS);
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

    // The splash's three images, and whether any was left out, as a splash on a worker loads them.
    private Object[] splashImages(Context context) throws Exception {
        return onThread(() -> {
            VmIcons.trim();
            SplashImages.begin();
            final Drawable icon = attr(context,
                    com.android.internal.R.styleable.Window_windowSplashScreenAnimatedIcon);
            final Drawable branding = attr(context,
                    com.android.internal.R.styleable.Window_windowSplashScreenBrandingImage);
            final Drawable background = attr(context, com.android.internal.R.styleable.Window_windowBackground);
            return new Object[] {icon, branding, background, SplashImages.missed()};
        });
    }

    /** Without clean copies: the bitmap images are left out at once, without the VM. */
    @Test
    public void noCopies_leftOut_noVmWait() throws Exception {
        final Context context = splashContext(R.style.VmTestSplash);
        final long start = SystemClock.elapsedRealtime();
        final Object[] result = splashImages(context);
        final long took = SystemClock.elapsedRealtime() - start;
        assertNull("icon left out", result[0]);
        assertNull("branding (<bitmap>) left out", result[1]);
        assertNull("background (layer list) left out", result[2]);
        assertTrue("splash missed", (Boolean) result[3]);
        assertTrue("took " + took + " ms: the start waited", took < 1_000);
    }

    /** The files a splash missed are made by the system server in the VM, and a later splash takes them. */
    @Test
    public void missedFiles_madeBySystemServer_thenTaken() throws Exception {
        final Context context = splashContext(R.style.VmTestSplash);
        Object[] result = splashImages(context);
        assertTrue("first splash misses", (Boolean) result[3]);
        final long end = SystemClock.elapsedRealtime() + MADE_WITHIN_MS;
        while ((Boolean) result[3] && SystemClock.elapsedRealtime() < end) {
            SystemClock.sleep(1_000);
            result = splashImages(context);
        }
        assertFalse("still missing after " + MADE_WITHIN_MS + " ms (is the decode VM up?)", (Boolean) result[3]);
        assertTrue("icon from its copy", result[0] instanceof BitmapDrawable);
        final Bitmap icon = ((BitmapDrawable) result[0]).getBitmap();
        assertEquals(0xffff0000, icon.getPixel(icon.getWidth() / 2, icon.getHeight() / 2));
        assertNotNull("branding from its copy", result[1]);
        assertNotNull("background from its copies", result[2]);
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

    /**
     * The system server makes no file of an APK of the system image (decoded as before everywhere), and does not count
     * it as open. Whose file it is comes from the system server alone: the request names no owner.
     */
    @Test
    public void foreignApk_refused() throws Exception {
        final ApplicationInfo other = mContext.getPackageManager().getApplicationInfo("com.android.settings", 0);
        final String file = "res/drawable/x.png";
        final String key = AppResourceImages.keyOf(other.sourceDir, file, 160, 100, 100);
        final Bundle extras = new Bundle();
        extras.putString(CleanCopyClient.EXTRA_APK, other.sourceDir);
        extras.putString(CleanCopyClient.EXTRA_FILE, file);
        extras.putInt(CleanCopyClient.EXTRA_DENSITY, 160);
        extras.putInt(CleanCopyClient.EXTRA_WIDTH, 100);
        extras.putInt(CleanCopyClient.EXTRA_HEIGHT, 100);
        extras.putInt(CleanCopyClient.EXTRA_USER, mUser);
        mContext.getContentResolver().call(CleanCopyClient.URI, CleanCopyClient.METHOD_REQUEST, null, extras);
        final long end = SystemClock.elapsedRealtime() + 10_000;
        int open = 1;
        while (open != 0 && SystemClock.elapsedRealtime() < end) {
            SystemClock.sleep(200);
            open = onThread(() -> CleanImages.get(mContext).open(Collections.singletonList(key)));
        }
        assertEquals("refused, not open", 0, open);
        try {
            mContext.getContentResolver().openFileDescriptor(CleanCopyClient.uriFor(key), "r").close();
            fail("a copy of a foreign APK's file");
        } catch (FileNotFoundException expected) {
            // Nothing made.
        }
    }

    /** The provider opens only plain copy names, read-only. */
    @Test
    public void provider_onlyCopyNames_readOnly() throws Exception {
        for (String name : new String[] {"..", "x.dci", "../packages.xml"}) {
            try {
                mContext.getContentResolver().openFileDescriptor(
                        CleanCopyClient.URI.buildUpon().appendPath(name).build(), "r").close();
                fail("opened " + name);
            } catch (FileNotFoundException | IllegalArgumentException expected) {
                // Refused.
            }
        }
        try {
            mContext.getContentResolver().openFileDescriptor(CleanCopyClient.uriFor("rfile:x"), "rw").close();
            fail("opened for writing");
        } catch (SecurityException | FileNotFoundException expected) {
            // Refused.
        }
    }

    /** A copy is read on the main thread too (as the APK's file was before): one small read, never a VM wait. */
    @Test
    public void store_readsOnTheMainThread() throws Exception {
        final CleanImageStore store = new CleanImageStore(mTempStore);
        onThread(() -> {
            store.save("rfile:/nowhere:1", solid(9, 5, 0xffff0000), null);
            return null;
        });
        final Object[] out = new Object[1];
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> out[0] = store.load("rfile:/nowhere:1"));
        assertNotNull(out[0]);
    }

    /**
     * The newest copy of a resource stands in while its exact copy is made: the closest density wins, a new copy
     * replaces the older ones of the same resource and density, and an app's removal takes its copies.
     */
    @Test
    public void store_standInCopies() throws Exception {
        final CleanImageStore store = new CleanImageStore(mTempStore);
        final String identity = "res:p.app/p.app:mipmap/ic//100x100";
        final String old480 = "rfile:/data/app/old/base.apk:1:2:res/a.png:480:100x100";
        final String old320 = "rfile:/data/app/old/base.apk:1:2:res/b.png:320:100x100";
        final String new480 = "rfile:/data/app/new/base.apk:3:4:res/a.png:480:100x100";
        final String[] got = onThread(() -> {
            final Bitmap b480 = solid(4, 4, 0xff111111);
            b480.setDensity(480);
            final Bitmap b320 = solid(4, 4, 0xff222222);
            b320.setDensity(320);
            final long now = System.currentTimeMillis();
            store.saveCopy(old480, identity, b480, null, now);
            store.saveCopy(old320, identity, b320, null, now + 1);
            final String closest = store.fallbackKey(identity, 440);
            final String low = store.fallbackKey(identity, 300);
            store.saveCopy(new480, identity, b480, null, now + 2);
            final String replaced = store.has(old480) ? "kept" : "gone";
            store.forgetOwner("p.app");
            final String afterRemoval = store.fallbackKey(identity, 480);
            return new String[] {closest, low, replaced, afterRemoval};
        });
        assertEquals("closest density", old480, got[0]);
        assertEquals("closest density", old320, got[1]);
        assertEquals("the new copy replaced the old one", "gone", got[2]);
        assertNull("an app's removal takes its copies", got[3]);
    }

    /** What is saved is loaded with its pixels and density, only under its own key, and a damaged file is removed. */
    @Test
    public void store_roundTrip_keyAndDamage() throws Exception {
        final String key = "rfile:" + mContext.getApplicationInfo().sourceDir + ":1:2:res/x.png:480:100x100";
        final Bitmap bitmap = solid(9, 5, 0xff336699);
        bitmap.setDensity(480);
        final CleanImageStore store = new CleanImageStore(mTempStore);
        final CleanCopies.Entry[] got = onThread(() -> {
            store.save(key, bitmap, null);
            return new CleanCopies.Entry[] {store.load(key), store.load(key + "x")};
        });
        assertNotNull(got[0]);
        assertEquals(9, got[0].bitmap.getWidth());
        assertEquals(5, got[0].bitmap.getHeight());
        assertEquals(480, got[0].bitmap.getDensity());
        assertEquals(0xff336699, got[0].bitmap.getPixel(4, 2));
        assertFalse(got[0].bitmap.isMutable());
        assertNull("another key", got[1]);

        final File file = store.fileFor(key);
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
        final CleanImageStore store = new CleanImageStore(mTempStore);
        final CleanCopies.Entry got = onThread(() -> {
            store.save(key, solid(9, 5, 0xff00ff00), chunk);
            return store.load(key);
        });
        assertNotNull(got);
        assertNotNull(got.chunk);
        assertArrayEquals(chunk.toFileFormat(), got.chunk.toFileFormat());
    }

    // An activity of this test APK whose icon is its red PNG.
    private ActivityInfo redIconActivity() {
        final ActivityInfo ai = new ActivityInfo();
        ai.packageName = mPackage;
        ai.name = "de.diamaneos.decode.client.SplashTestActivity";
        ai.applicationInfo = mContext.getApplicationInfo();
        ai.icon = R.drawable.vm_test_red;
        return ai;
    }

    /** The splash's app icon without its copy: none at once (an empty splash), then made by the system server. */
    @Test
    public void appIcon_noCopy_none_thenMade() throws Exception {
        final Context context = splashContext(R.style.VmTestSplashVector);
        final Callable<Object[]> load = () -> {
            VmIcons.trim();
            SplashImages.begin();
            final Drawable icon = SplashImages.appIcon(mContext, context, redIconActivity(), 480);
            return new Object[] {icon, SplashImages.missed()};
        };
        Object[] result = onThread(load);
        assertNull("no icon without its copy", result[0]);
        assertTrue("missed", (Boolean) result[1]);
        final long end = SystemClock.elapsedRealtime() + MADE_WITHIN_MS;
        while (result[0] == null && SystemClock.elapsedRealtime() < end) {
            SystemClock.sleep(1_000);
            result = onThread(load);
        }
        assertTrue("icon from its copy", result[0] instanceof BitmapDrawable);
        final Bitmap icon = ((BitmapDrawable) result[0]).getBitmap();
        assertEquals(0xffff0000, icon.getPixel(icon.getWidth() / 2, icon.getHeight() / 2));
        assertFalse("nothing missed", (Boolean) result[1]);
    }

    /** An activity without an icon gets Android's default icon, which is no app image. */
    @Test
    public void appIcon_none_isTheDefault() throws Exception {
        final ActivityInfo ai = redIconActivity();
        ai.icon = 0;
        final ApplicationInfo app = new ApplicationInfo(mContext.getApplicationInfo());
        app.icon = 0;
        ai.applicationInfo = app;
        final Context context = splashContext(R.style.VmTestSplash);
        final Object[] result = onThread(() -> {
            SplashImages.begin();
            return new Object[] {SplashImages.appIcon(mContext, context, ai, 480), SplashImages.missed()};
        });
        assertNotNull(result[0]);
        assertFalse((Boolean) result[1]);
    }

    private static Bitmap solid(int w, int h, int argb) {
        final Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(argb);
        return bitmap;
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
