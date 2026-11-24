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

import android.app.Notification;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.DrawableWrapper;
import android.graphics.drawable.Icon;
import android.os.Process;
import android.os.SystemClock;
import android.os.UserHandle;
import android.service.notification.StatusBarNotification;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.internal.diamaneos.cleancopies.VmIconLoads;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The icons of the Settings notification log, loaded as the log loads them: VmIconLoads.loadAsUserNoWait with the
 * notification's package context and user, on the main thread. A work notification's icon is the work profile's
 * input (the work test needs {@code -e workUser N}, a work profile with this package), and an icon shown blank by a
 * load that ran out of time fills in once its image is made.
 */
@RunWith(AndroidJUnit4.class)
public class NotificationLogIconsDeviceTest {
    private final Context mContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private Object mPreviousGateApp;
    private boolean mChangedGateApp;

    @Before
    public void gated() {
        VmIconLoads.resetForTest();
        VmIconLoads.setForcedForTest(true);
    }

    @After
    public void asBefore() throws Exception {
        if (mChangedGateApp) {
            final java.lang.reflect.Field app = com.android.internal.diamaneos.cleancopies.CleanCopyGate.class
                    .getDeclaredField("sApp");
            app.setAccessible(true);
            app.set(null, mPreviousGateApp);
        }
        android.content.res.ImageFileGates.setProcessDefault(null);
        com.android.internal.diamaneos.cleancopies.CleanCopyGate.setModeForTest(-1);
        VmIconLoads.setForcedForTest(null);
        VmIconLoads.resetForTest();
    }

    private static int workUser() {
        final String user = InstrumentationRegistry.getArguments().getString("workUser");
        assumeTrue("no work profile named (-e workUser N)", user != null);
        return Integer.parseInt(user);
    }

    private StatusBarNotification postedBy(int user) {
        final Notification notification = new Notification.Builder(mContext, "log").build();
        final int uid = UserHandle.getUid(user, UserHandle.getAppId(Process.myUid()));
        return new StatusBarNotification(mContext.getPackageName(), mContext.getPackageName(), 1, null, uid, 0,
                notification, UserHandle.of(user), null, System.currentTimeMillis());
    }

