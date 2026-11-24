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
import static org.junit.Assert.fail;

import android.app.Notification;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.os.SystemClock;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.RemoteViews;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import de.diamaneos.decode.client.tests.R;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * The VM path itself: every test here needs the decode service and fails when this process cannot reach it (on a
 * debuggable build the service lets this test package bind, never on a user build). The other device test classes
 * check what holds with or without the VM (no native decode, fail closed, layouts kept), and the fail-closed tests
 * there hold the VM away on purpose.
 */
@RunWith(AndroidJUnit4.class)
public class VmPathDeviceTest {
    private static final int WIDTH = 7;
    private static final int HEIGHT = 3;

    private final Context mContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private final String mPackage = mContext.getPackageName();

    // A valid opaque 7 x 3 PNG of one colour, made here.
    private static byte[] png(int rgb) {
        final Bitmap bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(0xff000000 | rgb);
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        return out.toByteArray();
    }

    private static Icon pngIcon(int rgb) {
        final byte[] png = png(rgb);
        return Icon.createWithData(png, 0, png.length);
    }

    private static <T> T onImageThread(Callable<T> callable) throws Exception {
        final FutureTask<T> task = new FutureTask<>(callable);
        ImageThreads.execute("de.diamaneos.decode.client.tests", task);
        return task.get(30, TimeUnit.SECONDS);
    }

    private static void assertMadeInTheVm(String what, Bitmap bitmap, int width, int height, int argb) {
        assertNotNull(what + ": nothing made", bitmap);
        assertEquals(what + " width", width, bitmap.getWidth());
        assertEquals(what + " height", height, bitmap.getHeight());
        assertEquals(what + " colour", argb, bitmap.getPixel(width / 2, height / 2));
    }

    /** Fails the test unless this process reaches the decode service and its VM decodes a PNG made here. */
    @Before
    public void vmReachable() throws Exception {
        final byte[] png = png(0x123456);
        final Bitmap decoded;
        try {
            decoded = onImageThread(() -> VmDecode.decodeBytes(mContext, png, 0, png.length, mPackage,
                    mContext.getUserId(), VmDecode.PRIORITY_USER_WAITING, 64, 64, VmDecode.deadlineIn(20_000)));
        } catch (java.util.concurrent.ExecutionException e) {
            fail("this test needs the decode VM, which this process could not use: " + e.getCause()
                    + " (a debuggable build lets the client tests bind the decode service)");
            return;
        }
        assertMadeInTheVm("probe PNG", decoded, WIDTH, HEIGHT, 0xff123456);
    }

    @Test
    public void dataIcon_madeInTheVm_atItsOwnSize() throws Exception {
        final Icon made = onImageThread(() -> VmIcons.toSafeIcon(pngIcon(0x00ff00), mContext, mPackage, 64,
                VmDecode.deadlineIn(20_000)));
        assertNotNull(made);
        assertEquals(Icon.TYPE_BITMAP, made.getType());
        assertMadeInTheVm("data icon", made.getBitmap(), WIDTH, HEIGHT, 0xff00ff00);
    }

    @Test
    public void notificationIcons_madeInTheVm() throws Exception {
        final Notification original = new Notification.Builder(mContext, "test")
                .setSmallIcon(Icon.createWithResource("android", android.R.drawable.ic_dialog_info))
                .setLargeIcon(pngIcon(0x0000ff))
                .build();
        final NotificationImageGate gate = NotificationImageGate.forNotification(mContext, mPackage,
                mContext.getUserId(), 64, 512, 512, new ImageBudget());
        gate.packageContext(mContext);
        final Notification copy = onImageThread(() -> gate.sanitizedCopy(original));
        assertNotNull(copy.getLargeIcon());
        assertEquals(Icon.TYPE_BITMAP, copy.getLargeIcon().getType());
        assertMadeInTheVm("large icon", copy.getLargeIcon().getBitmap(), WIDTH, HEIGHT, 0xff0000ff);
    }

    @Test
    public void layoutImages_madeInTheVm() throws Exception {
        final RemoteViews views = new RemoteViews(mPackage, R.layout.vm_test_probe);
        views.setImageViewIcon(R.id.vm_probe, pngIcon(0xff0000));
        final Notification original = new Notification.Builder(mContext, "test")
                .setSmallIcon(Icon.createWithResource("android", android.R.drawable.ic_dialog_info))
                .setCustomContentView(views)
                .build();
        final NotificationImageGate gate = NotificationImageGate.forNotification(mContext, mPackage,
                mContext.getUserId(), 64, 512, 512, new ImageBudget());
        gate.packageContext(mContext);
        final Notification copy = onImageThread(() -> gate.sanitizedCopy(original));
        assertNotNull("the layout is kept", copy.contentView);
        final List<Icon> icons = RemoteViewsImages.icons(copy.contentView);
        assertEquals(1, icons.size());
        assertMadeInTheVm("layout icon", icons.get(0).getBitmap(), WIDTH, HEIGHT, 0xffff0000);
    }

