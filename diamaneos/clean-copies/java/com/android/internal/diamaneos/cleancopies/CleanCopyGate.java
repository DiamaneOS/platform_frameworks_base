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

package com.android.internal.diamaneos.cleancopies;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.ShortcutInfo;
import android.content.res.ImageFileGate;
import android.content.res.ImageFileGates;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.NinePatch;
import android.graphics.Rect;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.NinePatchDrawable;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.os.UserHandle;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.LruCache;
import android.util.TypedValue;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Supplier;
import java.util.zip.CRC32;

/**
 * The image gate of a whole system process (the clean icon cache): in the system server and in the process of every
 * system app (but the few in {@link #EXEMPT}), every bitmap file of an APK outside the system image and outside the
 * process's own APKs (another app's launcher icon above all) is taken from its clean copy, made in the protected VM
 * and kept by the system server, and never decoded in this process. XML is parsed as before and the files it names go
 * through the same gate. A file without a copy is asked for. Meanwhile the newest copy of the same resource shows
 * (after an app update, or when Android picks another file for it), else it shows empty (Android's default icon for a
 * missing mipmap), and a caller that loads a whole icon ({@link #misses}) shows Android's default icon instead of one
 * with holes. Image files are known by name and by their first bytes: other raw resources of an app load as before.
 *
 * <p>The launcher's process is gated the same way, widgets and their previews included: an icon load
 * ({@link #iconLoad}) waits up to 1.5 s on a background thread for a new app's copy, other loads never wait, and a
 * hosted widget is applied again once copies of its app's images were made ({@link #hostedWidget}). In other system
 * apps an icon the package manager loads without a copy shows Android's default icon and fills itself in once the
 * copy is made ({@link #fillingIcon}), the installer's dialog icon of a staged APK included. A process whose gate
 * cannot be set up decodes no other app's image file at all. A Resources object with a gate of its own (SystemUI's
 * notification and splash gates) keeps it. A drawable made wholly of exact copies may be cached for this gate
 * ({@link #loadMark}).
 *
 * @hide
 */
public final class CleanCopyGate implements ImageFileGate {
    private static final String TAG = "CleanCopies";

    /**
     * System apps whose processes decode as before: the decode service (it decodes no app image itself, its
     * isolated resolver only reads resource tables) and the shell, which serves adb and must not reach the copies.
     */
    private static final String[] EXEMPT = {
        "de.diamaneos.sanitizer",
        "com.android.shell",
    };

    /** Every other app's image file comes from its copy. */
    public static final int MODE_ALL = 0;
    /** The launcher: as {@link #MODE_ALL}, and icon loads wait for a missing copy on a background thread. */
    public static final int MODE_LAUNCHER = 1;

    // How long a load may wait for a new copy, and how long no load waits after one waited in vain (the VM down).
    private static final long WAIT_MS = 1_500;
    private static final long WAIT_STEP_MS = 100;
    private static final long NO_WAIT_MS = 10_000;
    private static final int CACHE_BYTES = 8 * 1024 * 1024;

    /** Where a gate finds copies and asks for missing ones. */
    public interface Source {
        /** The checked copy under {@code key}, or null. */
        CleanImageFile read(String key);

        /**
         * The newest copy of the same resource as the file (the resource {@code resourceName} of the app the APK
         * belongs to, as {@code userId}), the closest to {@code density}, or null.
         */
        CleanImageFile fallback(String apkPath, String file, int density, int maxWidth, int maxHeight,
                String resourceName, int userId);

        /** Asks for the copy of a file, in the background. */
        void request(String key, String apkPath, String file, int density, int maxWidth, int maxHeight,
                String resourceName, int userId);
    }

    // Per thread: app images that loads could not take, icon loads running, and images taken from an older copy of
    // the same resource (the counters only grow).
    private static final ThreadLocal<int[]> sThread = ThreadLocal.withInitial(() -> new int[3]);
    // Per thread: the user whose app an icon load loads ({@link #iconLoad(int, Supplier)}), -1 for this process's.
    private static final ThreadLocal<int[]> sLoadUser = ThreadLocal.withInitial(() -> new int[] {-1});
    // Whether this process has a gate (then its other app image paths take clean copies too, see LauncherApps).
    private static volatile boolean sActive;
    // The mode of this process's gate, -1 without one, and its context for the watch (see watching).
    private static volatile int sMode = -1;
    private static volatile Context sApp;
    // What redraws once copies of a package were made: hosted widgets and filling icons, held weakly.
    private static final java.util.List<Waiter> sWaiters = new java.util.ArrayList<>(); // guarded by itself
    private static android.os.Handler sMadeHandler; // guarded by CleanCopyGate.class
    private static boolean sWatching; // guarded by CleanCopyGate.class

