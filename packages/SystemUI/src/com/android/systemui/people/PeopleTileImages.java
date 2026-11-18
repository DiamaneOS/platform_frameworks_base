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

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.util.TypedValue;

import com.android.systemui.people.widget.PeopleSpaceWidgetManager;

import java.io.IOException;

import de.diamaneos.decode.client.ForeignIcons;

/**
 * DiamaneOS: the image a conversation's app named for a people tile is decoded in the protected VM on an image thread,
 * charged to that app, never in this process, at most as big as the tile shows it. The calling thread does not wait
 * for it: until it is made the tile says that there is a new image, as when an image cannot load, and then the tiles
 * are drawn again.
 */
final class PeopleTileImages {
    private PeopleTileImages() {
    }

    /** The image at {@code uri} for a tile of {@code widthDp} x {@code heightDp}, or IOException while not made. */
    static Drawable resolve(Context context, Uri uri, String packageName, int widthDp, int heightDp)
            throws IOException {
        final int widthInPx = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, widthDp,
                context.getResources().getDisplayMetrics());
        final int heightInPx = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, heightDp,
                context.getResources().getDisplayMetrics());
        // As upstream's sample size: the tile's long side, at most 1.5 times its short side.
        final int maxPx = Math.max(1, Math.min(Math.max(widthInPx, heightInPx),
                (int) (1.5 * Math.min(widthInPx, heightInPx))));
        final Drawable drawable = ForeignIcons.loadNoWait(context, Icon.createWithContentUri(uri),
                context.getUserId(), packageName, maxPx, PeopleSpaceWidgetManager::onTileImageMade);
        if (drawable == null) {
            throw new IOException("image not decoded");
        }
        return drawable;
    }
}
