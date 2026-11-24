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
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.UserHandle;
import android.util.Log;

import java.util.function.Supplier;

/**
 * The images of an app's splash screen (its starting window, drawn by the WM Shell in SystemUI): the splash icon
 * ({@code windowSplashScreenAnimatedIcon}, a still for an animated one), the branding image, the window background,
 * the legacy splash content and the app icon. They come from the app's own theme and APK, and the Shell shows them on
 * every cold start, so waiting for the VM there would slow every app start. Instead their clean copies are made in
 * the VM ahead of time and kept by the system server ({@link CleanImages}):
 *
 * <ul>
 * <li>The Shell loads the app's theme drawables through {@link #gate}: resources whose image files come only from
 * the clean copies, never from a decoder in this process and never from the VM. XML (vectors, animated vectors,
 * layer lists, adaptive icons) is parsed as before, and the image files it names go through the same gate. A file
 * without a copy is asked for, and the system server makes it in the VM in the background.
 * <li>A drawable with an image file that has no clean copy yet is not used ({@link #complete}): the splash shows
 * without it (the app icon instead of the splash icon, no branding, the default background colour), and from the
 * next start with it.
 * <li>The app icon the splash shows when the app names no splash icon (the Tally keycap) comes from clean copies the
 * same way ({@link #appIcon}). While it has none, the splash shows without an icon.
 * </ul>
 *
 * <p>The system server makes every launcher app's copies at install and update and in one pass after an OS update
 * (its warm-up, which lists the files in the decode service's process), so a missing copy is the exception.
 */
public final class SplashImages {
    private static final String TAG = "VmDecode";

    // Per thread: images a splash could not take since begin().
    private static final ThreadLocal<int[]> sMissed = ThreadLocal.withInitial(() -> new int[1]);

    private SplashImages() {
    }

    /**
     * {@code packageContext} (the app's context the splash is drawn with) with resources of its own whose image files
     * of an APK outside the system image come only from clean copies. {@code shellContext} is SystemUI's own. Null
     * when such resources cannot be made: the caller then shows no splash of the app's (fail closed).
     */
    public static Context gate(Context shellContext, Context packageContext, String packageName, int userId) {
        try {
            final int[] box = box(shellContext);
            final AppResourceImages gate = new AppResourceImages(shellContext, packageName, userId,
                    new ImageBudget(0, 0, 0), box[0], box[1], false);
            gate.setStore(CleanImages.get(shellContext));
            // Never the VM here, on any thread: the start of an app does not wait for it.
            gate.setCancelled(() -> true);
            return VmImageContext.wrap(packageContext, gate);
        } catch (RuntimeException e) {
            Log.w(TAG, "no splash images of " + packageName + ": " + e);
            return null;
        }
    }

    /** Starts a splash on this thread. */
    public static void begin() {
        sMissed.get()[0] = 0;
    }

    /**
     * The icon of {@code ai} at {@code iconDpi} from the app's resources behind the splash gate, as the launcher icon
     * loader loads it (the activity's icon, else the application's, else Android's default), or null while an image
     * file in it has no clean copy (the splash then shows no icon, and the copy is made for the next start).
     * {@code splashContext} is the context {@link #gate} gave, else one is made for the app here.
     */
    public static Drawable appIcon(Context shellContext, Context splashContext, ActivityInfo ai, int iconDpi) {
        final int iconRes = ai.getIconResource();
        if (iconRes == 0) {
            return android.content.res.Resources.getSystem().getDrawableForDensity(
                    android.R.drawable.sym_def_app_icon, iconDpi, null);
        }
        Context context = splashContext;
        if (!(context instanceof VmImageContext)) {
            try {
                context = gate(shellContext, shellContext.createPackageContextAsUser(ai.packageName,
                        Context.CONTEXT_RESTRICTED, UserHandle.getUserHandleForUid(ai.applicationInfo.uid)),
                        ai.packageName, UserHandle.getUserId(ai.applicationInfo.uid));
            } catch (PackageManager.NameNotFoundException e) {
                context = null;
            }
            if (context == null) {
                sMissed.get()[0]++;
                return null;
            }
        }
        final android.content.res.Resources res = context.getResources();
        // At the density bucket at or above the one asked for: the same files Android would pick, and the density
        // the clean copies are made for ahead of time (the system server's warm-up loads icons at buckets). The
        // splash draws the icon at its own size, so only the sharpness could differ, never for the worse.
        final int density = bucketAtLeast(iconDpi);
        return complete(() -> {
            try {
                return res.getDrawableForDensity(iconRes, density, null);
            } catch (android.content.res.Resources.NotFoundException e) {
                return android.content.res.Resources.getSystem().getDrawableForDensity(
                        android.R.drawable.sym_def_app_icon, iconDpi, null);
            }
        });
    }

    // The density bucket at or above {@code dpi} (the largest one above them all).
    static int bucketAtLeast(int dpi) {
        for (int bucket : new int[] {160, 240, 320, 480, 640}) {
            if (bucket >= dpi) {
                return bucket;
            }
        }
        return 640;
    }

    /**
     * The drawable {@code load} gives through a {@link #gate}d context, or null when an image file in it has no clean
     * copy yet: an image with a hole is never shown. Exceptions of {@code load} pass through.
     */
    public static Drawable complete(Supplier<Drawable> load) {
        AppResourceImages.beginTracking();
        final Drawable drawable = load.get();
        if (AppResourceImages.missed()) {
            sMissed.get()[0]++;
            return null;
        }
        return drawable;
    }

    /** True when a drawable of the splash on this thread was left out since {@link #begin}. */
    public static boolean missed() {
        return sMissed.get()[0] > 0;
    }

    // The box the splash's images are made and looked up in: the one every process uses for clean copies.
    static int[] box(Context shellContext) {
        return com.android.internal.diamaneos.cleancopies.CleanCopyClient.box(shellContext);
    }
}
