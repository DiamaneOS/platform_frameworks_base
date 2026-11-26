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

import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetProviderInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageItemInfo;
import android.content.pm.PackageManager;
import android.content.res.ImageFileGate;
import android.content.res.ImageFileGates;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.DecodeGate;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.DrawableWrapper;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.TypedValue;
import android.util.Log;
import android.widget.ImageView;
import android.widget.RemoteViews;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.internal.diamaneos.cleancopies.CleanCopyClient;
import com.android.internal.diamaneos.cleancopies.CleanCopyGate;
import com.android.internal.diamaneos.cleancopies.CleanImageFile;

import de.diamaneos.decode.client.tests.R;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/**
 * The process image gate of the clean icon cache (CleanCopyGate), with a source of copies made here: a file without
 * a copy shows empty and is asked for with its key and resource name (never an owner: the system server finds that),
 * a copy shows with its pixels and density, the launcher's gate gates everything but hosted widgets and widget
 * previews, a waiting gate waits for a new copy on a worker and never on the main thread, raw image files are known by
 * their bytes, a missing mipmap shows Android's default icon, and the package manager shows the default icon (nothing
 * kept) while an icon misses a copy. This test APK is under /data, so its bitmap files are an app's.
 */
@RunWith(AndroidJUnit4.class)
public class CleanCopyGateDeviceTest {
    private final Context mContext = InstrumentationRegistry.getInstrumentation().getTargetContext();

    @After
    public void noProcessGate() {
        ImageFileGates.setProcessDefault(null);
        CleanCopyGate.setModeForTest(-1);
        DecodeGate.setForcedForTest(null);
        DecodeGate.resetForTest();
    }

    /** A source of copies made here: what it holds, and what it was asked for. */
    private static final class FakeSource implements CleanCopyGate.Source {
        final List<String> requests = new ArrayList<>();
        final List<String> resources = new ArrayList<>();
        volatile CleanImageFile copy;
        volatile CleanImageFile older;
        volatile long copyAfter;

        @Override
        public CleanImageFile read(String key) {
            final CleanImageFile c = copy;
            if (c == null || SystemClock.elapsedRealtime() < copyAfter) {
                return null;
            }
            return new CleanImageFile(key, "", c.width, c.height, c.density, c.colorSpace, c.npTc, c.npLb,
                    c.pixels);
        }

        @Override
        public CleanImageFile fallback(String apkPath, String file, int density, int maxWidth, int maxHeight,
                String resourceName, int userId) {
            final CleanImageFile o = older;
            return o == null ? null : new CleanImageFile("old", "", o.width, o.height, o.density,
                    o.colorSpace, o.npTc, o.npLb, o.pixels);
        }

        @Override
        public synchronized void request(String key, String apkPath, String file, int density, int maxWidth,
                int maxHeight, String resourceName, int userId) {
            requests.add(key);
            resources.add(resourceName);
        }
    }

    private static CleanImageFile solid(int w, int h, int argb, int density) {
        final Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(argb);
        final ByteBuffer pixels = ByteBuffer.allocate(4 * w * h);
        bitmap.copyPixelsToBuffer(pixels);
        return new CleanImageFile("k", "", w, h, density, CleanImageFile.COLOR_SRGB, null, null, pixels.array());
    }

    // This APK's resources behind {@code gate} (a Resources object of its own).
    private Resources gated(ImageFileGate gate) {
        final Resources res = mContext.createConfigurationContext(mContext.getResources().getConfiguration())
                .getResources();
        res.setImageFileGate(gate);
        return res;
    }

    /** How a copy key for this test's own user ends: the user serial, then the user. */
    private String userSuffix() {
        final int user = mContext.getUserId();
        return ":s" + CleanCopyClient.userSerial(mContext, user) + ":u" + user;
    }

    private static <T> T onThread(Callable<T> callable) throws Exception {
        final FutureTask<T> task = new FutureTask<>(callable);
        new Thread(task, "gate-test").start();
        return task.get(30, TimeUnit.SECONDS);
    }

