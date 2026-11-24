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

package com.android.systemui.statusbar.notification.row;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;

import android.app.Notification;
import android.app.Person;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** DiamaneOS: message images that could not be made for now load again for the same row. */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class NotificationInlineImageCacheTest extends SysuiTestCase {
    private final Uri mUri = Uri.parse("content://com.example.images/message/1");
    private NotificationInlineImageCache mCache;
    private NotificationInlineImageResolver mResolver;
    private Notification mNotification;

    @Before
    public void setUp() {
        mCache = new NotificationInlineImageCache();
        mResolver = spy(new NotificationInlineImageResolver(mContext, mCache));
        mCache.setImageResolver(mResolver);
        final Person sender = new Person.Builder().setName("sender").build();
        mNotification = new Notification.Builder(mContext, "test")
                .setStyle(new Notification.MessagingStyle(sender)
                        .addMessage(new Notification.MessagingStyle.Message("image", 0, sender)
                                .setData("image/png", mUri)))
                .build();
    }

    @Test
    public void anImageNotMadeForNow_isLoadedByTheNextBind() {
        // One stub for both loads: a spy is not stubbed again while the main thread calls it.
        final AtomicReference<Drawable> answer = new AtomicReference<>();
        doAnswer(invocation -> answer.get()).when(mResolver).resolveImage(eq(mUri), any(Runnable.class));
        mResolver.preloadImages(mNotification);
        assertNull(mCache.get(mUri, 5_000));

        final Drawable made = new ColorDrawable(0xff00ff00);
        answer.set(made);
        mResolver.preloadImages(mNotification);

        assertSame(made, mCache.get(mUri, 5_000));
    }

    @Test
    public void aCancelledLoad_isLoadedByTheNextBind() throws Exception {
        final Drawable made = new ColorDrawable(0xff00ff00);
        final CountDownLatch started = new CountDownLatch(1);
        final AtomicInteger loads = new AtomicInteger();
        doAnswer(invocation -> {
            if (loads.incrementAndGet() > 1) {
                return made;
            }
            started.countDown();
            try {
                Thread.sleep(10_000);
            } catch (InterruptedException e) {
                // Cancelled.
            }
            return null;
        }).when(mResolver).resolveImage(eq(mUri), any(Runnable.class));
        mResolver.preloadImages(mNotification);
        assertTrue(started.await(5, TimeUnit.SECONDS));
        mResolver.cancelRunningTasks();

        mResolver.preloadImages(mNotification);

        assertSame(made, mCache.get(mUri, 5_000));
    }

    @Test
    public void anImageTheVmRefused_isNotLoadedAgain() {
        final AtomicInteger loads = new AtomicInteger();
        mResolver.outcomes().refused(mUri);
        doAnswer(invocation -> {
            loads.incrementAndGet();
            return null;
        }).when(mResolver).resolveImage(eq(mUri), any(Runnable.class));
        mResolver.preloadImages(mNotification);
        assertNull(mCache.get(mUri, 5_000));

        mResolver.preloadImages(mNotification);
        mCache.get(mUri, 5_000);

        assertEquals(1, loads.get());
    }

    @Test
    public void severalFailuresOfOneUri_keepOneRetryDemand() throws Exception {
        final CountDownLatch asked = new CountDownLatch(1);
        final AtomicInteger times = new AtomicInteger();
        mResolver.outcomes().setRetry(() -> {
            times.incrementAndGet();
            asked.countDown();
        });
        doReturn(null).when(mResolver).resolveImage(eq(mUri), any(Runnable.class));
        for (int i = 0; i < 5; i++) {
            mResolver.preloadImages(mNotification);
            mCache.get(mUri, 5_000);
        }
        assertTrue(asked.await(5, TimeUnit.SECONDS));
        assertEquals(1, times.get());
    }

    @Test
    public void aCancelledLoad_registersAutonomousRetryDemand() throws Exception {
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch retry = new CountDownLatch(1);
        mResolver.outcomes().setRetry(retry::countDown);
        doAnswer(invocation -> {
            started.countDown();
            try { Thread.sleep(10_000); } catch (InterruptedException ignored) { }
            return null;
        }).when(mResolver).resolveImage(eq(mUri), any(Runnable.class));
        mResolver.preloadImages(mNotification);
        assertTrue(started.await(5, TimeUnit.SECONDS));
        mResolver.cancelRunningTasks();
        assertTrue("the row must retry without a new bind", retry.await(5, TimeUnit.SECONDS));
    }


    @Test
    public void retiredWorkerRefusal_doesNotRefuseReplacement() throws Exception {
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch refuse = new CountDownLatch(1);
        final CountDownLatch finished = new CountDownLatch(1);
        final AtomicInteger loads = new AtomicInteger();
        final Drawable made = new ColorDrawable(0xff00ff00);
        doAnswer(invocation -> {
            if (loads.incrementAndGet() > 1) return made;
            started.countDown();
            try {
                refuse.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                // An admitted VM operation can still deliver its result on cancellation.
            }
            invocation.getArgument(1, Runnable.class).run();
            finished.countDown();
            return null;
        }).when(mResolver).resolveImage(eq(mUri), any(Runnable.class));

        mResolver.preloadImages(mNotification);
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS));
            mCache.preload(mUri);
        } finally {
            refuse.countDown();
        }
        assertTrue(finished.await(5, TimeUnit.SECONDS));
        assertSame(made, mCache.get(mUri, 5_000));
        assertFalse(mResolver.outcomes().wasRefused(mUri));
    }
}
