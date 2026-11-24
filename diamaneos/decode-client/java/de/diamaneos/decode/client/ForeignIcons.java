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
 * <p>No caller waits for an image thread. Two shapes: {@link #loadNoWait} and {@link #safeIconNoWait} give what was
 * made before and make the rest in the background, telling the caller when it is ready, and {@link #loadAsync} hands
 * the result to a callback (suspending callers). An image the VM refused, or one that failed, is not made again until
 * its retry time.
 */
public final class ForeignIcons {
    private static final String TAG = "VmDecode";

    /**
     * The owner of the keyboard shortcut helper's icons. The focused app names their resource package, which says
     * nothing about who sent them, so they are charged to this one bounded lane (its image thread turn, quota and
     * decode lane), never to the package they name: that package is only where the image is looked up.
     */
    public static final String SHORTCUT_HELPER = VmDecode.unknownOwner("shortcut-helper");

    // Tests on debuggable builds: the owners image work was charged to on image threads, newest last.
    private static volatile java.util.ArrayDeque<String> sChargedForTest;
    // How long a caller waits for an image thread, the image's own time included.
    private static final long WAIT_MS = ImageBudget.TIME_MS + 1_000;

    // Icons being made in the background, or failed and waiting for their next try.
    private static final Backoff sMaking = new Backoff();
    // Everyone waiting for an icon being made: all are told, not only the first.
    private static final Waiters sWaiters = new Waiters();
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
        return uriGranted(icon, uriGrantsManager, callingUid, packageName,
                android.os.UserHandle.getUserId(callingUid));
    }

    /**
     * {@link #uriGranted(Icon, IUriGrantsManager, int, String)} for a URI opened as {@code userId}: a URI without a
     * user of its own is checked for that user, the one it is opened as.
     */
    public static boolean uriGranted(Icon icon, IUriGrantsManager uriGrantsManager, int callingUid,
            String packageName, int userId) {
        if (icon == null
                || (icon.getType() != Icon.TYPE_URI && icon.getType() != Icon.TYPE_URI_ADAPTIVE_BITMAP)) {
            return true;
        }
        try {
            uriGrantsManager.checkGrantUriPermission_ignoreNonSystem(callingUid, packageName,
                    ContentProvider.getUriWithoutUserId(icon.getUri()), Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    ContentProvider.getUserIdFromUri(icon.getUri(), userId));
            return true;
        } catch (SecurityException | RemoteException e) {
            Log.w(TAG, "no grant for the icon's URI, not loaded: " + e);
            return false;
        }
    }

    /**
     * {@link #loadNoWait}, or the neutral icon while the image is not made, for a view that cannot wait and may not
     * be told when it is made (onMade may be null).
     */
    public static Drawable loadOrNeutral(Context context, Icon icon, int userId, String ownerPackage, int maxPx,
            Runnable onMade) {
        final Drawable made = loadNoWait(context, icon, userId, ownerPackage, maxPx, onMade);
        return made != null ? made : AppIcons.neutral().loadDrawable(context);
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
        if (!NotificationImageGate.needsGate(icon)) {
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
            makeLater(context, user, icon, userId, ownerPackage, maxPx, onMade);
        }
        return drawable;
    }

    // Decodes a URI or data icon in the VM on an image thread for the next time, once at a time, spaced out after a
    // failure and never again for an image the VM refused. onMade runs on the main thread when it is made.
    private static void makeLater(Context context, Context user, Icon icon, int userId, String ownerPackage,
            int maxPx, Runnable onMade) {
        final String key = VmIcons.stableKey(VmIcons.cacheKey(icon, user, ownerPackage, maxPx + "x" + maxPx));
        if (key == null || VmIcons.wasRejected(key)) {
            return;
        }
        later(ownerPackage, key, () -> loadHere(context, icon, userId, ownerPackage, maxPx), onMade);
    }

    /**
     * {@code icon} as an Icon that never needs a decode of the app's bytes when it is loaded later, without waiting:
     * pixels as they are, a URI or data icon as a bitmap icon the VM made before, an app resource icon as a copy that
     * loads through the image gate (its images made). Null while it is being made: {@code onMade} runs on the main
     * thread once it is. For callers that keep an Icon (the media pipeline).
     */
    public static Icon safeIconNoWait(Context context, Icon icon, int userId, String ownerPackage, int maxPx,
            Runnable onMade) {
        if (icon == null || !NotificationImageGate.needsGate(icon)) {
            return icon;
        }
        if (icon.getType() == Icon.TYPE_RESOURCE) {
            return AppIcons.forViewOrNull(context, icon, userId, ownerPackage, onMade);
        }
        final Context user = userContext(context, userId);
        if (user == null) {
            return null;
        }
        final Icon cached = VmIcons.cachedSafeIcon(icon, user, ownerPackage, maxPx, maxPx);
        if (cached == null) {
            makeLater(context, user, icon, userId, ownerPackage, maxPx, onMade);
        }
        return cached;
    }

    /**
     * {@code icon} made on an image thread: {@code onLoaded} gets the drawable, or null, on {@code executor}. For
     * callers that suspend until it is there.
     */
    public static void loadAsync(Context context, Icon icon, int userId, String ownerPackage, int maxPx,
            Executor executor, Consumer<Drawable> onLoaded) {
        if (icon == null) {
            executor.execute(() -> onLoaded.accept(null));
            return;
        }
        final ImageThreads.Task task = ImageThreads.execute(ownerPackage, () -> {
            Drawable drawable = null;
            try {
                drawable = loadHere(context, icon, userId, ownerPackage, maxPx);
            } catch (Throwable e) {
                Log.w(TAG, "icon not loaded: " + e);
            }
            final Drawable result = drawable;
            executor.execute(() -> onLoaded.accept(result));
        }, () -> executor.execute(() -> onLoaded.accept(null)));
        if (task == null) {
            executor.execute(() -> onLoaded.accept(null));
        }
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
        return shortcutIcon(context, launcherApps, shortcut, maxPx, mayWait, null);
    }

    /**
     * {@link #shortcutIcon}: when it does not wait and the icon is not made yet, {@code onMade} (if not null) runs on
     * the main thread once it is.
     */
    public static Icon shortcutIcon(Context context, LauncherApps launcherApps, ShortcutInfo shortcut, int maxPx,
            boolean mayWait, Runnable onMade) {
        if (shortcut == null) {
            return null;
        }
        final String owner = shortcut.getPackage();
        final boolean wait = mayWait && Looper.myLooper() != Looper.getMainLooper();
        try {
            if (shortcut.hasIconFile()) {
                final String key = "shortcut:" + shortcut.getUserId() + ':' + owner + ':'
                        + VmIcons.bounded(shortcut.getId()) + ':' + shortcut.getLastChangedTimestamp() + ':' + maxPx;
                Bitmap bitmap = VmIcons.cacheGet(key);
                if (bitmap == null) {
                    final Supplier<Bitmap> work =
                            () -> decodeShortcutFile(context, launcherApps, shortcut, key, maxPx);
                    bitmap = wait ? onImageThread(owner, work) : later(owner, key, work, onMade);
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
                    : later(owner, VmIcons.stableKey(VmIcons.cacheKey(icon, user, owner, maxPx + "x" + maxPx)), work,
                            onMade);
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
        final ImageThreads.Task task = ImageThreads.execute(owner, () -> {
            Icon icon = null;
            try {
                icon = shortcutIcon(context, launcherApps, shortcut, maxPx, true);
            } catch (Throwable e) {
                Log.w(TAG, "shortcut icon not loaded: " + e);
            }
            final Icon result = icon;
            executor.execute(() -> onLoaded.accept(result));
        }, () -> executor.execute(() -> onLoaded.accept(null)));
        if (task == null) {
            executor.execute(() -> onLoaded.accept(null));
        }
    }

    /**
     * {@link #shortcutIcon} as a drawable, never waiting: what was made before (the shortcut's icon, then its images),
     * the rest made in the background for the next time. Null while it is being made.
     */
    public static Drawable shortcutDrawableNoWait(Context context, LauncherApps launcherApps, ShortcutInfo shortcut,
            int maxPx) {
        return shortcutDrawableNoWait(context, launcherApps, shortcut, maxPx, null);
    }

    /** {@link #shortcutDrawableNoWait}, and {@code onMade} runs on the main thread once what was missing is made. */
    public static Drawable shortcutDrawableNoWait(Context context, LauncherApps launcherApps, ShortcutInfo shortcut,
            int maxPx, Runnable onMade) {
        final Icon icon = shortcutIcon(context, launcherApps, shortcut, maxPx, false, onMade);
        if (icon == null) {
            return null;
        }
        return loadNoWait(context, icon, shortcut.getUserId(), shortcut.getPackage(), maxPx, onMade);
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
            Bitmap bitmap = VmDecode.decode(context, fd, owner, shortcut.getUserId(), VmDecode.PRIORITY_USER_WAITING,
                    maxPx, maxPx, VmDecode.deadlineIn(budget.remainingMs())).bitmap;
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

    // Makes work's result on an image thread in the background (once at a time, with retries spaced out) and returns
    // null now: the next call finds it in the cache. Everyone who asked meanwhile with a callback is told on the main
    // thread once it is made.
    private static <T> T later(String owner, String key, Supplier<T> work, Runnable onMade) {
        if (key == null) {
            return null;
        }
        askAgainLater(sWaiters.add(key, owner, null, onMade));
        if (!sMaking.start(key, SystemClock.uptimeMillis())) {
            return null;
        }
        final ImageThreads.Task task = ImageThreads.execute(owner, () -> {
            T made = null;
            VmIcons.beginKeptTracking();
            try {
                made = work.get();
            } catch (Throwable e) {
                Log.w(TAG, "icon not made: " + e);
            }
            final java.util.List<Waiters.Waiter> waiters = sWaiters.take(key);
            if (made != null && VmIcons.notKept()) {
                // Too big for the cache: whoever asks again would not find it, so nobody is told (no loop of makes),
                // and it is made again only after the backoff's wait.
                sMaking.failed(key, SystemClock.uptimeMillis());
            } else if (made != null) {
                sMaking.succeeded(key);
                for (Waiters.Waiter w : waiters) {
                    sMain.post(() -> Waiters.runSafely(w.onMade));
                }
            } else {
                sMaking.failed(key, SystemClock.uptimeMillis());
            }
        }, () -> {
            // Gave way to another app's task: its askers are told after the backoff's wait, so they ask again.
            final java.util.List<Waiters.Waiter> waiters = sWaiters.take(key);
            final long wait = sMaking.failed(key, SystemClock.uptimeMillis());
            sMain.postDelayed(() -> sWaiters.tell(waiters), wait);
        });
        if (task == null) {
            sWaiters.take(key);
            sMaking.failed(key, SystemClock.uptimeMillis());
        }
        return null;
    }

    // On an image thread: the icon made within the app's quota.
    private static Drawable loadHere(Context context, Icon icon, int userId, String ownerPackage, int maxPx) {
        if (!NotificationImageGate.needsGate(icon)) {
            return icon.loadDrawable(context);
        }
        final java.util.ArrayDeque<String> charged = sChargedForTest;
        if (charged != null) {
            synchronized (charged) {
                charged.addLast(String.valueOf(ownerPackage));
            }
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
        }, () -> result.complete(null));
        if (task == null) {
            return null;
        }
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

    // Askers a waiter cap let go: told after a while on the main thread, so they ask again.
    private static void askAgainLater(java.util.List<Waiters.Waiter> evicted) {
        if (evicted.isEmpty()) {
            return;
        }
        sMain.postDelayed(() -> sWaiters.tell(evicted), Waiters.EVICTED_RETRY_MS);
    }

    /**
     * Tests on debuggable builds: from now on (true) records the owners image work is charged to on image threads,
     * and returns those recorded so far, oldest first. False stops recording.
     */
    public static java.util.List<String> chargedForTest(boolean record) {
        if (!android.os.Build.IS_DEBUGGABLE) {
            throw new SecurityException("debuggable builds only");
        }
        final java.util.ArrayDeque<String> was = sChargedForTest;
        sChargedForTest = record ? new java.util.ArrayDeque<>() : null;
        if (was == null) {
            return new java.util.ArrayList<>();
        }
        synchronized (was) {
            return new java.util.ArrayList<>(was);
        }
    }
}
