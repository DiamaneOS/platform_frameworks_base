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

package com.android.systemui.media.dialog

import android.app.Notification
import android.media.session.MediaSession
import android.os.UserHandle
import com.android.systemui.statusbar.notification.collection.NotificationEntry

/**
 * DiamaneOS: the media notification an output dialog is for. The app in the dialog's user, as the same app can play
 * in a work profile and in its parent at once, and the dialog's media session when it names one.
 */
internal object MediaDialogNotifications {
    fun matches(
        entry: NotificationEntry,
        packageName: String?,
        user: UserHandle,
        token: MediaSession.Token?,
    ): Boolean {
        val sbn = entry.sbn
        return sbn.notification.isMediaNotification &&
            sbn.packageName == packageName &&
            sbn.normalizedUserId == user.identifier &&
            (token == null || sessionOf(sbn.notification).let { it == null || it == token })
    }

    private fun sessionOf(notification: Notification): MediaSession.Token? =
        notification.extras?.getParcelable(
            Notification.EXTRA_MEDIA_SESSION,
            MediaSession.Token::class.java,
        )
}
