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
import android.content.pm.ApplicationInfo;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * An app's resource icon where SystemUI loads it on the main thread (the status bar, the shelf, always on display):
 * through resources behind an image gate, so whatever configuration the icon is shown in (night, landscape, another
 * density, an updated app) a bitmap file of the app is decoded in the VM, never here. The main thread only finds images
 * already made. When the icon's image is not made yet, the neutral icon shows, the image is made on an image thread,
 * and the caller is told to load the icon again. The gated resources follow SystemUI's configuration: after a change
 * they are made anew, and the caller refreshes its icons.
 */
public final class AppIcons {
    private static final String TAG = "VmDecode";
    private static final int PICTURE_DP = 320;

    private static final Object sLock = new Object();
    // Gated resources and their gate per user and package, for one configuration.
    private static final Map<String, Gated> sGated = new HashMap<>(); // guarded by sLock
    private static final Handler sMain = new Handler(Looper.getMainLooper());

    private AppIcons() {
    }

    private static final class Gated {
        final Configuration config;
        final AppResourceImages gate;
        final Resources res;
        // Icons whose image was asked for in this configuration: asked once, the neutral icon after a failure.
        final Set<Integer> asked = new HashSet<>();

        Gated(Configuration config, AppResourceImages gate, Resources res) {
            this.config = config;
            this.gate = gate;
            this.res = res;
        }
    }

    /** The neutral icon shown in place of an app image that cannot be shown. */
    public static Icon neutral() {
        return Icon.createWithResource("android", android.R.drawable.ic_dialog_info);
    }

    /**
     * The icon to give a view that loads it on the main thread. A URI or data icon gives the neutral icon (decoded in
     * the VM elsewhere). An app resource icon gives a copy that loads through the image gate when its image is made,
     * else the neutral icon, and then {@code onMade} runs on the main thread once the image is made. Other icons
     * (pixels, the framework's) come back as they are. {@code context} is SystemUI's.
     */
    public static Icon forView(Context context, Icon icon, int userId, String ownerPackage, Runnable onMade) {
        if (icon == null) {
            return null;
        }
        if (VmIcons.needsVm(icon)) {
            return neutral();
        }
        if (icon.getType() != Icon.TYPE_RESOURCE || "android".equals(icon.getResPackage())) {
            return icon;
        }
        final String pkg = icon.getResPackage() == null || icon.getResPackage().isEmpty()
                ? ownerPackage : icon.getResPackage();
        final Gated gated = gated(context, userId, pkg, ownerPackage);
        if (gated == null) {
            return neutral();
        }
        final Icon copy = icon.copyWithResources(gated.res);
        if (loads(context, copy)) {
            return copy;
        }
        final boolean ask;
        synchronized (sLock) {
            ask = gated.asked.add(icon.getResId());
        }
        if (ask) {
            make(context, gated, copy, ownerPackage, onMade);
        }
        return neutral();
    }

    // True when the icon loads with every app image it names already made (no VM here).
    private static boolean loads(Context context, Icon icon) {
        AppResourceImages.beginTracking();
        try {
            final Drawable drawable = icon.loadDrawable(context);
            return drawable != null && !AppResourceImages.missed();
        } catch (RuntimeException e) {
            Log.w(TAG, "app resource icon not loaded: " + e);
            return false;
        }
    }

    // Makes the icon's images on an image thread, charged to the app, then tells the caller.
    private static void make(Context context, Gated gated, Icon copy, String ownerPackage, Runnable onMade) {
        ImageThreads.execute(ownerPackage, () -> {
            final long start = SystemClock.uptimeMillis();
            final DisplayMetrics metrics = context.getResources().getDisplayMetrics();
            gated.gate.startRound(NotificationImageGate.budgetFor(ownerPackage),
                    Math.max(1, Math.min(metrics.widthPixels, metrics.heightPixels)),
                    Math.max(1, (int) (PICTURE_DP * metrics.density)));
            final boolean made = loads(context, copy);
            NotificationImageGate.chargeTime(ownerPackage, SystemClock.uptimeMillis() - start);
            if (made && onMade != null) {
                sMain.post(onMade);
            }
        });
    }

    // The gated resources of a package for a user, in SystemUI's current configuration.
    private static Gated gated(Context context, int userId, String pkg, String ownerPackage) {
        final Configuration config = context.getResources().getConfiguration();
        final String key = userId + ":" + pkg;
        synchronized (sLock) {
            final Gated known = sGated.get(key);
            if (known != null && known.config.diff(config) == 0) {
                return known;
            }
        }
        try {
            final ApplicationInfo ai = context.getPackageManager().getApplicationInfoAsUser(pkg, 0, userId);
            final DisplayMetrics metrics = context.getResources().getDisplayMetrics();
            final AppResourceImages gate = new AppResourceImages(context, ownerPackage, userId,
                    new ImageBudget(0, 0, 0), Math.max(1, Math.min(metrics.widthPixels, metrics.heightPixels)),
                    Math.max(1, (int) (PICTURE_DP * metrics.density)), false);
            final Context app = VmImageContext.wrap(
                    context.createApplicationContext(ai, Context.CONTEXT_RESTRICTED), gate);
            final Gated made = new Gated(new Configuration(config), gate, app.getResources());
            synchronized (sLock) {
                if (sGated.size() > 256) {
                    sGated.clear();
                }
                sGated.put(key, made);
            }
            return made;
        } catch (Exception e) {
            Log.w(TAG, "no gated resources for " + pkg + ": " + e);
            return null;
        }
    }
}
