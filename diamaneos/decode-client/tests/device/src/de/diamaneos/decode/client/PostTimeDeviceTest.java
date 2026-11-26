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

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
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
 * The system server decodes nothing of a notification and reads no package's resources for it: a notification this
 * app posts (an app uid, its own binder call) comes back with its icons exactly as it gave them. SystemUI makes them
 * safe where it shows them (AppIcons, the checked copy of each row).
 */
@RunWith(AndroidJUnit4.class)
public class PostTimeDeviceTest {
    private static final String CHANNEL = "vm-post-time";
    private static final int ID = 4711;

    private final Context mContext = InstrumentationRegistry.getInstrumentation().getTargetContext();

    private static Icon pngIcon() {
        final Bitmap bitmap = Bitmap.createBitmap(9, 5, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(0xff336699);
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        final byte[] png = out.toByteArray();
        return Icon.createWithData(png, 0, png.length);
    }

    @Test
    public void appPost_iconsLeftAsTheyWere() throws Exception {
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
            final Icon small = posted.getSmallIcon();
            assertEquals("small icon " + small, Icon.TYPE_RESOURCE, small.getType());
            assertEquals(mContext.getPackageName(), small.getResPackage());
            assertEquals(R.drawable.vm_test_red, small.getResId());
            final Icon large = posted.getLargeIcon();
            assertNotNull("large icon removed", large);
            assertEquals(Icon.TYPE_DATA, large.getType());
        } finally {
            nm.cancel(ID);
        }
    }
}