    /**
     * A shortcut's icon given as a URI, for LauncherApps in a gated process: the descriptor {@code open} gives (the
     * URI opened in this process with its own grant, as Android does) is decoded in the VM by the system server, and
     * the drawable is made from its pixels. Null when there is none (the VM down, out of time, on the main thread).
     */
    public static Drawable shortcutUriIcon(Context context, ShortcutInfo shortcut,
            Supplier<ParcelFileDescriptor> open) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return null;
        }
        final Bitmap bitmap = CleanCopyClient.shortcutIcon(context, shortcut);
        if (bitmap == null) {
            return null;
        }
        final BitmapDrawable drawable = new BitmapDrawable(context.getResources(), bitmap);
        return shortcut.hasAdaptiveBitmap() ? new AdaptiveIconDrawable(null, drawable) : drawable;
    }

    /** True when this process takes apps' images from clean copies. */
    public static boolean isActive() {
        return sActive;
    }

    // A view or drawable that redraws once copies of {@code packageName} for {@code user} (or {@code alsoUser}) were
    // made.
    private static final class Waiter {
        final java.lang.ref.WeakReference<Object> target;
        final String packageName;
        final int user;
        final int alsoUser;
        final long serial;
        final long alsoSerial;

        Waiter(Object target, String packageName, int user) {
            this(target, packageName, user, user);
        }

        Waiter(Object target, String packageName, int user, int alsoUser) {
            this.target = new java.lang.ref.WeakReference<>(target);
            this.packageName = packageName;
            this.user = user;
            this.alsoUser = alsoUser;
            serial = waiterSerial(user);
            alsoSerial = waiterSerial(alsoUser);
        }

        boolean current() {
            return serial == waiterSerial(user) && alsoSerial == waiterSerial(alsoUser);
        }

        boolean waitsFor(String made, int madeUser) {
            return packageName.equals(made) && (madeUser < 0 || madeUser == user || madeUser == alsoUser);
        }
    }

    private static long waiterSerial(int user) {
        final Context app = sApp != null ? sApp : android.app.AppGlobals.getInitialApplication();
        return app == null ? -1 : CleanCopyClient.userSerial(app, user);
    }

    /**
     * The launcher hosts {@code view}, a widget of the app {@code provider} names (the system's provider info, never
     * what the widget's views say): it is applied again once copies of that app's images were made, so an image that
     * showed blank fills in from its copy. Its views load the app's files as this process's user (a load names no
     * other user), so copies made for either user apply it again.
     */
    public static void hostedWidget(android.appwidget.AppWidgetHostView view,
            android.appwidget.AppWidgetProviderInfo provider) {
        if (sMode != MODE_LAUNCHER || view == null || provider == null || provider.providerInfo == null) {
            return;
        }
        addWaiter(new Waiter(view, provider.providerInfo.packageName,
                UserHandle.getUserId(provider.providerInfo.applicationInfo.uid), UserHandle.myUserId()));
    }

    /**
     * An icon of {@code packageName} for {@code userId} the package manager had no copy for: Android's default icon
     * now, which fills itself in from {@code reload} (a load that gives null while a copy is missing) once copies of
     * that app's images were made. Null in the launcher (it keeps icons in its own cache and reloads them) and in a
     * process without a gate: the caller shows the default icon.
     */
    public static Drawable fillingIcon(String packageName, int userId, Supplier<Drawable> reload) {
        return fillingIcon(packageName, userId, null, reload);
    }

    /**
     * {@link #fillingIcon}, showing {@code shown} (an icon from an older copy of the same resource) instead of the
     * default icon until the exact copy is made: then {@code reload} (null until then) replaces it. {@code userId} is
     * the user the load asked for ({@link #iconLoad(int, Supplier)}), whose copies it waits for. Registered before it
     * looks once more, so a copy made between the miss and now is not missed.
     */
    public static Drawable fillingIcon(String packageName, int userId, Drawable shown, Supplier<Drawable> reload) {
        if (sMode != MODE_ALL || packageName == null) {
            return null;
        }
        final long serial = waiterSerial(userId);
        final FillingIcon icon = new FillingIcon(shown != null ? shown
                : Resources.getSystem().getDrawable(com.android.internal.R.drawable.sym_def_app_icon, null),
                () -> serial == waiterSerial(userId) ? reload.get() : null);
        addWaiter(new Waiter(icon, packageName, userId));
        refill(icon, new android.os.Handler(Looper.getMainLooper()));
        return icon;
    }

    /**
     * The package manager's load of a drawable of {@code packageName} for {@code userId} ({@code load}, an icon when
     * {@code icon}): its files from the copies made for that user. One without a copy yet is neither kept nor shown
     * with holes: an icon shows Android's default icon and fills itself in once the copy is made (null in the
     * launcher, which reloads its own), anything else is null. One shown from an older copy of the same resource
     * (after an update) is not kept, and an icon shown so is replaced by the exact copy once it is made. Only an exact
     * copy goes to {@code keep}, whose package manager cache key includes the requested user.
     */
    public static Drawable appImage(String packageName, int userId, boolean icon, Supplier<Drawable> load,
            java.util.function.Consumer<Drawable> keep) {
        final int misses = misses();
        final int provisional = provisional();
        final Supplier<Drawable> exact = () -> {
            final int missed = misses();
            final int older = provisional();
            final Drawable again = iconLoad(userId, load);
            return misses() != missed || provisional() != older ? null : again;
        };
        final Drawable drawable = iconLoad(userId, load);
        if (misses() != misses) {
            return icon ? fillingIcon(packageName, userId, exact) : null;
        }
        if (drawable != null && provisional() != provisional) {
            final Drawable filling = icon ? fillingIcon(packageName, userId, drawable, exact) : null;
            return filling != null ? filling : drawable;
        }
        if (drawable != null) {
            keep.accept(drawable);
        }
        return drawable;
    }

    /** Tests: copies of {@code packageName} for {@code userId} were made. */
    public static void madeForTest(String packageName, int userId) {
        made(packageName, userId);
    }

    /** Tests: the system server said its decode service answers again after an outage. */
    public static void decodeAnswersForTest() {
        decodeAnswers();
    }

    /** Tests: this process's gate mode (MODE_ALL or MODE_LAUNCHER), -1 for none. */
    public static void setModeForTest(int mode) {
        sMode = mode;
    }

    // Loads {@code icon} again off the main thread and shows what it got.
    private static void refill(FillingIcon icon, android.os.Handler main) {
        final Runnable refill = () -> {
            final Drawable loaded = icon.load();
            if (loaded != null) {
                main.post(() -> icon.fill(loaded));
            }
        };
        final android.os.Handler handler;
        synchronized (CleanCopyGate.class) {
            handler = sMadeHandler;
        }
        if (handler != null) {
            handler.post(refill);
        } else {
            new Thread(refill, "CleanCopiesFill").start();
        }
    }

    private static void addWaiter(Waiter waiter) {
        synchronized (sWaiters) {
            final Object target = waiter.target.get();
            // Live views retain their subscriptions. Dead and duplicate entries never accumulate.
            sWaiters.removeIf(w -> w.target.get() == null || !w.current() || w.target.get() == target);
            sWaiters.add(waiter);
        }
        watching();
    }

    // Asks the system server, once, to tell this process when copies are made (on a thread of its own: the system
    // server may not be up yet, then it is asked again a little later).
    private static synchronized void watching() {
        final Context app = sApp;
        if (sWatching || app == null) {
            return;
        }
        sWatching = true;
        final android.os.HandlerThread thread = new android.os.HandlerThread("CleanCopiesMade",
                android.os.Process.THREAD_PRIORITY_BACKGROUND);
        thread.start();
        sMadeHandler = new android.os.Handler(thread.getLooper());
        sMadeHandler.post(new Runnable() {
            private int mTries;
            private final android.os.IRemoteCallback mCallback = new android.os.IRemoteCallback.Stub() {
                @Override
                public void sendResult(android.os.Bundle data) {
                    if (data == null) {
                        return;
                    }
                    if (data.getBoolean(CleanCopyClient.EXTRA_DECODE_ANSWERS)) {
                        decodeAnswers();
                        return;
                    }
                    made(data.getString(CleanCopyClient.EXTRA_PACKAGE),
                            data.getInt(CleanCopyClient.EXTRA_USER, -1));
                }
            };

            @Override
            public void run() {
                final boolean watched = CleanCopyClient.watch(app, mCallback);
                if (watched) {
                    // A successful watch may disappear after a provider restart. Renew the same binder and recheck
                    // live demand so a completion lost during that gap cannot leave a mounted icon blank.
                    made(null, -1);
                }
                sMadeHandler.postDelayed(this, watched || ++mTries >= 20 ? 300_000 : 30_000);
            }
        });
    }

    // How often the system server said its decode service answers again (after calls failed for it), guarded by
    // sAnswersLock.
    private static final Object sAnswersLock = new Object();
    private static int sAnswers;

    // Waits before what waits for an app's images loads again after a decode at once gave nothing (SystemUI).
    private static final long[] RETRY_MS = {1_000, 3_000, 8_000};
    private static final Set<String> sRetries = new HashSet<>(); // package/user with retries posted, guarded by itself

    // What waits for the images of the resource's app (filling icons) loads again in a while, a few times.
    private static void retryLater(Resources res, TypedValue value, int user) {
        final String packageName;
        try {
            packageName = value.resourceId != 0 ? res.getResourcePackageName(value.resourceId) : null;
        } catch (Resources.NotFoundException e) {
            return;
        }
        final android.os.Handler handler;
        synchronized (CleanCopyGate.class) {
            handler = sMadeHandler;
        }
        final String token = packageName + "/" + user;
        if (packageName == null || handler == null) {
            return;
        }
        synchronized (sRetries) {
            if (!sRetries.add(token)) {
                return;
            }
        }
        for (int i = 0; i < RETRY_MS.length; i++) {
            final boolean last = i == RETRY_MS.length - 1;
            handler.postDelayed(() -> {
                if (last) {
                    synchronized (sRetries) {
                        sRetries.remove(token);
                    }
                }
                made(packageName, user);
            }, RETRY_MS[i]);
        }
    }

    private static void decodeAnswers() {
        CleanCopyClient.decodeAnswers();
        synchronized (sAnswersLock) {
            sAnswers++;
            sAnswersLock.notifyAll();
        }
        // Thumbnails that failed meanwhile: one change notice each, on the watch's thread.
        final android.os.Handler handler;
        synchronized (CleanCopyGate.class) {
            handler = sMadeHandler;
        }
        VmThumbnailNotices.answers(sApp, handler);
        // The system server dropped what it had given up on during the outage but makes nothing by itself: what
        // still waits loads again, which asks for its copies anew.
        made(null, -1);
    }

    /**
     * A mark to pass to {@link #awaitDecodeAnswers}, taken before a request to the system server's decode work: an
     * answer sent after it ends the wait at once. Starts the watch of this process if it has not started.
     */
    public static int decodeAnswersMark() {
        watching();
        synchronized (sAnswersLock) {
            return sAnswers;
        }
    }

    /**
     * Waits on this (background) thread until the system server says its decode service answers again after {@code
     * mark}, at most {@code timeoutMs}, or until {@code cancelled} says so (checked at least every half second). True
     * when it answers. Never on the main thread or on a binder call: false there at once.
     */
    public static boolean awaitDecodeAnswers(int mark, long timeoutMs, java.util.function.BooleanSupplier cancelled) {
        if (Looper.myLooper() == Looper.getMainLooper() || android.os.Binder.isDirectlyHandlingTransaction()
                || sApp == null) {
            return false;
        }
        final long end = SystemClock.uptimeMillis() + timeoutMs;
        synchronized (sAnswersLock) {
            while (sAnswers == mark) {
                final long left = end - SystemClock.uptimeMillis();
                if (left <= 0 || (cancelled != null && cancelled.getAsBoolean())) {
                    return false;
                }
                try {
                    sAnswersLock.wait(Math.min(left, 500));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return true;
        }
    }

    /** Told (on a background thread) when copies of an app's images were made, see {@link #whenMade}. */
    public interface MadeListener {
        void onMade(String packageName, int userId);
    }

    /**
     * Has {@code listener}, held weakly (the caller keeps it, a drawable or a view), told when copies of {@code
     * packageName}'s images for {@code userId} were made: what it showed without them can load again.
     */
    public static void whenMade(MadeListener listener, String packageName, int userId) {
        if (listener == null || packageName == null || sApp == null) {
            return;
        }
        addWaiter(new Waiter(listener, packageName, userId));
    }

    // Copies of {@code packageName} for {@code user} were made: its hosted widgets are applied again, its filling
    // icons load again (off the main thread) and show what they got.
    // A null {@code packageName}: every waiter (the decode service answers again after an outage).
    private static void made(String packageName, int user) {
        final java.util.List<Object> targets = new java.util.ArrayList<>();
        synchronized (sWaiters) {
            sWaiters.removeIf(w -> w.target.get() == null || !w.current());
            for (Waiter w : sWaiters) {
                final Object target = w.target.get();
                if (target != null && (packageName == null || w.waitsFor(packageName, user))) {
                    targets.add(target);
                }
            }
        }
        final android.os.Handler main = new android.os.Handler(Looper.getMainLooper());
        for (Object target : targets) {
            if (target instanceof android.appwidget.AppWidgetHostView) {
                final android.appwidget.AppWidgetHostView view = (android.appwidget.AppWidgetHostView) target;
                view.post(view::reapplyForCleanCopies);
            } else if (target instanceof MadeListener) {
                final MadeListener listener = (MadeListener) target;
                final android.os.Handler handler;
                synchronized (CleanCopyGate.class) {
                    handler = sMadeHandler;
                }
                final String madePackage = packageName != null ? packageName : waiterPackage(listener);
                final Runnable tell = () -> listener.onMade(madePackage, user);
                if (handler != null) {
                    handler.post(tell);
                } else {
                    new Thread(tell, "CleanCopiesMade").start();
                }
            } else if (target instanceof FillingIcon) {
                refill((FillingIcon) target, main);
            }
        }
    }

    // The package a waiter waits for.
    private static String waiterPackage(Object target) {
        synchronized (sWaiters) {
            sWaiters.removeIf(w -> w.target.get() == null || !w.current());
            for (Waiter w : sWaiters) {
                if (w.target.get() == target) {
                    return w.packageName;
                }
            }
        }
        return null;
    }

    /** Android's default icon until the app's own icon could be loaded from its copy, then that icon. */
    private static final class FillingIcon extends android.graphics.drawable.DrawableWrapper {
        private final Supplier<Drawable> mReload;
        private volatile boolean mFilled;

        FillingIcon(Drawable placeholder, Supplier<Drawable> reload) {
            super(placeholder);
            mReload = reload;
        }

        Drawable load() {
            if (mFilled) {
                return null;
            }
            try {
                return mReload.get();
            } catch (RuntimeException e) {
                return null;
            }
        }

        // On the main thread.
        void fill(Drawable icon) {
            if (mFilled) {
                return;
            }
            mFilled = true;
            setDrawable(icon);
            invalidateSelf();
        }
    }

    /**
     * An image a gate made in this process from a clean copy's pixels (a PNG it encoded itself): decoded here as it is,
     * never taken to the VM again (android.graphics.DecodeGate).
     */
    public static final class CleanImageStream extends ByteArrayInputStream {
        CleanImageStream(byte[] png) {
            super(png);
        }
    }

    /**
     * True when the files of the APK at {@code apkPath} are decoded in this process: an APK of the system image, or one
     * of this process's own app (as {@link #decodesHere} decides for resources).
     */
    public static boolean apkDecodesHere(String apkPath) {
        final Context app = sApp != null ? sApp : android.app.ActivityThread.currentApplication();
        return apkDecodesHere(app != null ? app.getApplicationInfo() : null, apkPath);
    }

    /** {@link #apkDecodesHere} for the app {@code own}. */
    public static boolean apkDecodesHere(ApplicationInfo own, String apkPath) {
        if (CleanCopyKey.isSystemImage(apkPath)) {
            return true;
        }
        if (apkPath == null || apkPath.isEmpty()) {
            return false;
        }
        return own != null && ownApks(own).contains(apkPath);
    }

    /**
     * The APKs of the app {@code own} as the package manager installed and checked them: its base and its splits.
     * Shared libraries, resource directories and overlays it loads are not its own (they decode here only from the
     * system image).
     */
    static Set<String> ownApks(ApplicationInfo own) {
        final Set<String> apks = new HashSet<>();
        addPath(apks, own.sourceDir);
        addPath(apks, own.publicSourceDir);
        for (String[] splits : new String[][] {own.splitSourceDirs, own.splitPublicSourceDirs}) {
            if (splits != null) {
                for (String split : splits) {
                    addPath(apks, split);
                }
            }
        }
        return apks;
    }

    private static void addPath(Set<String> apks, String path) {
        if (path != null && !path.isEmpty()) {
            apks.add(path);
        }
    }

    /** True when the process of the system app {@code packageName} takes other apps' images from clean copies. */
    public static boolean gatedApp(ApplicationInfo ai, String packageName) {
        return ai != null && ai.isSystemApp() && packageName != null
                && !Arrays.asList(EXEMPT).contains(packageName);
    }

    private final Context mContext;
    private final Source mSource;
    private final Set<String> mOwnApks = new HashSet<>();
    private final boolean mWaits;
    private String mProcess; // the process whose images these are, for what a file without a copy does (CopyMiss)
    private volatile long mNoWaitUntil;
    private boolean mKnownBoxOnly;
    private final LruCache<String, Made> mCache = new LruCache<String, Made>(CACHE_BYTES) {
        @Override
        protected int sizeOf(String key, Made value) {
            return value.bitmap.getAllocationByteCount();
        }
    };

    private static final class Made {
        final Bitmap bitmap;
        final byte[] chunk; // device format, or null
        final Rect padding;
        final Rect insets;
        final byte[] npLb; // the optical insets as a PNG stores them, or null

        Made(Bitmap bitmap, byte[] chunk, Rect padding, Rect insets, byte[] npLb) {
            this.bitmap = bitmap;
            this.chunk = chunk;
            this.padding = padding;
            this.insets = insets;
            this.npLb = npLb;
        }
    }

    private CleanCopyGate(Context context, Source source, boolean waits) {
        synchronized (CleanCopyGate.class) {
            if (sApp == null) {
                sApp = context;
            }
        }
        mContext = context;
        mSource = source;
        mWaits = waits;
        final ApplicationInfo own = context.getApplicationInfo();
        if (own != null) {
            mOwnApks.addAll(ownApks(own));
        }
    }

    /** Sets the gate of a system app's process (called once, at bind). */
    public static void installForApp(Context appContext) {
        final ApplicationInfo ai = appContext.getApplicationInfo();
        final String name = appContext.getPackageName();
        if (!gatedApp(ai, name)) {
            return;
        }
        final int mode = CopyMiss.waits(name) ? MODE_LAUNCHER : MODE_ALL;
        sApp = appContext;
        try {
            final Context app = appContext;
            final CleanCopyGate gate = new CleanCopyGate(appContext, new Source() {
                @Override
                public CleanImageFile read(String key) {
                    return CleanCopyClient.read(app, key);
                }

                @Override
                public CleanImageFile fallback(String apkPath, String file, int density, int maxWidth,
                        int maxHeight, String resourceName, int userId) {
                    return CleanCopyClient.readFallback(app, apkPath, file, density, maxWidth, maxHeight,
                            resourceName, userId);
                }

                @Override
                public void request(String key, String apkPath, String file, int density, int maxWidth,
                        int maxHeight, String resourceName, int userId) {
                    CleanCopyClient.request(app, key, apkPath, file, density, maxWidth, maxHeight, resourceName,
                            userId);
                }
            }, mode == MODE_LAUNCHER);
            gate.mProcess = name;
            ImageFileGates.setProcessDefault(gate);
            sMode = mode;
            Log.i(TAG, "apps' images in " + name + " come from clean copies"
                    + (mode == MODE_LAUNCHER ? " (launcher)" : ""));
        } catch (RuntimeException e) {
            // Fail closed: no other app's image file is decoded here.
            ImageFileGates.setProcessDefault(new DenyAll(ai));
            Log.e(TAG, "no clean copy gate, other apps' images stay empty in " + name + ": " + e);
        }
        sActive = true;
    }

    /**
     * A gate for tests: {@code source} instead of the system server, and the test's own APK treated as any app's (a
     * process gate decodes its own APK's files as before). {@code waits}: icon loads wait for a missing copy.
     */
    public static ImageFileGate forTest(Context context, Source source, boolean waits) {
        return forTest(context, source, waits, null);
    }

    /** {@link #forTest(Context, Source, boolean)}, gating images as the process {@code processName} does. */
    public static ImageFileGate forTest(Context context, Source source, boolean waits, String processName) {
        final CleanCopyGate gate = new CleanCopyGate(context, source, waits);
        gate.mOwnApks.clear();
        gate.mProcess = processName;
        return gate;
    }

    /**
     * Sets the system server's gate, which reads its own store directly. It never asks the display for the box (a
     * load can run under the window manager's lock): until the box is known ({@link CleanCopyClient#knownBox}), apps'
     * images here are empty.
     */
    public static void installForSystemServer(Context systemContext, Source source) {
        final CleanCopyGate gate = new CleanCopyGate(systemContext, source, false);
        gate.mKnownBoxOnly = true;
        ImageFileGates.setProcessDefault(gate);
        sActive = true;
    }

    /**
     * A counter of the app images loads on this thread could not take: a load that leaves it unchanged had every
     * image it named. Callers compare it before and after loading a whole icon.
     */
    public static int misses() {
        return sThread.get()[0];
    }

    /**
     * A counter of the app images loads on this thread took from an older copy of the same resource while the exact
     * one is made: such an icon shows, but is not kept in a cache.
     */
    public static int provisional() {
        return sThread.get()[2];
    }

    /**
     * {@link #iconLoad(Supplier)} of an app of {@code userId} (the package manager knows it: a work profile's app in
     * the user's launcher): its copies are made for and asked for as that user, as the VM labels its images. Loads
     * that name no user ask as this process's user.
     */
    public static <T> T iconLoad(int userId, Supplier<T> load) {
        final int[] user = sLoadUser.get();
        final int previous = user[0];
        user[0] = userId;
        try {
            return iconLoad(load);
        } finally {
            user[0] = previous;
        }
    }

    // The user the loads on this thread ask copies for.
    private static int loadUser() {
        final int user = sLoadUser.get()[0];
        return user >= 0 ? user : UserHandle.myUserId();
    }

    /** Runs {@code load} as an icon load: in the launcher it may wait for a new copy. */
    public static <T> T iconLoad(Supplier<T> load) {
        final int[] state = sThread.get();
        state[1]++;
        try {
            return load.get();
        } finally {
            state[1]--;
        }
    }

    // A load that could not take an image: counted, shown empty, or as Android's default icon for a mipmap (an app
    // icon, which a caller may show as is).
    private static Drawable missing(Resources res, TypedValue value) {
        sThread.get()[0]++;
        try {
            if (res != null && value != null && value.resourceId != 0
                    && "mipmap".equals(res.getResourceTypeName(value.resourceId))) {
                return Resources.getSystem().getDrawable(com.android.internal.R.drawable.sym_def_app_icon, null);
            }
        } catch (RuntimeException e) {
            // Empty.
        }
        return new ColorDrawable(Color.TRANSPARENT);
    }

    @Override
    public boolean decodesHere(String apkPath) {
        return CleanCopyKey.isSystemImage(apkPath) || (apkPath != null && mOwnApks.contains(apkPath));
    }

    /**
     * A mark of this thread's loads, taken before a drawable is loaded: when {@link #cleanSince} then answers true,
     * every image the load took was an exact copy and the drawable may be cached for this gate.
     */
    @Override
    public long cacheScope() {
        final int user = loadUser();
        return (CleanCopyClient.userSerial(mContext, user) << 32) ^ (user & 0xffffffffL);
    }

    @Override
    public long loadMark() {
        final int[] state = sThread.get();
        return ((long) state[0] << 32) | (state[2] & 0xffffffffL);
    }

    @Override
    public boolean cleanSince(long mark) {
        return loadMark() == mark;
    }

    @Override
    public Drawable loadImage(Resources res, TypedValue value, String apkPath) {
        final Made made = made(res, value, apkPath);
        if (made == null) {
            return missing(res, value);
        }
        if (made.chunk != null) {
            return made.insets != null
                    ? new NinePatchDrawable(res, made.bitmap, made.chunk, made.padding, made.insets, null)
                    : new NinePatchDrawable(res, made.bitmap, made.chunk, made.padding, null);
        }
        return new BitmapDrawable(res, made.bitmap);
    }

    @Override
    public InputStream openImage(Resources res, TypedValue value, String apkPath, InputStream original) {
        final InputStream in = original.markSupported() ? original : new BufferedInputStream(original, 64);
        if (!isImage(value.string, in)) {
            return in; // Not an image (sound, text): as before.
        }
        closeQuietly(in);
        final Made made = made(res, value, apkPath);
        if (made == null) {
            missing(null, null);
            return new CleanImageStream(emptyPng());
        }
        return new CleanImageStream(png(made.bitmap, made.chunk, made.npLb));
    }

    // True for a file Android's decoders read: by its name or, for any other name, its first bytes (a compare, no
    // parse). A stream whose first bytes cannot be read counts as an image (fail closed).
    static boolean isImage(CharSequence name, InputStream in) {
        if (isImageFile(name)) {
            return true;
        }
        try {
            in.mark(64);
            final byte[] head = new byte[16];
            int n = 0;
            while (n < head.length) {
                final int r = in.read(head, n, head.length - n);
                if (r < 0) {
                    break;
                }
                n += r;
            }
            in.reset();
            return imageMagic(head, n);
        } catch (IOException e) {
            return true;
        }
    }

    // The file kinds Android's decoders read, by name.
    private static boolean isImageFile(CharSequence name) {
        if (name == null) {
            return false;
        }
        final String n = name.toString().toLowerCase(java.util.Locale.ROOT);
        for (String ext : new String[] {".png", ".jpg", ".jpeg", ".webp", ".gif", ".bmp", ".wbmp", ".heic",
                ".heif", ".avif", ".ico", ".dng"}) {
            if (n.endsWith(ext)) {
                return true;
            }
        }
        return false;
    }

    private static final String[] HEIF_BRANDS = {
        "heic", "heix", "hevc", "hevx", "heim", "heis", "hevm", "hevs", "mif1", "msf1", "avif", "avis",
    };

    // The signatures the image decoders recognise a file by (PNG, JPEG, GIF, WebP, BMP, ICO and CUR, HEIF and AVIF,
    // TIFF-based DNG). WBMP has none worth the name and is known by its file name only.
    static boolean imageMagic(byte[] h, int n) {
        if (n >= 4 && (h[0] & 0xff) == 0x89 && h[1] == 'P' && h[2] == 'N' && h[3] == 'G') {
            return true;
        }
        if (n >= 3 && (h[0] & 0xff) == 0xff && (h[1] & 0xff) == 0xd8 && (h[2] & 0xff) == 0xff) {
            return true;
        }
        if (n >= 4 && h[0] == 'G' && h[1] == 'I' && h[2] == 'F' && h[3] == '8') {
            return true;
        }
        if (n >= 12 && h[0] == 'R' && h[1] == 'I' && h[2] == 'F' && h[3] == 'F' && h[8] == 'W' && h[9] == 'E'
                && h[10] == 'B' && h[11] == 'P') {
            return true;
        }
        if (n >= 2 && h[0] == 'B' && h[1] == 'M') {
            return true;
        }
        if (n >= 4 && h[0] == 0 && h[1] == 0 && (h[2] == 1 || h[2] == 2) && h[3] == 0) {
            return true;
        }
        if (n >= 12 && h[4] == 'f' && h[5] == 't' && h[6] == 'y' && h[7] == 'p') {
            final String brand = new String(h, 8, 4, StandardCharsets.US_ASCII);
            return Arrays.asList(HEIF_BRANDS).contains(brand);
        }
        return n >= 4 && ((h[0] == 'I' && h[1] == 'I' && h[2] == '*' && h[3] == 0)
                || (h[0] == 'M' && h[1] == 'M' && h[2] == 0 && h[3] == '*'));
    }

    private static void closeQuietly(InputStream in) {
        try {
            in.close();
        } catch (IOException ignored) {
            // Never read: the copy is handed out instead.
        }
    }

    private static byte[] emptyPng() {
        return png(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888), null, null);
    }

    @Override
    public Drawable onXmlDrawableFailed(Resources res, TypedValue value, Exception e) {
        final Drawable still = stillFrame(res, value);
        if (still != null) {
            return still;
        }
        Log.w(TAG, "app XML drawable not loaded, empty: " + value.string + ": " + e);
        return missing(res, value);
    }

    private static final String ANDROID_NS = "http://schemas.android.com/apk/res/android";

    /**
     * The still frame of an {@code <animated-image>} of an app: a gate hands its image file over as the one frame of
     * its clean copy, which AnimatedImageDrawable refuses as not animated. Its {@code android:src} loads instead as a
     * plain drawable through the same gate, the copy's frame (the first frame of the app's image), so it shows still
     * rather than empty. Animation of app images is not shown: the VM makes one frame. Null for any other XML
     * drawable, or a source that is not an image file.
     */
    public static Drawable stillFrame(Resources res, TypedValue value) {
        if (res == null || value == null || value.resourceId == 0) {
            return null;
        }
        final int src;
        try (android.content.res.XmlResourceParser parser = res.getXml(value.resourceId)) {
            int type;
            do {
                type = parser.next();
            } while (type != org.xmlpull.v1.XmlPullParser.START_TAG
                    && type != org.xmlpull.v1.XmlPullParser.END_DOCUMENT);
            if (type != org.xmlpull.v1.XmlPullParser.START_TAG || !"animated-image".equals(parser.getName())) {
                return null;
            }
            src = parser.getAttributeResourceValue(ANDROID_NS, "src", 0);
            if (src == 0) {
                return null;
            }
            // An image file, never another XML drawable: the still loads through loadImage, with no way back here.
            final TypedValue file = new TypedValue();
            res.getValue(src, file, true);
            if (file.string == null || file.string.toString().endsWith(".xml")) {
                return null;
            }
            return res.getDrawable(src, null);
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public Resources resourcesFor(String packageName) {
        return null;
    }

    // The copy of a file: from memory or the store, else asked for (and, where allowed, waited for), else null.
    private Made made(Resources res, TypedValue value, String apkPath) {
        if (value.string == null) {
            return null;
        }
        final String file = value.string.toString();
        final int density = bitmapDensity(value);
        final int[] box = mKnownBoxOnly ? CleanCopyClient.knownBox() : CleanCopyClient.box(mContext);
        if (box == null || box[0] < 16) {
            return null; // The display is not known yet: no key to look for.
        }
        // A copy is of one user: one made for another user (the same APK in a work profile) is never taken.
        final int user = loadUser();
        final String key = CleanCopyClient.key(mContext, apkPath, file, density, box[0], box[1], user);
        if (key == null) {
            return null;
        }
        Made made = mCache.get(key);
        if (made != null) {
            return made;
        }
        made = read(key);
        if (made != null) {
            return made;
        }
        // The resource's name only lets the system server find the stand-in among the copies of the app it finds
        // the APK installed for: it never decides whose copy this is.
        String name = null;
        try {
            if (value.resourceId != 0) {
                name = res.getResourceName(value.resourceId);
            }
        } catch (Resources.NotFoundException e) {
            // No name: no stand-in.
        }
        final String resourceName = name;
        made = CopyMiss.miss(mProcess, apkPath, file, CleanCopyGate::apkBytes, new DirectDecoder(density),
                CACHE_BYTES / 4, new CopyMiss.Miss<Made>() {
                    @Override
                    public void keep(Made decoded) {
                        mCache.put(key, decoded);
                    }

                    @Override
                    public void askCopy() {
                        mSource.request(key, apkPath, file, density, box[0], box[1], resourceName, user);
                    }

                    @Override
                    public void retryLater() {
                        CleanCopyGate.retryLater(res, value, user);
                    }
                });
        if (made != null) {
            return made;
        }
        // Meanwhile the newest copy of the same resource, made in the VM too (an older version of the app's file).
        final CleanImageFile older = resourceName != null
                ? mSource.fallback(apkPath, file, density, box[0], box[1], resourceName, user) : null;
        if (older != null) {
            final Made previous = made(older);
            if (previous != null) {
                sThread.get()[2]++;
                return previous;
            }
        }
        // Only the launcher's icon loads wait, and never on the main thread: widgets and everything else show blank
        // and fill in once the copy is made.
        if (!mWaits || sThread.get()[1] == 0 || SystemClock.elapsedRealtime() < mNoWaitUntil
                || Looper.myLooper() == Looper.getMainLooper()) {
            return null;
        }
        final long end = SystemClock.elapsedRealtime() + WAIT_MS;
        while (SystemClock.elapsedRealtime() < end) {
            SystemClock.sleep(WAIT_STEP_MS);
            made = read(key);
            if (made != null) {
                return made;
            }
        }
        mNoWaitUntil = SystemClock.elapsedRealtime() + NO_WAIT_MS;
        return null;
    }

    // A file of an APK, or null when it is larger than max.
    private static byte[] apkBytes(String apkPath, String file, int max) throws IOException {
        try (java.util.zip.ZipFile apk = new java.util.zip.ZipFile(apkPath)) {
            final java.util.zip.ZipEntry entry = apk.getEntry(file);
            if (entry == null) {
                throw new IOException("no " + file);
            }
            if (entry.getSize() < 0 || entry.getSize() > max) {
                return null;
            }
            try (InputStream in = apk.getInputStream(entry)) {
                final byte[] bytes = in.readNBytes(max + 1);
                return bytes.length > max ? null : bytes;
            }
        }
    }

    // The decode at once: through the decode gate (the VM, the process's own lane), unscaled, with the file's
    // density, as a copy is: the drawable scales it to the display.
    private static final class DirectDecoder implements CopyMiss.Decoder<Made> {
        private final int mDensity;

        DirectDecoder(int density) {
            mDensity = density;
        }

        @Override
        public Made decode(byte[] bytes) {
            final android.graphics.BitmapFactory.Options options = new android.graphics.BitmapFactory.Options();
            options.inScaled = false;
            final Bitmap bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
            if (bitmap == null) {
                return null;
            }
            bitmap.setDensity(mDensity);
            return new Made(bitmap, null, null, null, null);
        }

        @Override
        public long bytes(Made made) {
            return made.bitmap.getAllocationByteCount();
        }
    }

    private Made read(String key) {
        final Made made = made(mSource.read(key));
        if (made != null && made.bitmap.getAllocationByteCount() <= CACHE_BYTES / 4) {
            mCache.put(key, made);
        }
        return made;
    }

    /**
     * The gate of a process whose clean copy gate could not be set up: no other app's image file is decoded here,
     * none is shown.
     */
    private static final class DenyAll implements ImageFileGate {
        private final Set<String> mOwn = new HashSet<>();

        DenyAll(ApplicationInfo own) {
            try {
                if (own != null) {
                    mOwn.addAll(ownApks(own));
                }
            } catch (RuntimeException ignored) {
                // Only the system image then.
            }
        }

        @Override
        public boolean decodesHere(String apkPath) {
            return CleanCopyKey.isSystemImage(apkPath) || (apkPath != null && mOwn.contains(apkPath));
        }

        @Override
        public Drawable loadImage(Resources res, TypedValue value, String apkPath) {
            return missing(res, value);
        }

        @Override
        public InputStream openImage(Resources res, TypedValue value, String apkPath, InputStream original) {
            final InputStream in = original.markSupported() ? original : new BufferedInputStream(original, 64);
            if (!isImage(value.string, in)) {
                return in;
            }
            closeQuietly(in);
            missing(null, null);
            return new CleanImageStream(emptyPng());
        }

        @Override
        public Drawable onXmlDrawableFailed(Resources res, TypedValue value, Exception e) {
            return missing(res, value);
        }

        @Override
        public Resources resourcesFor(String packageName) {
            return null;
        }
    }

    // A checked copy as a bitmap with its nine-patch data, or null.
    private static Made made(CleanImageFile image) {
        if (image == null) {
            return null;
        }
        final Bitmap bitmap = CleanCopyClient.toBitmap(image);
        if (bitmap == null) {
            return null;
        }
        byte[] chunk = null;
        Rect padding = null;
        Rect insets = null;
        if (image.npTc != null) {
            final int[] ints = ninePatch(image.npTc, image.width, image.height);
            if (ints != null) {
                chunk = deviceChunk(image.npTc);
                padding = new Rect(ints[0], ints[2], ints[1], ints[3]);
                insets = opticalInsets(image.npLb, image.width, image.height);
                if (!NinePatch.isNinePatchChunk(chunk)) {
                    chunk = null;
                }
            }
        }
        return new Made(bitmap, chunk, padding, insets, chunk != null ? image.npLb : null);
    }

    // The padding of a nine-patch chunk as a PNG stores it (left, right, top, bottom), when its divisions fit the
    // image, else null (the image is shown plain).
    private static int[] ninePatch(byte[] c, int width, int height) {
        if (c.length < 32) {
            return null;
        }
        final int numX = c[1] & 0xff;
        final int numY = c[2] & 0xff;
        final int numColors = c[3] & 0xff;
        if (numX % 2 != 0 || numY % 2 != 0 || c.length != 32 + 4 * (numX + numY + numColors)) {
            return null;
        }
        final ByteBuffer in = ByteBuffer.wrap(c).order(ByteOrder.BIG_ENDIAN);
        final int[] pad = {in.getInt(12), in.getInt(16), in.getInt(20), in.getInt(24)};
        if (pad[0] < 0 || pad[1] < 0 || pad[2] < 0 || pad[3] < 0 || pad[0] > width || pad[1] > width
                || pad[2] > height || pad[3] > height) {
            return null;
        }
        int previous = 0;
        for (int i = 0; i < numX; i++) {
            final int div = in.getInt(32 + 4 * i);
            if (div < previous || div > width) {
                return null;
            }
            previous = div;
        }
        previous = 0;
        for (int i = 0; i < numY; i++) {
            final int div = in.getInt(32 + 4 * numX + 4 * i);
            if (div < previous || div > height) {
                return null;
            }
            previous = div;
        }
        return pad;
    }

    // The chunk as Android keeps it in memory: the same layout, every int in the device's own byte order.
    private static byte[] deviceChunk(byte[] c) {
        final ByteBuffer in = ByteBuffer.wrap(c).order(ByteOrder.BIG_ENDIAN);
        final ByteBuffer out = ByteBuffer.allocate(c.length).order(ByteOrder.nativeOrder());
        out.put(c, 0, 4);
        for (int i = 4; i + 4 <= c.length; i += 4) {
            out.putInt(i, in.getInt(i));
        }
        return out.array();
    }

    private static Rect opticalInsets(byte[] npLb, int width, int height) {
        if (npLb == null || npLb.length != 16) {
            return null;
        }
        final ByteBuffer in = ByteBuffer.wrap(npLb).order(ByteOrder.nativeOrder());
        final int left = in.getInt();
        final int top = in.getInt();
        final int right = in.getInt();
        final int bottom = in.getInt();
        if (left < 0 || top < 0 || right < 0 || bottom < 0 || left + right > width || top + bottom > height) {
            return null;
        }
        return new Rect(left, top, right, bottom);
    }

    // A PNG this process writes from a copy's pixels (with its nine-patch chunk and optical insets), for callers that
    // decode a stream.
    private static byte[] png(Bitmap bitmap, byte[] deviceChunk, byte[] npLb) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        final byte[] png = out.toByteArray();
        if (deviceChunk == null || png.length < 33) {
            return png;
        }
        byte[] result = insertAfterHeader(png, "npTc", fileChunk(deviceChunk));
        if (npLb != null && npLb.length == 16) {
            result = insertAfterHeader(result, "npLb", npLb);
        }
        return result;
    }

    // The chunk as a file stores it (network order) from the device format, to go right after IHDR (8 signature +
    // 25 IHDR bytes).
    private static byte[] fileChunk(byte[] deviceChunk) {
        final ByteBuffer in = ByteBuffer.wrap(deviceChunk).order(ByteOrder.nativeOrder());
        final ByteBuffer data = ByteBuffer.allocate(deviceChunk.length).order(ByteOrder.BIG_ENDIAN);
        data.put(deviceChunk, 0, 4);
        for (int i = 4; i + 4 <= deviceChunk.length; i += 4) {
            data.putInt(i, in.getInt(i));
        }
        return data.array();
    }

    // {@code png} (written here) with a chunk of {@code type} right after its header (8 signature + 25 IHDR bytes).
    private static byte[] insertAfterHeader(byte[] png, String type, byte[] data) {
        final byte[] name = type.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        final CRC32 crc = new CRC32();
        crc.update(name);
        crc.update(data);
        final ByteBuffer chunk = ByteBuffer.allocate(12 + data.length).order(ByteOrder.BIG_ENDIAN);
        chunk.putInt(data.length).put(name).put(data).putInt((int) crc.getValue());
        final byte[] result = new byte[png.length + chunk.capacity()];
        System.arraycopy(png, 0, result, 0, 33);
        System.arraycopy(chunk.array(), 0, result, 33, chunk.capacity());
        System.arraycopy(png, 33, result, 33 + chunk.capacity(), png.length - 33);
        return result;
    }

    private static int bitmapDensity(TypedValue value) {
        if (value.density == TypedValue.DENSITY_DEFAULT) {
            return DisplayMetrics.DENSITY_DEFAULT;
        }
        if (value.density == TypedValue.DENSITY_NONE) {
            return Bitmap.DENSITY_NONE;
        }
        return value.density;
    }
}
