/* Copyright (C) 2026 DiamaneOS */
package de.diamaneos.decode.client;

import static org.junit.Assert.*;

import android.content.Context;
import android.widget.RemoteViews;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public class WidgetImagesGenerationTest {
    private Context context() {
        return InstrumentationRegistry.getInstrumentation().getTargetContext();
    }

    @Test public void queuedNullResultCannotReplaceNewerOrCancelledUpdate() {
        WidgetImages widget = new WidgetImages(context());
        List<Runnable> queued = new ArrayList<>();
        List<String> delivered = new ArrayList<>();
        widget.prepare(null, queued::add, views -> delivered.add("old"));
        widget.prepare(null, queued::add, views -> delivered.add("new"));
        queued.get(1).run();
        queued.get(0).run();
        assertEquals(List.of("new"), delivered);
        queued.clear();
        widget.prepare(null, queued::add, views -> delivered.add("cancelled"));
        widget.cancel();
        queued.get(0).run();
        assertEquals(List.of("new"), delivered);
    }

    @Test public void claimedRecoveryCannotCancelNewerUpdate() throws Exception {
        WidgetImages widget = new WidgetImages(context());
        Field field = WidgetImages.class.getDeclaredField("mCancelled");
        field.setAccessible(true);
        widget.prepare(null, Runnable::run, views -> {});
        AtomicBoolean old = (AtomicBoolean) field.get(widget);
        widget.prepare(null, Runnable::run, views -> {});
        Object current = field.get(widget);
        Method retry = WidgetImages.class.getDeclaredMethod("prepareChecked", RemoteViews.class,
                java.util.concurrent.Executor.class, Consumer.class, AtomicBoolean.class);
        retry.setAccessible(true);
        retry.invoke(widget, null, (java.util.concurrent.Executor) Runnable::run,
                (Consumer<RemoteViews>) views -> fail("stale retry delivered"), old);
        assertSame(current, field.get(widget));
        assertFalse(((AtomicBoolean) current).get());
    }

    @Test public void immediateRefusalDeliversError() throws Exception {
        CountDownLatch running = new CountDownLatch(3);
        CountDownLatch release = new CountDownLatch(1);
        List<ImageThreads.Task> queued = new ArrayList<>();
        WidgetImages widget = new WidgetImages(context());
        try {
            for (int i = 0; i < 3; i++) {
                queued.add(ImageThreads.execute("widget-block-" + i, () -> {
                    running.countDown();
                    try { release.await(10, TimeUnit.SECONDS); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                }));
            }
            assertTrue(running.await(5, TimeUnit.SECONDS));
            String pkg = context().getPackageName();
            for (int i = 0; i < ImageThreads.MAX_QUEUED; i++) {
                ImageThreads.Task task = ImageThreads.execute(pkg, () -> {});
                assertNotNull(task);
                queued.add(task);
            }
            widget.setApp(pkg, context().getUserId());
            CountDownLatch delivered = new CountDownLatch(1);
            widget.prepare(new RemoteViews(pkg, 0), Runnable::run, views -> {
                assertNull(views);
                delivered.countDown();
            });
            assertTrue(delivered.await(1, TimeUnit.SECONDS));
        } finally {
            widget.cancel();
            for (ImageThreads.Task task : queued) { if (task != null) task.cancel(); }
            release.countDown();
        }
    }
}
