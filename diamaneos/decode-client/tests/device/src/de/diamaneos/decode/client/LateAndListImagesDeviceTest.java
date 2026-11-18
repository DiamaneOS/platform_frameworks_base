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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.DrawableWrapper;
import android.graphics.drawable.Icon;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.widget.RemoteViews;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import de.diamaneos.decode.client.tests.R;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Images that show before they are made (an autofill service's views in the system server), the items of a widget's
 * list checked with its update, the fair image queue, and every asker told when an image is made. Runs with or without
 * the decode service: without it images stay empty, and the checks hold either way.
 */
@RunWith(AndroidJUnit4.class)
public class LateAndListImagesDeviceTest {
    private final Context mContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private final String mPackage = mContext.getPackageName();

    // A valid 7 x 3 PNG, different for each n.
    private static Icon pngIcon(int n) {
        final Bitmap bitmap = Bitmap.createBitmap(7, 3, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(0xff000000 | n);
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        final byte[] png = out.toByteArray();
        return Icon.createWithData(png, 0, png.length);
    }

    private static <T> T onWorker(Callable<T> callable) throws Exception {
        final HandlerThread thread = new HandlerThread("late-images-test");
        thread.start();
        try {
            final FutureTask<T> task = new FutureTask<>(callable);
            new Handler(thread.getLooper()).post(task);
            return task.get(30, TimeUnit.SECONDS);
        } finally {
            thread.quitSafely();
        }
    }

    private static <T> T onMain(Callable<T> callable) {
        final AtomicReference<T> result = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            try {
                result.set(callable.call());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        return result.get();
    }

    // The drawable a waiting drawable got on the main thread, or null after 10 s.
    private static Drawable filled(Drawable waiting) throws InterruptedException {
        final long end = SystemClock.uptimeMillis() + 10_000;
        while (SystemClock.uptimeMillis() < end) {
            final Drawable inner = onMain(() -> ((DrawableWrapper) waiting).getDrawable());
            if (inner != null) {
                return inner;
            }
            Thread.sleep(100);
        }
        return null;
    }

    @Test
    public void lateImages_placeholderShowsAtOnce_thenFillsIn() throws Exception {
        final LateImages late = new LateImages(mContext, mPackage, mContext.getUserId(),
                new Handler(Looper.getMainLooper()));
        final RemoteViews views = new RemoteViews(mPackage, R.layout.vm_test_probe);
        views.setImageViewIcon(R.id.vm_probe, pngIcon(4646));
        assertTrue(late.prepareInPlace(views));
        final Icon placeholder = RemoteViewsImages.icons(views).get(0);
        assertEquals(Icon.TYPE_RESOURCE, placeholder.getType());
        assertEquals(LateImages.PACKAGE, placeholder.getResPackage());
        // Shown at once on a plain thread, nothing decoded: a drawable that waits.
        final Context shown = late.context(mContext);
        final Drawable waiting = onWorker(() -> placeholder.loadDrawable(shown));
        assertTrue(String.valueOf(waiting), waiting instanceof DrawableWrapper);
        assertNull(((DrawableWrapper) waiting).getDrawable());
        late.start();
        final Drawable made = filled(waiting);
        if (made != null) {
            // Pixels the VM made.
            assertTrue(String.valueOf(made), made instanceof BitmapDrawable);
        }
        // Views prepared after the start show without the app's images.
        final RemoteViews later = new RemoteViews(mPackage, R.layout.vm_test_probe);
        later.setImageViewIcon(R.id.vm_probe, pngIcon(4747));
        assertTrue(late.prepareInPlace(later));
        assertEquals(Icon.TYPE_BITMAP, RemoteViewsImages.icons(later).get(0).getType());
    }

    @Test
    public void lateImages_packageDrawable_bitmapIconIsNeverNull() throws Exception {
        // A PNG icon of a package (an autofill service's): shown at once, empty, then filled in. Never null, so the
        // save UI always shows.
        final Drawable icon = LateImages.packageDrawable(mContext, mPackage, mContext.getUserId(),
                R.drawable.vm_test_red, new Handler(Looper.getMainLooper()));
        assertNotNull(icon);
        if (icon instanceof DrawableWrapper) {
            final Drawable made = filled(icon);
            if (made != null) {
                assertTrue(String.valueOf(made), made instanceof BitmapDrawable);
            }
        } else {
            // Made before by another test in the same box: the VM's pixels.
            assertTrue(String.valueOf(icon), icon instanceof BitmapDrawable);
        }
    }

    @Test
    public void widgetLists_itemsInsideTheUpdate_checkedWithIt() throws Exception {
        // With remote_adapter_conversion on (this release), a widget's list items come inside the update: they are
        // checked and their images re-made with the rest of the views.
        final WidgetImages widget = onMain(() -> new WidgetImages(mContext));
        widget.setApp(mPackage, mContext.getUserId());
        final RemoteViews item = new RemoteViews(mPackage, R.layout.vm_test_probe);
        item.setImageViewIcon(R.id.vm_probe, pngIcon(4848));
        final RemoteViews list = new RemoteViews(mPackage, R.layout.vm_test_probe);
        list.setRemoteAdapter(R.id.vm_probe,
                new RemoteViews.RemoteCollectionItems.Builder().addItem(1, item).build());
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicReference<RemoteViews> checked = new AtomicReference<>();
        widget.prepare(list, Runnable::run, v -> {
            checked.set(v);
            done.countDown();
        });
        assertTrue(done.await(10, TimeUnit.SECONDS));
        assertNotNull(checked.get());
        final List<Icon> icons = RemoteViewsImages.icons(checked.get());
        assertTrue(!icons.isEmpty());
        for (Icon icon : icons) {
            // Pixels from the VM, or empty without it: never the app's bytes.
            assertEquals(Icon.TYPE_BITMAP, icon.getType());
        }
        // The app's own item is left as it is.
        assertEquals(Icon.TYPE_DATA, RemoteViewsImages.icons(item).get(0).getType());
    }

    @Test
    public void imageThreads_fullLane_theAppWithTheMostWaitingGivesWay() throws Exception {
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch dropped = new CountDownLatch(1);
        try {
            assertNotNull(ImageThreads.execute("app.fair", () -> {
                try {
                    release.await(20, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                    // Ends the task.
                }
            }));
            Thread.sleep(200);
            // One app may fill the lane while nobody else waits: no image is lost for many conversations.
            for (int i = 0; i < ImageThreads.MAX_QUEUED - 1; i++) {
                assertNotNull(ImageThreads.execute("app.fair", () -> { }));
            }
            assertNotNull(ImageThreads.execute("app.fair", () -> { }, dropped::countDown));
            // Full: that app is refused...
            assertNull(ImageThreads.execute("app.fair", () -> { }));
            // ...another app is not: the newest task of the app with the most waiting gives way, and is told.
            assertNotNull(ImageThreads.execute("app.fair.other", () -> { }));
            assertTrue(dropped.await(5, TimeUnit.SECONDS));
        } finally {
            release.countDown();
        }
    }

    @Test
    public void uriThatCannotBeOpened_isTriedAgain_aRefusedSchemeIsNot() throws Exception {
        // No provider behind it (not there yet, say): a passing failure, not a verdict on the image.
        final Icon missing = Icon.createWithContentUri("content://de.diamaneos.decode.client.tests.none/a:t1.png");
        assertNull(onWorker(() -> VmIcons.decode(missing, mContext, mPackage, 64,
                SystemClock.uptimeMillis() + 2_000)));
        final String key = VmIcons.cacheKey(missing, mContext, mPackage, "64x64");
        assertTrue(!VmIcons.wasRejected(key));
        // A file URI is never opened: that is a verdict.
        final Icon file = Icon.createWithContentUri("file:///data/local/tmp/x.png");
        assertNull(onWorker(() -> VmIcons.decode(file, mContext, mPackage, 64,
                SystemClock.uptimeMillis() + 2_000)));
        assertTrue(VmIcons.wasRejected(VmIcons.cacheKey(file, mContext, mPackage, "64x64")));
        // The stable key of a key is itself, even with ":t" inside the URI.
        final String stable = VmIcons.stableKey(key);
        assertEquals(stable, VmIcons.stableKey(stable));
        assertTrue(stable.contains("a:t1.png"));
    }

    @Test
    public void appIcons_everyAskerIsTold() throws Exception {
        final Icon data = pngIcon(4949);
        final CountDownLatch first = new CountDownLatch(1);
        final CountDownLatch second = new CountDownLatch(1);
        onMain(() -> AppIcons.forView(mContext, data, mContext.getUserId(), mPackage, "test.first",
                first::countDown));
        onMain(() -> AppIcons.forView(mContext, data, mContext.getUserId(), mPackage, "test.second",
                second::countDown));
        if (first.await(10, TimeUnit.SECONDS)) {
            // The second asker came while it was being made: told too.
            assertTrue(second.await(2, TimeUnit.SECONDS));
        }
    }
}
