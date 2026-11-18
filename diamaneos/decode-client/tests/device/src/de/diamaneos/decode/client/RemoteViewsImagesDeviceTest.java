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
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Notification;
import android.app.Person;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Insets;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.graphics.drawable.NinePatchDrawable;
import android.graphics.drawable.VectorDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Parcelable;
import android.os.SystemClock;
import android.util.SizeF;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.RemoteViews;
import android.widget.TextView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import de.diamaneos.decode.client.tests.R;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * An app's own notification layouts: their images are re-made from pixels decoded in the VM, nothing of the app is
 * decoded on Android, and the layout stays. Runs with or without the decode service: with it images are real, without
 * it they are empty, and the checks hold either way.
 *
 * <p>Checks that do not rely on the converter's own view of the layout: {@link ProbeView} keeps what the framework's
 * apply code hands it, and a native-decode oracle: the app images here are valid 7 x 3 PNGs, so with no time for the
 * VM, a 7 x 3 bitmap anywhere in the applied views can only come from a decode on Android.
 */
@RunWith(AndroidJUnit4.class)
public class RemoteViewsImagesDeviceTest {
    private static final Uri URI = Uri.parse("content://de.diamaneos.decode.client.tests.none/image.png");
    // A framework icon (not the test app's): passed as it is.
    private static final Icon FRAMEWORK_ICON = Icon.createWithResource("android", android.R.drawable.ic_dialog_info);
    private static final int ORACLE_WIDTH = 7;
    private static final int ORACLE_HEIGHT = 3;

    private final Context mContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private final String mPackage = mContext.getPackageName();

