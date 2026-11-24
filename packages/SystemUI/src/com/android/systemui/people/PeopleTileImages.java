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

package com.android.systemui.people;

import android.content.ContentProvider;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.service.notification.StatusBarNotification;
import android.util.TypedValue;

import com.android.systemui.people.widget.PeopleSpaceWidgetManager;

import java.io.IOException;

import de.diamaneos.decode.client.ForeignIcons;

/**
 * DiamaneOS: the image a conversation's app named for a people tile is decoded in the protected VM on an image thread,
 * charged to that app, never in this process, at most as big as the tile shows it. The calling thread does not wait
 * for it: until it is made the tile says that there is a new image, as when an image cannot load, and then the tiles
 * are drawn again. The image is opened as the user of the notification that named it, never as the tile's host.
 */
final class PeopleTileImages {
    private PeopleTileImages() {
    }

    /**
     * {@code uri}, an image of {@code sbn}, with the notification's user: a URI without a user of its own would else
     * be opened as the user of whatever renders the tile.
     */
    static Uri ofNotificationUser(Uri uri, StatusBarNotification sbn) {
        return uri == null ? null : ContentProvider.maybeAddUserId(uri, sbn.getNormalizedUserId());
    }

    /**
     * The image at {@code uri} for a tile of {@code widthDp} x {@code heightDp} of {@code tileUserId}, or IOException
     * while not made. A URI without a user is opened as the tile's user.
     */
    static Drawable resolve(Context context, Uri uri, String packageName, int tileUserId, int widthDp,
            int heightDp) throws IOException {
        final int widthInPx = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, widthDp,
                context.getResources().getDisplayMetrics());
        final int heightInPx = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, heightDp,
                context.getResources().getDisplayMetrics());
        // As upstream's sample size: the tile's long side, at most 1.5 times its short side.
        final int maxPx = Math.max(1, Math.min(Math.max(widthInPx, heightInPx),
                (int) (1.5 * Math.min(widthInPx, heightInPx))));
        final Drawable drawable = ForeignIcons.loadNoWait(context, Icon.createWithContentUri(uri),
                ContentProvider.getUserIdFromUri(uri, tileUserId), packageName, maxPx,
                PeopleSpaceWidgetManager::onTileImageMade);
        if (drawable == null) {
            throw new IOException("image not decoded");
        }
        return drawable;
    }
}
