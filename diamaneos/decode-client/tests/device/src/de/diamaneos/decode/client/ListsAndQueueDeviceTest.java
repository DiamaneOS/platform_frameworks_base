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
        final AtomicReference<WidgetImages> made = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> made.set(new WidgetImages(mContext)));
        made.get().setApp(mPackage, mContext.getUserId());
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
            final Method addMapping = cache.getClass().getDeclaredMethod("addMapping", int.class, String.class,
                    RemoteViews.RemoteCollectionItems.class);
            addMapping.setAccessible(true);
            addMapping.invoke(cache, 7, "intent:test#7", holder.items);
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
        assumeTrue("the cache form cannot be made here", views != null && views.isLegacyListRemoteViews());
        final WidgetImages widget = widget();
        final RemoteViews first = prepare(widget, views);
        assertNotNull(first);
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
        assumeTrue("the cache form cannot be made here", views != null);
        final RemoteViews checked = prepare(widget(), views);
        assertNotNull(checked);
        for (Icon icon : RemoteViewsImages.icons(checked)) {
            assertEquals(Icon.TYPE_BITMAP, icon.getType());
            assertEquals(1, icon.getBitmap().getWidth());
        }
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