    // The log's own call (NotificationStation.loadIcon), on the main thread.
    private Drawable loadAsTheLog(Icon icon, StatusBarNotification sbn) {
        final AtomicReference<Drawable> drawn = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> drawn.set(
                VmIconLoads.loadAsUserNoWait(icon, sbn.getPackageContext(mContext), sbn.getNormalizedUserId())));
        return drawn.get();
    }

    // The pixels the drawable shows within waitMs, or null while it stays blank.
    private static Bitmap shown(Drawable drawable, long waitMs) {
        final long end = SystemClock.uptimeMillis() + waitMs;
        while (true) {
            Drawable inner = drawable;
            if (inner instanceof DrawableWrapper) {
                inner = ((DrawableWrapper) inner).getDrawable();
            }
            if (inner instanceof BitmapDrawable && ((BitmapDrawable) inner).getBitmap().getWidth() > 1) {
                return ((BitmapDrawable) inner).getBitmap();
            }
            if (SystemClock.uptimeMillis() >= end) {
                return null;
            }
            SystemClock.sleep(100);
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        }
    }

    private static byte[] png(int color) {
        final Bitmap bitmap = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(color);
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out));
        return out.toByteArray();
    }

    /** H7: the same bytes posted in the parent and in the work profile are two inputs, the work one its own. */
    @Test
    public void aWorkNotificationsDataIcon_isNotTheParentsImage() {
        final int work = workUser();
        final byte[] png = png(0xff123456);
        final Icon icon = Icon.createWithData(png, 0, png.length);
        assertNotNull("the parent's icon was not made", shown(loadAsTheLog(icon, postedBy(0)), 5_000));
        final int made = VmIconLoads.madeForTest();

        final Drawable workIcon = loadAsTheLog(icon, postedBy(work));

        assertNotNull("the work icon was not made", shown(workIcon, 5_000));
        assertEquals("the work icon took the parent's pixels", made + 1, VmIconLoads.madeForTest());
    }

    /** M4: an icon whose load ran out of time stays the same drawable on the screen and fills in later. */
    @Test
    public void anIconShownBlankAfterItsLoadRanOutOfTime_fillsIn() {
        final int color = 0xff2468ac;
        final Icon slow = Icon.createWithContentUri(
                UserImageProvider.image(color, VmIconLoads.LOAD_MS + 1_000));
        final Drawable drawn = loadAsTheLog(slow, postedBy(mContext.getUserId()));
        assertNotNull(drawn);
        SystemClock.sleep(VmIconLoads.LOAD_MS + 200);

        final Bitmap filled = shown(drawn, 15_000);

        assertNotNull("the shown icon stayed blank", filled);
        assertEquals(color, filled.getPixel(filled.getWidth() / 2, filled.getHeight() / 2));
    }

    @Test
    public void aMissingWorkPackageContext_keepsTheNotificationsUser() {
        final int work = workUser();
        final byte[] bytes = png(0xff3579bd);
        final Icon icon = Icon.createWithData(bytes, 0, bytes.length);
        assertNotNull(shown(loadAsTheLog(icon, postedBy(0)), 15_000));
        final int made = VmIconLoads.madeForTest();
        final Notification notification = new Notification.Builder(mContext, "log").build();
        final StatusBarNotification removed = new StatusBarNotification("de.diamaneos.removed", null,
                2, null, UserHandle.getUid(work, UserHandle.getAppId(Process.myUid())), 0,
                notification, UserHandle.of(work), null, System.currentTimeMillis());
        assertEquals(mContext.getUserId(), removed.getPackageContext(mContext).getUserId());
        assertNotNull(shown(loadAsTheLog(icon, removed), 15_000));
        assertEquals("the explicit work user must survive the package fallback", made + 1,
                VmIconLoads.madeForTest());
    }

    @Test
    public void aWorkResourceIcon_fetchesTheSameUserItWatches() throws Exception {
        workResource(false);
    }

    @Test
    public void aWorkResourceIcon_refillsForTheSameUserItWatches() throws Exception {
        workResource(true);
    }

    private void workResource(boolean refill) throws Exception {
        final int work = workUser();
        final java.lang.reflect.Field app = com.android.internal.diamaneos.cleancopies.CleanCopyGate.class
                .getDeclaredField("sApp");
        app.setAccessible(true);
        mPreviousGateApp = app.get(null);
        mChangedGateApp = true;
        app.set(null, mContext);
        final java.util.concurrent.atomic.AtomicBoolean ready = new java.util.concurrent.atomic.AtomicBoolean(!refill);
        final java.util.List<Integer> requested = new java.util.concurrent.CopyOnWriteArrayList<>();
        final com.android.internal.diamaneos.cleancopies.CleanCopyGate.Source source =
                new com.android.internal.diamaneos.cleancopies.CleanCopyGate.Source() {
            @Override public com.android.internal.diamaneos.cleancopies.CleanImageFile read(String key) {
                boolean workKey = key.endsWith(":u" + work);
                if (workKey && !ready.get()) return null;
                Bitmap bitmap = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888);
                bitmap.eraseColor(workKey ? 0xff0000ff : 0xffff0000);
                java.nio.ByteBuffer bytes = java.nio.ByteBuffer.allocate(24 * 24 * 4);
                bitmap.copyPixelsToBuffer(bytes);
                return new com.android.internal.diamaneos.cleancopies.CleanImageFile(key, "", 24, 24, 0,
                        com.android.internal.diamaneos.cleancopies.CleanImageFile.COLOR_SRGB, null, null, bytes.array());
            }
            @Override public com.android.internal.diamaneos.cleancopies.CleanImageFile fallback(String apk,
                    String file, int density, int width, int height, String resource, int user) { return null; }
            @Override public void request(String key, String apk, String file, int density, int width,
                    int height, String resource, int user) { requested.add(user); }
        };
        final android.content.res.ImageFileGate gate =
                fixtureGate(source);
        android.content.res.ImageFileGates.setProcessDefault(gate);
        com.android.internal.diamaneos.cleancopies.CleanCopyGate.setModeForTest(
                com.android.internal.diamaneos.cleancopies.CleanCopyGate.MODE_ALL);
        final Icon resource = Icon.createWithResource(mContext,
                de.diamaneos.decode.client.tests.R.drawable.vm_test_red);
        final Drawable drawn = loadAsTheLog(resource, postedBy(work));
        if (refill) {
            assertTrue("the initial fetch must request the work copy", requested.contains(work));
            assertTrue("the initial fetch must not request the parent", !requested.contains(0));
            ready.set(true);
            com.android.internal.diamaneos.cleancopies.CleanCopyGate.madeForTest(mContext.getPackageName(), work);
        }
        final Bitmap bitmap = shown(drawn, 5_000);
        assertNotNull(bitmap);
        assertEquals("the log fetched the personal copy", 0xff0000ff, bitmap.getPixel(12, 12));
    }

    private android.content.res.ImageFileGate fixtureGate(
            com.android.internal.diamaneos.cleancopies.CleanCopyGate.Source source) throws Exception {
        final Class<?> gate = com.android.internal.diamaneos.cleancopies.CleanCopyGate.class;
        final Class<?> sourceType = com.android.internal.diamaneos.cleancopies.CleanCopyGate.Source.class;
        try {
            return (android.content.res.ImageFileGate) gate.getMethod("forTest", Context.class, sourceType,
                    boolean.class).invoke(null, mContext, source, false);
        } catch (NoSuchMethodException olderHelper) {
            return (android.content.res.ImageFileGate) gate.getMethod("forTest", Context.class, sourceType,
                    boolean.class, boolean.class).invoke(null, mContext, source, false, false);
        }
    }

}
