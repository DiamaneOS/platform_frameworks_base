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
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.app.Notification;
import android.app.Person;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.SystemClock;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.atomic.AtomicReference;

/** Every Icon type through VmIcons and NotificationImageGate. Runs with or without the decode service. */
@RunWith(AndroidJUnit4.class)
public class VmIconsDeviceTest {
    private static final Uri URI = Uri.parse("content://de.diamaneos.decode.client.tests.none/image.png");
    private static final byte[] DATA = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 0, 0, 0, 0};

    private final Context mContext = InstrumentationRegistry.getInstrumentation().getTargetContext();

    private static Bitmap bitmap() {
        return Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888);
    }

    // A gate for one inflation, with the test app's context as the posting app's.
    private NotificationImageGate gate() {
        final NotificationImageGate gate =
                NotificationImageGate.forNotification(mContext, "pkg", mContext.getUserId(), 64, 64, 64);
        gate.packageContext(mContext);
        return gate;
    }

    @Test
    public void needsVm_everyIconType() {
        assertFalse(VmIcons.needsVm(null));
        assertFalse(VmIcons.needsVm(Icon.createWithBitmap(bitmap())));
        assertFalse(VmIcons.needsVm(Icon.createWithAdaptiveBitmap(bitmap())));
        assertFalse(VmIcons.needsVm(Icon.createWithResource("android", android.R.drawable.ic_dialog_info)));
        assertTrue(VmIcons.needsVm(Icon.createWithData(DATA, 0, DATA.length)));
        assertTrue(VmIcons.needsVm(Icon.createWithContentUri(URI)));
        assertTrue(VmIcons.needsVm(Icon.createWithAdaptiveBitmapContentUri(URI)));
    }

    @Test
    public void withoutVmIcon_everyIconType() {
        final Icon placeholder = Icon.createWithResource("android", android.R.drawable.ic_dialog_info);
        final Icon bitmap = Icon.createWithBitmap(bitmap());
        final Icon adaptive = Icon.createWithAdaptiveBitmap(bitmap());
        final Icon resource = Icon.createWithResource("android", android.R.drawable.ic_menu_add);
        assertSame(bitmap, VmIcons.withoutVmIcon(bitmap, placeholder));
        assertSame(adaptive, VmIcons.withoutVmIcon(adaptive, placeholder));
        assertSame(resource, VmIcons.withoutVmIcon(resource, placeholder));
        assertSame(placeholder, VmIcons.withoutVmIcon(Icon.createWithData(DATA, 0, DATA.length), placeholder));
        assertSame(placeholder, VmIcons.withoutVmIcon(Icon.createWithContentUri(URI), placeholder));
        assertSame(placeholder,
                VmIcons.withoutVmIcon(Icon.createWithAdaptiveBitmapContentUri(URI), placeholder));
    }

    @Test
    public void mainThread_neverDecodes() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            assertNull(VmIcons.decode(Icon.createWithData(DATA, 0, DATA.length), mContext, "", 64,
                    VmDecode.deadlineIn(1_000)));
            try {
                VmDecode.decodeBytes(mContext, DATA, 0, DATA.length, "", mContext.getUserId(),
                        VmDecode.PRIORITY_USER_WAITING, 64, 64);
                fail("decoded on the main thread");
            } catch (VmDecode.UnavailableException expected) {
                // Refused at once.
            } catch (VmDecode.RejectedException e) {
                fail("rejected instead of refused: " + e.getMessage());
            }
        });
    }

    private Notification notificationWithEveryVmIcon() {
        final Person sender = new Person.Builder().setName("A").setIcon(Icon.createWithContentUri(URI)).build();
        return new Notification.Builder(mContext, "test")
                .setSmallIcon(Icon.createWithContentUri(URI))
                .setLargeIcon(Icon.createWithData(DATA, 0, DATA.length))
                .setStyle(new Notification.MessagingStyle(new Person.Builder().setName("me").build())
                        .addMessage("hi", 1, sender))
                .build();
    }

    @Test
    public void gate_onTheMainThread_removesVmIcons() {
        final Notification original = notificationWithEveryVmIcon();
        final AtomicReference<Notification> copy = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> copy.set(
                gate().sanitizedCopy(original)));
        final Notification n = copy.get();
        assertNotNull(n);
        assertEquals(Icon.TYPE_RESOURCE, n.getSmallIcon().getType());
        assertNull(n.getLargeIcon());
        final Notification.MessagingStyle.Message message =
                Notification.MessagingStyle.Message.getMessagesFromBundleArray(
                        n.extras.getParcelableArray(Notification.EXTRA_MESSAGES)).get(0);
        assertNull(message.getSenderPerson().getIcon());
        // The original is unchanged.
        assertEquals(Icon.TYPE_DATA, original.getLargeIcon().getType());
        assertEquals(Icon.TYPE_URI, original.getSmallIcon().getType());
    }

    @Test
    public void gate_cleanNotification_isPassedThrough() {
        final Notification clean = new Notification.Builder(mContext, "test")
                .setSmallIcon(Icon.createWithResource("android", android.R.drawable.ic_dialog_info))
                .setLargeIcon(Icon.createWithBitmap(bitmap()))
                .build();
        assertSame(clean, gate().sanitizedCopy(clean));
    }

    @Test
    public void gate_onAWorker_withoutTheService_failsClosedWithinTheBudget() throws Exception {
        final Notification original = notificationWithEveryVmIcon();
        final AtomicReference<Notification> copy = new AtomicReference<>();
        final java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        final long start = SystemClock.uptimeMillis();
        // On an image thread, where the VM is asked.
        ImageThreads.execute("pkg", () -> {
            copy.set(gate().sanitizedCopy(original));
            done.countDown();
        });
        done.await(10, java.util.concurrent.TimeUnit.SECONDS);
        final long took = SystemClock.uptimeMillis() - start;
        assertNotNull(copy.get());
        // No decode service holds an image for this URI or these bytes: every VM icon is gone, nothing hangs.
        assertNull(copy.get().getLargeIcon());
        assertEquals(Icon.TYPE_RESOURCE, copy.get().getSmallIcon().getType());
        assertTrue("took " + took + " ms", took < NotificationImageGate.BUDGET_MS + 2_000);
    }
}
