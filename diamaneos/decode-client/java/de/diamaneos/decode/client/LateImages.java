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

import android.app.IUriGrantsManager;
import android.app.UriGrantsManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BlendMode;
import android.graphics.ColorFilter;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.DrawableWrapper;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Handler;
import android.os.UserHandle;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.View;
import android.widget.RemoteViews;

import java.util.ArrayList;
import java.util.List;

/**
 * The images of another app's views that the system server shows (an autofill service's dataset picker, fill dialog
 * and save dialog, and the service's icon), made in the protected VM on an image thread while the views already show.
 * Nothing waits for the VM on the thread that shows them, on a binder thread or under a lock of the caller.
 *
 * <p>On the showing thread, {@link #prepareInPlace} replaces every URI and data image of the views by a placeholder
 * and keeps the original for the image thread, and {@link #context} gives the context to apply the views with: the
 * placeholders and every bitmap file of the app that is not made yet load as empty drawables that wait. {@link #start}
 * sends the work to an image thread: the URI and data images (each only if the app may read it) and the app's bitmap
 * files the views name are decoded in the VM there, within one budget for the whole set (at most
 * {@link ImageBudget#MAX_IMAGES} images, {@link ImageBudget#MAX_PIXELS} pixels and {@link ImageBudget#TIME_MS}). When
 * it ends, the waiting drawables get their pixels on the showing thread and the views redraw. What could not be made
 * stays empty.
 *
 * <p>In the system server the app's bitmap files come from its clean copy store first ({@link #setStore}): a file
 * with a maker copy shows at once, without VM work. A file without a copy takes the late path above, and the image
 * made there stays with the presentation. URI and data images always take the late path.
 */
public final class LateImages {
    private static final String TAG = "VmDecode";
    // The package name of the placeholder icons: no app can have it, as it is not a valid package name.
    static final String PACKAGE = "de.diamaneos.late images";
    private static final int ICON_DP = 128;
    private static final int PICTURE_DP = 320;
    // Images of the views made in the VM, and views and drawables made ahead: past these, they stay empty.
    private static final int MAX_ICONS = ImageBudget.MAX_IMAGES;
    private static final int MAX_VIEWS = 64;
    private static final int MAX_RESOURCES = 8;
    // Drawables that wait for their pixels.
    private static final int MAX_WAITING = 256;

    private final Context mContext;
    private final String mOwner;
    private final int mUserId;
    private final Handler mShowing;
    private final int mIconPx;
    private final int mPictureWidth;
    private final int mPictureHeight;
    private final AppResourceImages mShownGate;
    private final Resources mPlaceholders = new Placeholders();
    private final Object mLock = new Object();
    private final List<Icon> mOriginals = new ArrayList<>(); // guarded by mLock
    private final List<RemoteViews> mViews = new ArrayList<>(); // copies for the image thread, guarded by mLock
    private final List<Integer> mResources = new ArrayList<>(); // guarded by mLock
    private final List<Waiting> mWaiting = new ArrayList<>(); // guarded by mLock
    private final List<Runnable> mFilledListeners = new ArrayList<>(); // guarded by mLock
    private boolean mStarted; // guarded by mLock
    private boolean mDone; // guarded by mLock
    private AppResourceImages mWorkerGate; // the image thread's gate, until the fill, guarded by mLock
    // The URI and data images made, weakly: a later show takes them while the views of an earlier one still hold
    // them, else the cache. Guarded by mLock.
    private java.lang.ref.WeakReference<Bitmap>[] mMadeRefs;
    private boolean[] mMadeAdaptive;
    // Package icons made whole before, by package, user, resource and configuration: a later call takes them without
    // any image work.
    private static final android.util.LruCache<String, Drawable.ConstantState> sMadeIcons =
            new android.util.LruCache<>(16);
    private Context mUserContext; // for the cache keys of URI and data images, guarded by mLock
    // The store of clean copies the app's bitmap files are taken from first (the system server's), or null.
    private static volatile CleanCopies sStore;

    /**
     * The store of clean copies every LateImages takes an app's bitmap files from before the late path (the system
     * server's clean icon cache, set once at its start). Null means there is no store.
     */
    public static void setStore(CleanCopies store) {
        sStore = store;
    }

