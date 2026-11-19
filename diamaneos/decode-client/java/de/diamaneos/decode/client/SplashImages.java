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

import android.app.ActivityThread;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.content.res.TypedArray;
import android.graphics.drawable.Drawable;
import android.hardware.display.DisplayManager;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.os.SystemClock;
import android.os.UserHandle;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The images of an app's splash screen (its starting window, drawn by the WM Shell in SystemUI): the splash icon
 * ({@code windowSplashScreenAnimatedIcon}, a still for an animated one), the branding image, the window background
 * and the legacy splash content. They come from the app's own theme and APK, and the Shell shows them on every cold
 * start, so waiting for the VM there would slow every app start. Instead their clean copies are made in the VM ahead
 * of time and kept on disk ({@link CleanImageStore}):
 *
 * <ul>
 * <li>The Shell loads the app's theme drawables through {@link #gate}: resources whose image files come only from
 * the clean copies, never from a decoder in this process and never from the VM. XML (vectors, animated vectors,
 * layer lists, adaptive icons) is parsed as before, and the image files it names go through the same gate.
 * <li>A drawable with an image file that has no clean copy yet is not used ({@link #complete}): the splash shows
 * without it (the app icon instead of the splash icon, no branding, the default background colour), and
 * {@link #end} has the copies made in the VM in the background for the next start.
 * <li>The app icon the splash shows when the app names no splash icon (the Tally keycap) comes from clean copies the
 * same way ({@link #appIcon}). While it has none, the splash shows without an icon.
 * <li>When an app is installed or updated, the copies for its launcher activities are made right away, in both
 * night modes ({@link #watchPackages}).
 * </ul>
 *
 * <p>The work runs on one background thread of its own, one app at a time, so it never holds up notification images,
 * and each app's decodes are charged to it in the decode client.
 */
public final class SplashImages {
    private static final String TAG = "VmDecode";

    // One make: the images of one theme in one configuration.
    private static final long MAKE_MS = 15_000;
    private static final int MAKE_IMAGES = 16;
    private static final long MAKE_PIXELS = 8_000_000;
    // Waiting makes, all apps together. The oldest goes when a new one comes past this.
    private static final int MAX_PENDING = 64;
    // A make that was just done (or failed, the VM down) is not done again sooner than this.
    private static final long AGAIN_MS = 60_000;

    // The splash window attributes that can name an app's image.
    private static final int[] IMAGE_ATTRS = {
        com.android.internal.R.styleable.Window_windowSplashScreenAnimatedIcon,
        com.android.internal.R.styleable.Window_windowSplashScreenBrandingImage,
        com.android.internal.R.styleable.Window_windowBackground,
        com.android.internal.R.styleable.Window_windowSplashscreenContent,
    };

    // Per thread: images a splash could not take since begin(), and the app icon it asked for (resource, density).
    private static final ThreadLocal<int[]> sMissed = ThreadLocal.withInitial(() -> new int[3]);
    // The density the last splash loaded an app icon at, for the copies made at install. 0 until a splash asked.
    private static volatile int sIconDpi;

    private static final Object sLock = new Object();
    private static Handler sWorker; // guarded by sLock
    private static final LinkedHashMap<String, Request> sPending = new LinkedHashMap<>(); // guarded by sLock
    private static final Map<String, Long> sDone = new HashMap<>(); // guarded by sLock
    private static int[] sBox; // guarded by sLock

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
            gate.setStore(CleanImageStore.get(shellContext));
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
        final int[] state = sMissed.get();
        state[0] = 0;
        state[1] = 0;
        state[2] = 0;
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
        final int[] state = sMissed.get();
        state[1] = iconRes;
        state[2] = iconDpi;
        sIconDpi = iconDpi;
        final android.content.res.Resources res = context.getResources();
        return complete(() -> {
            try {
                return res.getDrawableForDensity(iconRes, iconDpi, null);
            } catch (android.content.res.Resources.NotFoundException e) {
                return android.content.res.Resources.getSystem().getDrawableForDensity(
                        android.R.drawable.sym_def_app_icon, iconDpi, null);
            }
        });
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

    /**
     * Ends a splash on this thread. When it left an image out, the clean copies of its theme's images are made in the
     * background, so the next start shows them. {@code splashContext} is the gated context the splash was drawn
     * with.
     */
    public static void end(Context shellContext, Context splashContext, String packageName, int userId) {
        if (!missed()) {
            return;
        }
        final int[] state = sMissed.get();
        final Request request = new Request(packageName, userId, splashContext.getThemeResId(),
                new Configuration(splashContext.getResources().getConfiguration()), state[1], state[2]);
        enqueue(shellContext, request);
    }

    /**
     * Makes the clean copies of every launcher activity's splash images when an app is installed or updated (any
     * user), in both night modes. {@code shellContext} is SystemUI's own.
     */
    public static void watchPackages(Context shellContext) {
        final Context app = shellContext.getApplicationContext() != null
                ? shellContext.getApplicationContext() : shellContext;
        final IntentFilter filter = new IntentFilter(Intent.ACTION_PACKAGE_ADDED);
        filter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        filter.addDataScheme(IntentFilter.SCHEME_PACKAGE);
        final BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                final Uri data = intent.getData();
                final String packageName = data == null ? null : data.getSchemeSpecificPart();
                final int uid = intent.getIntExtra(Intent.EXTRA_UID, -1);
                if (packageName == null || uid < 0) {
                    return;
                }
                final int userId = UserHandle.getUserId(uid);
                worker().post(() -> enqueueInstalled(app, packageName, userId));
            }
        };
        try {
            app.registerReceiverAsUser(receiver, UserHandle.ALL, filter, null, worker());
        } catch (RuntimeException e) {
            Log.w(TAG, "splash images not made at install: " + e);
        }
        // Copies of APKs that are gone are removed once, off the app start path.
        worker().post(() -> CleanImageStore.get(app).prepare());
    }

    // The launcher activities of a package that was just installed or changed: their splash themes, both night modes.
    private static void enqueueInstalled(Context shellContext, String packageName, int userId) {
        final List<ResolveInfo> activities;
        try {
            final Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                    .setPackage(packageName);
            activities = shellContext.getPackageManager().queryIntentActivitiesAsUser(launcher,
                    PackageManager.MATCH_DIRECT_BOOT_AWARE | PackageManager.MATCH_DIRECT_BOOT_UNAWARE, userId);
        } catch (RuntimeException e) {
            return;
        }
        if (activities == null || activities.isEmpty()) {
            return;
        }
        int splashTheme = 0;
        try {
            final String name = ActivityThread.getPackageManager().getSplashScreenTheme(packageName, userId);
            if (name != null) {
                splashTheme = shellContext.createPackageContextAsUser(packageName, Context.CONTEXT_RESTRICTED,
                        UserHandle.of(userId)).getResources().getIdentifier(name, null, null);
            }
        } catch (Exception e) {
            // The activity's own theme then.
        }
        final Configuration base = shellContext.getResources().getConfiguration();
        // The splash loads app icons a little above the display's density: the one the last splash used, else about
        // what the Tally keycap asks for (any density in the same bucket picks the same files).
        final int iconDpi = sIconDpi != 0 ? sIconDpi : Math.round(base.densityDpi * 1.25f);
        for (ResolveInfo info : activities) {
            final ActivityInfo ai = info.activityInfo;
            if (ai == null || !packageName.equals(ai.packageName)) {
                continue;
            }
            final int theme = splashTheme != 0 ? splashTheme
                    : ai.getThemeResource() != 0 ? ai.getThemeResource()
                    : com.android.internal.R.style.Theme_DeviceDefault_DayNight;
            for (int night : new int[] {Configuration.UI_MODE_NIGHT_NO, Configuration.UI_MODE_NIGHT_YES}) {
                final Configuration config = new Configuration(base);
                config.uiMode = (config.uiMode & ~Configuration.UI_MODE_NIGHT_MASK) | night;
                enqueue(shellContext, new Request(packageName, userId, theme, config, ai.getIconResource(),
                        iconDpi));
            }
        }
    }

    /** One make: an app's theme in one configuration, and its icon at a density (none when 0). */
    static final class Request {
        final String packageName;
        final int userId;
        final int theme;
        final Configuration config;
        final int iconRes;
        final int iconDpi;
        final String key;

        Request(String packageName, int userId, int theme, Configuration config, int iconRes, int iconDpi) {
            this.packageName = packageName;
            this.userId = userId;
            this.theme = theme;
            this.config = config;
            this.iconRes = iconRes;
            this.iconDpi = iconDpi;
            this.key = packageName + '/' + userId + '/' + Integer.toHexString(theme) + '/' + config.hashCode()
                    + '/' + Integer.toHexString(iconRes) + '/' + iconDpi;
        }
    }

    private static void enqueue(Context shellContext, Request request) {
        final Context app = shellContext.getApplicationContext() != null
                ? shellContext.getApplicationContext() : shellContext;
        synchronized (sLock) {
            final Long done = sDone.get(request.key);
            if (done != null && SystemClock.elapsedRealtime() - done < AGAIN_MS) {
                return;
            }
            if (sPending.containsKey(request.key)) {
                return;
            }
            sPending.put(request.key, request);
            if (sPending.size() > MAX_PENDING) {
                final Iterator<String> it = sPending.keySet().iterator();
                it.next();
                it.remove();
            }
        }
        worker().post(() -> runNext(app));
    }

    private static void runNext(Context shellContext) {
        final Request request;
        synchronized (sLock) {
            final Iterator<Request> it = sPending.values().iterator();
            if (!it.hasNext()) {
                return;
            }
            request = it.next();
            it.remove();
            final long now = SystemClock.elapsedRealtime();
            sDone.put(request.key, now);
            if (sDone.size() > 4 * MAX_PENDING) {
                sDone.values().removeIf(t -> now - t >= AGAIN_MS);
            }
        }
        try {
            make(shellContext, request);
        } catch (RuntimeException | OutOfMemoryError | StackOverflowError e) {
            Log.w(TAG, "splash images of " + request.packageName + " not made: " + e);
        }
    }

    // Loads the splash images of the request's theme through a gate that may ask the VM, which keeps every image file
    // it makes in the store. The drawables themselves are dropped: only the copies are wanted.
    static void make(Context shellContext, Request request) {
        final Context packageContext;
        try {
            packageContext = shellContext.createPackageContextAsUser(request.packageName,
                    Context.CONTEXT_RESTRICTED, UserHandle.of(request.userId));
        } catch (PackageManager.NameNotFoundException e) {
            return;
        }
        final int[] box = box(shellContext);
        final ImageBudget budget = new ImageBudget(MAKE_MS, MAKE_IMAGES, MAKE_PIXELS);
        final AppResourceImages gate = new AppResourceImages(shellContext, request.packageName, request.userId,
                budget, box[0], box[1], true);
        gate.setStore(CleanImageStore.get(shellContext));
        gate.setPriority(VmDecode.PRIORITY_BACKGROUND);
        try {
            final Context context = VmImageContext.wrap(
                    packageContext.createConfigurationContext(request.config), gate);
            try {
                context.setTheme(request.theme);
                final TypedArray a = context.obtainStyledAttributes(com.android.internal.R.styleable.Window);
                try {
                    for (int attr : IMAGE_ATTRS) {
                        try {
                            a.getDrawable(attr);
                        } catch (RuntimeException e) {
                            // Not set, not a drawable, or broken: nothing to make.
                        }
                    }
                } finally {
                    a.recycle();
                }
            } catch (RuntimeException e) {
                // A theme that does not resolve: the icon is still made.
                Log.w(TAG, "splash theme of " + request.packageName + " not read: " + e);
            }
            if (request.iconRes != 0 && request.iconDpi > 0) {
                try {
                    context.getResources().getDrawableForDensity(request.iconRes, request.iconDpi, null);
                } catch (RuntimeException e) {
                    // No such icon: the splash shows Android's default one.
                }
            }
            if (VmDecode.madeLogging()) {
                VmDecode.madeLine("splash images of " + request.packageName + ": " + gate.files().size()
                        + " app files, " + budget.vmCalls() + " made in the VM");
            }
        } finally {
            budget.release();
        }
    }

    // The box the splash's images are made and looked up in: the default display's size, either way round, so a
    // copy is found in both orientations (and at most a cache entry, see VmIcons.cacheableBox).
    static int[] box(Context shellContext) {
        synchronized (sLock) {
            if (sBox != null) {
                return sBox;
            }
        }
        int w = 1080;
        int h = 2400;
        try {
            final Display display = shellContext.getSystemService(DisplayManager.class)
                    .getDisplay(Display.DEFAULT_DISPLAY);
            final DisplayMetrics metrics = new DisplayMetrics();
            display.getRealMetrics(metrics);
            w = Math.min(metrics.widthPixels, metrics.heightPixels);
            h = Math.max(metrics.widthPixels, metrics.heightPixels);
        } catch (RuntimeException e) {
            // The phone's size then.
        }
        final int[] box = {Math.max(1, w), Math.max(1, h)};
        synchronized (sLock) {
            if (sBox == null) {
                sBox = box;
            }
            return sBox;
        }
    }

    private static Handler worker() {
        synchronized (sLock) {
            if (sWorker == null) {
                final HandlerThread thread = new HandlerThread("VmSplashImages", Process.THREAD_PRIORITY_BACKGROUND);
                thread.start();
                sWorker = new Handler(thread.getLooper());
            }
            return sWorker;
        }
    }

    /** The makes waiting, for tests. */
    static List<String> pending() {
        synchronized (sLock) {
            return new ArrayList<>(sPending.keySet());
        }
    }
}
