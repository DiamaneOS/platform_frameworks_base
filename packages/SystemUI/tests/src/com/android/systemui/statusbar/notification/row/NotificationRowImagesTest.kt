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

package com.android.systemui.statusbar.notification.row

import android.app.Notification
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.statusbar.notification.collection.NotificationEntryBuilder
import com.android.systemui.util.mockito.mock
import java.lang.ref.WeakReference
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** DiamaneOS: the checked copies of notifications go with the notifications. */
@SmallTest
@RunWith(AndroidJUnit4::class)
class NotificationRowImagesTest : SysuiTestCase() {
    @Test
    fun aNotificationShownAsItIs_goesOnceDismissed() {
        val dismissed = (1..20).map { showAndDismiss(it) }

        for (i in 0 until 50) {
            if (dismissed.all { it.get() == null }) {
                break
            }
            Runtime.getRuntime().gc()
            System.runFinalization()
            Thread.sleep(20)
        }
        // One may still be referenced for a while by the last image work: what may not happen is that they add up.
        val held = dismissed.count { it.get() != null }
        assertTrue("dismissed notifications still held: $held of 20", held <= 1)
    }

    // Prepares the row images of a notification with nothing to check (its own pixels only) in a row of its own,
    // then lets both go, as a dismissed notification's row goes.
    private fun showAndDismiss(id: Int): WeakReference<Notification> {
        val notification =
            Notification.Builder(context, "test")
                .setSmallIcon(Icon.createWithBitmap(Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)))
                .setContentText("message $id")
                .build()
        val entry =
            NotificationEntryBuilder()
                .setNotification(notification)
                .setPkg(context.packageName)
                .setUser(context.user)
                .setId(id)
                .build()
        NotificationRowImages(context, entry, mock<ExpandableNotificationRow>()).prepare()
        assertSame(notification, NotificationRowImages.sourceOf(notification, context))
        return WeakReference(notification)
    }
    @Test
    fun mainRowSuccess_keepsInlineRecoveryDemand() {
        val cache = NotificationInlineImageCache()
        val resolver = NotificationInlineImageResolver(context, cache)
        val row = mock<ExpandableNotificationRow>()
        org.mockito.Mockito.`when`(row.imageResolver).thenReturn(resolver)
        val notification = Notification.Builder(context, "test").build()
        val entry = NotificationEntryBuilder().setNotification(notification)
            .setPkg(context.packageName).setUser(context.user).build()
        val images = NotificationRowImages(context, entry, row)
        images.prepare()
        resolver.outcomes().ended(android.net.Uri.parse("content://images/missing"), false, true)
        images.prepare()
        val rebind = NotificationRowImages.Companion.javaClass.getDeclaredMethod("rebindLeftOut")
        rebind.isAccessible = true
        rebind.invoke(NotificationRowImages.Companion)
        org.mockito.Mockito.verify(row).rebindForMissingImages()
    }

}
