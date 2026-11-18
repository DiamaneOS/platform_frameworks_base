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
import android.os.Looper;
import android.os.UserHandle;
import android.util.DisplayMetrics;
import android.util.Log;
import android.widget.RemoteViews;
import android.widget.RemoteViewsAdapter;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * App widgets shown in SystemUI (the glanceable hub and its editor) with nothing of the app's images decoded in
 * SystemUI, like a notification's custom layout: each update is checked on an image thread (URI, data and resource
 * Icons and image links re-made from pixels decoded in the VM, empty when they cannot be made) and the app resource
 * images its layouts name are made ahead there. The host view is built with {@link #context}: its own theme and
 * resources stay as they are, and every package context RemoteViews makes from it for the widget's app loads that
 * app's bitmap files through the image gate (on the main and apply threads only what was made, else empty).
 *
 * <p>A list the app's RemoteViewsService fills is checked row by row: the context is the list adapter's
 * {@link RemoteViewsAdapter.RowCheck}, so each row (and the loading view) is checked on an image thread while the
 * adapter's own worker thread waits, and the loading view shows until then. Rows past {@link #MAX_ROWS} and images
 * past the widget's caps ({@link #MAX_WIDGET_IMAGES}, {@link #MAX_WIDGET_PIXELS}) show without the app's images. A row
 * that cannot be checked is not shown.
 *
 * <p>Only the latest update of a widget waits for an image thread: a newer one, {@link #setApp} and {@link #cancel}
 * drop the older one before it runs and stop it if it does, and it never delivers.
 */
public final class WidgetImages {
    private static final String TAG = "VmDecode";
    private static final int ICON_DP = 128;
    private static final int PICTURE_DP = 320;
    /** Rows of a widget's list whose images are made. Later rows show without the app's images. */
    public static final int MAX_ROWS = 50;
    /** Images of all the rows of one widget's lists together. */
    public static final int MAX_WIDGET_IMAGES = 48;
    /** Pixels of those images together (32 MB in ARGB). */
    public static final long MAX_WIDGET_PIXELS = 8_000_000;
    // How long the list adapter's worker thread waits for a row: a turn on the image threads and the row's own time.
    private static final long ROW_WAIT_MS = ImageBudget.TIME_MS + 3_000;

    private final Context mHost;
    private final GatedPackages mContext;
    private final Object mLock = new Object();
    private AppResourceImages mGate; // guarded by mLock
    private AppResourceImages mRowGate; // the rows' own, guarded by mLock
    private String mOwner = ""; // guarded by mLock
    private int mUserId; // guarded by mLock
    private AtomicBoolean mCancelled = new AtomicBoolean(); // the latest update's, guarded by mLock
    private ImageThreads.Task mTask; // the latest update's, guarded by mLock
    // Images and pixels of each row slot (the rows, then the loading view), guarded by mLock.
    private final int[] mRowImages = new int[MAX_ROWS + 1];
    private final long[] mRowPixels = new long[MAX_ROWS + 1];

    /** For a host view made with {@code themed} (the view's own context, its theme kept). */
    public WidgetImages(Context themed) {
        mHost = themed;
        mContext = new GatedPackages(themed, this);
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
            mRowGate = new AppResourceImages(mHost, owner, userId, new ImageBudget(0, 0, 0), pictureWidth(metrics),
                    pictureHeight(metrics), false);
            java.util.Arrays.fill(mRowImages, 0);
            java.util.Arrays.fill(mRowPixels, 0);
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
        });
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

    /**
     * A row of one of the widget's lists ({@code position} -1: its loading view) as it may be shown, or null. On the
     * list adapter's worker thread, which waits for an image thread. A row that waited too long, came past the caps
     * or found the image threads full shows without the app's images.
     */
    RemoteViews checkRow(RemoteViews row, int position) {
        final int slot = position < 0 ? MAX_ROWS : position;
        final String owner;
        final int userId;
        final AppResourceImages rowGate;
        final int maxImages;
        final long maxPixels;
        synchronized (mLock) {
            owner = mOwner;
            userId = mUserId;
            rowGate = mRowGate;
            if (slot <= MAX_ROWS) {
                maxImages = MAX_WIDGET_IMAGES - sum(mRowImages) + mRowImages[slot];
                maxPixels = MAX_WIDGET_PIXELS - sum(mRowPixels) + mRowPixels[slot];
            } else {
                maxImages = 0;
                maxPixels = 0;
            }
        }
        if (rowGate == null) {
            return null;
        }
        if (maxImages <= 0 || maxPixels <= 0 || Looper.myLooper() == Looper.getMainLooper()
                || ImageThreads.isImageThread()) {
            return NoImageViews.withoutImages(row);
        }
        final AtomicBoolean cancelled = new AtomicBoolean();
        final CountDownLatch finished = new CountDownLatch(1);
        final AtomicReference<RemoteViews> result = new AtomicReference<>();
        final ImageThreads.Task task = ImageThreads.execute(owner, () -> {
            try {
                if (cancelled.get()) {
                    return;
                }
                final DisplayMetrics metrics = mHost.getResources().getDisplayMetrics();
                final NotificationImageGate gate = NotificationImageGate.forWidgetRow(mHost, owner, userId,
                        Math.max(1, (int) (ICON_DP * metrics.density)), pictureWidth(metrics),
                        pictureHeight(metrics), rowGate, maxImages, maxPixels);
                result.set(check(gate, row, owner, userId, cancelled));
                synchronized (mLock) {
                    if (rowGate == mRowGate) {
                        mRowImages[slot] = gate.budget().images();
                        mRowPixels[slot] = gate.budget().pixels();
                    }
                }
            } catch (RuntimeException | Error e) {
                Log.w(TAG, "widget row not checked, not shown: " + e);
            } finally {
                finished.countDown();
            }
        }, () -> {
            // Gave way to another app's task: the row shows without the app's images.
            result.set(NoImageViews.withoutImages(row));
            finished.countDown();
        });
        if (task == null) {
            return NoImageViews.withoutImages(row);
        }
        boolean done;
        try {
            done = finished.await(ROW_WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            done = false;
        }
        if (!done) {
            cancelled.set(true);
            task.cancel();
            Log.w(TAG, "widget row " + position + " of " + owner + " not made in time, without its images");
            return NoImageViews.withoutImages(row);
        }
        return result.get();
    }

    private static int sum(int[] values) {
        int total = 0;
        for (int v : values) {
            total += v;
        }
        return total;
    }

    private static long sum(long[] values) {
        long total = 0;
        for (long v : values) {
            total += v;
        }
        return total;
    }

    private static int pictureWidth(DisplayMetrics metrics) {
        return Math.max(1, Math.min(metrics.widthPixels, metrics.heightPixels));
    }

    private static int pictureHeight(DisplayMetrics metrics) {
        return Math.max(1, (int) (PICTURE_DP * metrics.density));
    }

    // The host view's own context, theme and resources unchanged. Package contexts made from it (RemoteViews makes
    // one for the widget's app) load through the gate. A list adapter made under it checks its rows here.
    private static final class GatedPackages extends ContextWrapper implements RemoteViewsAdapter.RowCheck {
        private final WidgetImages mWidget;
        private volatile AppResourceImages mGate;

        GatedPackages(Context base, WidgetImages widget) {
            super(base);
            mWidget = widget;
        }

        @Override
        public RemoteViews checkRow(RemoteViews row, int position) {
            return mWidget.checkRow(row, position);
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
            final GatedPackages wrapped = new GatedPackages(made, mWidget);
            wrapped.setGate(mGate);
            return wrapped;
        }
    }
}
