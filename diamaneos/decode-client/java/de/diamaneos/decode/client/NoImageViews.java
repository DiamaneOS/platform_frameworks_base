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

import android.content.Context;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.util.Log;
import android.widget.RemoteViews;

/**
 * Another app's RemoteViews shown in the system server (the autofill dataset picker, its dialog and the save dialog),
 * with none of that app's compressed images decoded there and no wait for the VM on the system server's threads: URI
 * and data Icons and image links become empty images, and the views are applied with resources whose bitmap files of
 * apps outside the system image come out empty. XML (layouts, vectors) and the system image's files load as before,
 * text and the rest of the views too. Nothing is decoded anywhere.
 */
public final class NoImageViews {
    private static final String TAG = "VmDecode";

    private NoImageViews() {
    }

    /**
     * A copy of {@code views} with every URI and data image replaced by an empty image, or null when the views hold
     * something that cannot be checked (they are then not shown). The original is not changed.
     */
    public static RemoteViews withoutImages(RemoteViews views) {
        if (views == null) {
            return null;
        }
        try {
            final RemoteViews copy = views.clone();
            return RemoteViewsImages.convert(copy, EMPTY) ? copy : null;
        } catch (RuntimeException e) {
            Log.w(TAG, "views from another app not shown: " + e);
            return null;
        }
    }

    /**
     * {@code context} with resources, and every package context made from it, whose bitmap files of apps outside the
     * system image come out empty. Apply another app's views with it: RemoteViews makes its package context from it.
     */
    public static Context context(Context context) {
        // No budget, never an image thread here: the gate only ever finds nothing made, so it decodes nothing.
        final AppResourceImages gate = new AppResourceImages(context, "", context.getUserId(),
                new ImageBudget(0, 0, 0), 1, 1, false);
        return VmImageContext.wrap(context, gate);
    }

    private static final RemoteViewsImages.Images EMPTY = new RemoteViewsImages.Images() {
        @Override
        public boolean needsWork(Icon icon) {
            return VmIcons.needsVm(icon);
        }

        @Override
        public Icon convert(Icon icon) {
            return VmIcons.emptyIcon();
        }

        @Override
        public Icon convert(Uri uri) {
            return VmIcons.emptyIcon();
        }
    };
}