    @Test
    public void noCopy_empty_askedWithItsResource() throws Exception {
        final FakeSource source = new FakeSource();
        final Resources res = gated(CleanCopyGate.forTest(mContext, source, false));
        final Object[] out = onThread(() -> {
            final int before = CleanCopyGate.misses();
            final Drawable d = res.getDrawable(R.drawable.vm_test_red, null);
            return new Object[] {d, CleanCopyGate.misses() - before};
        });
        assertTrue("empty", out[0] instanceof ColorDrawable);
        assertEquals("one miss", 1, out[1]);
        assertEquals(1, source.requests.size());
        assertEquals(mContext.getPackageName() + ":drawable/vm_test_red", source.resources.get(0));
        final int[] box = CleanCopyClient.box(mContext);
        assertTrue(source.requests.get(0).startsWith("rfile:" + mContext.getApplicationInfo().sourceDir + ":"));
        assertTrue(source.requests.get(0).endsWith(":" + box[0] + "x" + box[1] + userSuffix()));
    }

    @Test
    public void copy_shownWithPixelsAndDensity() throws Exception {
        final FakeSource source = new FakeSource();
        source.copy = solid(9, 5, 0xff123456, 480);
        final Resources res = gated(CleanCopyGate.forTest(mContext, source, false));
        final Object[] out = onThread(() -> {
            final int before = CleanCopyGate.misses();
            final Drawable d = res.getDrawable(R.drawable.vm_test_red, null);
            return new Object[] {d, CleanCopyGate.misses() - before};
        });
        assertTrue(out[0] instanceof BitmapDrawable);
        final Bitmap b = ((BitmapDrawable) out[0]).getBitmap();
        assertEquals(9, b.getWidth());
        assertEquals(480, b.getDensity());
        assertEquals(0xff123456, b.getPixel(4, 2));
        assertEquals(0, out[1]);
        assertTrue("nothing asked", source.requests.isEmpty());
    }

    /** A {@code <bitmap>} (a stream the framework decodes itself) gets a PNG written here from the copy. */
    @Test
    public void stream_isAPngOfTheCopy() throws Exception {
        final FakeSource source = new FakeSource();
        source.copy = solid(7, 3, 0xff00ff00, 0);
        final Resources res = gated(CleanCopyGate.forTest(mContext, source, false));
        final Bitmap decoded = onThread(() -> {
            final TypedValue value = new TypedValue();
            try (InputStream in = res.openRawResource(R.drawable.vm_test_green, value)) {
                return BitmapFactory.decodeStream(in);
            }
        });
        assertNotNull(decoded);
        assertEquals(7, decoded.getWidth());
        assertEquals(0xff00ff00, decoded.getPixel(3, 1));
    }

    /** The launcher's gate takes every other app's file from copies, whatever loads it: nothing is exempt. */
    @Test
    public void launcher_gatesEverything() throws Exception {
        final FakeSource source = new FakeSource();
        final Resources res = gated(CleanCopyGate.forTest(mContext, source, false));
        final Object[] out = onThread(() -> {
            final Drawable plain = res.getDrawable(R.drawable.vm_test_red, null);
            final Drawable icon = CleanCopyGate.iconLoad(() -> res.getDrawable(R.drawable.vm_test_red, null));
            return new Object[] {plain, icon};
        });
        assertTrue("any load: from copies", out[0] instanceof ColorDrawable);
        assertTrue("an icon load: from copies", out[1] instanceof ColorDrawable);
    }

    private static Bitmap rendered(Drawable drawable) {
        final Bitmap bitmap = Bitmap.createBitmap(83, 61, Bitmap.Config.ARGB_8888);
        drawable.setBounds(0, 0, 83, 61);
        drawable.draw(new Canvas(bitmap));
        return bitmap;
    }

