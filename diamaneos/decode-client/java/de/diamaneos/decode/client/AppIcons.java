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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * An app's resource icon where SystemUI loads it on the main thread (the status bar, the shelf, always on display):
 * through resources behind an image gate, so whatever configuration the icon is shown in (night, landscape, another
 * density, an updated app) a bitmap file of the app is decoded in the VM, never here. The main thread only finds images
 * already made. URI and data icons of the original notification are decoded in the VM the same way, at the size the
 * notification views use, so the status bar shares what the row made. When an icon's image is not made yet, the
 * neutral icon shows, the image is made on an image thread, and the caller is told to load the icon again. An image
 * that could not be made (the VM not up yet, the app's quota spent) is tried again on its own, after a wait that grows
 * with each failure ({@link Backoff}), and the caller is told when it worked. The gated resources follow the parts of
 * SystemUI's configuration that can pick another resource ({@link #configKey}): after such a change they are made anew
 * ({@link #prepare}, off the main thread), and the caller refreshes its icons. Window-only changes keep them.
 */
public final class AppIcons {
    private static final String TAG = "VmDecode";
    private static final int PICTURE_DP = 320;
    /** The size URI and data icons are decoded at, the same as the notification views (so they share the cache). */
    public static final int ICON_DP = 128;
    private static final int MAX_GATED = 128;
    // Retries stop after this many failures in a row (a wait of about five minutes in all): the next build tries.
    private static final int MAX_RETRIES = 8;

    private static final Object sLock = new Object();
    // Gated resources and their gate per posting app, user and package, for one configuration and one APK. Least
    // recently used first.
    private static final LinkedHashMap<String, Gated> sGated = new LinkedHashMap<String, Gated>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Gated> eldest) {
            return size() > MAX_GATED;
        }
    }; // guarded by sLock
    // Images asked for and not made yet, or failed and waiting for their next try.
    private static final Backoff sRetry = new Backoff();
    private static final Handler sMain = new Handler(Looper.getMainLooper());
    // Gated resources are made here after a configuration change, not on the main thread.
    private static final ExecutorService sPrepare = Executors.newSingleThreadExecutor(r -> {
        final Thread thread = new Thread(r, "AppIconsPrepare");
        thread.setDaemon(true);
        return thread;
    });

    private AppIcons() {
    }

    private static final class Gated {
        final String key;
        final String configKey;
        final String sourceDir;
        final AppResourceImages gate;
        final Resources res;

        Gated(String key, String configKey, String sourceDir, AppResourceImages gate, Resources res) {
            this.key = key;
            this.configKey = configKey;
            this.sourceDir = sourceDir;
            this.gate = gate;
            this.res = res;
        }
    }

    /** The neutral icon shown in place of an app image that cannot be shown. */
    public static Icon neutral() {
        return Icon.createWithResource("android", android.R.drawable.ic_dialog_info);
    }

    /**
     * The parts of {@code config} that can make an app's resources pick another file: night mode and UI type,
     * orientation, density, locales, layout direction and screen layout, screen sizes, mobile network codes, input
     * qualifiers, color mode, grammatical gender and overlays. Not the window bounds or the font scale.
     */
    public static String configKey(Configuration config) {
        return config.uiMode + "/" + config.orientation + "/" + config.densityDpi + "/"
                + config.getLocales().toLanguageTags() + "/" + config.screenLayout + "/"
                + config.smallestScreenWidthDp + "/" + config.screenWidthDp + "x" + config.screenHeightDp + "/"
                + config.mcc + "-" + config.mnc + "/" + config.touchscreen + "/" + config.keyboard + "/"
                + config.navigation + "/" + config.colorMode + "/" + config.getGrammaticalGender() + "/"
                + config.assetsSeq;
    }

    /** True when going from {@code before} to {@code after} can change which resource files apps' icons use. */
    public static boolean resourceConfigChanged(Configuration before, Configuration after) {
        return before == null || after == null || !configKey(before).equals(configKey(after));
    }

    /** True for a resource icon of any package but the framework: such an icon is loaded through an image gate. */
    public static boolean isAppResource(Icon icon) {
        return icon != null && icon.getType() == Icon.TYPE_RESOURCE && !"android".equals(icon.getResPackage());
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
        final Icon shown = forViewOrNull(context, icon, userId, ownerPackage, onMade);
        return shown != null ? shown : neutral();
    }

    /** {@link #forView}, with null where it would give the neutral icon (the caller has a better fallback). */
    public static Icon forViewOrNull(Context context, Icon icon, int userId, String ownerPackage, Runnable onMade) {
        if (icon == null) {
            return null;
        }
        if (VmIcons.needsVm(icon)) {
            return vmIcon(context, icon, userId, ownerPackage, onMade);
        }
        if (!isAppResource(icon)) {
            return icon;
        }
        final String pkg = packageOf(icon, ownerPackage);
        final Gated gated = gated(context, userId, pkg, ownerPackage);
        if (gated == null) {
            return null;
        }
        final Icon copy = icon.copyWithResources(gated.res);
        if (loads(context, copy)) {
            return copy;
        }
        final String retryKey = gated.key + ':' + gated.configKey + ':' + icon.getResId();
        if (sRetry.start(retryKey, SystemClock.uptimeMillis())) {
            make(context, gated, copy, ownerPackage, retryKey, onMade);
        }
        return null;
    }

    /** The size {@link #forView} decodes URI and data icons at. */
    public static int iconPx(Context context) {
        return Math.max(1, (int) (ICON_DP * context.getResources().getDisplayMetrics().density));
    }

    // A URI or data icon: what the VM made of it, else null while an image thread makes it.
    private static Icon vmIcon(Context context, Icon icon, int userId, String ownerPackage, Runnable onMade) {
        final Context user = userContext(context, userId);
        if (user == null) {
            return null;
        }
        final int px = iconPx(context);
        final Icon cached = VmIcons.cachedSafeIcon(icon, user, ownerPackage, px, px);
        if (cached != null) {
            return cached;
        }
        final String key = VmIcons.cacheKey(icon, user, ownerPackage, px + "x" + px);
        if (key != null && sRetry.start(key, SystemClock.uptimeMillis())) {
            makeVm(user, icon, ownerPackage, px, key, onMade);
        }
        return null;
    }

    // Decodes a URI or data icon in the VM on an image thread, charged to the app, into the shared cache.
    private static void makeVm(Context user, Icon icon, String ownerPackage, int px, String key, Runnable onMade) {
        ImageThreads.execute(ownerPackage, () -> {
            final ImageBudget budget = NotificationImageGate.budgetFor(ownerPackage);
            boolean made = false;
            try {
                made = !budget.expired() && VmIcons.toSafeIcon(icon, user, ownerPackage, px, px,
                        VmDecode.deadlineIn(budget.remainingMs())) != null;
            } catch (Throwable e) {
                Log.w(TAG, "icon not made: " + e);
            } finally {
                NotificationImageGate.settle(ownerPackage, budget, false);
            }
            finished(key, made, onMade, () -> makeVm(user, icon, ownerPackage, px, key, onMade));
        });
    }

    // After a try: tell the caller, or schedule the next try after the backoff's wait.
    private static void finished(String key, boolean made, Runnable onMade, Runnable again) {
        if (made) {
            sRetry.succeeded(key);
            if (onMade != null) {
                sMain.post(onMade);
            }
            return;
        }
        final long wait = sRetry.failed(key, SystemClock.uptimeMillis());
        if (sRetry.failures(key) > MAX_RETRIES) {
            return;
        }
        sMain.postDelayed(() -> {
            if (sRetry.start(key, SystemClock.uptimeMillis())) {
                again.run();
            }
        }, wait);
    }

    private static Context userContext(Context context, int userId) {
        if (userId < 0 || context.getUserId() == userId) {
            return context;
        }
        try {
            return context.createContextAsUser(android.os.UserHandle.of(userId), 0);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** An icon {@link #prepare} makes gated resources for: its user, the app that posted it, the icon. */
    public static final class Wanted {
        final int userId;
        final String ownerPackage;
        final Icon icon;

        public Wanted(int userId, String ownerPackage, Icon icon) {
            this.userId = userId;
            this.ownerPackage = ownerPackage;
            this.icon = icon;
        }
    }

    /**
     * After a configuration change: makes the gated resources {@code wanted} need in the new configuration on a
     * background thread (a package lookup and two contexts each), then runs {@code onReady} on the main thread, which
     * rebuilds its icons. Until then the icons shown stay as they are.
     */
    public static void prepare(Context context, List<Wanted> wanted, Runnable onReady) {
        sPrepare.execute(() -> {
            for (Wanted w : wanted) {
                if (isAppResource(w.icon)) {
                    try {
                        gated(context, w.userId, packageOf(w.icon, w.ownerPackage), w.ownerPackage);
                    } catch (Throwable e) {
                        Log.w(TAG, "gated resources not made ahead: " + e);
                    }
                }
            }
            sMain.post(onReady);
        });
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

    // Makes the icon's images on an image thread, charged to the app, then tells the caller. A failure is tried again
    // later, after a growing wait.
    private static void make(Context context, Gated gated, Icon copy, String ownerPackage, String retryKey,
            Runnable onMade) {
        ImageThreads.execute(ownerPackage, () -> {
            final ImageBudget budget = NotificationImageGate.budgetFor(ownerPackage);
            boolean made = false;
            try {
                startRound(context, gated, budget);
                made = loads(context, copy);
            } catch (Throwable e) {
                Log.w(TAG, "app resource icon not made: " + e);
            } finally {
                NotificationImageGate.settle(ownerPackage, budget, false);
            }
            finished(retryKey, made, onMade, () -> make(context, gated, copy, ownerPackage, retryKey, onMade));
        });
    }

    private static void startRound(Context context, Gated gated, ImageBudget budget) {
        final DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        gated.gate.startRound(budget, Math.max(1, Math.min(metrics.widthPixels, metrics.heightPixels)),
                Math.max(1, (int) (PICTURE_DP * metrics.density)));
    }

    /**
     * On an image thread: {@code icon}, an app resource icon, loaded through the gated resources of its package
     * within {@code budget}, its bitmap files decoded in the VM. Null when it cannot be made whole.
     */
    static Drawable loadOnImageThread(Context context, Icon icon, int userId, String ownerPackage,
            ImageBudget budget) {
        final Gated gated = gated(context, userId, packageOf(icon, ownerPackage), ownerPackage);
        if (gated == null) {
            return null;
        }
        final Icon copy = icon.copyWithResources(gated.res);
        startRound(context, gated, budget);
        AppResourceImages.beginTracking();
        try {
            final Drawable drawable = copy.loadDrawable(context);
            return drawable == null || AppResourceImages.missed() ? null : drawable;
        } catch (RuntimeException e) {
            Log.w(TAG, "app resource icon not loaded: " + e);
            return null;
        }
    }

    private static String packageOf(Icon icon, String ownerPackage) {
        final String pkg = icon.getResPackage();
        return pkg == null || pkg.isEmpty() ? ownerPackage : pkg;
    }

    // The gated resources of a package for a user, for the posting app, in SystemUI's current configuration.
    private static Gated gated(Context context, int userId, String pkg, String ownerPackage) {
        final String configKey = configKey(context.getResources().getConfiguration());
        final String owner = ownerPackage == null ? "" : ownerPackage;
        // The posting app is part of the key: its gate charges that app, never another one.
        final String key = owner + '|' + userId + ':' + pkg;
        try {
            final ApplicationInfo ai = context.getPackageManager().getApplicationInfoAsUser(pkg, 0, userId);
            synchronized (sLock) {
                final Gated known = sGated.get(key);
                // An updated app has another APK: its gate is made anew, the old one is let go.
                if (known != null && known.configKey.equals(configKey)
                        && Objects.equals(known.sourceDir, ai.sourceDir)) {
                    return known;
                }
            }
            final DisplayMetrics metrics = context.getResources().getDisplayMetrics();
            final AppResourceImages gate = new AppResourceImages(context, owner, userId,
                    new ImageBudget(0, 0, 0), Math.max(1, Math.min(metrics.widthPixels, metrics.heightPixels)),
                    Math.max(1, (int) (PICTURE_DP * metrics.density)), false);
            final Context app = VmImageContext.wrap(
                    context.createApplicationContext(ai, Context.CONTEXT_RESTRICTED), gate);
            final Gated made = new Gated(key, configKey, ai.sourceDir, gate, app.getResources());
            synchronized (sLock) {
                sGated.put(key, made);
            }
            return made;
        } catch (Exception e) {
            Log.w(TAG, "no gated resources for " + pkg + ": " + e);
            return null;
        }
    }
}
