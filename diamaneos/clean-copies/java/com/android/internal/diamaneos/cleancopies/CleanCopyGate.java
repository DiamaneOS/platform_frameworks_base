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
    private static final String LAUNCHER = "com.android.launcher3";

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
    // Whether this process has a gate (then its other app image paths take clean copies too, see LauncherApps).
    private static volatile boolean sActive;
    // The mode of this process's gate, -1 without one, and its context for the watch (see watching).
    private static volatile int sMode = -1;
    private static volatile Context sApp;
    // What redraws once copies of a package were made: hosted widgets and filling icons, held weakly.
    private static final java.util.List<Waiter> sWaiters = new java.util.ArrayList<>(); // guarded by itself
    private static final int MAX_WAITERS = 512;
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
        final ParcelFileDescriptor fd;
        try {
            fd = open.get();
        } catch (RuntimeException e) {
            return null;
        }
        final Bitmap bitmap = CleanCopyClient.shortcutIcon(context, fd, shortcut.getPackage(),
                shortcut.getUserId());
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

    // A view or drawable that redraws once copies of {@code packageName} for {@code user} were made.
    private static final class Waiter {
        final java.lang.ref.WeakReference<Object> target;
        final String packageName;
        final int user;

        Waiter(Object target, String packageName, int user) {
            this.target = new java.lang.ref.WeakReference<>(target);
            this.packageName = packageName;
            this.user = user;
        }
    }

    /**
     * The launcher hosts {@code view}, a widget of the app {@code provider} names (the system's provider info, never
     * what the widget's views say): it is applied again once copies of that app's images were made, so an image that
     * showed blank fills in from its copy.
     */
    public static void hostedWidget(android.appwidget.AppWidgetHostView view,
            android.appwidget.AppWidgetProviderInfo provider) {
        if (sMode != MODE_LAUNCHER || view == null || provider == null || provider.providerInfo == null) {
            return;
        }
        addWaiter(new Waiter(view, provider.providerInfo.packageName,
                UserHandle.getUserId(provider.providerInfo.applicationInfo.uid)));
    }

    /**
     * An icon of {@code packageName} for {@code userId} the package manager had no copy for: Android's default icon
     * now, which fills itself in from {@code reload} (a load that gives null while a copy is missing) once copies of
     * that app's images were made. Null in the launcher (it keeps icons in its own cache and reloads them) and in a
     * process without a gate: the caller shows the default icon.
     */
    public static Drawable fillingIcon(String packageName, int userId, Supplier<Drawable> reload) {
        if (sMode != MODE_ALL || packageName == null) {
            return null;
        }
        final FillingIcon icon = new FillingIcon(
                Resources.getSystem().getDrawable(com.android.internal.R.drawable.sym_def_app_icon, null), reload);
        addWaiter(new Waiter(icon, packageName, userId));
        return icon;
    }

    /** Tests: copies of {@code packageName} for {@code userId} were made. */
    public static void madeForTest(String packageName, int userId) {
        made(packageName, userId);
    }

    /** Tests: this process's gate mode (MODE_ALL or MODE_LAUNCHER), -1 for none. */
    public static void setModeForTest(int mode) {
        sMode = mode;
    }

    private static void addWaiter(Waiter waiter) {
        synchronized (sWaiters) {
            sWaiters.removeIf(w -> w.target.get() == null);
            if (sWaiters.size() >= MAX_WAITERS) {
                sWaiters.remove(0);
            }
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

            @Override
            public void run() {
                final boolean watched = CleanCopyClient.watch(app, new android.os.IRemoteCallback.Stub() {
                    @Override
                    public void sendResult(android.os.Bundle data) {
                        if (data != null) {
                            made(data.getString(CleanCopyClient.EXTRA_PACKAGE),
                                    data.getInt(CleanCopyClient.EXTRA_USER, -1));
                        }
                    }
                });
                if (!watched && ++mTries < 20) {
                    sMadeHandler.postDelayed(this, 30_000);
                }
            }
        });
    }

    // Copies of {@code packageName} for {@code user} were made: its hosted widgets are applied again, its filling
    // icons load again (off the main thread) and show what they got.
    private static void made(String packageName, int user) {
        if (packageName == null) {
            return;
        }
        final java.util.List<Object> targets = new java.util.ArrayList<>();
        synchronized (sWaiters) {
            for (Waiter w : sWaiters) {
                final Object target = w.target.get();
                if (target != null && packageName.equals(w.packageName) && (user < 0 || user == w.user)) {
                    targets.add(target);
                }
            }
        }
        final android.os.Handler main = new android.os.Handler(Looper.getMainLooper());
        for (Object target : targets) {
            if (target instanceof android.appwidget.AppWidgetHostView) {
                final android.appwidget.AppWidgetHostView view = (android.appwidget.AppWidgetHostView) target;
                view.post(view::reapplyForCleanCopies);
            } else if (target instanceof FillingIcon) {
                final FillingIcon icon = (FillingIcon) target;
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
        }
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

    /** True when the process of the system app {@code packageName} takes other apps' images from clean copies. */
    public static boolean gatedApp(ApplicationInfo ai, String packageName) {
        return ai != null && ai.isSystemApp() && packageName != null
                && !Arrays.asList(EXEMPT).contains(packageName);
    }

    private final Context mContext;
    private final Source mSource;
    private final Set<String> mOwnApks = new HashSet<>();
    private final int mMode;
    private final boolean mWaits;
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

    private CleanCopyGate(Context context, Source source, int mode, boolean waits) {
        mContext = context;
        mSource = source;
        mMode = mode;
        mWaits = waits;
        final ApplicationInfo own = context.getApplicationInfo();
        if (own != null) {
            mOwnApks.addAll(Arrays.asList(own.getAllApkPaths()));
        }
    }

    /** Sets the gate of a system app's process (called once, at bind). */
    public static void installForApp(Context appContext) {
        final ApplicationInfo ai = appContext.getApplicationInfo();
        final String name = appContext.getPackageName();
        if (!gatedApp(ai, name)) {
            return;
        }
        final int mode = LAUNCHER.equals(name) ? MODE_LAUNCHER : MODE_ALL;
        sApp = appContext;
        try {
            final Context app = appContext;
            ImageFileGates.setProcessDefault(new CleanCopyGate(appContext, new Source() {
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
            }, mode, mode == MODE_LAUNCHER));
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
     * process gate decodes its own APK's files as before). {@code launcher}: the launcher's gate.
     */
    public static ImageFileGate forTest(Context context, Source source, boolean launcher, boolean waits) {
        final CleanCopyGate gate = new CleanCopyGate(context, source, launcher ? MODE_LAUNCHER : MODE_ALL, waits);
        gate.mOwnApks.clear();
        return gate;
    }

    /**
     * Sets the system server's gate, which reads its own store directly. It never asks the display for the box (a
     * load can run under the window manager's lock): until the box is known ({@link CleanCopyClient#knownBox}), apps'
     * images here are empty.
     */
    public static void installForSystemServer(Context systemContext, Source source) {
        final CleanCopyGate gate = new CleanCopyGate(systemContext, source, MODE_ALL, false);
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
            return new ByteArrayInputStream(emptyPng());
        }
        return new ByteArrayInputStream(png(made.bitmap, made.chunk, made.npLb));
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
        final String key = CleanCopyKey.of(apkPath, file, density, box[0], box[1]);
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
        String resourceName = null;
        try {
            if (value.resourceId != 0) {
                resourceName = res.getResourceName(value.resourceId);
            }
        } catch (Resources.NotFoundException e) {
            // No name: no stand-in.
        }
        final int user = UserHandle.myUserId();
        mSource.request(key, apkPath, file, density, box[0], box[1], resourceName, user);
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
                    mOwn.addAll(Arrays.asList(own.getAllApkPaths()));
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
            return new ByteArrayInputStream(emptyPng());
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

    // The chunk as a file stores it (network order) from the device format.
    private static byte[] fileChunk(byte[] deviceChunk) {
        // The chunk as a file stores it (network order), right after IHDR (8 signature + 25 IHDR bytes).
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