    /**
     * For the views of {@code ownerPackage} (the app every decode is charged to) for {@code userId}, shown with
     * {@code context} (the system server's own), whose drawables are filled in on {@code showing}.
     */
    public LateImages(Context context, String ownerPackage, int userId, Handler showing) {
        mContext = context;
        mOwner = ownerPackage == null ? "" : ownerPackage;
        mUserId = userId;
        mShowing = showing;
        final DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        mIconPx = Math.max(1, (int) (ICON_DP * metrics.density));
        mPictureWidth = Math.max(1, Math.min(metrics.widthPixels, metrics.heightPixels));
        mPictureHeight = Math.max(1, (int) (PICTURE_DP * metrics.density));
        // The same box as the image thread's gate, so what it made is found here. It never asks the VM: this is not
        // an image thread.
        mShownGate = new AppResourceImages(context, mOwner, userId, new ImageBudget(0, 0, 0), mPictureWidth,
                mPictureHeight, false);
        mShownGate.setLate(this);
        final CleanCopies store = sStore;
        if (store != null) {
            // A kept copy shows at once: the shown gate only reads it from disk, it never asks the VM.
            mShownGate.setStore(store);
        }
    }

    /**
     * The drawable {@code resId} of {@code packageName} for {@code userId}, shown at once: vectors and the system
     * image's files load, the package's bitmap files are empty until an image thread has made them in the VM, and then
     * show on {@code showing}. Null only when it cannot be loaded at all.
     */
    public static Drawable packageDrawable(Context context, String packageName, int userId, int resId,
            Handler showing) {
        if (resId == 0 || packageName == null) {
            return null;
        }
        final String apk;
        try {
            // The APK path is in the key: an update of the app gives a new path, so its new icon is not hidden by
            // the old one in the cache.
            apk = context.getPackageManager().getApplicationInfoAsUser(packageName, 0, userId).sourceDir;
        } catch (Exception e) {
            Log.w(TAG, "drawable of " + packageName + " not loaded: " + e);
            return null;
        }
        final String cacheKey = userId + ":" + packageName + ":" + apk + ":" + resId + ":"
                + AppIcons.configKey(context.getResources().getConfiguration());
        final Drawable.ConstantState made = sMadeIcons.get(cacheKey);
        if (made != null) {
            return made.newDrawable();
        }
        try {
            final LateImages late = new LateImages(context, packageName, userId, showing);
            final Context app = context.createPackageContextAsUser(packageName, 0, UserHandle.of(userId));
            final Drawable drawable = VmImageContext.wrap(app, late.mShownGate).getDrawable(resId);
            synchronized (late.mLock) {
                if (late.mWaiting.isEmpty()) {
                    // Nothing waits (a vector, files of the system image, files made before): no image work, and
                    // the next call takes it as it is.
                    final Drawable.ConstantState state = drawable == null ? null : drawable.getConstantState();
                    if (state != null) {
                        sMadeIcons.put(cacheKey, state);
                    }
                    return drawable;
                }
                late.mResources.add(resId);
            }
            late.start();
            return drawable;
        } catch (Exception e) {
            Log.w(TAG, "drawable of " + packageName + " not loaded: " + e);
            return null;
        }
    }

    /**
     * The context to apply the prepared views with: resources whose bitmap files of apps outside the system image
     * load as made here (empty until then), and starts and system services from {@code caller}, as
     * {@link NoImageViews#context}. Throws when it cannot be made.
     */
    public Context context(Context caller) {
        return new NoImageViews.Starts(VmImageContext.wrap(caller, mShownGate), caller);
    }

    /**
     * Replaces, in place, every URI and data image of {@code views} (the caller's own copy) by a placeholder that
     * shows the image once it is made, and keeps the views for the image thread. Before {@link #start}. False when
     * the views cannot be checked: then they may not be shown.
     */
    public boolean prepareInPlace(RemoteViews views) {
        if (views == null) {
            return true;
        }
        synchronized (mLock) {
            if (mStarted || mViews.size() >= MAX_VIEWS) {
                // Too late for the image thread, or past the cap: the views show without the app's images, and
                // nothing is copied for them.
                return NoImageViews.stripInPlace(views);
            }
        }
        try {
            if (!RemoteViewsImages.convert(views, mToPlaceholders)) {
                return false;
            }
            final RemoteViews copy = views.clone();
            synchronized (mLock) {
                if (mViews.size() < MAX_VIEWS) {
                    mViews.add(copy);
                }
            }
            return true;
        } catch (RuntimeException e) {
            Log.w(TAG, "views from another app not shown: " + e);
            return false;
        }
    }

    /** A prepared copy of {@code views} (see {@link #prepareInPlace}), or null when they cannot be checked. */
    public RemoteViews preparedCopy(RemoteViews views) {
        if (views == null) {
            return null;
        }
        try {
            final RemoteViews copy = views.clone();
            return prepareInPlace(copy) ? copy : null;
        } catch (RuntimeException e) {
            Log.w(TAG, "views from another app not shown: " + e);
            return null;
        }
    }

