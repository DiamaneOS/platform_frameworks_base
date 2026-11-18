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
import android.content.ContentProvider;
import android.content.Context;
import android.content.Intent;
import android.content.pm.LauncherApps;
import android.content.pm.ShortcutInfo;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.util.Log;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Icons other apps hand SystemUI and the shell outside notification rows: picture-in-picture actions, Quick Settings
 * tiles and the add-tile dialog, device controls, the media controls and the media output dialog, mode icons, the
 * conversation guts and conversation shortcuts (bubbles, the people widget, the status bar avatar, the conversation
 * layout). No app's compressed image is decoded in the calling process: pixels and the framework's own icons load as
 * they are, URI and data icons are decoded in the VM, and an app's resource icon loads through resources behind an
 * image gate, its bitmap files decoded in the VM. The VM is only asked on the image threads, charged to the app that
 * handed the icon over ({@link ImageThreads}, {@link OwnerQuota}).
 *
 * <p>Three shapes. {@link #loadNoWait} never waits: what was made before, the rest made in the background and the
 * caller told when it is ready (the main thread and shared background threads). {@link #loadAsync} hands the result to
 * a callback. {@link #load} and {@link #safeIcon} wait for an image thread, for callers that have their own thread and
 * need the result now (the media pipeline, bubbles, the people widget): an icon that failed is not waited for again
 * until its retry time.
 */
public final class ForeignIcons {
    private static final String TAG = "VmDecode";
    // How long a caller waits for an image thread, the image's own time included.
    private static final long WAIT_MS = ImageBudget.TIME_MS + 1_000;

    // Icons being made in the background, or failed and waiting for their next try.
    private static final Backoff sMaking = new Backoff();
    // Icons a waiting caller could not get: not waited for again until their retry time.
    private static final Backoff sFailed = new Backoff();
    private static final Handler sMain = new Handler(Looper.getMainLooper());

    // Shortcut icons are adaptive icons of 108 dp: decoded at that size, so every place that shows one is sharp.
    private static final int SHORTCUT_DP = 108;

    private ForeignIcons() {
    }

    /** The size {@link #shortcutIcon} decodes at, the same for every caller, so they share what was decoded. */
    public static int shortcutPx(Context context) {
        return Math.max(1, (int) (SHORTCUT_DP * context.getResources().getDisplayMetrics().density));
    }

    /**
     * Whether {@code packageName} ({@code callingUid}), which handed over {@code icon}, may read it: for a URI icon the
     * same grant check as {@link Icon#loadDrawableCheckingUriGrant}, done before anything is opened. True for other
     * icons.
     */
    public static boolean uriGranted(Icon icon, IUriGrantsManager uriGrantsManager, int callingUid,
            String packageName) {
        if (icon == null
                || (icon.getType() != Icon.TYPE_URI && icon.getType() != Icon.TYPE_URI_ADAPTIVE_BITMAP)) {
            return true;
        }
        try {
            uriGrantsManager.checkGrantUriPermission_ignoreNonSystem(callingUid, packageName,
                    ContentProvider.getUriWithoutUserId(icon.getUri()), Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    ContentProvider.getUserIdFromUri(icon.getUri()));
            return true;
        } catch (SecurityException | RemoteException e) {
            Log.w(TAG, "no grant for the icon's URI, not loaded: " + e);
            return false;
        }
    }

    /**
     * {@code icon} as a drawable, never waiting: what was made before, or null while an image thread makes it for
     * {@code ownerPackage} (the app that handed it over, whose quota pays). {@code onMade}, if not null, runs on the
     * main thread once it is made, so the caller can load it again. {@code context} is the caller's own (SystemUI or
     * the shell), {@code userId} the user whose packages and URIs the icon names.
     */
    public static Drawable loadNoWait(Context context, Icon icon, int userId, String ownerPackage, int maxPx,
            Runnable onMade) {
        if (icon == null) {
            return null;
        }
        if (!needsWork(icon)) {
            return icon.loadDrawable(context);
        }
        if (icon.getType() == Icon.TYPE_RESOURCE) {
            final Icon copy = AppIcons.forViewOrNull(context, icon, userId, ownerPackage, onMade);
            return copy == null ? null : copy.loadDrawable(context);
        }
        final Context user = userContext(context, userId);
        if (user == null) {
            return null;
        }
        final Drawable drawable = VmIcons.loadDrawableNoWait(icon, user, ownerPackage, maxPx);
        if (drawable == null) {
            final String key = VmIcons.cacheKey(icon, user, ownerPackage, maxPx + "x" + maxPx);
            if (key != null && sMaking.start(key, SystemClock.uptimeMillis())) {
                ImageThreads.execute(ownerPackage, () -> {
                    Drawable made = null;
                    try {
                        made = loadHere(context, icon, userId, ownerPackage, maxPx);
                    } catch (Throwable e) {
                        Log.w(TAG, "icon not made: " + e);
                    }
                    if (made != null) {
                        sMaking.succeeded(key);
                        if (onMade != null) {
                            sMain.post(onMade);
                        }
                    } else {
                        sMaking.failed(key, SystemClock.uptimeMillis());
                    }
                });
            }
        }
        return drawable;
    }

    /**
     * {@code icon} as a drawable, waiting for an image thread (at most {@code WAIT_MS}), for a caller on a thread of its
     * own that needs it now. On the main thread, or for an icon that failed and is not due again, only what was made
     * before. Null when it cannot be made.
     */
    public static Drawable load(Context context, Icon icon, int userId, String ownerPackage, int maxPx) {
        if (icon == null) {
            return null;
        }
        if (!needsWork(icon)) {
            return icon.loadDrawable(context);
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return loadNoWait(context, icon, userId, ownerPackage, maxPx, null);
        }
        final String key = failKey(context, icon, userId, ownerPackage, maxPx);
        if (key != null && !sFailed.mayTry(key, SystemClock.uptimeMillis())) {
            return loadNoWait(context, icon, userId, ownerPackage, maxPx, null);
        }
        final Drawable drawable = onImageThread(ownerPackage,
                () -> loadHere(context, icon, userId, ownerPackage, maxPx));
        if (key != null) {
            if (drawable != null) {
                sFailed.succeeded(key);
            } else {
                sFailed.failed(key, SystemClock.uptimeMillis());
            }
        }
        return drawable;
    }

    /**
     * Like {@link #load}, as an Icon that never needs a decode of the app's bytes when it is loaded later: pixels as
     * they are, a URI or data icon as a bitmap icon from the VM, an app resource icon as a copy that loads through the
     * image gate (its images made). Null when it cannot be made. For callers that keep an Icon (the media pipeline).
     */
    public static Icon safeIcon(Context context, Icon icon, int userId, String ownerPackage, int maxPx) {
        if (icon == null || !needsWork(icon)) {
            return icon;
        }
        final boolean main = Looper.myLooper() == Looper.getMainLooper();
        if (icon.getType() == Icon.TYPE_RESOURCE) {
            if (!main) {
                onImageThread(ownerPackage, () -> {
                    final ImageBudget budget = NotificationImageGate.budgetFor(ownerPackage);
                    try {
                        return AppIcons.loadOnImageThread(context, icon, userId, ownerPackage, budget);
                    } finally {
                        NotificationImageGate.settle(ownerPackage, budget, false);
                    }
                });
            }
            return AppIcons.forViewOrNull(context, icon, userId, ownerPackage, null);
        }
        final Context user = userContext(context, userId);
        if (user == null) {
            return null;
        }
        final Icon cached = VmIcons.cachedSafeIcon(icon, user, ownerPackage, maxPx, maxPx);
        if (cached != null || main) {
            return cached;
        }
        return onImageThread(ownerPackage, () -> {
            final ImageBudget budget = NotificationImageGate.budgetFor(ownerPackage);
            try {
                return budget.expired() ? null : VmIcons.toSafeIcon(icon, user, ownerPackage, maxPx,
                        VmDecode.deadlineIn(budget.remainingMs()));
            } finally {
                NotificationImageGate.settle(ownerPackage, budget, false);
            }
        });
    }

    /**
     * Like {@link #load}, without waiting: {@code onLoaded} gets the drawable, or null, on {@code executor}.
     */
    public static void loadAsync(Context context, Icon icon, int userId, String ownerPackage, int maxPx,
            Executor executor, Consumer<Drawable> onLoaded) {
        if (icon == null) {
            executor.execute(() -> onLoaded.accept(null));
            return;
        }
        ImageThreads.execute(ownerPackage, () -> {
            Drawable drawable = null;
            try {
                drawable = loadHere(context, icon, userId, ownerPackage, maxPx);
            } catch (Throwable e) {
                Log.w(TAG, "icon not loaded: " + e);
            }
            final Drawable result = drawable;
            executor.execute(() -> onLoaded.accept(result));
        });
    }

    /**
     * A context whose resources, and every package context made from it, load an app's bitmap files through an image
     * gate that asks the VM on any thread but the main thread, within one image budget from now, charged to
     * {@code ownerPackage}. For code that loads another app's drawables by ID on a background thread of its own (mode
     * icons). On the main thread such files are empty unless made before.
     */
    public static Context gatedContext(Context context, String ownerPackage, int userId) {
        final android.util.DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        final AppResourceImages gate = new AppResourceImages(context, ownerPackage, userId, new ImageBudget(),
                Math.max(1, Math.min(metrics.widthPixels, metrics.heightPixels)),
                Math.max(1, Math.min(metrics.widthPixels, metrics.heightPixels)), true);
        return VmImageContext.wrap(context, gate);
    }

    /**
     * The icon of conversation shortcut {@code shortcut} as an Icon nothing has to decode in this process: a file icon
     * (saved by the system) and a URI icon become a bitmap icon decoded in the VM, a resource icon comes back as it is
     * (load it through {@link AppIcons} or {@link #load}). {@code mayWait}: whether this thread may wait for an image
     * thread (never the main thread or the notification inflation thread). Otherwise only what was decoded before, and
     * what is missing is made in the background for the next time.
     */
    public static Icon shortcutIcon(Context context, LauncherApps launcherApps, ShortcutInfo shortcut, int maxPx,
            boolean mayWait) {
        if (shortcut == null) {
            return null;
        }
        final String owner = shortcut.getPackage();
        final boolean wait = mayWait && Looper.myLooper() != Looper.getMainLooper();
        try {
            if (shortcut.hasIconFile()) {
                final String key = "shortcut:" + shortcut.getUserId() + ':' + owner + ':' + shortcut.getId() + ':'
                        + shortcut.getLastChangedTimestamp() + ':' + maxPx;
                Bitmap bitmap = VmIcons.cacheGet(key);
                if (bitmap == null) {
                    final Supplier<Bitmap> work =
                            () -> decodeShortcutFile(context, launcherApps, shortcut, key, maxPx);
                    bitmap = wait ? onImageThread(owner, work) : later(owner, key, work);
                }
                if (bitmap == null) {
                    return null;
                }
                return shortcut.hasAdaptiveBitmap() ? Icon.createWithAdaptiveBitmap(bitmap)
                        : Icon.createWithBitmap(bitmap);
            }
            if (shortcut.hasIconResource()) {
                return Icon.createWithResource(owner, shortcut.getIconResourceId());
            }
            final Icon icon = shortcut.hasIconUri() ? launcherApps.getShortcutIcon(shortcut) : shortcut.getIcon();
            if (!VmIcons.needsVm(icon)) {
                return icon;
            }
            final Context user = userContext(context, shortcut.getUserId());
            if (user == null) {
                return null;
            }
            final Icon cached = VmIcons.cachedSafeIcon(icon, user, owner, maxPx, maxPx);
            if (cached != null) {
                return cached;
            }
            final Supplier<Icon> work = () -> {
                final ImageBudget budget = NotificationImageGate.budgetFor(owner);
                try {
                    return budget.expired() ? null
                            : VmIcons.toSafeIcon(icon, user, owner, maxPx, VmDecode.deadlineIn(budget.remainingMs()));
                } finally {
                    NotificationImageGate.settle(owner, budget, false);
                }
            };
            return wait ? onImageThread(owner, work)
                    : later(owner, VmIcons.cacheKey(icon, user, owner, maxPx + "x" + maxPx), work);
        } catch (RuntimeException e) {
            // A locked user, a removed shortcut: no icon, the caller falls back.
            Log.w(TAG, "shortcut icon not loaded: " + e);
            return null;
        }
    }

    /** {@link #shortcutIcon} without waiting: {@code onLoaded} gets the icon, or null, on {@code executor}. */
    public static void shortcutIconAsync(Context context, LauncherApps launcherApps, ShortcutInfo shortcut,
            int maxPx, Executor executor, Consumer<Icon> onLoaded) {
        final String owner = shortcut == null ? "" : shortcut.getPackage();
        ImageThreads.execute(owner, () -> {
            Icon icon = null;
            try {
                icon = shortcutIcon(context, launcherApps, shortcut, maxPx, true);
            } catch (Throwable e) {
                Log.w(TAG, "shortcut icon not loaded: " + e);
            }
            final Icon result = icon;
            executor.execute(() -> onLoaded.accept(result));
        });
    }

    /**
     * {@link #shortcutIcon} as a drawable, for a worker thread of its own that may wait (bubbles, the people widget).
     * Null when it cannot be made.
     */
    public static Drawable shortcutDrawable(Context context, LauncherApps launcherApps, ShortcutInfo shortcut,
            int maxPx) {
        final Icon icon = shortcutIcon(context, launcherApps, shortcut, maxPx, true);
        if (icon == null) {
            return null;
        }
        return load(context, icon, shortcut.getUserId(), shortcut.getPackage(), maxPx);
    }

    private static Bitmap decodeShortcutFile(Context context, LauncherApps launcherApps, ShortcutInfo shortcut,
            String key, int maxPx) {
        final String owner = shortcut.getPackage();
        final ImageBudget budget = NotificationImageGate.budgetFor(owner);
        try (ParcelFileDescriptor fd = launcherApps.getShortcutIconFd(shortcut)) {
            if (fd == null || budget.expired()) {
                return null;
            }
            // Written by the system from pixels the app gave, decoded in the VM all the same.
            Bitmap bitmap = VmDecode.decode(context, fd, owner, VmDecode.PRIORITY_USER_WAITING, maxPx, maxPx,
                    VmDecode.deadlineIn(budget.remainingMs())).bitmap;
            bitmap = VmIcons.immutable(bitmap);
            VmIcons.cachePut(key, bitmap);
            return bitmap;
        } catch (IOException | VmDecode.UnavailableException | VmDecode.RejectedException | RuntimeException e) {
            Log.w(TAG, "shortcut icon file not decoded: " + e);
            return null;
        } finally {
            NotificationImageGate.settle(owner, budget, false);
        }
    }

    // Icons that would need a decode of an app's bytes here: URI, data and app resource icons.
    private static boolean needsWork(Icon icon) {
        return VmIcons.needsVm(icon) || AppIcons.isAppResource(icon);
    }

    // Makes work's result on an image thread in the background (once at a time, with retries spaced out) and returns
    // null now: the next call finds it in the cache.
    private static <T> T later(String owner, String key, Supplier<T> work) {
        if (key == null || !sMaking.start(key, SystemClock.uptimeMillis())) {
            return null;
        }
        ImageThreads.execute(owner, () -> {
            T made = null;
            try {
                made = work.get();
            } catch (Throwable e) {
                Log.w(TAG, "icon not made: " + e);
            }
            if (made != null) {
                sMaking.succeeded(key);
            } else {
                sMaking.failed(key, SystemClock.uptimeMillis());
            }
        });
        return null;
    }

    // The key a failed wait is remembered by: per user, owner and image.
    private static String failKey(Context context, Icon icon, int userId, String ownerPackage, int maxPx) {
        if (icon.getType() == Icon.TYPE_RESOURCE) {
            return "res:" + userId + ':' + ownerPackage + ':' + icon.getResPackage() + ':' + icon.getResId();
        }
        final Context user = userContext(context, userId);
        return user == null ? null : VmIcons.cacheKey(icon, user, ownerPackage, maxPx + "x" + maxPx);
    }

    // On an image thread: the icon made within the app's quota.
    private static Drawable loadHere(Context context, Icon icon, int userId, String ownerPackage, int maxPx) {
        if (!needsWork(icon)) {
            return icon.loadDrawable(context);
        }
        final ImageBudget budget = NotificationImageGate.budgetFor(ownerPackage);
        try {
            if (icon.getType() == Icon.TYPE_RESOURCE) {
                return AppIcons.loadOnImageThread(context, icon, userId, ownerPackage, budget);
            }
            final Context user = userContext(context, userId);
            if (user == null) {
                return null;
            }
            final long left = budget.remainingMs();
            return left > 0
                    ? VmIcons.loadDrawable(icon, user, ownerPackage, maxPx, VmDecode.deadlineIn(left))
                    : VmIcons.loadDrawableNoWait(icon, user, ownerPackage, maxPx);
        } finally {
            NotificationImageGate.settle(ownerPackage, budget, false);
        }
    }

    // Runs work on an image thread of owner and waits for it, at most WAIT_MS. Null when it fails or is late.
    private static <T> T onImageThread(String owner, Supplier<T> work) {
        if (ImageThreads.isImageThread()) {
            return work.get();
        }
        final CompletableFuture<T> result = new CompletableFuture<>();
        final ImageThreads.Task task = ImageThreads.execute(owner, () -> {
            try {
                result.complete(work.get());
            } catch (Throwable e) {
                Log.w(TAG, "icon not made: " + e);
                result.complete(null);
            }
        });
        try {
            return result.get(WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException | ExecutionException e) {
            task.cancel();
            return null;
        } catch (InterruptedException e) {
            task.cancel();
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private static Context userContext(Context context, int userId) {
        if (userId < 0 || context.getUserId() == userId) {
            return context;
        }
        try {
            return context.createContextAsUser(UserHandle.of(userId), 0);
        } catch (RuntimeException e) {
            // Never another user's URIs or packages in its place.
            return null;
        }
    }
}
