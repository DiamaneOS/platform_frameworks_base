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
 * app's bitmap files through the image gate (on the main and apply threads only what was made, else empty). Views
 * that cannot be checked are not shown, among them a collection whose items the app's service sends later.
 */
public final class WidgetImages {
    private static final String TAG = "VmDecode";
    private static final int ICON_DP = 128;
    private static final int PICTURE_DP = 320;

    private final Context mHost;
    private final GatedPackages mContext;
    private final Object mLock = new Object();
    private AppResourceImages mGate; // guarded by mLock
    private String mOwner = ""; // guarded by mLock
    private int mUserId; // guarded by mLock
    private AtomicBoolean mCancelled = new AtomicBoolean(); // the latest update's, guarded by mLock

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
     * contexts load through a gate of its own.
     */
    public void setApp(String ownerPackage, int userId) {
        synchronized (mLock) {
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

    /**
     * Checks {@code views} on an image thread and hands the checked copy (or null: show nothing) to {@code done} on
     * {@code executor}. A newer call makes an older one stop and never deliver.
     */
    public void prepare(RemoteViews views, Executor executor, Consumer<RemoteViews> done) {
        final String owner;
        final int userId;
        final AppResourceImages gate;
        final AtomicBoolean cancelled = new AtomicBoolean();
        synchronized (mLock) {
            mCancelled.set(true);
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
            RemoteViews checked = null;
            final DisplayMetrics metrics = mHost.getResources().getDisplayMetrics();
            NotificationImageGate imageGate = null;
            try {
                imageGate = NotificationImageGate.forNotification(mHost, owner, userId,
                        Math.max(1, (int) (ICON_DP * metrics.density)), pictureWidth(metrics),
                        pictureHeight(metrics), gate);
                imageGate.setCancelled(cancelled);
                final ApplicationInfo ai = mHost.getPackageManager().getApplicationInfoAsUser(owner, 0, userId);
                final Context gated = imageGate.packageContext(
                        mHost.createApplicationContext(ai, Context.CONTEXT_RESTRICTED));
                checked = imageGate.layoutCopy(views);
                if (checked != null) {
                    imageGate.prewarmViews(checked, gated);
                }
            } catch (PackageManager.NameNotFoundException | RuntimeException | Error e) {
                Log.w(TAG, "widget views not checked, not shown: " + e);
                checked = null;
            } finally {
                if (imageGate != null) {
                    imageGate.finish(false);
                }
            }
            final RemoteViews result = checked;
            if (!cancelled.get()) {
                executor.execute(() -> {
                    if (!cancelled.get()) {
                        done.accept(result);
                    }
                });
            }
        });
        if (task == null) {
            // Too many image tasks of the app are waiting: this update is not shown, the next one is.
            Log.w(TAG, "widget update not checked now: too many image tasks of " + owner);
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