    /**
     * Adds {@code views}, prepared already and changed since (transformations), to what the image thread makes the
     * app's bitmap files ahead for. Before {@link #start}.
     */
    public void addViews(RemoteViews views) {
        if (views == null) {
            return;
        }
        final RemoteViews copy;
        try {
            copy = views.clone();
        } catch (RuntimeException e) {
            return;
        }
        synchronized (mLock) {
            if (!mStarted && mViews.size() < MAX_VIEWS) {
                mViews.add(copy);
            }
        }
    }

    /** Sends the work to an image thread. Once: the views prepared before are the ones it makes the images of. */
    public void start() {
        final Icon[] originals;
        final RemoteViews[] views;
        final int[] resources;
        synchronized (mLock) {
            if (mStarted) {
                return;
            }
            mStarted = true;
            originals = mOriginals.toArray(new Icon[0]);
            views = mViews.toArray(new RemoteViews[0]);
            resources = new int[Math.min(mResources.size(), MAX_RESOURCES)];
            for (int i = 0; i < resources.length; i++) {
                resources[i] = mResources.get(i);
            }
            mViews.clear();
            if (originals.length == 0 && views.length == 0 && resources.length == 0) {
                mDone = true;
                return;
            }
        }
        final ImageThreads.Task task = ImageThreads.execute(mOwner, () -> {
            final Icon[] made = make(originals, views, resources);
            mShowing.post(() -> filled(made));
        }, () -> {
            // Gave way to another app's task: the images stay empty.
            mShowing.post(() -> filled(new Icon[originals.length]));
        });
        if (task == null) {
            Log.w(TAG, "images of " + mOwner + " not made: too many image tasks waiting");
            mShowing.post(() -> filled(new Icon[originals.length]));
        }
    }

    // On an image thread: the URI and data images in the VM, and the app's bitmap files of the views and drawables
    // ahead, all within one budget.
    private Icon[] make(Icon[] originals, RemoteViews[] views, int[] resources) {
        final Icon[] made = new Icon[originals.length];
        NotificationImageGate gate = null;
        VmIcons.localCacheWrites(true);
        try {
            gate = NotificationImageGate.forNotification(mContext, mOwner, mUserId, mIconPx, mPictureWidth,
                    mPictureHeight);
            final CleanCopies store = sStore;
            if (store != null) {
                // This worker may read the maker copies.
                gate.resourceImages().setStore(store);
            }
            final ApplicationInfo ai = mContext.getPackageManager().getApplicationInfoAsUser(mOwner, 0, mUserId);
            final Context app = gate.packageContext(
                    mContext.createApplicationContext(ai, Context.CONTEXT_RESTRICTED));
            final IUriGrantsManager grants = UriGrantsManager.getService();
            for (int i = 0; i < originals.length; i++) {
                // The system server could read any URI: only what the app itself may read is opened for it.
                if (ForeignIcons.uriGranted(originals[i], grants, ai.uid, mOwner, mUserId)) {
                    made[i] = gate.layoutIcon(originals[i]);
                }
            }
            for (RemoteViews v : views) {
                gate.prewarmViews(v, app);
            }
            for (int id : resources) {
                gate.prewarmDrawable(app, id);
            }
            synchronized (mLock) {
                mWorkerGate = gate.resourceImages();
            }
        } catch (Throwable e) {
            Log.w(TAG, "images of " + mOwner + " not made, they stay empty: " + e);
        } finally {
            VmIcons.localCacheWrites(false);
            if (gate != null) {
                gate.finish(false);
            }
        }
        return made;
    }

