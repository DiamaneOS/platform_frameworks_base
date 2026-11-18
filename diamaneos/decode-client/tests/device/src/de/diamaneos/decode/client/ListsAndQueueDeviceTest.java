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
import static org.junit.Assume.assumeTrue;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.widget.RemoteViews;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import de.diamaneos.decode.client.tests.R;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Widget lists: the flag the hub relies on, the old service-backed list actions refused in every form, list items in
 * the form real widgets send (the collection cache) checked on a copy that shares nothing with the update, and URIs
 * of list items only with the app's own access. Also the waiter caps and the drop callback of a loader. Runs with or
 * without the decode service.
 */
@RunWith(AndroidJUnit4.class)
public class ListsAndQueueDeviceTest {
    private final Context mContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private final String mPackage = mContext.getPackageName();

    private static Icon pngIcon(int n) {
        final Bitmap bitmap = Bitmap.createBitmap(7, 3, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(0xff000000 | n);
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        final byte[] png = out.toByteArray();
        return Icon.createWithData(png, 0, png.length);
    }

    private WidgetImages widget() {
        return widget(mPackage);
    }

    // A host view showing a widget of app ownerPackage.
    private WidgetImages widget(String ownerPackage) {
        final AtomicReference<WidgetImages> made = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> made.set(new WidgetImages(mContext)));
        made.get().setApp(ownerPackage, mContext.getUserId());
        return made.get();
    }

    // The checked copy of views (null: not shown), within 10 s.
    private static RemoteViews prepare(WidgetImages widget, RemoteViews views) throws InterruptedException {
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicReference<RemoteViews> checked = new AtomicReference<>();
        widget.prepare(views, Runnable::run, v -> {
            checked.set(v);
            done.countDown();
        });
        assertTrue(done.await(10, TimeUnit.SECONDS));
        return checked.get();
    }

    @Test
    public void remoteAdapterConversion_isOnInThisBuild() {
        // The hub's list path relies on it (the build checks it too).
        assertTrue(android.appwidget.flags.Flags.remoteAdapterConversion());
    }

    @Test
    public void serviceBackedLists_refusedInEveryForm() throws Exception {
        final Intent service = new Intent("x").setPackage(mPackage);
        // By method name, as a StackView or an AdapterViewFlipper takes it.
        final RemoteViews byName = new RemoteViews(mPackage, R.layout.vm_test_probe);
        byName.setIntent(R.id.vm_probe, "setRemoteViewsAdapter", service);
        assertNull(NoImageViews.withoutImages(byName));
        assertNull(prepare(widget(), byName));
        // Any view given an Intent.
        final RemoteViews anyIntent = new RemoteViews(mPackage, R.layout.vm_test_probe);
        anyIntent.setIntent(R.id.vm_probe, "setSomething", service);
        assertNull(NoImageViews.withoutImages(anyIntent));
        // The old action itself (tag 10), which the public API no longer makes with the flag on.
        final RemoteViews legacy = new RemoteViews(mPackage, R.layout.vm_test_probe);
        Object action;
        Method addAction;
        try {
            final Class<?> cls = Class.forName("android.widget.RemoteViews$SetRemoteViewsAdapterIntent");
            final Constructor<?> ctor = cls.getDeclaredConstructor(RemoteViews.class, int.class, Intent.class);
            ctor.setAccessible(true);
            action = ctor.newInstance(legacy, R.id.vm_probe, service);
            addAction = RemoteViews.class.getDeclaredMethod("addAction",
                    Class.forName("android.widget.RemoteViews$Action"));
            addAction.setAccessible(true);
        } catch (ReflectiveOperationException | RuntimeException e) {
            assumeTrue("the old action cannot be made here: " + e, false);
            return;
        }
        addAction.invoke(legacy, action);
        assertNull(NoImageViews.withoutImages(legacy));
        assertNull(prepare(widget(), legacy));
    }