    @Test
    public void launcherFirstLoad_hasVmPixelsWithoutACopy() throws Exception {
        final TypedValue value = new TypedValue();
        mContext.getResources().getValue(R.drawable.vm_test_large, value, true);
        try (java.util.zip.ZipFile apk = new java.util.zip.ZipFile(mContext.getApplicationInfo().sourceDir)) {
            assertTrue("positive icon above 1 MiB", apk.getEntry(value.string.toString()).getSize() > 1024 * 1024);
        }
        for (int id : new int[] {R.drawable.vm_test_red, R.drawable.vm_test_adaptive,
                R.drawable.vm_test_large, R.drawable.vm_test_patch}) {
            DecodeGate.setForcedForTest(false);
            final Bitmap stock = rendered(onThread(() -> mContext.getResources().getDrawable(id, null)));
            final FakeSource source = new FakeSource();
            final Resources res = gated(CleanCopyGate.forTest(mContext, source, true, "com.android.launcher3"));
            DecodeGate.setForcedForTest(true);
            DecodeGate.resetForTest();
            final int[] before = DecodeGate.countsForTest();
            final long start = SystemClock.elapsedRealtime();
            final Drawable first = onThread(() -> CleanCopyGate.iconLoad(() -> res.getDrawable(id, null)));
            Log.i("CleanCopyGateTest", "first icon " + id + " ms=" + (SystemClock.elapsedRealtime() - start));
            assertTrue("checked first-load geometry and pixels " + id, stock.sameAs(rendered(first)));
            assertTrue("VM answered " + id, DecodeGate.countsForTest()[0] > before[0]);
            assertTrue("no copy requested for " + id, source.requests.isEmpty());
        }
    }

    @Test
    public void launcherInactiveGate_doesNotDecodeFirstLoad() throws Exception {
        DecodeGate.setForcedForTest(false);
        final FakeSource source = new FakeSource();
        final Resources res = gated(CleanCopyGate.forTest(mContext, source, true, "com.android.launcher3"));
        final Drawable first = onThread(() -> CleanCopyGate.iconLoad(() -> res.getDrawable(R.drawable.vm_test_red, null)));
        assertTrue("inactive gate remains empty", first instanceof ColorDrawable);
        assertTrue("VM refusal uses refill", source.requests.isEmpty());
    }

    // A widget provider of this test APK, as the system gives it (the package comes from here, not from views).
    private AppWidgetProviderInfo provider() {
        final AppWidgetProviderInfo info = new AppWidgetProviderInfo();
        info.provider = new ComponentName(mContext.getPackageName(), "Widget");
        info.providerInfo = new ActivityInfo();
        info.providerInfo.packageName = mContext.getPackageName();
        info.providerInfo.name = "Widget";
        info.providerInfo.nonLocalizedLabel = "widget";
        info.providerInfo.applicationInfo = mContext.getApplicationInfo();
        info.previewImage = R.drawable.vm_test_red;
        return info;
    }

    /**
     * A widget preview in the launcher is an ordinary app image: from its copy, waited for on a worker (an icon load),
     * empty (no preview) while it has none, never decoded here.
     */
    @Test
    public void launcher_widgetPreview_fromItsCopy() throws Exception {
        final FakeSource source = new FakeSource();
        ImageFileGates.setProcessDefault(CleanCopyGate.forTest(mContext, source, true));
        final AppWidgetProviderInfo info = provider();
        final Object[] none = onThread(() -> {
            final int misses = CleanCopyGate.misses();
            final Drawable d = CleanCopyGate.iconLoad(() -> info.loadPreviewImage(mContext, 0));
            return new Object[] {d, CleanCopyGate.misses() - misses};
        });
        assertTrue("no copy: empty", none[0] == null || none[0] instanceof ColorDrawable);
        assertEquals("a miss, so the launcher shows no preview", 1, none[1]);
        assertFalse("asked for", source.requests.isEmpty());
        source.copy = solid(9, 5, 0xff0000ff, 0);
        final Drawable made = onThread(() -> CleanCopyGate.iconLoad(() -> info.loadPreviewImage(mContext, 0)));
        assertTrue(String.valueOf(made), made instanceof BitmapDrawable);
        assertEquals(0xff0000ff, ((BitmapDrawable) made).getBitmap().getPixel(4, 2));
    }

    /**
     * A host view as the launcher's are, attached to a window: what it posts runs on the main thread. A view that is
     * not attached keeps what it posts until it is, and no window is opened here.
     */
    private static final class AttachedHostView extends AppWidgetHostView {
        private final Handler mMain = new Handler(Looper.getMainLooper());

