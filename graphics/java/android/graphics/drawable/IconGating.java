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

package android.graphics.drawable;

import android.content.Context;
import android.content.res.ImageFileGate;
import android.content.res.Resources;
import android.text.TextUtils;
import android.util.Log;

/**
 * DiamaneOS: how a resource Icon loads through a context whose resources have an image file gate (see
 * {@link Resources#setImageFileGate}): every package's resources come through the gate, so an app's bitmap files are
 * decoded in a protected VM. Kept out of Icon, which only calls in here.
 */
final class IconGating {
    private static final String TAG = "Icon";

    private IconGating() {
    }

    /** The gated resources of {@code resPackage} for {@code context}, or null without a gate (or none for it). */
    static Resources resourcesFor(Context context, String resPackage) {
        final Resources res = context.getResources();
        final ImageFileGate gate = res == null ? null : res.getImageFileGate();
        if (gate == null) {
            return null;
        }
        return resPackage.equals(context.getPackageName()) ? res : gate.resourcesFor(resPackage);
    }

    /** The gated resources {@code icon} loads from through {@code context}, or null when there are none. */
    static Resources resourcesFor(Icon icon, Context context) {
        return resourcesFor(context, resPackageFor(icon, context));
    }

    /** True when {@code context}'s resources have an image file gate. */
    static boolean isGated(Context context) {
        final Resources res = context.getResources();
        return res != null && res.getImageFileGate() != null;
    }

    /**
     * True when a load of {@code icon} through {@code context} must take the context's gated resources: the context
     * has a gate, the icon is an app's (not the framework's) and holds no gated resources of its own already.
     */
    static boolean mustLoadGated(Icon icon, Context context) {
        if (!isGated(context) || "android".equals(resPackageFor(icon, context))) {
            return false;
        }
        final Resources cached = icon.getResources();
        return cached == null || cached.getImageFileGate() == null;
    }

    /**
     * {@code icon} loaded with {@code gated}, the result of {@link #resourcesFor(Icon, Context)}, for this load only:
     * plain resources cached in the (shared) icon are never used for it, and the gated ones are never kept in it. Null
     * when nothing can be shown.
     */
    static Drawable load(Icon icon, Context context, Resources gated) {
        if (gated == null) {
            return null;
        }
        try {
            return gated.getDrawable(icon.getResId(), context.getTheme());
        } catch (RuntimeException e) {
            Log.e(TAG, String.format("Unable to load resource 0x%08x from pkg=%s", icon.getResId(),
                    icon.getResPackage()), e);
            return null;
        }
    }

    private static String resPackageFor(Icon icon, Context context) {
        final String resPackage = icon.getResPackage();
        return TextUtils.isEmpty(resPackage) ? context.getPackageName() : resPackage;
    }
}