    @Test
    public void layoutXmlBitmapFile_madeInTheVm() throws Exception {
        // android:src="@drawable/vm_test_red", an opaque red 8 x 8 PNG of this APK.
        final NotificationImageGate gate = NotificationImageGate.forNotification(mContext, mPackage,
                mContext.getUserId(), 64, 512, 512, new ImageBudget());
        final Context context = gate.packageContext(mContext);
        final View root = onImageThread(() -> new RemoteViews(mPackage, R.layout.vm_test_layout)
                .apply(context, new FrameLayout(mContext)));
        final Drawable src = ((ImageView) root.findViewById(R.id.vm_src_png)).getDrawable();
        assertTrue("not made in the VM: " + src, src instanceof BitmapDrawable);
        assertMadeInTheVm("layout PNG", ((BitmapDrawable) src).getBitmap(), 8, 8, 0xffff0000);
    }

    @Test
    public void widgetListItem_inTheCacheForm_madeInTheVm() throws Exception {
        // A widget update as the system sends it with remote_adapter_conversion: the items in the collection cache.
        final RemoteViews item = new RemoteViews(mPackage, R.layout.vm_test_probe);
        item.setImageViewIcon(R.id.vm_probe, pngIcon(0xffff00));
        final RemoteViews views = ListsAndQueueDeviceTest.cacheForm(mPackage,
                new ListsAndQueueDeviceTest.RemoteCollectionItemsHolder(item));
        assertNotNull("the cache form cannot be made", views);
        final WidgetImages widget = onMainMade(() -> new WidgetImages(mContext));
        widget.setApp(mPackage, mContext.getUserId());
        final java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        final RemoteViews[] checked = new RemoteViews[1];
        widget.prepare(views, Runnable::run, v -> {
            checked[0] = v;
            done.countDown();
        });
        assertTrue(done.await(30, TimeUnit.SECONDS));
        assertNotNull("the update is not shown", checked[0]);
        final List<Icon> icons = RemoteViewsImages.icons(checked[0]);
        assertEquals(1, icons.size());
        assertMadeInTheVm("list item", icons.get(0).getBitmap(), WIDTH, HEIGHT, 0xffffff00);
    }

    private static <T> T onMainMade(Callable<T> callable) {
        final Object[] made = new Object[1];
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            try {
                made[0] = callable.call();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        @SuppressWarnings("unchecked")
        final T result = (T) made[0];
        return result;
    }

    @Test
    public void foreignIcons_madeInTheVm_afterAskingAgain() throws Exception {
        // As the wallet, shortcut helper, screen capture, selection toolbar and tuner callers load: nothing waits,
        // they are told, and they ask again.
        final Icon data = pngIcon(0xff00ff);
        final Semaphore told = new Semaphore(0);
        final Callable<Drawable> ask = () -> {
            final Drawable[] shown = new Drawable[1];
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> shown[0] = ForeignIcons.loadNoWait(
                    mContext, data, mContext.getUserId(), mPackage, 64, told::release));
            return shown[0];
        };
        Drawable shown = ask.call();
        final long end = SystemClock.uptimeMillis() + 20_000;
        while (shown == null) {
            final long left = end - SystemClock.uptimeMillis();
            assertTrue("never made in the VM", left > 0 && told.tryAcquire(left, TimeUnit.MILLISECONDS));
            shown = ask.call();
        }
        assertTrue(String.valueOf(shown), shown instanceof BitmapDrawable);
        assertMadeInTheVm("app icon", ((BitmapDrawable) shown).getBitmap(), WIDTH, HEIGHT, 0xffff00ff);
    }

    @Test
    public void autofillXmlBitmap_filledInFromTheVm() throws Exception {
        // <bitmap android:src="@drawable/vm_test_green"/> (a 6 x 4 PNG of this APK) in a layout shown before its files
        // were made: once they are, the drawable loads again with the VM's pixels.
        final Drawable xml = LateAndListImagesDeviceTest.xmlBitmapOfALateLayout(mContext, mPackage);
        final Drawable shown = xml instanceof android.graphics.drawable.DrawableWrapper
                ? ((android.graphics.drawable.DrawableWrapper) xml).getDrawable() : xml;
        assertTrue("not filled in from the VM: " + shown, shown instanceof BitmapDrawable);
        final Bitmap bitmap = ((BitmapDrawable) shown).getBitmap();
        assertEquals(6, bitmap.getWidth());
        assertEquals(4, bitmap.getHeight());
    }

    @Test
    public void appIcons_dataSmallIcon_madeAfterAskingAgain() throws Exception {
        // The status bar asks, gets the placeholder, is told, asks again: with the VM there it gets the image.
        final Icon data = pngIcon(0x00ffff);
        final Semaphore told = new Semaphore(0);
        final Callable<Icon> ask = () -> {
            final Icon[] shown = new Icon[1];
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> shown[0] = AppIcons.forView(mContext,
                    data, mContext.getUserId(), mPackage, told::release));
            return shown[0];
        };
        Icon shown = ask.call();
        final long end = SystemClock.uptimeMillis() + 20_000;
        while (shown.getType() != Icon.TYPE_BITMAP || shown.getBitmap().getWidth() == 1) {
            final long left = end - SystemClock.uptimeMillis();
            assertTrue("never made in the VM", left > 0 && told.tryAcquire(left, TimeUnit.MILLISECONDS));
            shown = ask.call();
        }
        assertMadeInTheVm("status bar icon", shown.getBitmap(), WIDTH, HEIGHT, 0xff00ffff);
    }
}
