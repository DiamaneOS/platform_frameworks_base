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
import static org.junit.Assume.assumeTrue;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.drawable.Icon;
import android.os.SystemClock;
import android.service.notification.StatusBarNotification;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import de.diamaneos.decode.client.tests.R;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;

/**
 * A notification posted by this app (an app uid, through its own binder call) gets its packed images re-made in the
 * system server at post time: a resource PNG small icon and a data large icon come back as real bitmaps, not the
 * neutral icon. Catches a decode client that binds with the posting app's identity (refused by the activity manager,
 * so every image fell back to the neutral icon). Needs the decode service: skipped on a build without it.
 */
@RunWith(AndroidJUnit4.class)
public class PostTimeDeviceTest {
    private static final String CHANNEL = "vm-post-time";
    private static final int ID = 4711;

    private final Context mContext = InstrumentationRegistry.getInstrumentation().getTargetContext();

    private boolean decodeServicePresent() {
        return mContext.getPackageManager().resolveService(
                new Intent(DecodeClient.SERVICE_ACTION).setPackage(DecodeClient.SERVICE_PACKAGE),
                PackageManager.MATCH_SYSTEM_ONLY) != null;
    }

    private static Icon pngIcon() {
        final Bitmap bitmap = Bitmap.createBitmap(9, 5, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(0xff336699);
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        final byte[] png = out.toByteArray();
        return Icon.createWithData(png, 0, png.length);
    }

    @Test
    public void appPost_reachesRealBitmaps() throws Exception {
        assumeTrue("no decode service in this build", decodeServicePresent());
        InstrumentationRegistry.getInstrumentation().getUiAutomation().grantRuntimePermission(
                mContext.getPackageName(), Manifest.permission.POST_NOTIFICATIONS);
        final NotificationManager nm = mContext.getSystemService(NotificationManager.class);
        nm.createNotificationChannel(
                new NotificationChannel(CHANNEL, "VM post time", NotificationManager.IMPORTANCE_LOW));
        nm.notify(ID, new Notification.Builder(mContext, CHANNEL)
                .setSmallIcon(Icon.createWithResource(mContext.getPackageName(), R.drawable.vm_test_red))
                .setLargeIcon(pngIcon())
                .setContentTitle("post time")
                .build());
        try {
            Notification posted = null;
            final long end = SystemClock.uptimeMillis() + 10_000;
            while (posted == null && SystemClock.uptimeMillis() < end) {
                for (StatusBarNotification sbn : nm.getActiveNotifications()) {
                    if (sbn.getId() == ID) {
                        posted = sbn.getNotification();
                    }
                }
                if (posted == null) {
                    Thread.sleep(100);
                }
            }
            assertNotNull("not posted", posted);
            // The PNG of this app's APK, re-made from VM pixels: a bitmap of its size, not the neutral icon.
            final Icon small = posted.getSmallIcon();
            assertEquals("small icon " + small, Icon.TYPE_BITMAP, small.getType());
            assertEquals(8, small.getBitmap().getWidth());
            final Icon large = posted.getLargeIcon();
            assertNotNull("large icon removed", large);
            assertEquals(Icon.TYPE_BITMAP, large.getType());
            assertEquals(9, large.getBitmap().getWidth());
        } finally {
            nm.cancel(ID);
        }
    }
}