    // On the showing thread: the waiting drawables get what was made.
    private void filled(Icon[] made) {
        final List<Waiting> waiting;
        final AppResourceImages worker;
        synchronized (mLock) {
            mDone = true;
            waiting = new ArrayList<>(mWaiting);
            mWaiting.clear();
            worker = mWorkerGate;
            // Nothing made is held once the waiting drawables have it: a later show takes it weakly, else from the
            // cache.
            mWorkerGate = null;
            @SuppressWarnings("unchecked")
            final java.lang.ref.WeakReference<Bitmap>[] refs = new java.lang.ref.WeakReference[made.length];
            mMadeAdaptive = new boolean[made.length];
            for (int i = 0; i < made.length; i++) {
                if (made[i] != null && (made[i].getType() == Icon.TYPE_BITMAP
                        || made[i].getType() == Icon.TYPE_ADAPTIVE_BITMAP)) {
                    refs[i] = new java.lang.ref.WeakReference<>(made[i].getBitmap());
                    mMadeAdaptive[i] = made[i].getType() == Icon.TYPE_ADAPTIVE_BITMAP;
                }
            }
            mMadeRefs = refs;
        }
        for (Waiting w : waiting) {
            try {
                if (w.mXmlId != 0) {
                    mShownGate.useRound(worker);
                    try {
                        w.fill(w.reloaded());
                    } finally {
                        mShownGate.useRound(null);
                    }
                } else if (w.mKey != null) {
                    w.fill(worker != null ? worker.madeDrawable(w.mRes, w.mKey)
                            : AppResourceImages.cachedDrawable(w.mRes, w.mKey));
                } else {
                    w.fill(madeIcon(made, w.mIndex));
                }
            } catch (RuntimeException e) {
                Log.w(TAG, "image not filled in, it stays empty: " + e);
            }
        }
        final List<Runnable> listeners;
        synchronized (mLock) {
            listeners = new ArrayList<>(mFilledListeners);
            mFilledListeners.clear();
        }
        for (Runnable r : listeners) {
            Waiters.runSafely(r);
        }
    }

    /**
     * Runs {@code listener} on the showing thread once the images are filled in (at once, there, when they are
     * already): a UI measured before can measure again.
     */
    public void whenFilled(Runnable listener) {
        synchronized (mLock) {
            if (!mDone) {
                mFilledListeners.add(listener);
                return;
            }
        }
        mShowing.post(() -> Waiters.runSafely(listener));
    }

    /**
     * For the gate: an XML drawable of the app that got an empty image file, as a drawable that shows it now and
     * loads it again once the files are made ({@code <bitmap>} and {@code <nine-patch>} elements).
     */
    Drawable waitingXml(Resources res, int id, int density, Drawable shown) {
        synchronized (mLock) {
            if (id == 0 || mDone || mWaiting.size() >= MAX_WAITING) {
                return shown;
            }
            final Waiting waiting = new Waiting(res, id, density, shown);
            mWaiting.add(waiting);
            return waiting;
        }
    }

    // A URI or data image of the views made before, from the cache (a show after the fill), as a drawable, or null.
    private Drawable cachedIcon(int index) {
        final Bitmap kept = mMadeRefs == null || index >= mMadeRefs.length || mMadeRefs[index] == null
                ? null : mMadeRefs[index].get();
        if (kept != null && (kept.getWidth() > 1 || kept.getHeight() > 1)) {
            return (mMadeAdaptive[index] ? Icon.createWithAdaptiveBitmap(kept) : Icon.createWithBitmap(kept))
                    .loadDrawable(mContext);
        }
        final Icon original = mOriginals.get(index);
        if (mUserContext == null) {
            try {
                mUserContext = mContext.getUserId() == mUserId ? mContext
                        : mContext.createContextAsUser(UserHandle.of(mUserId), 0);
            } catch (RuntimeException e) {
                return null;
            }
        }
        final Icon cached = VmIcons.cachedSafeIcon(original, mUserContext, mOwner, mPictureWidth, mPictureHeight);
        return cached == null ? null : cached.loadDrawable(mContext);
    }

    private Drawable madeIcon(Icon[] made, int index) {
        final Icon icon = made == null || index >= made.length ? null : made[index];
        if (icon == null
                || (icon.getType() != Icon.TYPE_BITMAP && icon.getType() != Icon.TYPE_ADAPTIVE_BITMAP)) {
            return null;
        }
        final Bitmap bitmap = icon.getBitmap();
        if (bitmap == null || (bitmap.getWidth() <= 1 && bitmap.getHeight() <= 1)) {
            // The empty image of what could not be made.
            return null;
        }
        // Pixels from the VM: nothing is decoded here.
        return icon.loadDrawable(mContext);
    }

    // The drawable a placeholder icon loads as: what was made, or one that waits for it.
    private Drawable placeholder(int index) {
        synchronized (mLock) {
            if (index < 0 || index >= mOriginals.size()) {
                return AppResourceImages.empty();
            }
            if (mDone) {
                final Drawable made = cachedIcon(index);
                return made != null ? made : AppResourceImages.empty();
            }
            if (mWaiting.size() >= MAX_WAITING) {
                return AppResourceImages.empty();
            }
            final Waiting waiting = new Waiting(index, null, null);
            mWaiting.add(waiting);
            return waiting;
        }
    }

    /** For the gate: a bitmap file of the app that is not made, as a drawable that waits for it (or empty). */
    Drawable waitingFile(Resources res, String key) {
        synchronized (mLock) {
            if (key == null || mDone || mWaiting.size() >= MAX_WAITING) {
                return AppResourceImages.empty();
            }
            final Waiting waiting = new Waiting(-1, res, key);
            mWaiting.add(waiting);
            return waiting;
        }
    }