    private static Icon dataIcon(int n) {
        final byte[] data = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 0, 0, 0, (byte) n, (byte) (n >> 8)};
        return Icon.createWithData(data, 0, data.length);
    }

    // A valid 7 x 3 PNG, different for each n: what Android would decode if it ever got it.
    private static Icon oracleIcon(int n) {
        final Bitmap bitmap = Bitmap.createBitmap(ORACLE_WIDTH, ORACLE_HEIGHT, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(0xff000000 | n);
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        final byte[] png = out.toByteArray();
        return Icon.createWithData(png, 0, png.length);
    }

    private NotificationImageGate gate(ImageBudget budget) {
        return NotificationImageGate.forNotification(mContext, mPackage, mContext.getUserId(), 64, 512, 512,
                budget);
    }

    // On an image thread: the only place the VM is asked.
    private static <T> T onImageThread(Callable<T> callable) throws Exception {
        final FutureTask<T> task = new FutureTask<>(callable);
        ImageThreads.execute("de.diamaneos.decode.client.tests", task);
        return task.get(30, TimeUnit.SECONDS);
    }

    // On a plain worker, like SystemUI's inflation thread: nothing may wait for the VM there.
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

    // Every drawable in the applied views, compound drawables and backgrounds included.
    private static void drawables(View view, List<Drawable> out) {
        if (view.getBackground() != null) {
            out.add(view.getBackground());
        }
        if (view instanceof ImageView && ((ImageView) view).getDrawable() != null) {
            out.add(((ImageView) view).getDrawable());
        }
        if (view instanceof TextView) {
            for (Drawable d : ((TextView) view).getCompoundDrawablesRelative()) {
                if (d != null) {
                    out.add(d);
                }
            }
        }
        if (view instanceof ViewGroup) {
            for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
                drawables(((ViewGroup) view).getChildAt(i), out);
            }
        }
    }

    // No drawable in the tree holds an oracle image, so nothing of the app was decoded on Android.
    private static void assertNoNativeDecode(View root) {
        final List<Drawable> found = new ArrayList<>();
        drawables(root, found);
        for (Drawable d : found) {
            if (d instanceof BitmapDrawable && ((BitmapDrawable) d).getBitmap() != null) {
                final Bitmap b = ((BitmapDrawable) d).getBitmap();
                assertFalse("decoded on Android: " + d,
                        b.getWidth() == ORACLE_WIDTH && b.getHeight() == ORACLE_HEIGHT);
            }
        }
    }

    private static void assertIconMadeOrEmpty(String what, Icon icon) {
        assertNotNull(what, icon);
        assertTrue(what + ": " + icon, icon.getType() == Icon.TYPE_BITMAP
                || icon.getType() == Icon.TYPE_ADAPTIVE_BITMAP);
        assertFalse(what + " is the app's image", icon.getBitmap().getWidth() == ORACLE_WIDTH
                && icon.getBitmap().getHeight() == ORACLE_HEIGHT);
    }

    @Test
    public void layoutXml_everyBitmapFileGoesThroughTheGate() throws Exception {
        final NotificationImageGate gate = gate(new ImageBudget());
        final Context context = gate.packageContext(mContext);
        final View root = onImageThread(() -> inflate(context, new RemoteViews(mPackage, R.layout.vm_test_layout)));

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
        final View root = onImageThread(() -> inflate(context, views));

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
    public void inflationThread_neverWaitsForTheVm() throws Exception {
        // Like SystemUI's inflation thread: a worker that is not an image thread.
        final NotificationImageGate gate = gate(new ImageBudget());
        final Context context = gate.packageContext(mContext);
        final Notification copy = onWorker(() -> gate.sanitizedCopy(everyLayout(1000)));
        final View root = onWorker(() -> inflate(context, new RemoteViews(mPackage, R.layout.vm_test_layout)));
        assertNotNull(root);
        assertNotNull(copy.contentView);
        assertEquals(0, gate.budget().vmCalls());
    }

    @Test
    public void prewarm_makesTheImagesAheadOfTheInflation() throws Exception {
        final NotificationImageGate gate = gate(new ImageBudget());
        final Context context = gate.packageContext(mContext);
        final Notification n = new Notification.Builder(mContext, "test")
                .setSmallIcon(FRAMEWORK_ICON)
                .setCustomContentView(new RemoteViews(mPackage, R.layout.vm_test_layout))
                .build();
        final Notification copy = onImageThread(() -> {
            final Notification c = gate.sanitizedCopy(n);
            gate.prewarm(c, context);
            return c;
        });
        final int calls = gate.budget().vmCalls();
        // The inflation thread then finds what the image thread made: the same pixels, no new VM call.
        final View warm = onWorker(() -> inflate(context, copy.contentView));
        assertEquals(calls, gate.budget().vmCalls());
        assertTrue(gate.resourceImages().files().contains(file(R.drawable.vm_test_red)));
        assertMadeOrEmpty("src", ((ImageView) warm.findViewById(R.id.vm_src_png)).getDrawable());
    }

    @Test
    public void bitmapXml_onTheMainThread_usesWhatWasMade() throws Exception {
        final NotificationImageGate gate = gate(new ImageBudget());
        final Context context = gate.packageContext(mContext);
        final View worker = onImageThread(() -> inflate(context, new RemoteViews(mPackage, R.layout.vm_test_layout)));
        final View main = onMain(() -> inflate(context, new RemoteViews(mPackage, R.layout.vm_test_layout)));
        final Drawable made = ((ImageView) worker.findViewById(R.id.vm_src_bitmap_xml)).getDrawable();
        final Drawable shown = ((ImageView) main.findViewById(R.id.vm_src_bitmap_xml)).getDrawable();
        if (made instanceof BitmapDrawable && ((BitmapDrawable) made).getBitmap().getWidth() > 1) {
            // The VM made it: the main thread shows the same image, not an empty one.
            assertTrue(shown instanceof BitmapDrawable);
            assertEquals(((BitmapDrawable) made).getBitmap().getWidth(),
                    ((BitmapDrawable) shown).getBitmap().getWidth());
        }
    }

    @Test
    public void ninePatch_keepsItsOpticalInsets() throws Exception {
        final NotificationImageGate gate = gate(new ImageBudget());
        final Context context = gate.packageContext(mContext);
        final Drawable d = onImageThread(() -> context.getDrawable(R.drawable.vm_test_patch_lb));
        assertTrue(gate.resourceImages().files().contains(file(R.drawable.vm_test_patch_lb)));
        if (d instanceof NinePatchDrawable) {
            // Made in the VM: stretching and optical insets as the file has them.
            assertEquals(Insets.of(1, 1, 1, 1), d.getOpticalInsets());
        } else {
            assertTrue(String.valueOf(d), d instanceof ColorDrawable);
        }
    }

    @Test
    public void vmDown_costsImagesNotTheLayout() throws Exception {
        // No time left for the VM: the same path as a VM that does not answer.
        final NotificationImageGate gate = gate(new ImageBudget(0, ImageBudget.MAX_IMAGES, ImageBudget.MAX_PIXELS));
        final Context context = gate.packageContext(mContext);
        final long start = SystemClock.uptimeMillis();
        final View root = onImageThread(() -> inflate(context, new RemoteViews(mPackage, R.layout.vm_test_layout)));
        assertTrue(SystemClock.uptimeMillis() - start < 5_000);
        assertEquals(0, gate.budget().vmCalls());
        assertMadeOrEmpty("src", ((ImageView) root.findViewById(R.id.vm_src_png)).getDrawable());
        assertNotNull(((ImageView) root.findViewById(R.id.vm_src_nine)).getDrawable());
    }

    private RemoteViews everyIconAction(int seed) {
        final RemoteViews layout = new RemoteViews(mPackage, R.layout.vm_test_layout);
        layout.setImageViewIcon(R.id.vm_src_png, dataIcon(seed + 1));
        layout.setImageViewUri(R.id.vm_target, URI);
        layout.setIcon(R.id.vm_src_bitmap_xml, "setImageIcon",
                Icon.createWithResource(mContext, R.drawable.vm_test_red));
        layout.setIcon(R.id.vm_target_night, "setImageIcon", dataIcon(seed + 2), Icon.createWithContentUri(URI));
        layout.setTextViewCompoundDrawablesRelative(R.id.vm_text, dataIcon(seed + 3), null,
                Icon.createWithResource(mContext, R.drawable.vm_test_vector), null);
        // A framework resource is kept as it is.
        layout.setImageViewIcon(R.id.vm_src_vector,
                Icon.createWithResource("android", android.R.drawable.ic_menu_add));
        final RemoteViews child = new RemoteViews(mPackage, R.layout.vm_test_child);
        child.setImageViewIcon(R.id.vm_child_image, dataIcon(seed + 4));
        child.setImageViewUri(R.id.vm_child_image, URI);
        layout.addView(R.id.vm_container, child);
        return layout;
    }

    private RemoteViews childWith(Icon icon) {
        final RemoteViews child = new RemoteViews(mPackage, R.layout.vm_test_child);
        child.setImageViewIcon(R.id.vm_child_image, icon);
        return child;
    }

    private Notification everyLayout(int seed) {
        final Map<SizeF, RemoteViews> sized = new LinkedHashMap<>();
        sized.put(new SizeF(100, 100), childWith(dataIcon(seed + 5)));
        sized.put(new SizeF(200, 200), childWith(Icon.createWithContentUri(URI)));
        final Notification publicVersion = new Notification.Builder(mContext, "test")
                .setSmallIcon(FRAMEWORK_ICON)
                .setCustomContentView(childWith(dataIcon(seed + 6)))
                .build();
        return new Notification.Builder(mContext, "test")
                .setSmallIcon(FRAMEWORK_ICON)
                .setCustomContentView(everyIconAction(seed))
                .setCustomBigContentView(new RemoteViews(childWith(dataIcon(seed + 7)),
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

    // Kept, and no link left (the framework's own list of links).
    private static void assertKeptWithoutLinks(RemoteViews views) {
        assertNotNull("layout dropped", views);
        final List<Uri> links = new ArrayList<>();
        views.visitUris(links::add);
        assertTrue(links.toString(), links.isEmpty());
    }

    @Test
    public void everyIconAndLink_becomesABitmap_layoutsKept() throws Exception {
        final Notification original = everyLayout(0);
        final NotificationImageGate gate = gate(new ImageBudget());
        final Context context = gate.packageContext(mContext);
        final Notification copy = onImageThread(() -> gate.sanitizedCopy(original));

        for (RemoteViews views : layouts(copy)) {
            assertKeptWithoutLinks(views);
        }
        // The original is unchanged.
        final List<Uri> links = new ArrayList<>();
        original.contentView.visitUris(links::add);
        assertFalse(links.isEmpty());

        // The converted layout applies: setImageViewUri became setImageIcon on the same view.
        final View root = onImageThread(() -> inflate(context, copy.contentView));
        assertNotNull(((ImageView) root.findViewById(R.id.vm_target)).getDrawable());
        assertNotNull(((ImageView) root.findViewById(R.id.vm_src_png)).getDrawable());
        assertNotNull(((ImageView) root.findViewById(R.id.vm_child_image)).getDrawable());
    }

    // A layout with every kind of image action, all of them oracle images (decodable on Android).
    private RemoteViews oracleLayout() {
        final RemoteViews layout = new RemoteViews(mPackage, R.layout.vm_test_probe);
        layout.setImageViewIcon(R.id.vm_image, oracleIcon(1));
        layout.setIcon(R.id.vm_image_night, "setImageIcon", oracleIcon(2), oracleIcon(3));
        layout.setTextViewCompoundDrawablesRelative(R.id.vm_compound, oracleIcon(4), oracleIcon(5), null, null);
        layout.setIcon(R.id.vm_probe, "setImageIcon", oracleIcon(6));
        layout.setImageViewUri(R.id.vm_probe, URI);
        // A Bundle like MessagingLayout.setData gets: a sender and a list of messages with their senders.
        final Bundle data = new Bundle();
        data.putParcelable(Notification.EXTRA_MESSAGING_PERSON,
                new Person.Builder().setName("me").setIcon(oracleIcon(7)).build());
        final Bundle message = new Bundle();
        message.putCharSequence("text", "hi");
        message.putParcelable("sender_person", new Person.Builder().setName("A").setIcon(oracleIcon(8)).build());
        data.putParcelableArray(Notification.EXTRA_MESSAGES, new Parcelable[] {message});
        layout.setBundle(R.id.vm_probe, "setData", data);
        final RemoteViews child = new RemoteViews(mPackage, R.layout.vm_test_child);
        child.setImageViewIcon(R.id.vm_child_image, oracleIcon(9));
        layout.addView(R.id.vm_probe_container, child);
        return layout;
    }

    @Test
    public void oracle_nothingOfTheAppIsDecodedOnAndroid() throws Exception {
        final Notification original = new Notification.Builder(mContext, "test")
                .setSmallIcon(FRAMEWORK_ICON)
                .setCustomContentView(oracleLayout())
                .build();
        // No time for the VM: every app image must come out empty.
        final NotificationImageGate gate = gate(new ImageBudget(0, ImageBudget.MAX_IMAGES, ImageBudget.MAX_PIXELS));
        final Context context = gate.packageContext(mContext);
        final Notification copy = onImageThread(() -> gate.sanitizedCopy(original));
        assertKeptWithoutLinks(copy.contentView);
        final View root = onImageThread(() -> inflate(context, copy.contentView));
        assertNoNativeDecode(root);

        final ProbeView probe = root.findViewById(R.id.vm_probe);
        assertTrue(probe.uris.toString(), probe.uris.isEmpty());
        assertEquals(2, probe.icons.size());
        for (Icon icon : probe.icons) {
            assertIconMadeOrEmpty("probe", icon);
        }
        // The Bundle: both avatars were re-made too, as the view gets them.
        assertEquals(1, probe.bundles.size());
        final Bundle data = probe.bundles.get(0);
        assertIconMadeOrEmpty("messaging person",
                data.getParcelable(Notification.EXTRA_MESSAGING_PERSON, Person.class).getIcon());
        final Bundle message = (Bundle) data.getParcelableArray(Notification.EXTRA_MESSAGES, Parcelable.class)[0];
        assertIconMadeOrEmpty("sender", message.getParcelable("sender_person", Person.class).getIcon());
    }

    @Test
    public void bundle_withALinkOrAnUnknownValue_dropsTheLayout() throws Exception {
        final RemoteViews withLink = new RemoteViews(mPackage, R.layout.vm_test_probe);
        final Bundle message = new Bundle();
        message.putParcelable("uri", URI);
        final Bundle data = new Bundle();
        data.putParcelableArray(Notification.EXTRA_MESSAGES, new Parcelable[] {message});
        withLink.setBundle(R.id.vm_probe, "setData", data);

        final RemoteViews withUnknown = new RemoteViews(mPackage, R.layout.vm_test_probe);
        final Bundle odd = new Bundle();
        odd.putParcelable("rect", new android.graphics.Rect(1, 2, 3, 4));
        withUnknown.setBundle(R.id.vm_probe, "setData", odd);

        final Notification original = new Notification.Builder(mContext, "test")
                .setSmallIcon(FRAMEWORK_ICON)
                .setCustomContentView(withLink)
                .setCustomBigContentView(withUnknown)
                .build();
        final NotificationImageGate gate = gate(new ImageBudget());
        gate.packageContext(mContext);
        final Notification copy = onImageThread(() -> gate.sanitizedCopy(original));
        assertNull(copy.contentView);
        assertNull(copy.bigContentView);
    }

    @Test
    public void mainThread_layoutsKept_nothingDecoded() {
        final Notification original = everyLayout(2000);
        final NotificationImageGate gate = gate(new ImageBudget());
        final Notification copy = onMain(() -> {
            gate.packageContext(mContext);
            return gate.sanitizedCopy(original);
        });
        for (RemoteViews views : layouts(copy)) {
            assertKeptWithoutLinks(views);
        }
        assertEquals(0, gate.budget().vmCalls());
    }

    @Test
    public void imageCap_overTheCapIsEmptyWithoutAskingTheVm() throws Exception {
        final int count = ImageBudget.MAX_IMAGES + 16;
        final RemoteViews layout = new RemoteViews(mPackage, R.layout.vm_test_layout);
        for (int i = 0; i < count; i++) {
            layout.addView(R.id.vm_container, childWith(dataIcon(3000 + i)));
        }
        final Notification original = new Notification.Builder(mContext, "test")
                .setSmallIcon(FRAMEWORK_ICON)
                .setCustomContentView(layout)
                .build();
        final NotificationImageGate gate = gate(new ImageBudget());
        gate.packageContext(mContext);
        final long start = SystemClock.uptimeMillis();
        final Notification copy = onImageThread(() -> gate.sanitizedCopy(original));
        assertTrue(SystemClock.uptimeMillis() - start < NotificationImageGate.BUDGET_MS + 3_000);
        assertKeptWithoutLinks(copy.contentView);
        assertTrue(gate.budget().vmCalls() <= ImageBudget.MAX_IMAGES);
        assertTrue(gate.budget().refused() >= count - ImageBudget.MAX_IMAGES);
    }

    @Test
    public void noImageGate_customLayoutsDropped() throws Exception {
        final Notification original = everyLayout(4000);
        // packageContext() was not called: the layouts could not be applied safely.
        final NotificationImageGate gate = gate(new ImageBudget());
        final Notification copy = onImageThread(() -> gate.sanitizedCopy(original));
        assertNull(copy.contentView);
        assertNull(copy.bigContentView);
        assertNull(copy.headsUpContentView);
        assertNull(copy.publicVersion.contentView);
    }

    @Test
    public void reinflation_startsAFreshRoundOfTheSameGate() throws Exception {
        final NotificationImageGate first = gate(new ImageBudget());
        for (int i = 0; i < ImageBudget.MAX_IMAGES; i++) {
            first.budget().admit("image" + i);
        }
        assertEquals(ImageBudget.Admission.REFUSED, first.budget().admit("one more"));
        final NotificationImageGate second = NotificationImageGate.forNotification(mContext, mPackage,
                mContext.getUserId(), 64, 512, 512, first.resourceImages());
        // The row's views keep the same gate, which goes on with a fresh budget.
        assertSame(first.resourceImages(), second.resourceImages());
        assertNotSame(first.budget(), second.budget());
        assertSame(second.budget(), second.resourceImages().budget());
        assertEquals(ImageBudget.Admission.NEW, second.budget().admit("one more"));
    }

    @Test
    public void strippedCopy_removesEveryAppImage() {
        final Notification original = new Notification.Builder(mContext, "test")
                .setSmallIcon(Icon.createWithResource(mPackage, R.drawable.vm_test_red))
                .setLargeIcon(dataIcon(1))
                .setCustomContentView(everyIconAction(5000))
                .build();
        final Notification copy = NotificationImageGate.strippedCopy(mContext, original);
        // The app's resource small icon too: the neutral icon.
        assertEquals("android", copy.getSmallIcon().getResPackage());
        assertNull(copy.getLargeIcon());
        assertNull(copy.contentView);
        // The original is unchanged.
        assertNotNull(original.contentView);
        assertEquals(Icon.TYPE_DATA, original.getLargeIcon().getType());
    }

    @Test
    public void templateOnly_doesNotCopyForALayout() {
        final Notification n = new Notification.Builder(mContext, "test")
                .setSmallIcon(FRAMEWORK_ICON)
                .setCustomContentView(everyIconAction(6000))
                .build();
        final NotificationImageGate gate = NotificationImageGate.forNotification(mContext, mPackage,
                mContext.getUserId(), 1, 1, 1).templateOnly();
        assertSame(n, gate.sanitizedCopy(n));
    }

    @Test
    public void uriCacheKeys_carryTheUser() {
        final Context otherUser = new ContextWrapper(mContext) {
            @Override
            public int getUserId() {
                return 10;
            }
        };
        final Icon icon = Icon.createWithContentUri(URI);
        final String own = VmIcons.cacheKey(icon, mContext, mPackage, "1x1");
        final String other = VmIcons.cacheKey(icon, otherUser, mPackage, "1x1");
        assertTrue(other, other.contains(":10:"));
        assertFalse(own.equals(other));
    }

    @Test
    public void imageThreads_oneTaskPerAppAtATime_appsTakeTurns() throws Exception {
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch otherRan = new CountDownLatch(1);
        final AtomicInteger running = new AtomicInteger();
        final AtomicInteger maxRunning = new AtomicInteger();
        final CountDownLatch done = new CountDownLatch(3);
        final Runnable slow = () -> {
            maxRunning.accumulateAndGet(running.incrementAndGet(), Math::max);
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                // Ends the task.
            }
            running.decrementAndGet();
            done.countDown();
        };
        ImageThreads.execute("app.slow", slow);
        ImageThreads.execute("app.slow", slow);
        ImageThreads.execute("app.other", () -> {
            otherRan.countDown();
            done.countDown();
        });
        // Another app is not held up by the slow one.
        assertTrue(otherRan.await(5, TimeUnit.SECONDS));
        release.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS));
        // The slow app never had two tasks at once.
        assertEquals(1, maxRunning.get());
    }

    private byte[] fileBytes(int drawable) throws Exception {
        final TypedValue value = new TypedValue();
        mContext.getResources().getValue(drawable, value, true);
        try (java.io.InputStream in = mContext.getResources().getAssets().openNonAsset(value.assetCookie,
                value.string.toString())) {
            return in.readAllBytes();
        }
    }

    @Test
    public void ninePatch_opticalInsetsOfTheBuiltFile_inTheDeviceByteOrder() throws Exception {
        // The file as aapt2 built it into this APK: its npLb chunk must read as the insets drawn in its border.
        final byte[] file = fileBytes(R.drawable.vm_test_patch_lb);
        final NinePatchChunk chunk = NinePatchChunk.fromPng(file, file.length);
        assertNotNull(chunk);
        assertNotNull("optical insets not read", chunk.opticalInsets());
        assertEquals(java.util.Arrays.toString(new int[] {1, 1, 1, 1}),
                java.util.Arrays.toString(chunk.opticalInsets()));
    }

    private Context withConfig(int uiNight, int orientation) {
        final Configuration config = new Configuration(mContext.getResources().getConfiguration());
        config.uiMode = (config.uiMode & ~Configuration.UI_MODE_NIGHT_MASK) | uiNight;
        config.orientation = orientation;
        return mContext.createConfigurationContext(config);
    }

    @Test
    public void resourceIconVariants_nightAndLandscapePngGoThroughTheGate() throws Exception {
        final Icon variant = Icon.createWithResource(mPackage, R.drawable.vm_test_variant);
        final String nightFile;
        final String landFile;
        {
            final TypedValue value = new TypedValue();
            withConfig(Configuration.UI_MODE_NIGHT_YES, Configuration.ORIENTATION_PORTRAIT).getResources()
                    .getValue(R.drawable.vm_test_variant, value, true);
            nightFile = value.string.toString();
            withConfig(Configuration.UI_MODE_NIGHT_NO, Configuration.ORIENTATION_LANDSCAPE).getResources()
                    .getValue(R.drawable.vm_test_variant, value, true);
            landFile = value.string.toString();
            assertTrue(nightFile, nightFile.endsWith(".png"));
            assertTrue(landFile, landFile.endsWith(".png"));
        }
        for (Context config : new Context[] {
                withConfig(Configuration.UI_MODE_NIGHT_NO, Configuration.ORIENTATION_PORTRAIT),
                withConfig(Configuration.UI_MODE_NIGHT_YES, Configuration.ORIENTATION_PORTRAIT),
                withConfig(Configuration.UI_MODE_NIGHT_NO, Configuration.ORIENTATION_LANDSCAPE)}) {
            final Notification original = new Notification.Builder(mContext, "test")
                    .setSmallIcon(variant)
                    .setLargeIcon(variant)
                    .build();
            final NotificationImageGate gate = NotificationImageGate.forNotification(config, mPackage,
                    mContext.getUserId(), 64, 512, 512, new ImageBudget());
            gate.packageContext(config);
            final Notification copy = onImageThread(() -> gate.sanitizedCopy(original));
            final Icon small = copy.getSmallIcon();
            if (small.getType() == Icon.TYPE_RESOURCE && !"android".equals(small.getResPackage())) {
                // Kept as a resource icon: it loads through the gate, in this configuration and in any other.
                assertNotNull(small.getResources());
                assertNotNull(small.getResources().getImageFileGate());
            }
            final boolean night = (config.getResources().getConfiguration().uiMode
                    & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
            final boolean land = config.getResources().getConfiguration().orientation
                    == Configuration.ORIENTATION_LANDSCAPE;
            final List<String> files = gate.resourceImages().files();
            if (night) {
                assertTrue(files.toString(), files.contains(nightFile));
            } else if (land) {
                assertTrue(files.toString(), files.contains(landFile));
            } else {
                // The vector: no bitmap file.
                assertTrue(files.toString(), files.isEmpty());
            }
        }
    }

    @Test
    public void resourceIcon_loadedWithAGatedContext_neverUsesPlainResources() throws Exception {
        // An Icon that lost its resources (parcelled) and is loaded with the context of a gated layout: the PNG goes
        // through the gate, never through the package manager's resources.
        final Context night = withConfig(Configuration.UI_MODE_NIGHT_YES, Configuration.ORIENTATION_PORTRAIT);
        final NotificationImageGate gate = NotificationImageGate.forNotification(night, mPackage,
                mContext.getUserId(), 64, 512, 512, new ImageBudget());
        final Context gated = gate.packageContext(night);
        final Icon variant = Icon.createWithResource(mPackage, R.drawable.vm_test_variant);
        final Drawable drawable = onImageThread(() -> variant.loadDrawable(gated));
        // The gated resources are used for that load only: nothing is kept in the (shared) icon.
        assertNull(variant.getResources());
        assertTrue(gate.resourceImages().files().toString(), gate.resourceImages().files().size() == 1);
        assertMadeOrEmpty("night variant", drawable);
    }

    @Test
    public void resourceIcon_withPlainResourcesCached_stillLoadsThroughTheGate() throws Exception {
        // A shared icon that some other code loaded with plain resources first (they are cached in it). A load
        // through a gated context must not take them.
        final Icon red = Icon.createWithResource(mPackage, R.drawable.vm_test_red);
        assertNotNull(red.loadDrawable(mContext));
        assertNotNull(red.getResources());
        assertNull(red.getResources().getImageFileGate());
        final NotificationImageGate gate = gate(new ImageBudget());
        final Context gated = gate.packageContext(mContext);
        // On a worker nothing is decoded: the PNG is missing, so it went to the gate, not to the cached resources.
        final boolean missed = onWorker(() -> {
            AppResourceImages.beginTracking();
            red.loadDrawable(gated);
            return AppResourceImages.missed();
        });
        assertTrue(missed);
        assertTrue(gate.resourceImages().files().contains(file(R.drawable.vm_test_red)));
        // The plain resources stay where they were, the gated ones were not kept.
        assertNull(red.getResources().getImageFileGate());
    }

    @Test
    public void rowGate_keepsNoTask_rowIsCollectable() throws Exception {
        // As in SystemUI: the gate of each row in a weak map keyed by the row, and a task that holds the row and
        // cancels the gate's work. The gate must keep only the task's flag, or the row could never be collected.
        final Map<Object, AppResourceImages> rows =
                java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());
        final Object[] task = {new Object[] {new Object(), new java.util.concurrent.atomic.AtomicBoolean()}};
        {
            final Object row = ((Object[]) task[0])[0];
            final java.util.concurrent.atomic.AtomicBoolean cancelled =
                    (java.util.concurrent.atomic.AtomicBoolean) ((Object[]) task[0])[1];
            final NotificationImageGate gate = NotificationImageGate.forNotification(mContext, mPackage,
                    mContext.getUserId(), 64, 512, 512, rows.get(row));
            rows.put(row, gate.resourceImages());
            gate.setCancelled(cancelled);
            gate.packageContext(mContext);
            onImageThread(() -> gate.sanitizedCopy(everyLayout(7100)));
            gate.finish(false);
            // The task is cancelled and the notification removed: nothing holds the row any more.
            cancelled.set(true);
        }
        task[0] = null;
        final long end = SystemClock.uptimeMillis() + 10_000;
        while (!rows.isEmpty() && SystemClock.uptimeMillis() < end) {
            Runtime.getRuntime().gc();
            Runtime.getRuntime().runFinalization();
            Thread.sleep(50);
        }
        assertTrue("row still reachable", rows.isEmpty());
    }

    @Test
    public void appIcons_onTheMainThread_neutralOrGated_neverPlain() throws Exception {
        final Context night = withConfig(Configuration.UI_MODE_NIGHT_YES, Configuration.ORIENTATION_PORTRAIT);
        final Icon variant = Icon.createWithResource(mPackage, R.drawable.vm_test_variant);
        final CountDownLatch made = new CountDownLatch(1);
        final Icon shown = onMain(() -> AppIcons.forView(night, variant, mContext.getUserId(), mPackage,
                made::countDown));
        assertNotNull(shown);
        if ("android".equals(shown.getResPackage())) {
            // Not made yet: the neutral icon, and an image thread makes it.
            assertEquals(android.R.drawable.ic_dialog_info, shown.getResId());
        } else {
            assertNotNull(shown.getResources().getImageFileGate());
        }
        // The day variant is a vector: shown through the gate at once.
        final Context day = withConfig(Configuration.UI_MODE_NIGHT_NO, Configuration.ORIENTATION_PORTRAIT);
        final Icon dayIcon = onMain(() -> AppIcons.forView(day, variant, mContext.getUserId(), mPackage, null));
        assertEquals(mPackage, dayIcon.getResPackage());
        assertNotNull(dayIcon.getResources().getImageFileGate());
        // A URI or data icon is never loaded on the main thread.
        assertEquals("android", onMain(() -> AppIcons.forView(day, dataIcon(1), 0, mPackage, null)).getResPackage());
    }

    @Test
    public void bundle_toAnotherMethodOrTooLarge_dropsTheLayout() throws Exception {
        final RemoteViews otherMethod = new RemoteViews(mPackage, R.layout.vm_test_probe);
        final Bundle data = new Bundle();
        data.putCharSequence(Notification.EXTRA_TITLE, "t");
        otherMethod.setBundle(R.id.vm_probe, "setSomethingElse", data);

        final RemoteViews tooLarge = new RemoteViews(mPackage, R.layout.vm_test_probe);
        final Bundle big = new Bundle();
        big.putCharSequence(Notification.EXTRA_TEXT, "x".repeat(300_000));
        tooLarge.setBundle(R.id.vm_probe, "setData", big);

        final Notification original = new Notification.Builder(mContext, "test")
                .setSmallIcon(FRAMEWORK_ICON)
                .setCustomContentView(otherMethod)
                .setCustomBigContentView(tooLarge)
                .build();
        final NotificationImageGate gate = gate(new ImageBudget());
        gate.packageContext(mContext);
        final Notification copy = onImageThread(() -> gate.sanitizedCopy(original));
        assertNull(copy.contentView);
        assertNull(copy.bigContentView);
    }

    @Test
    public void bundle_keepsOnlyWhatTheViewReads() throws Exception {
        final RemoteViews views = new RemoteViews(mPackage, R.layout.vm_test_probe);
        final Bundle data = new Bundle();
        data.putCharSequence(Notification.EXTRA_CONVERSATION_TITLE, "chat");
        data.putBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, true);
        views.setBundle(R.id.vm_probe, "setGroupFacePile", data);
        final Notification original = new Notification.Builder(mContext, "test")
                .setSmallIcon(FRAMEWORK_ICON)
                .setCustomContentView(views)
                .build();
        final NotificationImageGate gate = gate(new ImageBudget());
        final Context context = gate.packageContext(mContext);
        final Notification copy = onImageThread(() -> gate.sanitizedCopy(original));
        assertNotNull(copy.contentView);
        final View root = onImageThread(() -> inflate(context, copy.contentView));
        final Bundle got = ((ProbeView) root.findViewById(R.id.vm_probe)).bundles.get(0);
        assertEquals("chat", String.valueOf(got.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)));
        assertTrue(got.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION));
        assertEquals(2, got.keySet().size());
    }

    private NotificationImageGate.Lane lane(Notification n, int uid) {
        return NotificationImageGate.lane(mContext, n, mPackage, uid, mContext.getUserId());
    }

    @Test
    public void lanes_urgentOnlyForWhatAnAppCannotClaim() {
        final int appUid = android.os.Process.FIRST_APPLICATION_UID + 1234;
        final Notification plain = new Notification.Builder(mContext, "test").setSmallIcon(FRAMEWORK_ICON).build();
        assertEquals(NotificationImageGate.Lane.NONE, lane(plain, appUid));
        // A category or a call person any app can set: the normal lane.
        final Notification claimed = new Notification.Builder(mContext, "test")
                .setSmallIcon(Icon.createWithResource(mPackage, R.drawable.vm_test_red))
                .setCategory(Notification.CATEGORY_CALL)
                .build();
        claimed.extras.putParcelable(Notification.EXTRA_CALL_PERSON, new Person.Builder().setName("a").build());
        assertEquals(NotificationImageGate.Lane.NORMAL, lane(claimed, appUid));
        // A call style of a foreground service, a full-screen intent, a system process: urgent.
        final Notification call = new Notification.Builder(mContext, "test")
                .setSmallIcon(Icon.createWithResource(mPackage, R.drawable.vm_test_red))
                .setStyle(Notification.CallStyle.forOngoingCall(new Person.Builder().setName("a").build(),
                        android.app.PendingIntent.getActivity(mContext, 0,
                                new android.content.Intent("x").setPackage(mPackage),
                                android.app.PendingIntent.FLAG_IMMUTABLE)))
                .build();
        // A call style of a foreground service of an app not on the system image and in no self-managed call
        // (this test app): normal.
        call.flags |= Notification.FLAG_FOREGROUND_SERVICE;
        assertEquals(NotificationImageGate.Lane.NORMAL, lane(call, appUid));
        // A full-screen intent no longer counts: any app can hold the permission.
        final Notification alarm = new Notification.Builder(mContext, "test")
                .setSmallIcon(Icon.createWithResource(mPackage, R.drawable.vm_test_red))
                .setCategory(Notification.CATEGORY_ALARM)
                .setFullScreenIntent(android.app.PendingIntent.getActivity(mContext, 0,
                        new android.content.Intent("x").setPackage(mPackage),
                        android.app.PendingIntent.FLAG_IMMUTABLE), true)
                .build();
        assertEquals(NotificationImageGate.Lane.NORMAL, lane(alarm, appUid));
        // The same alarm from an app on the system image (as the notification service records it): urgent.
        final android.content.pm.ApplicationInfo system =
                new android.content.pm.ApplicationInfo(mContext.getApplicationInfo());
        system.flags |= android.content.pm.ApplicationInfo.FLAG_SYSTEM;
        alarm.extras.putParcelable(Notification.EXTRA_BUILDER_APPLICATION_INFO, system);
        assertEquals(NotificationImageGate.Lane.URGENT, lane(alarm, appUid));
        // A system process: urgent.
        assertEquals(NotificationImageGate.Lane.URGENT, lane(claimed, android.os.Process.PHONE_UID));
    }

    @Test
    public void appIcons_dataSmallIcon_neutralThenDecodedInTheVm() throws Exception {
        // The status bar, shelf and always-on display show the original notification's small icon. A data icon is
        // never decoded on the main thread: the neutral icon, then the VM's pixels once an image thread made them.
        final Icon data = oracleIcon(4242);
        final CountDownLatch made = new CountDownLatch(1);
        final Icon first = onMain(() -> AppIcons.forView(mContext, data, mContext.getUserId(), mPackage,
                made::countDown));
        assertEquals("android", first.getResPackage());
        if (made.await(10, TimeUnit.SECONDS)) {
            final Icon second = onMain(() -> AppIcons.forView(mContext, data, mContext.getUserId(), mPackage,
                    null));
            assertEquals(Icon.TYPE_BITMAP, second.getType());
        } else {
            // Without the decode service: still the neutral icon, nothing decoded here.
            assertEquals("android", onMain(() -> AppIcons.forView(mContext, data, mContext.getUserId(),
                    mPackage, null)).getResPackage());
        }
    }

    @Test
    public void noImageViews_linksGoToTheCallersContext_andNothingThrows() {
        final List<Intent> started = new ArrayList<>();
        final Context base = new ContextWrapper(mContext) {
            @Override
            public void startActivity(Intent intent) {
                started.add(intent);
                throw new android.util.AndroidRuntimeException("no new-task flag");
            }
        };
        final Context context = NoImageViews.context(base);
        // A tapped link (URLSpan) starts an activity on the view's context: the caller's wrapper gets it, and its
        // exception does not reach the touch handling.
        context.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://example.org")));
        assertEquals(1, started.size());
        // System services come from the caller's own context.
        assertSame(base.getSystemService(android.view.WindowManager.class),
                context.getSystemService(android.view.WindowManager.class));
    }

    @Test
    public void noImageViews_stripInPlace_andPackageDrawables() {
        final RemoteViews views = new RemoteViews(mPackage, R.layout.vm_test_probe);
        views.setImageViewIcon(R.id.vm_probe, oracleIcon(4444));
        assertTrue(NoImageViews.stripInPlace(views, null));
        assertTrue(RemoteViewsImages.icons(views).stream().allMatch(
                i -> i.getType() == Icon.TYPE_BITMAP && i.getBitmap().getWidth() == 1));
        // Views that cannot be checked: refused.
        final RemoteViews other = new RemoteViews(mPackage, R.layout.vm_test_probe);
        final Bundle data = new Bundle();
        data.putCharSequence(Notification.EXTRA_TITLE, "t");
        other.setBundle(R.id.vm_probe, "setSomethingElse", data);
        assertFalse(NoImageViews.stripInPlace(views, other));
        // A vector of an app loads, its PNG does not.
        assertNotNull(NoImageViews.packageDrawable(mContext, mPackage, mContext.getUserId(),
                R.drawable.vm_test_vector));
        assertNull(NoImageViews.packageDrawable(mContext, mPackage, mContext.getUserId(), R.drawable.vm_test_red));
    }

    @Test
    public void widgetImages_checkedCopy() throws Exception {
        final WidgetImages widget = onMain(() -> new WidgetImages(mContext));
        widget.setApp(mPackage, mContext.getUserId());
        final RemoteViews views = new RemoteViews(mPackage, R.layout.vm_test_probe);
        views.setImageViewIcon(R.id.vm_probe, oracleIcon(4545));
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicReference<RemoteViews> checked = new AtomicReference<>();
        widget.prepare(views, Runnable::run, v -> {
            checked.set(v);
            done.countDown();
        });
        assertTrue(done.await(10, TimeUnit.SECONDS));
        assertNotNull(checked.get());
        for (Icon icon : RemoteViewsImages.icons(checked.get())) {
            // Pixels from the VM, or empty without it: never the app's bytes.
            assertEquals(Icon.TYPE_BITMAP, icon.getType());
        }
        // The original is not changed.
        assertEquals(Icon.TYPE_DATA, RemoteViewsImages.icons(views).get(0).getType());
        // The host context keeps the caller's theme and resources.
        assertSame(mContext.getTheme(), widget.context().getTheme());
    }

    @Test
    public void noImageViews_emptyImagesAndNoAppBitmapFiles() throws Exception {
        final RemoteViews views = new RemoteViews(mPackage, R.layout.vm_test_probe);
        views.setImageViewIcon(R.id.vm_probe, oracleIcon(4343));
        views.setImageViewUri(R.id.vm_probe, URI);
        final RemoteViews copy = NoImageViews.withoutImages(views);
        assertNotNull(copy);
        final Context context = NoImageViews.context(mContext);
        final View root = onWorker(() -> inflate(context, copy));
        for (Icon icon : ((ProbeView) root.findViewById(R.id.vm_probe)).icons) {
            assertEquals(Icon.TYPE_BITMAP, icon.getType());
            assertEquals(1, icon.getBitmap().getWidth());
        }
        // A bitmap file of an app outside the system image comes out empty, a vector loads as before.
        assertTrue(onWorker(() -> context.getDrawable(R.drawable.vm_test_red)) instanceof ColorDrawable);
        assertTrue(onWorker(() -> context.getDrawable(R.drawable.vm_test_vector)) instanceof VectorDrawable);
    }

    @Test
    public void lanes_knownCleanIconsNeedNoImageThread() throws Exception {
        // A vector of the app: the normal lane until a load has shown that it names no bitmap file of the app.
        final Notification n = new Notification.Builder(mContext, "test")
                .setSmallIcon(Icon.createWithResource(mPackage, R.drawable.vm_test_vector))
                .build();
        final int appUid = android.os.Process.FIRST_APPLICATION_UID + 1234;
        n.extras.putParcelable(Notification.EXTRA_BUILDER_APPLICATION_INFO, mContext.getApplicationInfo());
        final NotificationImageGate gate = gate(new ImageBudget());
        gate.packageContext(mContext);
        onImageThread(() -> gate.sanitizedCopy(n));
        assertEquals(NotificationImageGate.Lane.NONE, lane(n, appUid));
        // A PNG of the app never becomes clean.
        final Notification png = new Notification.Builder(mContext, "test")
                .setSmallIcon(Icon.createWithResource(mPackage, R.drawable.vm_test_red))
                .build();
        png.extras.putParcelable(Notification.EXTRA_BUILDER_APPLICATION_INFO, mContext.getApplicationInfo());
        final NotificationImageGate pngGate = gate(new ImageBudget());
        pngGate.packageContext(mContext);
        onImageThread(() -> pngGate.sanitizedCopy(png));
        assertEquals(NotificationImageGate.Lane.NORMAL, lane(png, appUid));
        // Another APK (an update) or no APK known: not clean.
        final Notification other = new Notification.Builder(mContext, "test")
                .setSmallIcon(Icon.createWithResource(mPackage, R.drawable.vm_test_vector))
                .build();
        final android.content.pm.ApplicationInfo updated =
                new android.content.pm.ApplicationInfo(mContext.getApplicationInfo());
        updated.sourceDir = updated.sourceDir + ".new";
        other.extras.putParcelable(Notification.EXTRA_BUILDER_APPLICATION_INFO, updated);
        assertEquals(NotificationImageGate.Lane.NORMAL, lane(other, appUid));
        other.extras.remove(Notification.EXTRA_BUILDER_APPLICATION_INFO);
        assertEquals(NotificationImageGate.Lane.NORMAL, lane(other, appUid));
    }

    @Test
    public void headerCopy_loadsOnlyTheSmallIcon_andSurvivesBadExtras() {
        final Notification n = new Notification.Builder(mContext, "test")
                .setSmallIcon(Icon.createWithResource(mPackage, R.drawable.vm_test_vector))
                .setLargeIcon(Icon.createWithResource(mPackage, R.drawable.vm_test_red))
                .build();
        // A value of the wrong class under an icon key: read as nothing, never made.
        n.extras.putParcelable(Notification.EXTRA_PICTURE_ICON, new Bundle());
        final Notification copy = onMain(() -> NotificationImageGate.headerCopy(mContext, n, mPackage,
                mContext.getUserId()));
        assertNotNull(copy);
        // The small icon as the status bar shows it: through the gate (a vector loads at once).
        assertNotNull(copy.getSmallIcon().getResources());
        assertNotNull(copy.getSmallIcon().getResources().getImageFileGate());
        // Every other app image is left out without being loaded.
        assertNull(copy.getLargeIcon());
    }

    @Test
    public void layoutResourceIcons_cappedBeforeInflation_andTimed() throws Exception {
        final RemoteViews views = new RemoteViews(mPackage, R.layout.vm_test_probe);
        for (int i = 0; i < 200; i++) {
            views.setImageViewIcon(R.id.vm_probe, Icon.createWithResource(mPackage, R.drawable.vm_test_vector));
        }
        final Notification original = new Notification.Builder(mContext, "test")
                .setSmallIcon(FRAMEWORK_ICON)
                .setCustomContentView(views)
                .build();
        final NotificationImageGate gate = gate(new ImageBudget());
        final Context context = gate.packageContext(mContext);
        final Notification copy = onImageThread(() -> gate.sanitizedCopy(original));
        assertNotNull(copy.contentView);
        final View root = onImageThread(() -> inflate(context, copy.contentView));
        int drawn = 0;
        for (Icon icon : ((ProbeView) root.findViewById(R.id.vm_probe)).icons) {
            if (icon.getType() == Icon.TYPE_BITMAP && icon.getBitmap().getWidth() > 1) {
                drawn++;
            }
        }
        assertTrue("drawn " + drawn, drawn <= ImageBudget.MAX_IMAGES);
        // No time at all: nothing is inflated, every image is empty, and it ends at once.
        final NotificationImageGate spent = gate(new ImageBudget(0, ImageBudget.MAX_IMAGES, ImageBudget.MAX_PIXELS));
        final Context spentContext = spent.packageContext(mContext);
        final long start = SystemClock.uptimeMillis();
        final Notification empty = onImageThread(() -> spent.sanitizedCopy(original));
        assertTrue(SystemClock.uptimeMillis() - start < 5_000);
        final View emptyRoot = onImageThread(() -> inflate(spentContext, empty.contentView));
        for (Icon icon : ((ProbeView) emptyRoot.findViewById(R.id.vm_probe)).icons) {
            assertTrue(icon.getType() == Icon.TYPE_BITMAP && icon.getBitmap().getWidth() == 1);
        }
    }

    @Test
    public void fileUris_areRefused() throws Exception {
        final java.io.File file = new java.io.File(mContext.getCacheDir(), "vm_test.png");
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(file)) {
            Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, out);
        }
        try {
            onWorker(() -> VmDecode.decodeUri(mContext, mContext.getContentResolver(), Uri.fromFile(file),
                    mPackage, VmDecode.PRIORITY_USER_WAITING, 64, 64));
            throw new AssertionError("a file URI was opened");
        } catch (java.util.concurrent.ExecutionException e) {
            assertTrue(String.valueOf(e.getCause()), e.getCause() instanceof VmDecode.RejectedException);
        } finally {
            file.delete();
        }
    }

    @Test
    public void urgentLane_isNotHeldUpByNormalImageWork() throws Exception {
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch urgentRan = new CountDownLatch(1);
        // Every normal thread busy.
        for (int i = 0; i < 4; i++) {
            ImageThreads.execute("app.busy" + i, () -> {
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                    // Ends the task.
                }
            });
        }
        ImageThreads.executeUrgent("app.call", urgentRan::countDown);
        assertTrue(urgentRan.await(5, TimeUnit.SECONDS));
        release.countDown();
    }

    @Test
    public void cancelledWork_neverAsksTheVm() throws Exception {
        final Notification original = everyLayout(7000);
        final NotificationImageGate gate = gate(new ImageBudget());
        gate.packageContext(mContext);
        gate.setCancelled(new java.util.concurrent.atomic.AtomicBoolean(true));
        final Notification copy = onImageThread(() -> gate.sanitizedCopy(original));
        assertNotNull(copy);
        assertEquals(0, gate.budget().vmCalls());
        // A task cancelled before it runs is dropped.
        final CountDownLatch block = new CountDownLatch(1);
        final AtomicInteger ran = new AtomicInteger();
        ImageThreads.execute("app.cancel", () -> {
            try {
                block.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                // Ends the task.
            }
        });
        final ImageThreads.Task second = ImageThreads.execute("app.cancel", ran::incrementAndGet);
        second.cancel();
        block.countDown();
        final CountDownLatch after = new CountDownLatch(1);
        ImageThreads.execute("app.cancel", after::countDown);
        assertTrue(after.await(5, TimeUnit.SECONDS));
        assertEquals(0, ran.get());
    }

    @Test
    public void cachedImage_keptWhenNoTimeIsLeft() throws Exception {
        final Icon large = dataIcon(9100);
        final Bitmap made = Bitmap.createBitmap(3, 2, Bitmap.Config.ARGB_8888);
        VmIcons.cachePut(VmIcons.cacheKey(large, mContext, mPackage, "64x64"), made);
        final Notification original = new Notification.Builder(mContext, "test")
                .setSmallIcon(FRAMEWORK_ICON)
                .setLargeIcon(large)
                .build();
        // No time for the VM at all: the image already made is still shown.
        final NotificationImageGate gate = gate(new ImageBudget(0, ImageBudget.MAX_IMAGES, ImageBudget.MAX_PIXELS));
        gate.packageContext(mContext);
        final Notification copy = onImageThread(() -> gate.sanitizedCopy(original));
        assertNotNull(copy.getLargeIcon());
        assertSame(made, copy.getLargeIcon().getBitmap());
        assertEquals(0, gate.budget().vmCalls());
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
