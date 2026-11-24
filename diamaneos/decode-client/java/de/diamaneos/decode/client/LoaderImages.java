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
import android.graphics.Bitmap;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.Size;

/**
 * SystemUI's image loader (pod {@code graphics}): images behind a URI and data icons come from other apps, so they
 * are decoded in the protected VM of the decode service, never in SystemUI. Null (a placeholder) when the VM cannot
 * do it. The loader does not know the app that gave the image: its requests go in a lane of their own per provider,
 * never in an app's. Never larger than the screen. Call on a worker thread.
 */
public final class LoaderImages {
    private static final String TAG = "VmDecode";
    // An image from another app may take this long in the VM before a placeholder shows.
    private static final long DEADLINE_MS = 2_000;
    private static final String OWNER = VmDecode.unknownOwner("loader");

    private LoaderImages() {
    }

    /** The image behind {@code uri}, at most {@code maxWidth} x {@code maxHeight} (0: the screen), or null. */
    public static Bitmap uri(Context context, Uri uri, int maxWidth, int maxHeight, boolean hardware) {
        try {
            final Bitmap bitmap = VmDecode.decodeUri(context, context.getContentResolver(), uri, OWNER,
                    VmDecode.PRIORITY_USER_WAITING, box(context, maxWidth), box(context, maxHeight),
                    VmDecode.deadlineIn(DEADLINE_MS));
            if (hardware) {
                final Bitmap copy = bitmap.copy(Bitmap.Config.HARDWARE, false);
                return copy != null ? copy : bitmap;
            }
            return bitmap;
        } catch (VmDecode.UnavailableException | VmDecode.RejectedException e) {
            Log.w(TAG, "image not decoded in the VM: " + e.getMessage());
            return null;
        }
    }

    /** {@code icon}, a URI or adaptive URI icon, as a drawable, or null. */
    public static Drawable uriIcon(Context context, Icon icon, int maxWidth, int maxHeight, boolean hardware) {
        final Bitmap bitmap = uri(context, icon.getUri(), maxWidth, maxHeight, hardware);
        if (bitmap == null) {
            return null;
        }
        final BitmapDrawable drawable = new BitmapDrawable(context.getResources(), bitmap);
        return icon.getType() == Icon.TYPE_URI_ADAPTIVE_BITMAP ? new AdaptiveIconDrawable(null, drawable) : drawable;
    }

    /** {@code icon}, a data icon, as a drawable, or null. */
    public static Drawable dataIcon(Context context, Icon icon, int maxWidth, int maxHeight) {
        try {
            return new BitmapDrawable(context.getResources(), VmDecode.decodeBytes(context, icon.getDataBytes(),
                    icon.getDataOffset(), icon.getDataLength(), OWNER, VmDecode.PRIORITY_USER_WAITING,
                    box(context, maxWidth), box(context, maxHeight), VmDecode.deadlineIn(DEADLINE_MS)));
        } catch (VmDecode.UnavailableException | VmDecode.RejectedException e) {
            Log.w(TAG, "icon not decoded in the VM: " + e.getMessage());
            return null;
        }
    }

    /** The stored size of the image behind {@code uri}, read in the VM, or null. */
    public static Size size(Context context, Uri uri) {
        try {
            return VmDecode.probeUri(context, context.getContentResolver(), uri, OWNER,
                    VmDecode.PRIORITY_USER_WAITING, VmDecode.deadlineIn(DEADLINE_MS));
        } catch (VmDecode.UnavailableException | VmDecode.RejectedException e) {
            Log.w(TAG, "size not read in the VM: " + e.getMessage());
            return null;
        }
    }

    // The box asked of the VM: the caller's, never larger than the screen's long side (also when the caller asks for
    // no resizing).
    private static int box(Context context, int px) {
        final DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        final int screen = Math.max(1, Math.max(metrics.widthPixels, metrics.heightPixels));
        return px <= 0 ? screen : Math.min(px, screen);
    }
}