    /** For the gate: the resources the placeholder icons load from. */
    Resources placeholders() {
        return mPlaceholders;
    }

    // URI and data images become placeholders, the originals are kept (as copies: the walker changes the icon object
    // in place) for the image thread. Past the cap, an empty image.
    private final RemoteViewsImages.Images mToPlaceholders = new RemoteViewsImages.Images() {
        @Override
        public boolean needsWork(Icon icon) {
            return VmIcons.needsVm(icon);
        }

        @Override
        public Icon convert(Icon icon) {
            final Icon original = Icon.createWithData(new byte[0], 0, 0);
            original.setTo(icon);
            return placeholderFor(original);
        }

        @Override
        public Icon convert(Uri uri) {
            return placeholderFor(Icon.createWithContentUri(uri));
        }
    };

    private Icon placeholderFor(Icon original) {
        synchronized (mLock) {
            if (mStarted || mOriginals.size() >= MAX_ICONS) {
                return VmIcons.emptyIcon();
            }
            mOriginals.add(original);
            return Icon.createWithResource(PACKAGE, mOriginals.size());
        }
    }

    // The resources a placeholder icon loads from: resource N is the Nth image kept.
    private final class Placeholders extends Resources {
        Placeholders() {
            super((ClassLoader) null);
        }

        @Override
        public Drawable getDrawable(int id) {
            return placeholder(id - 1);
        }

        @Override
        public Drawable getDrawable(int id, Resources.Theme theme) {
            return placeholder(id - 1);
        }

        @Override
        public Drawable getDrawableForDensity(int id, int density) {
            return placeholder(id - 1);
        }

        @Override
        public Drawable getDrawableForDensity(int id, int density, Resources.Theme theme) {
            return placeholder(id - 1);
        }
    }

    /**
     * An empty drawable that takes the pixels of its image once they are made, with the tint, filter and alpha its
     * view gave it, and asks its view to lay out again (the image may have a size now).
     */
    private static final class Waiting extends DrawableWrapper {
        final int mIndex;
        final Resources mRes;
        final String mKey;
        private ColorStateList mTint;
        private boolean mTinted;
        private BlendMode mBlendMode;
        private ColorFilter mFilter;
        private boolean mFiltered;
        private int mAlpha = 255;

        // An XML drawable of the app that loads again once its files are made (0: not one).
        final int mXmlId;
        final int mXmlDensity;
        private Resources.Theme mTheme;

        Waiting(int index, Resources res, String key) {
            super(null);
            mIndex = index;
            mRes = res;
            mKey = key;
            mXmlId = 0;
            mXmlDensity = 0;
        }

        Waiting(Resources res, int xmlId, int density, Drawable shown) {
            super(shown);
            mIndex = -1;
            mRes = res;
            mKey = null;
            mXmlId = xmlId;
            mXmlDensity = density;
        }

        @Override
        public void applyTheme(Resources.Theme theme) {
            mTheme = theme;
            super.applyTheme(theme);
        }

        // The XML drawable loaded again, now that its files are made (from the cache), or null.
        Drawable reloaded() {
            try {
                return mRes.getDrawableForDensity(mXmlId, mXmlDensity, mTheme);
            } catch (RuntimeException e) {
                return null;
            }
        }

        void fill(Drawable made) {
            if (made == null) {
                return;
            }
            if (mTinted) {
                made.setTintList(mTint);
            }
            if (mBlendMode != null) {
                made.setTintBlendMode(mBlendMode);
            }
            if (mFiltered) {
                made.setColorFilter(mFilter);
            }
            if (mAlpha != 255) {
                made.setAlpha(mAlpha);
            }
            setDrawable(made);
            invalidateSelf();
            final Callback callback = getCallback();
            if (callback instanceof View) {
                ((View) callback).requestLayout();
            }
        }

        @Override
        public void setTintList(ColorStateList tint) {
            mTint = tint;
            mTinted = true;
            super.setTintList(tint);
        }

        @Override
        public void setTintBlendMode(BlendMode blendMode) {
            mBlendMode = blendMode;
            super.setTintBlendMode(blendMode);
        }

        @Override
        public void setColorFilter(ColorFilter filter) {
            mFilter = filter;
            mFiltered = true;
            super.setColorFilter(filter);
        }

        @Override
        public void setAlpha(int alpha) {
            mAlpha = alpha;
            super.setAlpha(alpha);
        }
    }
}
