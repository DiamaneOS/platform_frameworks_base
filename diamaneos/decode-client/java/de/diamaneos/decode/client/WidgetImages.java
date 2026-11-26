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

import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.UserHandle;
import android.util.DisplayMetrics;
import android.util.Log;
import android.widget.RemoteViews;

import com.android.internal.diamaneos.cleancopies.CleanCopyGate;

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
 *
 * <p>An update whose images were left out because the decode service did not answer is checked again from the same
 * views once the service answers ({@link VmDecode#addRecoveryListener}), at most {@link #MAX_RECOVERY_CHECKS} times,
 * unless a newer update or {@link #cancel} came first: the blank images fill in without waiting for the app's next
 * update.
 *
 * <p>The launcher uses {@link #forLauncher}: its process gate ({@link CleanCopyGate}) already serves the app's resource
 * images from clean copies, so the host view keeps its own context and nothing is made ahead for it. Icons, links and
 * list items in the update are re-made in the VM as above.
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
    // App resources load through the process's own gate (the launcher's clean copies), not through mContext.
    private final boolean mProcessGate;
    // URIs are checked with checkContentUriPermissionFull (a host without INTERACT_ACROSS_USERS_FULL).
    private final boolean mLauncher;
    private final Object mLock = new Object();
    private AppResourceImages mGate; // guarded by mLock
    private String mOwner = ""; // guarded by mLock
    private int mUserId; // guarded by mLock
    private ApplicationInfo mAppInfo; // the widget app's, when the host was given it, guarded by mLock
    private AtomicBoolean mCancelled = new AtomicBoolean(); // the latest update's, guarded by mLock
    private ImageThreads.Task mTask; // the latest update's, guarded by mLock
    // The latest update when its images were left out for the decode service, to check again once it answers, and how
    // often it was checked again. Guarded by mLock.
    private Runnable mCheckAgain;
    private boolean mWaitForCapacity;
    static final int MAX_RECOVERY_CHECKS = 3;
    private static final RetryTurns<WidgetImages> sLeftOut = new RetryTurns<>(MAX_RECOVERY_CHECKS);
    private static final java.util.Set<WidgetImages> sCapacity =
            java.util.Collections.synchronizedSet(java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>()));
    private static final AtomicBoolean sRecoveryListening = new AtomicBoolean();

    /** For a host view made with {@code themed} (the view's own context, its theme kept). */
    public WidgetImages(Context themed) {
        this(themed, false, false);
    }

    private WidgetImages(Context themed, boolean processGate, boolean launcher) {
        mHost = themed;
        mContext = new GatedPackages(themed);
        mUserId = themed.getUserId();
        mProcessGate = processGate;
        mLauncher = launcher;
    }

    /**
     * For a host view of the launcher made with {@code themed}. While the process has its gate (see
     * {@link CleanCopyGate#isActive}), {@link #context} is {@code themed} itself and the app's resource images come from
     * that gate. Without it, they go through the VM as with {@link #WidgetImages(Context)}. Either way URIs are opened
     * only when the widget's app may read them itself, which the launcher checks with
     * {@link Context#checkContentUriPermissionFull}.
     */
    public static WidgetImages forLauncher(Context themed) {
        return new WidgetImages(themed, CleanCopyGate.isActive(), true);
    }

    /** The context to make the host view with. */
    public Context context() {
        return mProcessGate ? mHost : mContext;
    }

    /**
     * The widget's app, set when the host view is given its widget: the images are charged to it, and its package
     * contexts load through a gate of its own. An update still being checked is dropped: it was for the widget the
     * view showed before.
     */
    public void setApp(String ownerPackage, int userId) {
        setApp(ownerPackage, userId, null);
    }

    /**
     * {@link #setApp(String, int)} for the app {@code appInfo} (the system's provider info of the widget): the checks
     * use it when the package manager does not give the app to this host (a work profile app seen from the launcher).
     */
    public void setApp(ApplicationInfo appInfo) {
        if (appInfo == null) {
            setApp(null, mHost.getUserId(), null);
        } else {
            setApp(appInfo.packageName, UserHandle.getUserId(appInfo.uid), appInfo);
        }
    }

    private void setApp(String ownerPackage, int userId, ApplicationInfo appInfo) {
        synchronized (mLock) {
            cancelLocked();
            final String owner = ownerPackage == null ? "" : ownerPackage;
            mAppInfo = appInfo;
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
        cancelLocked(false);
    }

    private void cancelLocked(boolean keepTurns) {
        mCheckAgain = null;
        if (!keepTurns) sLeftOut.done(this);
        sCapacity.remove(this);
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
        prepareChecked(views, executor, done, null);
    }

    private void prepareChecked(RemoteViews views, Executor executor, Consumer<RemoteViews> done,
            AtomicBoolean expected) {
        final String owner;
        final int userId;
        final AppResourceImages gate;
        final ApplicationInfo appInfo;
        final AtomicBoolean cancelled = new AtomicBoolean();
        synchronized (mLock) {
            if (expected != null && (mCancelled != expected || expected.get())) {
                return;
            }
            cancelLocked(expected != null);
            mCancelled = cancelled;
            owner = mOwner;
            userId = mUserId;
            gate = mGate;
            appInfo = mAppInfo;
        }
        if (views == null || gate == null) {
            deliver(cancelled, executor, done, null);
            return;
        }
        final long capacity = ImageCapacity.version();
        final Runnable retry = () -> prepareChecked(views, executor, done, cancelled);
        final ImageThreads.Task task = ImageThreads.execute(owner, () -> {
            if (cancelled.get()) {
                return;
            }
            final DisplayMetrics metrics = mHost.getResources().getDisplayMetrics();
            final int unavailable = VmDecode.unavailableCount();
            RemoteViews checked = null;
            try {
                checked = check(NotificationImageGate.forNotification(mHost, owner, userId,
                        Math.max(1, (int) (ICON_DP * metrics.density)), pictureWidth(metrics),
                        pictureHeight(metrics), gate), views, owner, userId, appInfo, cancelled);
            } catch (RuntimeException | Error e) {
                Log.w(TAG, "widget views not checked, not shown: " + e);
            }
            final RemoteViews result = checked;
            if (VmDecode.unavailableCount() != unavailable) {
                leftOut(cancelled, false, capacity, retry);
            } else {
                synchronized (mLock) {
                    if (mCancelled == cancelled && !cancelled.get()) sLeftOut.done(this);
                }
            }
            deliver(cancelled, executor, done, result);
        }, () -> sStrip.execute(() -> {
            // Gave way to another app's task: shown without the app's images, never unchecked. The copy is made on a
            // thread of its own, not on the main thread the drop is told on.
            if (cancelled.get()) {
                return;
            }
            final RemoteViews stripped = NoImageViews.withoutImages(views);
            leftOut(cancelled, true, capacity, retry);
            deliver(cancelled, executor, done, stripped);
        }));
        if (task == null) {
            Log.w(TAG, "widget update not checked now: too many image tasks of " + owner);
            leftOut(cancelled, true, capacity, retry);
            deliver(cancelled, executor, done, null);
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

    private void deliver(AtomicBoolean token, Executor executor, Consumer<RemoteViews> done, RemoteViews result) {
        executor.execute(() -> {
            synchronized (mLock) {
                if (mCancelled == token && !token.get()) {
                    done.accept(result);
                }
            }
        });
    }

    // Retry only the generation that registered the missing result.
    private void leftOut(AtomicBoolean token, boolean capacity, long observed, Runnable checkAgain) {
        synchronized (mLock) {
            if (mCancelled != token || token.get()) {
                return;
            }
            mCheckAgain = checkAgain;
            mWaitForCapacity = capacity;
            if (capacity) {
                sCapacity.add(this);
                sLeftOut.done(this);
            } else {
                sCapacity.remove(this);
                sLeftOut.leftOut(this, VmDecode.wakes(), VmDecode.capacityWakes());
            }
        }
        if (sRecoveryListening.compareAndSet(false, true)) {
            VmDecode.addRecoveryListener(() -> checkLeftOutAgain(false));
            ImageCapacity.listen(() -> checkLeftOutAgain(true));
        }
        if (capacity) {
            ImageCapacity.wakeSince(observed);
        }
    }

    private static void checkLeftOutAgain(boolean capacity) {
        final java.util.List<WidgetImages> hosts;
        if (capacity) {
            synchronized (sCapacity) {
                hosts = new java.util.ArrayList<>(sCapacity);
            }
        } else {
            hosts = sLeftOut.due(VmDecode.wakes());
        }
        for (WidgetImages host : hosts) {
            final Runnable again;
            synchronized (host.mLock) {
                if (host.mWaitForCapacity != capacity) {
                    continue;
                }
                again = host.mCheckAgain;
                host.mCheckAgain = null;
                sCapacity.remove(host);
            }
            if (again != null) {
                again.run();
            }
        }
    }

    // On an image thread: the checked copy of views made with imageGate (then finished), or null.
    private RemoteViews check(NotificationImageGate imageGate, RemoteViews views, String owner, int userId,
            ApplicationInfo appInfo, AtomicBoolean cancelled) {
        try {
            imageGate.setCancelled(cancelled);
            final ApplicationInfo ai = appInfo(owner, userId, appInfo);
            // URIs in the update, list items included, are opened only when the widget's app may read them itself,
            // as its user. The host's own access is never lent to them.
            if (mLauncher) {
                final int uid = ai.uid;
                imageGate.setUriCheck(uri -> appMayRead(mHost, uri, uid, userId));
            } else {
                imageGate.checkUrisAs(ai.uid, owner, userId);
            }
            final Context gated = imageGate.packageContext(
                    mHost.createApplicationContext(ai, Context.CONTEXT_RESTRICTED));
            final RemoteViews checked = imageGate.layoutCopy(views);
            if (checked != null && !mProcessGate) {
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

    // The app as the package manager has it now, else {@code given} (a host that may not look up apps of another
    // user).
    private ApplicationInfo appInfo(String owner, int userId, ApplicationInfo given)
            throws PackageManager.NameNotFoundException {
        try {
            return mHost.getPackageManager().getApplicationInfoAsUser(owner, 0, userId);
        } catch (PackageManager.NameNotFoundException | SecurityException e) {
            if (given == null) {
                throw e;
            }
            return given;
        }
    }

    // True when the app {@code uid} may read the content URI {@code uri} itself, as {@code userId}: the provider's
    // permissions and the app's grants, the check the widget service makes on updates. Other schemes and URIs of
    // another user are refused.
    private static boolean appMayRead(Context host, Uri uri, int uid, int userId) {
        if (uri == null || !ContentResolver.SCHEME_CONTENT.equals(uri.getScheme())
                || ContentProvider.getUserIdFromUri(uri, userId) != userId) {
            return false;
        }
        return host.checkContentUriPermissionFull(ContentProvider.maybeAddUserId(uri, userId), -1, uid,
                Intent.FLAG_GRANT_READ_URI_PERMISSION) == PackageManager.PERMISSION_GRANTED;
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
