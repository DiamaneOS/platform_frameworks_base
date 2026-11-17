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

import android.app.Notification;
import android.content.Context;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.graphics.drawable.NinePatchDrawable;
import android.graphics.drawable.VectorDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.SizeF;
import android.util.TypedValue;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.RemoteViews;
import android.widget.TextView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import de.diamaneos.decode.client.tests.R;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * An app's own notification layouts: their images are re-made from pixels decoded in the VM, nothing of the app is
 * decoded on Android, and the layout stays. Runs with or without the decode service: with it images are real, without
 * it they are empty, and the checks hold either way.
 */
@RunWith(AndroidJUnit4.class)
public class RemoteViewsImagesDeviceTest {
    private static final Uri URI = Uri.parse("content://de.diamaneos.decode.client.tests.none/image.png");

    private final Context mContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private final String mPackage = mContext.getPackageName();

    private static Icon dataIcon(int n) {
        final byte[] data = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 0, 0, 0, (byte) n, (byte) (n >> 8)};
        return Icon.createWithData(data, 0, data.length);
    }

    private NotificationImageGate gate(ImageBudget budget) {
        return NotificationImageGate.forNotification(mContext, mPackage, mContext.getUserId(), 64, 512, 512,
                budget);
    }

    private static <T> T onWorker(Callable<T> callable) throws Exception {
        final HandlerThread thread = new HandlerThread("vm-images-test");
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

    private String file(int drawable) {
        final TypedValue value = new TypedValue();
        mContext.getResources().getValue(drawable, value, true);
        return value.string.toString();
    }

    // What an image of the app may become: pixels from the VM, or empty.
    private static void assertMadeOrEmpty(String what, Drawable drawable) {
        assertNotNull(what, drawable);
        assertTrue(what + ": " + drawable, drawable instanceof BitmapDrawable
                || drawable instanceof NinePatchDrawable || drawable instanceof ColorDrawable);
    }

    private View inflate(Context context, RemoteViews views) {
        return views.apply(context, new FrameLayout(mContext));
    }

    @Test
    public void layoutXml_everyBitmapFileGoesThroughTheGate() throws Exception {
        final NotificationImageGate gate = gate(new ImageBudget());
        final Context context = gate.packageContext(mContext);
        final View root = onWorker(() -> inflate(context, new RemoteViews(mPackage, R.layout.vm_test_layout)));

        final List<String> files = gate.resourceImages().files();
        // android:src, <bitmap> in an XML drawable, a layer list (by reference and inline), <nine-patch>, a nine-patch
        // background and android:drawableStart.
        assertTrue(files.toString(), files.contains(file(R.drawable.vm_test_red)));
        assertTrue(files.toString(), files.contains(file(R.drawable.vm_test_green)));
        assertTrue(files.toString(), files.contains(file(R.drawable.vm_test_blue)));
        assertTrue(files.toString(), files.contains(file(R.drawable.vm_test_patch)));
        for (String f : files) {
            assertFalse(f, f.endsWith(".xml"));
        }

        assertMadeOrEmpty("src", ((ImageView) root.findViewById(R.id.vm_src_png)).getDrawable());
        assertNotNull(((ImageView) root.findViewById(R.id.vm_src_bitmap_xml)).getDrawable());
        assertNotNull(((ImageView) root.findViewById(R.id.vm_src_nine)).getDrawable());
        assertNotNull(root.findViewById(R.id.vm_nine_background).getBackground());
        assertNotNull(root.getBackground());
        assertNotNull(((TextView) root.findViewById(R.id.vm_text)).getCompoundDrawablesRelative()[0]);
        // XML that is not an image codec loads as before.
        assertTrue(((ImageView) root.findViewById(R.id.vm_src_vector)).getDrawable() instanceof VectorDrawable);
    }

    @Test
    public void resourceActions_goThroughTheGate() throws Exception {
        final NotificationImageGate gate = gate(new ImageBudget());
        final Context context = gate.packageContext(mContext);
        final RemoteViews views = new RemoteViews(mPackage, R.layout.vm_test_child);
        views.setImageViewResource(R.id.vm_child_image, R.drawable.vm_test_green);
        views.setInt(R.id.vm_child_image, "setBackgroundResource", R.drawable.vm_test_blue);
        final View root = onWorker(() -> inflate(context, views));

        final List<String> files = gate.resourceImages().files();
        assertTrue(files.toString(), files.contains(file(R.drawable.vm_test_green)));
        assertTrue(files.toString(), files.contains(file(R.drawable.vm_test_blue)));
        assertMadeOrEmpty("resource", ((ImageView) root).getDrawable());
        assertMadeOrEmpty("background", root.getBackground());
    }

    @Test
    public void mainThread_layoutShowsAndTheVmIsNotAsked() {
        final NotificationImageGate gate = gate(new ImageBudget());
        final Context context = gate.packageContext(mContext);
        final View root = onMain(() -> inflate(context, new RemoteViews(mPackage, R.layout.vm_test_layout)));
        assertNotNull(root);
        assertEquals(0, gate.budget().vmCalls());
        assertTrue(gate.resourceImages().files().contains(file(R.drawable.vm_test_red)));
        assertMadeOrEmpty("src", ((ImageView) root.findViewById(R.id.vm_src_png)).getDrawable());
    }

    @Test
    public void vmDown_costsImagesNotTheLayout() throws Exception {
        // No time left for the VM: the same path as a VM that does not answer.
        final NotificationImageGate gate = gate(new ImageBudget(0, ImageBudget.MAX_IMAGES, ImageBudget.MAX_PIXELS));
        final Context context = gate.packageContext(mContext);
        final long start = SystemClock.uptimeMillis();
        final View root = onWorker(() -> inflate(context, new RemoteViews(mPackage, R.layout.vm_test_layout)));
        assertTrue(SystemClock.uptimeMillis() - start < 5_000);
        assertEquals(0, gate.budget().vmCalls());
        assertMadeOrEmpty("src", ((ImageView) root.findViewById(R.id.vm_src_png)).getDrawable());
        assertNotNull(((ImageView) root.findViewById(R.id.vm_src_nine)).getDrawable());
    }

    private RemoteViews everyIconAction() {
        final RemoteViews layout = new RemoteViews(mPackage, R.layout.vm_test_layout);
        layout.setImageViewIcon(R.id.vm_src_png, dataIcon(1));
        layout.setImageViewUri(R.id.vm_target, URI);
        layout.setIcon(R.id.vm_src_bitmap_xml, "setImageIcon",
                Icon.createWithResource(mContext, R.drawable.vm_test_red));
        layout.setIcon(R.id.vm_target_night, "setImageIcon", dataIcon(2), Icon.createWithContentUri(URI));
        layout.setTextViewCompoundDrawablesRelative(R.id.vm_text, dataIcon(3), null,
                Icon.createWithResource(mContext, R.drawable.vm_test_vector), null);
        // A framework resource is kept as it is.
        layout.setImageViewIcon(R.id.vm_src_vector,
                Icon.createWithResource("android", android.R.drawable.ic_menu_add));
        final RemoteViews child = new RemoteViews(mPackage, R.layout.vm_test_child);
        child.setImageViewIcon(R.id.vm_child_image, dataIcon(4));
        child.setImageViewUri(R.id.vm_child_image, URI);
        layout.addView(R.id.vm_container, child);
        return layout;
    }

    private RemoteViews childWith(Icon icon) {
        final RemoteViews child = new RemoteViews(mPackage, R.layout.vm_test_child);
        child.setImageViewIcon(R.id.vm_child_image, icon);
        return child;
    }

    private Notification everyLayout() {
        final Map<SizeF, RemoteViews> sized = new LinkedHashMap<>();
        sized.put(new SizeF(100, 100), childWith(dataIcon(5)));
        sized.put(new SizeF(200, 200), childWith(Icon.createWithContentUri(URI)));
        final Notification publicVersion = new Notification.Builder(mContext, "test")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setCustomContentView(childWith(dataIcon(6)))
                .build();
        return new Notification.Builder(mContext, "test")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setCustomContentView(everyIconAction())
                .setCustomBigContentView(new RemoteViews(childWith(dataIcon(7)),
                        childWith(Icon.createWithResource(mContext, R.drawable.vm_test_green))))
                .setCustomHeadsUpContentView(new RemoteViews(sized))
                .setPublicVersion(publicVersion)
                .build();
    }

    private static List<RemoteViews> layouts(Notification n) {
        final List<RemoteViews> list = new ArrayList<>();
        list.add(n.contentView);
        list.add(n.bigContentView);
        list.add(n.headsUpContentView);
        list.add(n.publicVersion.contentView);
        return list;
    }

    // Only bitmaps and framework resources are left, and no link.
    private static void assertConverted(RemoteViews views) {
        assertNotNull("layout dropped", views);
        final List<Icon> icons = RemoteViewsImages.icons(views);
        assertFalse(icons.isEmpty());
        for (Icon icon : icons) {
            final boolean bitmap = icon.getType() == Icon.TYPE_BITMAP || icon.getType() == Icon.TYPE_ADAPTIVE_BITMAP;
            final boolean framework = icon.getType() == Icon.TYPE_RESOURCE && "android".equals(icon.getResPackage());
            assertTrue(icon.toString(), bitmap || framework);
        }
        final List<Uri> links = new ArrayList<>();
        views.visitUris(links::add);
        assertTrue(links.toString(), links.isEmpty());
    }

    @Test
    public void everyIconAndLink_becomesABitmap_layoutsKept() throws Exception {
        final Notification original = everyLayout();
        final NotificationImageGate gate = gate(new ImageBudget());
        final Context context = gate.packageContext(mContext);
        final Notification copy = onWorker(() -> gate.sanitizedCopy(original));

        for (RemoteViews views : layouts(copy)) {
            assertConverted(views);
        }
        // Nested: the addView child (data and link), landscape and portrait, sized layouts, the public version.
        assertTrue(RemoteViewsImages.icons(copy.contentView).size() >= 10);

        // The original is unchanged.
        boolean data = false;
        for (Icon icon : RemoteViewsImages.icons(original.contentView)) {
            data |= icon.getType() == Icon.TYPE_DATA;
        }
        assertTrue(data);
        final List<Uri> links = new ArrayList<>();
        original.contentView.visitUris(links::add);
        assertFalse(links.isEmpty());

        // The converted layout applies: setImageViewUri became setImageIcon on the same view.
        final View root = onWorker(() -> inflate(context, copy.contentView));
        assertNotNull(((ImageView) root.findViewById(R.id.vm_target)).getDrawable());
        assertNotNull(((ImageView) root.findViewById(R.id.vm_src_png)).getDrawable());
        assertNotNull(((ImageView) root.findViewById(R.id.vm_child_image)).getDrawable());
    }

    @Test
    public void mainThread_layoutsKept_nothingDecoded() {
        final Notification original = everyLayout();
        final NotificationImageGate gate = gate(new ImageBudget());
        final Notification copy = onMain(() -> {
            gate.packageContext(mContext);
            return gate.sanitizedCopy(original);
        });
        for (RemoteViews views : layouts(copy)) {
            assertConverted(views);
        }
        assertEquals(0, gate.budget().vmCalls());
    }

    @Test
    public void imageCap_overTheCapIsEmptyWithoutAskingTheVm() throws Exception {
        final int count = ImageBudget.MAX_IMAGES + 16;
        final RemoteViews layout = new RemoteViews(mPackage, R.layout.vm_test_layout);
        for (int i = 0; i < count; i++) {
            layout.addView(R.id.vm_container, childWith(dataIcon(100 + i)));
        }
        final Notification original = new Notification.Builder(mContext, "test")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setCustomContentView(layout)
                .build();
        final NotificationImageGate gate = gate(new ImageBudget());
        gate.packageContext(mContext);
        final long start = SystemClock.uptimeMillis();
        final Notification copy = onWorker(() -> gate.sanitizedCopy(original));
        assertTrue(SystemClock.uptimeMillis() - start < NotificationImageGate.BUDGET_MS + 3_000);
        assertConverted(copy.contentView);
        assertEquals(count, RemoteViewsImages.icons(copy.contentView).size());
        assertTrue(gate.budget().vmCalls() <= ImageBudget.MAX_IMAGES);
        assertTrue(gate.budget().refused() >= count - ImageBudget.MAX_IMAGES);
    }

    @Test
    public void noImageGate_customLayoutsDropped() throws Exception {
        final Notification original = everyLayout();
        // packageContext() was not called: the layouts could not be applied safely.
        final NotificationImageGate gate = gate(new ImageBudget());
        final Notification copy = onWorker(() -> gate.sanitizedCopy(original));
        assertNull(copy.contentView);
        assertNull(copy.bigContentView);
        assertNull(copy.headsUpContentView);
        assertNull(copy.publicVersion.contentView);
    }

    @Test
    public void iconSetTo_copiesTypeImageAndTint() {
        final Icon icon = dataIcon(1);
        final Icon bitmap = VmIcons.emptyIcon().setTint(0xff00ff00);
        icon.setTo(bitmap);
        assertEquals(Icon.TYPE_BITMAP, icon.getType());
        assertNotNull(icon.getBitmap());
        assertEquals(bitmap.getTintList(), icon.getTintList());
    }
}
