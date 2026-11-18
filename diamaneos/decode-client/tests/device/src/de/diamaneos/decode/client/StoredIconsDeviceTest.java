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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.graphics.Bitmap;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Process;
import android.service.notification.StatusBarNotification;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import de.diamaneos.decode.client.tests.R;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * What the notification history and the archive hand to system readers (Settings): pixels, framework resources and
 * the app's launcher icon only, that launcher icon in place of anything else. Nothing is decoded.
 */
@RunWith(AndroidJUnit4.class)
public class StoredIconsDeviceTest {
    private final Context mContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private final String mPackage = mContext.getPackageName();

    private ApplicationInfo appInfo() {
        final ApplicationInfo ai = new ApplicationInfo(mContext.getApplicationInfo());
        ai.icon = R.drawable.vm_test_vector;
        return ai;
    }

    @Test
    public void dataAndUriIcons_becomeTheAppsIcon() {
        final byte[] data = {1, 2, 3};
        for (Icon icon : new Icon[] {Icon.createWithData(data, 0, data.length),
                Icon.createWithContentUri(Uri.parse("content://x/y")),
                Icon.createWithAdaptiveBitmapContentUri("content://x/z")}) {
            final Icon stored = StoredIcons.forSystemReaders(icon, mPackage, appInfo());
            assertEquals(Icon.TYPE_RESOURCE, stored.getType());
            assertEquals(mPackage, stored.getResPackage());
            assertEquals(R.drawable.vm_test_vector, stored.getResId());
            // Without the app's info, or with another app's: the generic app icon.
            assertEquals("android", StoredIcons.forSystemReaders(icon, mPackage, null).getResPackage());
            assertEquals("android", StoredIcons.forSystemReaders(icon, "other.app", appInfo()).getResPackage());
        }
        // Another resource of the app becomes its launcher icon too, kept without the app's info (history files).
        final Icon resource = Icon.createWithResource(mPackage, R.drawable.vm_test_red);
        assertEquals(R.drawable.vm_test_vector,
                StoredIcons.forSystemReaders(resource, mPackage, appInfo()).getResId());
        assertSame(resource, StoredIcons.forSystemReaders(resource, mPackage, null));
        // The launcher icon, framework resources and pixels are kept as they are.
        final Icon launcher = Icon.createWithResource(mPackage, R.drawable.vm_test_vector);
        assertSame(launcher, StoredIcons.forSystemReaders(launcher, mPackage, appInfo()));
        final Icon framework = Icon.createWithResource("android", android.R.drawable.ic_dialog_info);
        assertSame(framework, StoredIcons.forSystemReaders(framework, mPackage, appInfo()));
        final Icon pixels = Icon.createWithBitmap(Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888));
        assertSame(pixels, StoredIcons.forSystemReaders(pixels, mPackage, appInfo()));
    }

    @Test
    public void notifications_forSystemReaders() {
        final byte[] data = {1, 2, 3};
        final PendingIntent intent = PendingIntent.getActivity(mContext, 0,
                new Intent("x").setPackage(mPackage), PendingIntent.FLAG_IMMUTABLE);
        final Notification n = new Notification.Builder(mContext, "test")
                .setSmallIcon(Icon.createWithData(data, 0, data.length))
                .setLargeIcon(Icon.createWithData(data, 0, data.length))
                .addAction(new Notification.Action.Builder(Icon.createWithData(data, 0, data.length), "a",
                        intent).build())
                .build();
        n.extras.putParcelable(Notification.EXTRA_BUILDER_APPLICATION_INFO, appInfo());
        final StatusBarNotification sbn = new StatusBarNotification(mPackage, mPackage, 1, null,
                Process.myUid(), 0, n, Process.myUserHandle(), null, 0);
        final StatusBarNotification stored = StoredIcons.forSystemReaders(sbn);
        final Notification copy = stored.getNotification();
        assertEquals(Icon.TYPE_RESOURCE, copy.getSmallIcon().getType());
        assertEquals(R.drawable.vm_test_vector, copy.getSmallIcon().getResId());
        assertNull(copy.getLargeIcon());
        assertNull(copy.actions[0].getIcon());
        // The original is not changed.
        assertEquals(Icon.TYPE_DATA, n.getSmallIcon().getType());
        assertEquals(Icon.TYPE_DATA, n.actions[0].getIcon().getType());
        // A notification with nothing to replace is handed on as it is.
        final Notification plain = new Notification.Builder(mContext, "test")
                .setSmallIcon(Icon.createWithResource(mPackage, R.drawable.vm_test_vector))
                .build();
        plain.extras.putParcelable(Notification.EXTRA_BUILDER_APPLICATION_INFO, appInfo());
        final StatusBarNotification plainSbn = new StatusBarNotification(mPackage, mPackage, 2, null,
                Process.myUid(), 0, plain, Process.myUserHandle(), null, 0);
        assertSame(plainSbn, StoredIcons.forSystemReaders(plainSbn));
        assertTrue(StoredIcons.needsNoAppBytes(null));
    }
}
