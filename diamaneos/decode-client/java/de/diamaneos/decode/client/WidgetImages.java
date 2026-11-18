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
import android.content.ContextWrapper;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.UserHandle;
import android.util.DisplayMetrics;
import android.util.Log;
import android.widget.RemoteViews;

import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * App widgets shown in SystemUI (the glanceable hub and its editor) with nothing of the app's images decoded in
 * SystemUI, like a notification's custom layout: each update is checked on an image thread (URI, data and resource
 * Icons and image links re-made from pixels decoded in the VM, empty when they cannot be made) and the app resource
 * images its layouts name are made ahead there. The host view is built with {@link #context}: its own theme and
 * resources stay as they are, and every package context RemoteViews makes from it for the widget's app loads that
 * app's bitmap files through the image gate (on the main and apply threads only what was made, else empty).
 *
 * <p>A list widget's items come inside the update: the widget's app fetches them from its own RemoteViewsService
 * before it sends the views (the {@code remote_adapter_conversion} flag, which the build requires to be a fixed true),
 * so they are checked with the rest on a copy that shares nothing with the update, and share the update's image caps.
 * Their URIs are opened only when the widget's app may read them itself. Views that would make SystemUI fetch a list
 * from the app's service itself (the older action, or the same by method name or with any Intent) are not shown,
 * whatever the flag says.
 *
 * <p>Only the latest update of a widget waits for an image thread: a newer one, {@link #setApp} and {@link #cancel}
 * drop the older one before it runs and stop it if it does, and it never delivers.
 */
public final class WidgetImages {
    private static final String TAG = "VmDecode";
    private static final int ICON_DP = 128;
    private static final int PICTURE_DP = 320;

    // Copies without the app's images for updates that gave way in a full image lane.
    private static final java.util.concurrent.ExecutorService sStrip =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                final Thread thread = new Thread(r, "WidgetImagesStrip");
                thread.setDaemon(true);
                return thread;
            });

    private final Context mHost;
    private final GatedPackages mContext;
    private final Object mLock = new Object();
    private AppResourceImages mGate; // guarded by mLock
    private String mOwner = ""; // guarded by mLock
    private int mUserId; // guarded by mLock
    private AtomicBoolean mCancelled = new AtomicBoolean(); // the latest update's, guarded by mLock
    private ImageThreads.Task mTask; // the latest update's, guarded by mLock

    /** For a host view made with {@code themed} (the view's own context, its theme kept). */
    public WidgetImages(Context themed) {
        mHost = themed;
        mContext = new GatedPackages(themed);
        mUserId = themed.getUserId();
    }

    /** The context to make the host view with. */
    public Context context() {
        return mContext;
    }

    /**
     * The widget's app, set when the host view is given its widget: the images are charged to it, and its package
     * contexts load through a gate of its own. An update still being checked is dropped: it was for the widget the
     * view showed before.
     */
    public void setApp(String ownerPackage, int userId) {
        synchronized (mLock) {
            cancelLocked();
            final String owner = ownerPackage == null ? "" : ownerPackage;
            if (mGate != null && owner.equals(mOwner) && userId == mUserId) {
                return;
            }
            mOwner = owner;
            mUserId = userId;
            final DisplayMetrics metrics = mHost.getResources().getDisplayMetrics();
            mGate = new AppResourceImages(mHost, owner, userId, new ImageBudget(0, 0, 0), pictureWidth(metrics),
                    pictureHeight(metrics), false);
            mContext.setGate(mGate);
        }
    }

    /** Drops the update being checked: it never delivers. For a view that is detached or shows nothing. */
    public void cancel() {
        synchronized (mLock) {
            cancelLocked();
        }
    }

    private void cancelLocked() {
        mCancelled.set(true);
        if (mTask != null) {
            mTask.cancel();
            mTask = null;
        }
    }

    /**
     * Checks {@code views} on an image thread and hands the checked copy (or null: show nothing) to {@code done} on
     * {@code executor}. A newer call drops an older one: it never runs if it has not started, stops if it has, and
     * never delivers.
     */
    public void prepare(RemoteViews views, Executor executor, Consumer<RemoteViews> done) {
        final String owner;
        final int userId;
        final AppResourceImages gate;
        final AtomicBoolean cancelled = new AtomicBoolean();
        synchronized (mLock) {
            cancelLocked();
            mCancelled = cancelled;
            owner = mOwner;
            userId = mUserId;
            gate = mGate;
        }
        if (views == null || gate == null) {
            executor.execute(() -> done.accept(null));
            return;
        }
        final ImageThreads.Task task = ImageThreads.execute(owner, () -> {
            if (cancelled.get()) {
                return;
            }
            final DisplayMetrics metrics = mHost.getResources().getDisplayMetrics();
            RemoteViews checked = null;
            try {
                checked = check(NotificationImageGate.forNotification(mHost, owner, userId,
                        Math.max(1, (int) (ICON_DP * metrics.density)), pictureWidth(metrics),
                        pictureHeight(metrics), gate), views, owner, userId, cancelled);
            } catch (RuntimeException | Error e) {
                Log.w(TAG, "widget views not checked, not shown: " + e);
            }
            final RemoteViews result = checked;
            if (!cancelled.get()) {
                executor.execute(() -> {
                    if (!cancelled.get()) {
                        done.accept(result);
                    }
                });
            }
        }, () -> sStrip.execute(() -> {
            // Gave way to another app's task: shown without the app's images, never unchecked. The copy is made on a
            // thread of its own, not on the main thread the drop is told on.
            if (cancelled.get()) {
                return;
            }
            final RemoteViews stripped = NoImageViews.withoutImages(views);
            executor.execute(() -> {
                if (!cancelled.get()) {
                    done.accept(stripped);
                }
            });
        }));
        if (task == null) {
            // Too many image tasks are waiting: this update is not shown, the next one is.
            Log.w(TAG, "widget update not checked now: too many image tasks of " + owner);
            return;
        }
        synchronized (mLock) {
            if (mCancelled == cancelled) {
                mTask = task;
            } else {
                task.cancel();
            }
        }
    }

    // On an image thread: the checked copy of views made with imageGate (then finished), or null.
    private RemoteViews check(NotificationImageGate imageGate, RemoteViews views, String owner, int userId,
            AtomicBoolean cancelled) {
        try {
            imageGate.setCancelled(cancelled);
            final ApplicationInfo ai = mHost.getPackageManager().getApplicationInfoAsUser(owner, 0, userId);
            // URIs in the update, list items included, are opened only when the widget's app may read them itself,
            // as its user. SystemUI's own access is never lent to them.
            final android.app.IUriGrantsManager grants = android.app.UriGrantsManager.getService();
            imageGate.setUriCheck(uri -> android.content.ContentProvider.getUserIdFromUri(uri, userId) == userId
                    && ForeignIcons.uriGranted(android.graphics.drawable.Icon.createWithContentUri(uri), grants,
                            ai.uid, owner, userId));
            final Context gated = imageGate.packageContext(
                    mHost.createApplicationContext(ai, Context.CONTEXT_RESTRICTED));
            final RemoteViews checked = imageGate.layoutCopy(views);
            if (checked != null) {
                imageGate.prewarmViews(checked, gated);
            }
            return checked;
        } catch (PackageManager.NameNotFoundException | RuntimeException | Error e) {
            Log.w(TAG, "widget views not checked, not shown: " + e);
            return null;
        } finally {
            imageGate.finish(false);
        }
    }

    private static int pictureWidth(DisplayMetrics metrics) {
        return Math.max(1, Math.min(metrics.widthPixels, metrics.heightPixels));
    }

    private static int pictureHeight(DisplayMetrics metrics) {
        return Math.max(1, (int) (PICTURE_DP * metrics.density));
    }

    // The host view's own context, theme and resources unchanged. Package contexts made from it (RemoteViews makes
    // one for the widget's app) load through the gate.
    private static final class GatedPackages extends ContextWrapper {
        private volatile AppResourceImages mGate;

        GatedPackages(Context base) {
            super(base);
        }

        void setGate(AppResourceImages gate) {
            mGate = gate;
        }

        private Context gated(Context made) {
            final AppResourceImages gate = mGate;
            if (gate == null) {
                // No widget set yet: no app's resources may be loaded through this context.
                throw new IllegalStateException("no image gate for the widget's app yet");
            }
            return VmImageContext.wrap(made, gate);
        }

        @Override
        public Context createApplicationContext(ApplicationInfo application, int flags)
                throws PackageManager.NameNotFoundException {
            return gated(super.createApplicationContext(application, flags));
        }

        @Override
        public Context createPackageContext(String packageName, int flags)
                throws PackageManager.NameNotFoundException {
            return gated(super.createPackageContext(packageName, flags));
        }

        @Override
        public Context createPackageContextAsUser(String packageName, int flags, UserHandle user)
                throws PackageManager.NameNotFoundException {
            return gated(super.createPackageContextAsUser(packageName, flags, user));
        }

        @Override
        public Context createContextAsUser(UserHandle user, int flags) {
            final Context made = super.createContextAsUser(user, flags);
            final GatedPackages wrapped = new GatedPackages(made);
            wrapped.setGate(mGate);
            return wrapped;
        }
    }
}
