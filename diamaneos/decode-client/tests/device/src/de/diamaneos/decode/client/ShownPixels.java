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

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.Drawable;

/**
 * What a drawable shows, read as a caller that draws it outside a view reads it: the bitmap its draw hands the
 * canvas. A VM icon keeps pixels that arrived aside until a view it is bound to is attached, and shows them offscreen
 * only through its intrinsic size and its draw, so its wrapped drawable alone does not tell what it shows.
 */
final class ShownPixels {
    private ShownPixels() {
    }

    /** The bitmap {@code drawable} draws (an adaptive icon: its foreground's), or null when it draws none. */
    static Bitmap of(Drawable drawable) {
        if (drawable instanceof AdaptiveIconDrawable) {
            drawable = ((AdaptiveIconDrawable) drawable).getForeground();
        }
        if (drawable == null) {
            return null;
        }
        final Bitmap[] drawn = new Bitmap[1];
        final Canvas canvas = new Canvas(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)) {
            @Override
            public void drawBitmap(Bitmap bitmap, Rect src, Rect dst, Paint paint) {
                drawn[0] = bitmap;
            }
        };
        if (drawable.getBounds().isEmpty()) {
            drawable.setBounds(0, 0, Math.max(1, drawable.getIntrinsicWidth()),
                    Math.max(1, drawable.getIntrinsicHeight()));
        }
        drawable.draw(canvas);
        return drawn[0];
    }
}