        AttachedHostView(Context context) {
            super(context);
        }

        @Override
        public boolean post(Runnable action) {
            return mMain.post(action);
        }
    }

    /**
     * A hosted widget's images come from copies: blank the first time, and once copies of the provider's app were
     * made (the system says so) the widget is applied again and shows the copy.
     */
    @Test
    public void launcher_hostedWidget_blankThenFilledFromItsCopy() throws Exception {
        final FakeSource source = new FakeSource();
        ImageFileGates.setProcessDefault(CleanCopyGate.forTest(mContext, source, true));
        CleanCopyGate.setModeForTest(CleanCopyGate.MODE_LAUNCHER);
        final AppWidgetHostView[] host = new AppWidgetHostView[1];
        final RemoteViews views = new RemoteViews(mContext.getPackageName(), R.layout.vm_test_probe);
        views.setImageViewResource(R.id.vm_image, R.drawable.vm_test_red);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            host[0] = new AttachedHostView(mContext);
            host[0].setAppWidget(1, provider());
            host[0].updateAppWidget(views);
        });
        final Drawable[] first = new Drawable[1];
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                first[0] = ((ImageView) host[0].findViewById(R.id.vm_image)).getDrawable());
        assertTrue("first show: blank", first[0] == null || first[0] instanceof ColorDrawable);
        assertFalse("asked for", source.requests.isEmpty());
        source.copy = solid(9, 5, 0xff00ff00, 0);
        CleanCopyGate.madeForTest(mContext.getPackageName(), mContext.getUserId());
        final Drawable[] filled = new Drawable[1];
        final long end = SystemClock.elapsedRealtime() + 5_000;
        while (SystemClock.elapsedRealtime() < end) {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                    filled[0] = ((ImageView) host[0].findViewById(R.id.vm_image)).getDrawable());
            if (filled[0] instanceof BitmapDrawable) {
                break;
            }
            SystemClock.sleep(50);
        }
        assertTrue(String.valueOf(filled[0]), filled[0] instanceof BitmapDrawable);
        assertEquals(0xff00ff00, ((BitmapDrawable) filled[0]).getBitmap().getPixel(4, 2));
    }

    /**
     * In other system apps an icon the package manager has no copy for shows Android's default icon and fills itself
     * in once the copy is made.
     */
    @Test
    public void packageManagerIcon_default_thenFillsIn() throws Exception {
        final FakeSource source = new FakeSource();
        ImageFileGates.setProcessDefault(CleanCopyGate.forTest(mContext, source, false));
        CleanCopyGate.setModeForTest(CleanCopyGate.MODE_ALL);
        final PackageManager pm = mContext.getPackageManager();
        final PackageItemInfo item = new PackageItemInfo();
        item.packageName = mContext.getPackageName();
        item.icon = R.drawable.vm_test_green;
        final Drawable icon = onThread(() -> pm.loadUnbadgedItemIcon(item, mContext.getApplicationInfo()));
        assertTrue(String.valueOf(icon), icon instanceof DrawableWrapper);
        assertFalse("the default icon meanwhile",
                ((DrawableWrapper) icon).getDrawable() instanceof BitmapDrawable);
        source.copy = solid(9, 5, 0xff123456, 0);
        CleanCopyGate.madeForTest(mContext.getPackageName(), mContext.getUserId());
        final long end = SystemClock.elapsedRealtime() + 5_000;
        while (SystemClock.elapsedRealtime() < end
                && !(((DrawableWrapper) icon).getDrawable() instanceof BitmapDrawable)) {
            SystemClock.sleep(50);
        }
        final Drawable inner = ((DrawableWrapper) icon).getDrawable();
        assertTrue(String.valueOf(inner), inner instanceof BitmapDrawable);
        assertEquals(0xff123456, ((BitmapDrawable) inner).getBitmap().getPixel(4, 2));
    }

    /** A raw file whose name is no image's but whose bytes are a PNG's goes through the gate too. */
    @Test
    public void rawImageByItsBytes_gated() throws Exception {
        final FakeSource source = new FakeSource();
        source.copy = solid(7, 3, 0xff00ff00, 0);
        final Resources res = gated(CleanCopyGate.forTest(mContext, source, false));
        final Bitmap decoded = onThread(() -> {
            try (InputStream in = res.openRawResource(R.raw.vm_test_png_bytes)) {
                return BitmapFactory.decodeStream(in);
            }
        });
        assertNotNull(decoded);
        assertEquals("the copy, not the file", 7, decoded.getWidth());
    }

    /** A missing mipmap (an app icon) shows Android's default icon, never an empty one. */
    @Test
    public void missingMipmap_defaultIcon() throws Exception {
        final FakeSource source = new FakeSource();
        final Resources res = gated(CleanCopyGate.forTest(mContext, source, false));
        final Object[] out = onThread(() -> {
            final int before = CleanCopyGate.misses();
            final Drawable d = res.getDrawable(R.mipmap.vm_test_icon, null);
            return new Object[] {d, CleanCopyGate.misses() - before};
        });
        assertFalse("not empty", out[0] instanceof ColorDrawable);
        assertEquals("still a miss", 1, out[1]);
    }

    /** A waiting gate waits on a worker for a copy made meanwhile, never on the main thread. */
    @Test
    public void waits_onWorker_notOnMain() throws Exception {
        final FakeSource source = new FakeSource();
        source.copy = solid(9, 5, 0xffff0000, 0);
        source.copyAfter = SystemClock.elapsedRealtime() + 400;
        final Resources res = gated(CleanCopyGate.forTest(mContext, source, true));
        final Drawable[] main = new Drawable[1];
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                main[0] = CleanCopyGate.iconLoad(() -> res.getDrawable(R.drawable.vm_test_red, null)));
        assertTrue("main thread: empty at once", main[0] instanceof ColorDrawable);
        final Drawable waited = onThread(() ->
                CleanCopyGate.iconLoad(() -> res.getDrawable(R.drawable.vm_test_red, null)));
        assertTrue("worker: the copy made meanwhile", waited instanceof BitmapDrawable);
    }

    /** While the exact copy is made, the newest copy of the same resource shows, counted as provisional. */
    @Test
    public void olderCopy_standsIn_provisional() throws Exception {
        final FakeSource source = new FakeSource();
        source.older = solid(9, 5, 0xff445566, 0);
        final Resources res = gated(CleanCopyGate.forTest(mContext, source, false));
        final Object[] out = onThread(() -> {
            final int misses = CleanCopyGate.misses();
            final int provisional = CleanCopyGate.provisional();
            final Drawable d = res.getDrawable(R.drawable.vm_test_red, null);
            return new Object[] {d, CleanCopyGate.misses() - misses, CleanCopyGate.provisional() - provisional};
        });
        assertTrue(out[0] instanceof BitmapDrawable);
        assertEquals(0xff445566, ((BitmapDrawable) out[0]).getBitmap().getPixel(4, 2));
        assertEquals("no miss", 0, out[1]);
        assertEquals("provisional", 1, out[2]);
        assertEquals("asked for the exact copy", 1, source.requests.size());
        assertEquals(mContext.getPackageName() + ":drawable/vm_test_red", source.resources.get(0));
    }

    /**
     * Review 2b L2: an app's {@code <animated-image>} gets its file as the one frame of its clean copy, which
     * AnimatedImageDrawable refuses: it shows that still frame instead of nothing.
     */
    @Test
    public void animatedImage_showsItsStillFrame() throws Exception {
        final FakeSource source = new FakeSource();
        source.copy = solid(8, 8, 0xff00ff00, 0);
        final Resources res = gated(CleanCopyGate.forTest(mContext, source, false));
        final Drawable drawable = onThread(() -> res.getDrawable(R.drawable.vm_test_animated, null));
        assertTrue("empty: " + drawable, drawable instanceof BitmapDrawable);
        assertEquals(0xff00ff00, ((BitmapDrawable) drawable).getBitmap().getPixel(4, 4));
    }

    /** Raw resources that are no image (a text here) load as before, never replaced by a picture. */
    @Test
    public void rawNonImage_untouched() throws Exception {
        final FakeSource source = new FakeSource();
        final Resources res = gated(CleanCopyGate.forTest(mContext, source, false));
        final String text = onThread(() -> {
            try (InputStream in = res.openRawResource(R.raw.vm_test_text)) {
                return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }
        });
        assertEquals("not an image\n", text);
        assertTrue(source.requests.isEmpty());
    }

    /**
     * A load at another density keys the file as Resources hands it on (the density rewritten), the same way the
     * system server's warm-up keys it: a 320 dpi file loaded at 640 dpi is keyed at display * 320 / 640.
     */
    @Test
    public void densityOfALoad_keyedAsResourcesRewritesIt() throws Exception {
        final FakeSource source = new FakeSource();
        final Resources res = gated(CleanCopyGate.forTest(mContext, source, false));
        onThread(() -> res.getDrawableForDensity(R.drawable.vm_test_dens, 640, null));
        final int display = mContext.getResources().getDisplayMetrics().densityDpi;
        final int expected = 320 * display / 640;
        final int[] box = CleanCopyClient.box(mContext);
        assertEquals(1, source.requests.size());
        assertTrue(source.requests.get(0), source.requests.get(0).endsWith(
                ":" + expected + ":" + box[0] + "x" + box[1] + userSuffix()));
    }

    /** With the process gate, the package manager gives null (the caller's default) for an icon without a copy. */
    @Test
    public void packageManager_noCopy_null_thenTheCopy() throws Exception {
        final FakeSource source = new FakeSource();
        ImageFileGates.setProcessDefault(CleanCopyGate.forTest(mContext, source, false));
        final PackageManager pm = mContext.getPackageManager();
        final Drawable none = onThread(() ->
                pm.getDrawable(mContext.getPackageName(), R.drawable.vm_test_blue, mContext.getApplicationInfo()));
        assertNull("no copy: null, nothing kept", none);
        source.copy = solid(9, 5, 0xff0000ff, 0);
        final Drawable made = onThread(() ->
                pm.getDrawable(mContext.getPackageName(), R.drawable.vm_test_blue, mContext.getApplicationInfo()));
        assertTrue("then the copy", made instanceof BitmapDrawable);
        assertEquals(0xff0000ff, ((BitmapDrawable) made).getBitmap().getPixel(4, 2));
        assertFalse(source.requests.isEmpty());
    }

    @Test
    public void ownAssetsAreTheBaseAndSplitsOnly() {
        // An app whose base and split are its own, which also loads a shared library, a resource directory and an
        // overlay from outside the system image.
        final android.content.pm.ApplicationInfo app = new android.content.pm.ApplicationInfo();
        app.packageName = "de.diamaneos.example";
        app.sourceDir = "/data/app/~~x/de.diamaneos.example-1/base.apk";
        app.publicSourceDir = app.sourceDir;
        app.splitSourceDirs = new String[] {"/data/app/~~x/de.diamaneos.example-1/split_more.apk"};
        app.splitPublicSourceDirs = app.splitSourceDirs;
        app.sharedLibraryFiles = new String[] {"/data/app/~~y/com.example.library-2/base.apk"};
        app.resourceDirs = new String[] {"/data/resource-cache/de.diamaneos.example.resources.apk"};
        app.overlayPaths = new String[] {"/data/resource-cache/de.diamaneos.example.overlay.frro"};
        final List<String> extra = java.util.Arrays.asList(app.sharedLibraryFiles[0], app.resourceDirs[0],
                app.overlayPaths[0]);
        // What the exemption took before: every path the app loads.
        assertTrue(java.util.Arrays.asList(app.getAllApkPaths()).containsAll(extra));
        assertTrue(CleanCopyGate.apkDecodesHere(app, app.sourceDir));
        assertTrue(CleanCopyGate.apkDecodesHere(app, app.splitSourceDirs[0]));
        for (String path : extra) {
            assertFalse(path, CleanCopyGate.apkDecodesHere(app, path));
        }
        assertTrue(CleanCopyGate.apkDecodesHere(app, "/system/framework/framework-res.apk"));
    }
}
