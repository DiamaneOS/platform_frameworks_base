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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.DrawableWrapper;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.SystemClock;
import android.util.Log;
import android.widget.ImageView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.internal.diamaneos.cleancopies.VmIconLoads;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Icons of compressed bytes ({@code Icon.createWithData}, content and file URIs) loaded through
 * {@code Icon.loadDrawable} in a process that has them decoded in the VM (VmIconLoads, forced on here as in a system
 * app's process): the pixels come from the VM through the system server, broken bytes give a blank picture, the main
 * thread waits at most the short wait and the picture fills in later, and a process without it decodes as before.
 * With the instrumentation argument {@code iconsVmStopped=true} (the decode service stopped by the caller), loads give
 * blank pictures within their time and nothing is decoded here.
 */
@RunWith(AndroidJUnit4.class)
public class VmIconLoadsDeviceTest {
    private static final String TAG = "VmIconTest";
    private static final int SIDE = 16;
    // Room for scheduling on top of the waits the code allows.
    private static final long MARGIN_MS = 150;

    private final Context mContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private static final AtomicLong sColor = new AtomicLong(0x203040);

    @Before
    public void gated() {
        VmIconLoads.resetForTest();
        VmIconLoads.setForcedForTest(true);
        ProbeProvider.ICONS.clear();
    }

    @After
    public void asBefore() {
        VmIconLoads.setForcedForTest(null);
        VmIconLoads.resetForTest();
        ProbeProvider.ICONS.clear();
    }

    // A PNG of one colour no other test used, so no load finds it made before.
    private static int freshColor() {
        return 0xff000000 | (int) (sColor.addAndGet(0x010307) & 0xffffff);
    }

    private static byte[] png(int color) {
        final Bitmap bitmap = Bitmap.createBitmap(SIDE, SIDE, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(color);
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out));
        return out.toByteArray();
    }

    private static Bitmap bitmapOf(Drawable drawable) {
        if (drawable instanceof AdaptiveIconDrawable) {
            drawable = ((AdaptiveIconDrawable) drawable).getForeground();
        }
        if (drawable instanceof DrawableWrapper) {
            drawable = ((DrawableWrapper) drawable).getDrawable();
        }
        assertTrue("a bitmap drawable: " + drawable, drawable instanceof BitmapDrawable);
        return ((BitmapDrawable) drawable).getBitmap();
    }

    private static void assertPicture(int color, Drawable drawable) {
        assertNotNull("no drawable", drawable);
        final Bitmap bitmap = bitmapOf(drawable);
        assertNotNull("no pixels", bitmap);
        assertEquals(SIDE, bitmap.getWidth());
        assertEquals(SIDE, bitmap.getHeight());
        final int got = bitmap.getPixel(SIDE / 2, SIDE / 2);
        assertTrue(String.format("pixel %08x, want %08x", got, color),
                Math.abs(Color.red(got) - Color.red(color)) <= 2
                        && Math.abs(Color.green(got) - Color.green(color)) <= 2
                        && Math.abs(Color.blue(got) - Color.blue(color)) <= 2);
    }

    private static void assertBlank(Drawable drawable) {
        if (drawable == null) {
            return; // A URI icon without pixels is null, as Icon gives it.
        }
        final Bitmap bitmap = bitmapOf(drawable);
        assertNotNull(bitmap);
        assertTrue("not blank: " + bitmap.getWidth() + "x" + bitmap.getHeight(),
                bitmap.getWidth() == 1 && bitmap.getHeight() == 1 && Color.alpha(bitmap.getPixel(0, 0)) == 0);
    }

    private Uri probeIcon(String folder, int color) {
        final String name = Integer.toHexString(color);
        ProbeProvider.ICONS.put(name, png(color));
        return Uri.parse("content://" + ProbeProvider.AUTHORITY + "/" + folder + "/" + name);
    }

    @Test
    public void dataIconComesFromTheVm() {
        final int color = freshColor();
        final int made = VmIconLoads.madeForTest();
        final Drawable drawable = Icon.createWithData(png(color), 0, png(color).length).loadDrawable(mContext);
        assertPicture(color, drawable);
        assertEquals("the pixels came from the VM", made + 1, VmIconLoads.madeForTest());
        // Kept: the same bytes again need no VM.
        assertPicture(color, Icon.createWithData(png(color), 0, png(color).length).loadDrawable(mContext));
        assertEquals(made + 1, VmIconLoads.madeForTest());
    }

    @Test
    public void contentUriIconsComeFromTheVm() {
        final int color = freshColor();
        final int made = VmIconLoads.madeForTest();
        final int opens = ProbeProvider.OPENS.get();
        assertPicture(color, Icon.createWithContentUri(probeIcon("icon", color)).loadDrawable(mContext));
        assertEquals("opened here with this process's access", opens + 1, ProbeProvider.OPENS.get());
        assertEquals(made + 1, VmIconLoads.madeForTest());

        final int adaptiveColor = freshColor();
        final Drawable adaptive = Icon.createWithAdaptiveBitmapContentUri(probeIcon("icon", adaptiveColor))
                .loadDrawable(mContext);
        assertTrue("adaptive as before: " + adaptive, adaptive instanceof AdaptiveIconDrawable);
        assertPicture(adaptiveColor, adaptive);
        assertEquals(made + 2, VmIconLoads.madeForTest());
    }

    @Test
    public void fileIconsComeFromTheVm() throws Exception {
        final int color = freshColor();
        final File file = new File(mContext.getCacheDir(), "vm-icon-" + Integer.toHexString(color) + ".png");
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(png(color));
        }
        try {
            final int made = VmIconLoads.madeForTest();
            assertPicture(color, Icon.createWithFilePath(file.getPath()).loadDrawable(mContext));
            assertPicture(color, Icon.createWithContentUri(Uri.fromFile(file)).loadDrawable(mContext));
            assertEquals(made + 2, VmIconLoads.madeForTest());
        } finally {
            file.delete();
        }
    }

    @Test
    public void imageViewLinksComeFromTheVm() {
        final int color = freshColor();
        final Uri uri = probeIcon("icon", color);
        final int made = VmIconLoads.madeForTest();
        final AtomicReference<ImageView> view = new AtomicReference<>();
        final AtomicLong took = new AtomicLong();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            final ImageView v = new ImageView(mContext);
            final long start = SystemClock.uptimeMillis();
            v.setImageURI(uri);
            took.set(SystemClock.uptimeMillis() - start);
            view.set(v);
        });
        Log.i(TAG, "ImageView.setImageURI on the main thread: " + took.get() + " ms");
        assertTrue("main thread waited " + took.get() + " ms", took.get() <= VmIconLoads.SHORT_WAIT_MS + MARGIN_MS);
        final long until = SystemClock.uptimeMillis() + VmIconLoads.LOAD_MS;
        while (SystemClock.uptimeMillis() < until && VmIconLoads.madeForTest() == made) {
            SystemClock.sleep(20);
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        assertEquals("the picture came from the VM", made + 1, VmIconLoads.madeForTest());
        assertPicture(color, view.get().getDrawable());
    }

    @Test
    public void brokenBytesAreBlankAndNotSentAgain() {
        final byte[] broken = png(freshColor());
        broken[20] ^= 0x55; // The header's checksum no longer matches.
        final int made = VmIconLoads.madeForTest();
        final int failed = VmIconLoads.failedForTest();
        assertBlank(Icon.createWithData(broken, 0, broken.length).loadDrawable(mContext));
        assertBlank(Icon.createWithData(broken, 0, broken.length).loadDrawable(mContext));
        assertEquals(made, VmIconLoads.madeForTest());
        assertEquals(failed + 2, VmIconLoads.failedForTest());
    }

    @Test
    public void processWithoutItDecodesAsBefore() {
        VmIconLoads.setForcedForTest(false);
        final int color = freshColor();
        final int made = VmIconLoads.madeForTest();
        assertPicture(color, Icon.createWithData(png(color), 0, png(color).length).loadDrawable(mContext));
        assertPicture(color, Icon.createWithContentUri(probeIcon("icon", color)).loadDrawable(mContext));
        assertEquals("nothing went to the VM", made, VmIconLoads.madeForTest());
    }

    @Test
    public void mainThreadWaitsAtMostTheShortWait() {
        final int color = freshColor();
        final Uri slow = probeIcon("icon-slow", color);
        final AtomicReference<Drawable> loaded = new AtomicReference<>();
        final AtomicLong took = new AtomicLong();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            final long start = SystemClock.uptimeMillis();
            loaded.set(Icon.createWithContentUri(slow).loadDrawable(mContext));
            took.set(SystemClock.uptimeMillis() - start);
        });
        Log.i(TAG, "main thread, provider slower than the wait: " + took.get() + " ms");
        assertTrue("main thread waited " + took.get() + " ms",
                took.get() <= VmIconLoads.SHORT_WAIT_MS + MARGIN_MS);
        final Drawable filling = loaded.get();
        assertNotNull(filling);
        assertBlank(filling);

        // The wait ran out: the next main thread loads do not wait at all.
        final int next = freshColor();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            final long start = SystemClock.uptimeMillis();
            Icon.createWithData(png(next), 0, png(next).length).loadDrawable(mContext);
            took.set(SystemClock.uptimeMillis() - start);
        });
        Log.i(TAG, "main thread right after a wait ran out: " + took.get() + " ms");
        assertTrue("main thread waited " + took.get() + " ms after a wait ran out", took.get() <= MARGIN_MS);

        // The picture fills in on the main thread once the provider answered and the VM made it.
        final long until = SystemClock.uptimeMillis() + ProbeProvider.SLOW_MS + VmIconLoads.LOAD_MS;
        while (SystemClock.uptimeMillis() < until && bitmapOf(filling).getWidth() == 1) {
            SystemClock.sleep(50);
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        }
        Log.i(TAG, "filled in: " + bitmapOf(filling).getWidth() + "x" + bitmapOf(filling).getHeight());
        assertPicture(color, filling);
    }

    @Test
    public void mainThreadTimeWithTheVmUp() {
        final long[] times = new long[5];
        for (int i = 0; i < times.length; i++) {
            VmIconLoads.resetForTest();
            VmIconLoads.setForcedForTest(true);
            final int color = freshColor();
            final byte[] bytes = png(color);
            final AtomicReference<Drawable> loaded = new AtomicReference<>();
            final int index = i;
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                final long start = SystemClock.uptimeMillis();
                loaded.set(Icon.createWithData(bytes, 0, bytes.length).loadDrawable(mContext));
                times[index] = SystemClock.uptimeMillis() - start;
            });
            assertTrue("main thread waited " + times[i] + " ms",
                    times[i] <= VmIconLoads.SHORT_WAIT_MS + MARGIN_MS);
            assertNotNull(loaded.get());
        }
        Log.i(TAG, "main thread, data icon, VM up: " + java.util.Arrays.toString(times) + " ms");
    }

    @Test
    public void failsClosedWhileTheDecodeServiceIsStopped() {
        assumeTrue("run with -e iconsVmStopped true while the decode service is stopped",
                "true".equals(InstrumentationRegistry.getArguments().getString("iconsVmStopped")));
        final int color = freshColor();
        final int made = VmIconLoads.madeForTest();
        long start = SystemClock.uptimeMillis();
        assertBlank(Icon.createWithData(png(color), 0, png(color).length).loadDrawable(mContext));
        long took = SystemClock.uptimeMillis() - start;
        Log.i(TAG, "worker thread, decode service stopped: " + took + " ms");
        assertTrue("took " + took + " ms", took <= VmIconLoads.LOAD_MS + MARGIN_MS);

        final int uriColor = freshColor();
        start = SystemClock.uptimeMillis();
        assertBlank(Icon.createWithContentUri(probeIcon("icon", uriColor)).loadDrawable(mContext));
        took = SystemClock.uptimeMillis() - start;
        assertTrue("took " + took + " ms", took <= VmIconLoads.LOAD_MS + MARGIN_MS);

        final int mainColor = freshColor();
        final AtomicLong mainTook = new AtomicLong();
        final AtomicReference<Drawable> loaded = new AtomicReference<>();
        VmIconLoads.resetForTest();
        VmIconLoads.setForcedForTest(true);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            final long s = SystemClock.uptimeMillis();
            loaded.set(Icon.createWithData(png(mainColor), 0, png(mainColor).length).loadDrawable(mContext));
            mainTook.set(SystemClock.uptimeMillis() - s);
        });
        Log.i(TAG, "main thread, decode service stopped: " + mainTook.get() + " ms");
        assertTrue("main thread waited " + mainTook.get() + " ms",
                mainTook.get() <= VmIconLoads.SHORT_WAIT_MS + MARGIN_MS);
        assertBlank(loaded.get());
        assertEquals("nothing was made, here or in the VM", made, VmIconLoads.madeForTest());
    }
}