    // views with a list in the form real widgets send it: the action names an entry of the collection cache, whose
    // items are items. Null when that cannot be made here.
    private RemoteViews cacheForm(RemoteCollectionItemsHolder holder) {
        try {
            final RemoteViews views = new RemoteViews(mPackage, R.layout.vm_test_probe);
            // With the flag on this is the list action with a service intent and no items.
            views.setRemoteAdapter(R.id.vm_probe, new Intent("x").setPackage(mPackage));
            final Field actionsField = RemoteViews.class.getDeclaredField("mActions");
            actionsField.setAccessible(true);
            final List<?> actions = (List<?>) actionsField.get(views);
            final Object action = actions.get(actions.size() - 1);
            final Field intentId = action.getClass().getDeclaredField("mIntentId");
            intentId.setAccessible(true);
            intentId.setInt(action, 7);
            final Field cacheField = RemoteViews.class.getDeclaredField("mCollectionCache");
            cacheField.setAccessible(true);
            final Object cache = cacheField.get(views);
            // As the system fills the cache (RemoteCollectionCache.connectAllUniqueIntents): the items take the
            // update's hierarchy, so they are written as its children. Without that the update does not survive a
            // parcel even on Android.
            final Method rootData = RemoteViews.class.getDeclaredMethod("getHierarchyRootData");
            rootData.setAccessible(true);
            final Object root = rootData.invoke(views);
            final Method setRoot = RemoteViews.RemoteCollectionItems.class.getDeclaredMethod("setHierarchyRootData",
                    root.getClass());
            setRoot.setAccessible(true);
            setRoot.invoke(holder.items, root);
            final Method addMapping = cache.getClass().getDeclaredMethod("addMapping", int.class, String.class,
                    RemoteViews.RemoteCollectionItems.class);
            addMapping.setAccessible(true);
            addMapping.invoke(cache, 7, "intent:test#7", holder.items);
            // The form must survive the parcel the system sends it in, as SystemUI receives it.
            final RemoteViews received = RemoteViewsImages.deepCopy(views);
            if (RemoteViewsImages.icons(received).size() != RemoteViewsImages.icons(views).size()) {
                return null;
            }
            return views;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    private static final class RemoteCollectionItemsHolder {
        final RemoteViews.RemoteCollectionItems items;

        RemoteCollectionItemsHolder(RemoteViews item) {
            items = new RemoteViews.RemoteCollectionItems.Builder().addItem(1, item).build();
        }
    }

    @Test
    public void cacheFormList_checkedOnACopy_andAgainTheSame() throws Exception {
        final RemoteViews item = new RemoteViews(mPackage, R.layout.vm_test_probe);
        item.setImageViewIcon(R.id.vm_probe, pngIcon(5151));
        final RemoteViews views = cacheForm(new RemoteCollectionItemsHolder(item));
        assertNotNull("the cache form cannot be made", views);
        assertTrue(views.isLegacyListRemoteViews());
        // The cached item is walked: its image is found.
        assertEquals(1, RemoteViewsImages.icons(views).size());
        final WidgetImages widget = widget();
        final RemoteViews first = prepare(widget, views);
        assertNotNull(first);
        assertEquals(1, RemoteViewsImages.icons(first).size());
        for (Icon icon : RemoteViewsImages.icons(first)) {
            assertEquals(Icon.TYPE_BITMAP, icon.getType());
        }
        // The update itself, its cached items included, is left as it is...
        for (Icon icon : RemoteViewsImages.icons(views)) {
            assertEquals(Icon.TYPE_DATA, icon.getType());
        }
        // ...so a check that starts again from it makes the same as the first.
        final RemoteViews second = prepare(widget, views);
        assertNotNull(second);
        final List<Icon> a = RemoteViewsImages.icons(first);
        final List<Icon> b = RemoteViewsImages.icons(second);
        assertEquals(a.size(), b.size());
        for (int i = 0; i < a.size(); i++) {
            assertEquals(a.get(i).getBitmap().getWidth(), b.get(i).getBitmap().getWidth());
        }
    }

    @Test
    public void listItemUris_onlyWithTheAppsAccess() throws Exception {
        // An item naming a URI of another user is never opened: an empty image.
        final RemoteViews item = new RemoteViews(mPackage, R.layout.vm_test_probe);
        item.setImageViewIcon(R.id.vm_probe,
                Icon.createWithContentUri("content://99@de.diamaneos.decode.client.tests.none/x.png"));
        final RemoteViews views = cacheForm(new RemoteCollectionItemsHolder(item));
        assertNotNull("the cache form cannot be made", views);
        final RemoteViews checked = prepare(widget(), views);
        assertNotNull(checked);
        final List<Icon> icons = RemoteViewsImages.icons(checked);
        assertEquals(1, icons.size());
        for (Icon icon : icons) {
            assertEquals(Icon.TYPE_BITMAP, icon.getType());
            assertEquals(1, icon.getBitmap().getWidth());
        }
    }

    @Test
    public void uriCheck_isTheAppsOwnAccess_notSystemUis() throws Exception {
        // The rule the widget check applies to every URI of an update, list items included: the app's own read
        // access, as its user. SystemUI's wider access is never lent.
        final NotificationImageGate gate = NotificationImageGate.forNotification(mContext, mPackage,
                mContext.getUserId(), 64, 512, 512, new ImageBudget());
        gate.checkUrisAs(android.os.Process.myUid(), mPackage, mContext.getUserId());
        final Field field = NotificationImageGate.class.getDeclaredField("mUriCheck");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        final java.util.function.Predicate<android.net.Uri> mayOpen =
                (java.util.function.Predicate<android.net.Uri>) field.get(gate);
        assertNotNull(mayOpen);
        // Its own provider, as its own user: yes.
        assertTrue(mayOpen.test(android.net.Uri.parse("content://" + ProbeProvider.AUTHORITY + "/a.png")));
        // The same provider as another user: no.
        assertFalse(mayOpen.test(android.net.Uri.parse("content://99@" + ProbeProvider.AUTHORITY + "/a.png")));
        // A provider guarded by a permission the app does not hold (contacts): no.
        assertFalse(mayOpen.test(android.net.Uri.parse("content://com.android.contacts/contacts/1/photo")));
    }

    @Test
    public void listItemUri_theWidgetAppMayNotRead_isNeverOpened() throws Exception {
        // The widget is the shell's, the item names this test APK's private provider: the shell may not read it,
        // though this process (standing in for SystemUI) could. It is never opened.
        ProbeProvider.OPENS.set(0);
        final RemoteViews item = new RemoteViews(mPackage, R.layout.vm_test_probe);
        item.setImageViewIcon(R.id.vm_probe,
                Icon.createWithContentUri("content://" + ProbeProvider.AUTHORITY + "/x.png"));
        final RemoteViews views = cacheForm(new RemoteCollectionItemsHolder(item));
        assertNotNull("the cache form cannot be made", views);
        assertNotNull(prepare(widget("com.android.shell"), views));
        assertEquals("a list item URI the widget app may not read was opened", 0, ProbeProvider.OPENS.get());
    }

    @Test
    public void waiters_cappedPerApp_andCancelledBySequence() {
        final Waiters waiters = new Waiters();
        final Runnable[] calls = new Runnable[Waiters.MAX_KEYS_PER_OWNER + 1];
        int evicted = 0;
        for (int i = 0; i < calls.length; i++) {
            calls[i] = () -> { };
            evicted += waiters.add("key" + i, "app.many", null, calls[i]).size();
        }
        // Past its cap the app gives up its oldest asker, handed back to be told later.
        assertEquals(1, evicted);
        assertEquals(Waiters.MAX_KEYS_PER_OWNER, waiters.size());
        // Another app is not pushed out by it.
        assertEquals(0, waiters.add("other", "app.few", "tag.few", () -> { }).size());
        assertTrue(waiters.has("other"));
        // A cancel drops the tag's askers, and the key nobody waits for any more is handed back.
        assertEquals(List.of("other"), waiters.cancel("tag.few"));
        final List<Waiters.Waiter> again = waiters.take("key5");
        assertEquals(1, again.size());
        assertTrue(waiters.live(again.get(0)));
    }

    @Test
    public void loaderTaskThatGivesWay_answersNull() throws Exception {
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch answered = new CountDownLatch(1);
        final AtomicReference<Drawable> result = new AtomicReference<>(new android.graphics.drawable.ColorDrawable());
        try {
            assertNotNull(ImageThreads.execute("app.give", () -> {
                try {
                    release.await(20, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                    // Ends the task.
                }
            }));
            Thread.sleep(200);
            for (int i = 0; i < ImageThreads.MAX_QUEUED - 1; i++) {
                assertNotNull(ImageThreads.execute("app.give", () -> { }));
            }
            // The newest task of the app with the most waiting is a loader's.
            ForeignIcons.loadAsync(mContext, pngIcon(5252), mContext.getUserId(), "app.give", 64, Runnable::run,
                    d -> {
                        result.set(d);
                        answered.countDown();
                    });
            // Another app's task takes its place: the loader is told, with null.
            assertNotNull(ImageThreads.execute("app.give.other", () -> { }));
            assertTrue(answered.await(5, TimeUnit.SECONDS));
            assertNull(result.get());
        } finally {
            release.countDown();
        }
    }
}
