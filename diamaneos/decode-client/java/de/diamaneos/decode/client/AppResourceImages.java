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
import android.content.res.AssetManager;
import android.content.res.ImageFileGate;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.NinePatchDrawable;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.LruCache;
import android.util.TypedValue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * The image file gate of the resources an app's notification layouts are inflated with (see {@link ImageFileGate}):
 * every bitmap file (PNG, JPEG, WebP, GIF and so on) from an APK outside the system image is decoded in the VM and
 * charged to the posting app. That covers {@code setImageViewResource}, background and other drawable resource
 * actions, drawables named in the app's layout XML, and bitmap files inside the app's XML drawables ({@code <bitmap>},
 * {@code <nine-patch>}, layer lists and so on). Files of the system image load as before.
 *
 * <p>One gate serves a notification row for its whole life (its views keep the resources it is set on). Each
 * inflation starts a round ({@link #startRound}) with a fresh budget. The images a round makes are kept for it, so
 * the views applied later in the round (and reapplied ones) get them from here.
 *
 * <p>The VM is asked only on {@link ImageThreads} (in SystemUI) or, for a gate made for any worker, on any thread but
 * the main thread. Elsewhere an image comes from this round or the cache, or is empty. Before a decode the file's
 * header sets the box and the most pixels it can have, which are charged first: no file makes the VM return more than
 * the box (a nine-patch bigger than the box is shown plain). Nine-patches keep their stretching and optical insets:
 * the chunks are read and checked in Java. Fail closed: an image that cannot be made becomes empty, and the rest of
 * the layout still shows. Decoded files are cached by APK (path, time and size), file, density and box.
 */
public final class AppResourceImages implements ImageFileGate {
    private static final String TAG = "VmDecode";
    private static final int MAX_FILE_BYTES = 8 * 1024 * 1024;

    // The nine-patch data (or NO_CHUNK) of a cached file, by the same key as its bitmap in VmIcons' cache.
    private static final Object NO_CHUNK = new Object();
    private static final LruCache<String, Object> sChunks = new LruCache<>(512);
    private static volatile byte[] sEmptyPng;

    // Per thread: how many app images a load could not make (missing off the image threads, refused, failed, or an
    // XML drawable that failed), how many bitmap files of an app it asked for at all, and how many of them the VM
    // refused. See beginTracking.
    private static final ThreadLocal<int[]> sMisses = ThreadLocal.withInitial(() -> new int[3]);
    // Files the VM refused (broken, unsupported, hostile), by cache key: never asked for again.
    private static final LruCache<String, Boolean> sRejected = new LruCache<>(256);

    private final Context mContext;
    private final String mOwner;
    private final int mUserId;
    private final boolean mAnyWorker;
    private final Object mLock = new Object();
    private BooleanSupplier mCancelled; // guarded by mLock
    private final Map<String, Resources> mOtherPackages = new HashMap<>(); // guarded by mLock
    private ImageBudget mBudget; // guarded by mLock
    private int mMaxWidth; // guarded by mLock
    private int mMaxHeight; // guarded by mLock
    private Map<String, Made> mRound = new HashMap<>(); // guarded by mLock
    private final Set<String> mFiles = new LinkedHashSet<>(); // guarded by mLock
    private volatile LateImages mLate; // files not made yet wait for it, see setLate

    /**
     * A gate for SystemUI: {@code context} is SystemUI's own (it binds the decode service), {@code ownerPackage} the
     * posting app, which every decode is charged to. The VM is asked only on {@link ImageThreads}.
     */
    public AppResourceImages(Context context, String ownerPackage, ImageBudget budget, int maxWidth,
            int maxHeight) {
        this(context, ownerPackage, context.getUserId(), budget, maxWidth, maxHeight, false);
    }

    /**
     * {@code userId}: the user whose packages {@link #resourcesFor} opens. {@code anyWorker}: the VM may be asked on
     * any thread but the main thread, not only on {@link ImageThreads}.
     */
    public AppResourceImages(Context context, String ownerPackage, int userId, ImageBudget budget, int maxWidth,
            int maxHeight, boolean anyWorker) {
        mContext = context;
        mOwner = ownerPackage == null ? "" : ownerPackage;
        mUserId = userId;
        mAnyWorker = anyWorker;
        startRound(budget, maxWidth, maxHeight);
    }

    /** Starts counting, on this thread, the app images that loads could not make, and the app files they asked for. */
    public static void beginTracking() {
        final int[] counts = sMisses.get();
        counts[0] = 0;
        counts[1] = 0;
        counts[2] = 0;
    }

    /**
     * True when a load on this thread since {@link #beginTracking} met a bitmap file the VM refused: trying again will
     * not make it.
     */
    public static boolean rejected() {
        return sMisses.get()[2] > 0;
    }

    /**
     * True when a load on this thread since {@link #beginTracking} asked for a bitmap file of an APK outside the system
     * image. A load that did not (a vector, the system image's files) needs no image thread when it comes again.
     */
    public static boolean touchedAppFile() {
        return sMisses.get()[1] > 0;
    }

    /** True when a load on this thread since {@link #beginTracking} could not make an app image. */
    public static boolean missed() {
        return sMisses.get()[0] > 0;
    }

    private static Made missing() {
        sMisses.get()[0]++;
        return null;
    }

    /**
     * A gate that never asks the VM itself, for views that show before their images are made: a bitmap file not made
     * yet loads as a drawable that waits for {@code late}, and the placeholder icons of {@code late} load from it.
     */
    void setLate(LateImages late) {
        mLate = late;
    }

    /** While {@code cancelled} answers true, the VM is not asked: the work is no longer wanted. */
    public void setCancelled(BooleanSupplier cancelled) {
        synchronized (mLock) {
            mCancelled = cancelled;
        }
    }

    @Override
    public Resources resourcesFor(String packageName) {
        final LateImages late = mLate;
        if (LateImages.PACKAGE.equals(packageName)) {
            return late != null ? late.placeholders() : null;
        }
        synchronized (mLock) {
            final Resources known = mOtherPackages.get(packageName);
            if (known != null) {
                return known;
            }
        }
        try {
            final ApplicationInfo ai =
                    mContext.getPackageManager().getApplicationInfoAsUser(packageName, 0, mUserId);
            final Resources res = VmImageContext.wrap(
                    mContext.createApplicationContext(ai, Context.CONTEXT_RESTRICTED), this).getResources();
            synchronized (mLock) {
                mOtherPackages.put(packageName, res);
            }
            return res;
        } catch (Exception e) {
            Log.w(TAG, "no gated resources for " + packageName + ": " + e);
            return null;
        }
    }

    /** The posting app this gate charges. */
    public String owner() {
        return mOwner;
    }

    /**
     * Starts a new inflation: a fresh budget and box. The images of the previous round stay with the views that
     * have them, and are found again in the cache.
     */
    public void startRound(ImageBudget budget, int maxWidth, int maxHeight) {
        final ImageBudget previous;
        synchronized (mLock) {
            previous = mBudget;
            mBudget = budget;
            mMaxWidth = Math.max(1, maxWidth);
            mMaxHeight = Math.max(1, maxHeight);
            mRound = new HashMap<>();
            mFiles.clear();
        }
        if (previous != null && previous != budget) {
            // The images of the last round are replaced: they no longer count against the app's pixels.
            previous.release();
        }
    }

    /** The budget of the current round. */
    public ImageBudget budget() {
        synchronized (mLock) {
            return mBudget;
        }
    }

    @Override
    public boolean decodesHere(String apkPath) {
        return SystemImagePaths.isSystemImage(apkPath);
    }

    @Override
    public Drawable loadImage(Resources res, TypedValue value, String apkPath) {
        final Made made = make(res, value, apkPath, null);
        if (made == null) {
            final LateImages late = mLate;
            return late != null ? late.waitingFile(res, keyFor(value, apkPath)) : empty();
        }
        return drawable(res, made);
    }

    /**
     * A file this gate made in its current round (also one too big for the cache), else one the cache holds, as a
     * drawable, or null. By its cache key.
     */
    Drawable madeDrawable(Resources res, String key) {
        if (key == null) {
            return null;
        }
        synchronized (mLock) {
            final Made made = mRound.get(key);
            if (made != null) {
                return drawable(res, made);
            }
        }
        return cachedDrawable(res, key);
    }

    /** The drawable of a file made before, found by its cache key, or null. */
    static Drawable cachedDrawable(Resources res, String key) {
        final Made made = key == null ? null : cached(key);
        return made == null ? null : drawable(res, made);
    }

    // The key the file has in the cache in this gate's current box.
    private String keyFor(TypedValue value, String apkPath) {
        if (value.string == null) {
            return null;
        }
        final int maxWidth;
        final int maxHeight;
        synchronized (mLock) {
            maxWidth = mMaxWidth;
            maxHeight = mMaxHeight;
        }
        return key(apkPath, value.string.toString(), bitmapDensity(value), maxWidth, maxHeight);
    }

    private static Drawable drawable(Resources res, Made made) {
        if (made.chunk != null) {
            final NinePatchChunk c = made.chunk;
            final int[] insets = c.opticalInsets();
            final Rect padding = new Rect(c.paddingLeft, c.paddingTop, c.paddingRight, c.paddingBottom);
            final byte[] chunk = c.toDeviceFormat(ByteOrder.nativeOrder());
            if (insets != null) {
                return new NinePatchDrawable(res, made.bitmap, chunk, padding,
                        new Rect(insets[0], insets[1], insets[2], insets[3]), null);
            }
            return new NinePatchDrawable(res, made.bitmap, chunk, padding, null);
        }
        return new BitmapDrawable(res, made.bitmap);
    }

    @Override
    public InputStream openImage(Resources res, TypedValue value, String apkPath, InputStream original) {
        Made made;
        try {
            made = make(res, value, apkPath, original);
        } finally {
            try {
                original.close();
            } catch (IOException ignored) {
                // Nothing to do.
            }
        }
        if (made == null) {
            return new ByteArrayInputStream(emptyPng());
        }
        byte[] png = encode(made.bitmap);
        if (png != null && made.chunk != null) {
            png = NinePatchChunk.insertChunk(png, "npTc", made.chunk.toFileFormat());
            final byte[] insets = made.chunk.insetsFileFormat();
            if (png != null && insets != null) {
                png = NinePatchChunk.insertChunk(png, "npLb", insets);
            }
        }
        return new ByteArrayInputStream(png != null ? png : emptyPng());
    }

    @Override
    public Drawable onXmlDrawableFailed(Resources res, TypedValue value, Exception e) {
        Log.w(TAG, "app XML drawable not loaded, empty: " + value.string + ": " + e);
        missing();
        return empty();
    }

    /** The bitmap files of APKs outside the system image asked for in this round, for tests and logs. */
    public List<String> files() {
        synchronized (mLock) {
            return new ArrayList<>(mFiles);
        }
    }

    /** An empty drawable: what a layout shows for an image that could not be made. */
    public static Drawable empty() {
        return new ColorDrawable(Color.TRANSPARENT);
    }

    private static final class Made {
        final Bitmap bitmap;
        final NinePatchChunk chunk;

        Made(Bitmap bitmap, NinePatchChunk chunk) {
            this.bitmap = bitmap;
            this.chunk = chunk;
        }
    }

    /** True on a thread where this gate may wait for the VM. */
    boolean vmAllowedHere() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return false;
        }
        synchronized (mLock) {
            if (mCancelled != null && mCancelled.getAsBoolean()) {
                return false;
            }
        }
        return mAnyWorker || ImageThreads.isImageThread();
    }

    // The pixels of one file: from this round, the cache or the VM, or null. original: the file's stream when the
    // caller opened it already (read only when the VM is asked).
    private Made make(Resources res, TypedValue value, String apkPath, InputStream original) {
        final String file = value.string == null ? null : value.string.toString();
        sMisses.get()[1]++;
        if (file == null) {
            return missing();
        }
        final ImageBudget budget;
        final int maxWidth;
        final int maxHeight;
        synchronized (mLock) {
            mFiles.add(file);
            budget = mBudget;
            maxWidth = mMaxWidth;
            maxHeight = mMaxHeight;
        }
        final int density = bitmapDensity(value);
        final String key = key(apkPath, file, density, maxWidth, maxHeight);
        final String roundKey = key != null ? key : "nocache:" + apkPath + ':' + file + ':' + density;
        synchronized (mLock) {
            final Made kept = mRound.get(roundKey);
            if (kept != null) {
                return kept;
            }
        }
        if (!vmAllowedHere()) {
            // Not a thread that may wait for the VM: what is cached, else empty (never charged here).
            final Made hit = key == null ? null : cached(key);
            return hit != null ? hit : missing();
        }
        final ImageBudget.Admission admission = budget.admit(roundKey);
        if (admission == ImageBudget.Admission.REFUSED) {
            Log.w(TAG, "too many images in one notification layout, empty: " + file);
            return missing();
        }
        if (budget.hasFailed(roundKey)) {
            return missing();
        }
        if (key != null && sRejected.get(key) != null) {
            sMisses.get()[2]++;
            return missing();
        }
        Made made = key == null ? null : cached(key);
        if (made != null) {
            if (admission == ImageBudget.Admission.NEW && !budget.charge(roundKey, pixels(made.bitmap))) {
                return missing();
            }
            return keep(roundKey, made);
        }
        if (budget.expired()) {
            // The time is up: not even the file is read.
            return missing();
        }
        byte[] bytes;
        try {
            bytes = original != null ? readAll(original) : readFile(res.getAssets(), value.assetCookie, file);
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "app image file not read, empty: " + file + ": " + e.getMessage());
            budget.failed(roundKey);
            return missing();
        }
        // At most as big as a cache entry holds, so what is made is kept (under the key of the gate's box).
        final int[] box = VmIcons.cacheableBox(maxWidth, maxHeight);
        final ImagePlan plan = ImagePlan.of(bytes, bytes.length, box[0], box[1]);
        if (admission == ImageBudget.Admission.NEW && !budget.charge(roundKey, plan.pixels)) {
            Log.w(TAG, "notification layout images over their pixel cap, empty: " + file);
            return missing();
        }
        made = decode(budget, bytes, plan, density, file, key);
        if (made == null) {
            if (admission == ImageBudget.Admission.NEW) {
                budget.refund(plan.pixels);
            }
            budget.failed(roundKey);
            return missing();
        }
        final long pixels = pixels(made.bitmap);
        if (admission == ImageBudget.Admission.NEW) {
            if (pixels < plan.pixels) {
                budget.refund(plan.pixels - pixels);
            } else if (pixels > plan.pixels && !budget.charge(roundKey, pixels - plan.pixels)) {
                return missing();
            }
        }
        // Charged before it is cached: a refused image is never kept.
        if (key != null) {
            VmIcons.cachePut(key, made.bitmap);
            sChunks.put(key, made.chunk != null ? made.chunk : NO_CHUNK);
        }
        return keep(roundKey, made);
    }

    private Made keep(String roundKey, Made made) {
        synchronized (mLock) {
            mRound.put(roundKey, made);
        }
        return made;
    }

    private static long pixels(Bitmap bitmap) {
        return (long) bitmap.getWidth() * bitmap.getHeight();
    }

    private static Made cached(String key) {
        final Bitmap bitmap = VmIcons.cacheGet(key);
        final Object chunk = sChunks.get(key);
        if (bitmap == null || chunk == null) {
            return null;
        }
        return new Made(bitmap, chunk instanceof NinePatchChunk ? (NinePatchChunk) chunk : null);
    }

    private Made decode(ImageBudget budget, byte[] bytes, ImagePlan plan, int density, String file, String key) {
        final long left = budget.remainingMs();
        if (left <= 0) {
            Log.w(TAG, "notification image budget spent, empty: " + file);
            return null;
        }
        Bitmap bitmap;
        try {
            budget.countVmCall();
            bitmap = VmDecode.decodeBytes(mContext, bytes, 0, bytes.length, mOwner,
                    VmDecode.PRIORITY_USER_WAITING, plan.boxWidth, plan.boxHeight, VmDecode.deadlineIn(left));
        } catch (VmDecode.RejectedException e) {
            // Refused by the VM: not asked for again, and its icon is not tried again.
            if (key != null) {
                sRejected.put(key, Boolean.TRUE);
            }
            sMisses.get()[2]++;
            Log.w(TAG, "app image file refused, empty: " + file + ": " + e.getMessage());
            return null;
        } catch (VmDecode.UnavailableException e) {
            Log.w(TAG, "app image file not decoded, empty: " + file + ": " + e.getMessage());
            return null;
        }
        bitmap = VmIcons.immutable(bitmap);
        // Scaled down to fit the box: its density goes down with it, so it keeps the size the app designed.
        if (density != Bitmap.DENSITY_NONE && plan.storedWidth > bitmap.getWidth()) {
            density = Math.max(1, (int) ((long) density * bitmap.getWidth() / plan.storedWidth));
        }
        bitmap.setDensity(density);
        final boolean keepsChunk = plan.chunk != null && plan.chunk.fits(bitmap.getWidth(), bitmap.getHeight());
        return new Made(bitmap, keepsChunk ? plan.chunk : null);
    }

    // By APK (path, time and size, so an update decodes again), file in it, density and box. Null when the APK is
    // not known: such a file is never cached, so two packages can never share an entry.
    private static String key(String apkPath, String file, int density, int maxWidth, int maxHeight) {
        if (apkPath == null) {
            return null;
        }
        final File apk = new File(apkPath);
        return "rfile:" + apkPath + ':' + apk.lastModified() + ':' + apk.length() + ':' + file + ':' + density
                + ':' + maxWidth + 'x' + maxHeight;
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

    private static byte[] readFile(AssetManager assets, int cookie, String file) throws IOException {
        // The file's bytes as an opaque blob: never decoded here.
        try (InputStream in = assets.openNonAsset(cookie, file, AssetManager.ACCESS_STREAMING)) {
            return readAll(in);
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final byte[] buffer = new byte[64 * 1024];
        int n;
        while ((n = in.read(buffer)) > 0) {
            if (out.size() + n > MAX_FILE_BYTES) {
                throw new IOException("image file too large");
            }
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }

    // A PNG this process writes from checked pixels, for callers that decode a stream themselves.
    private static byte[] encode(Bitmap bitmap) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        return bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) ? out.toByteArray() : null;
    }

    private static byte[] emptyPng() {
        byte[] png = sEmptyPng;
        if (png == null) {
            final Bitmap one = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
            png = encode(one);
            one.recycle();
            sEmptyPng = png;
        }
        return png;
    }
}
