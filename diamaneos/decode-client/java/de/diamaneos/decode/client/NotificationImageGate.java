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

import android.app.Notification;
import android.app.Person;
import android.app.RemoteInput;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Bundle;
import android.os.Process;
import android.os.SystemClock;
import android.os.UserHandle;
import android.util.Log;
import android.util.LruCache;
import android.widget.RemoteViews;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/**
 * The images of one notification in SystemUI, for one inflation of its views. Two parts:
 *
 * <ul>
 * <li>{@link #sanitizedCopy}: a copy of the notification in which no image needs decoding from another app's
 * compressed bytes. Every icon given as a URI or as createWithData bytes becomes a bitmap icon decoded in the VM, or is
 * removed when that fails: the small and large icons, the big picture and big large icon, the conversation,
 * verification and progress icons, every Person, action icons and the public version. The app's own layouts
 * (RemoteViews: content, big, heads-up and public) are kept and their images re-made in place: URI, data and app
 * resource Icons and {@code setImageViewUri} become bitmaps decoded in the VM, nested layouts included. An image that
 * cannot be made becomes an empty bitmap. Only a layout that cannot be read or converted is dropped.
 * <li>{@link #packageContext}: the context the layouts are applied with. Its resources decode every bitmap file of
 * an APK outside the system image in the VM ({@link AppResourceImages}): resource IDs in actions, drawables named in
 * the app's layout XML and bitmap files inside its XML drawables.
 * </ul>
 *
 * <p>The image work of one notification has {@link #BUDGET_MS} of wall-clock time, checked in every loop, and each
 * app has an {@link OwnerQuota} across its notifications: the time is reserved before the work and settled after it,
 * so an app whose work overran waits its debt out. A VM or provider that hangs costs images, not a stuck or missing
 * notification. The images of its layouts are capped ({@link ImageBudget#MAX_IMAGES} and
 * {@link ImageBudget#MAX_PIXELS}) and so are the pixels each app holds across its notifications ({@link OwnerPixels}):
 * anything over the caps is empty. Every decode is charged to the posting app, and URIs are opened as the
 * notification's user.
 *
 * <p>The VM is asked only on {@link ImageThreads}: the caller makes the copy and {@link #prewarm}s the layouts there,
 * before it inflates on its own thread. Anywhere else (the inflation thread, the main thread) nothing is decoded:
 * images come from what the image threads made, or are empty or removed. A notification whose only app images are
 * resource icons known to name no bitmap file of the app (vectors, files of the system image) needs no image thread
 * at all ({@link #lane}).
 */
public final class NotificationImageGate {
    private static final String TAG = "VmDecode";

    // Message bundle key of the sender (Notification.MessagingStyle.Message.KEY_SENDER_PERSON).
    private static final String KEY_SENDER_PERSON = "sender_person";

    private static final String[] ICON_EXTRAS = {
            Notification.EXTRA_LARGE_ICON,
            Notification.EXTRA_LARGE_ICON_BIG,
            Notification.EXTRA_PICTURE_ICON,
            Notification.EXTRA_CONVERSATION_ICON,
            Notification.EXTRA_VERIFICATION_ICON,
            Notification.EXTRA_PROGRESS_TRACKER_ICON,
            Notification.EXTRA_PROGRESS_START_ICON,
            Notification.EXTRA_PROGRESS_END_ICON,
    };

    /** The wall-clock time the image work of one notification may take. */
    public static final long BUDGET_MS = ImageBudget.TIME_MS;

    // Resource icons loaded per notification for its standard fields (small, large, actions, people), and drawables
    // and layouts read ahead for its custom layouts: anything past them is removed or left to the inflation.
    private static final int MAX_RESOURCE_LOADS = 32;
    private static final int MAX_PREWARM_DRAWABLES = 32;
    private static final int MAX_PREWARM_LAYOUTS = 8;
    // People and message senders per notification that keep an app image.
    private static final int MAX_PEOPLE_ICONS = 32;

    // Time each app may keep the image threads busy across its notifications, and the pixels it holds.
    private static final OwnerQuota sQuota = new OwnerQuota();
    private static final OwnerPixels sPixels = new OwnerPixels();
    // App resource icons known to load without any bitmap file of an app (a vector, the system image's own files),
    // by user, package, resource, APK and configuration: such an icon needs no image thread.
    private static final LruCache<String, Boolean> sClean = new LruCache<>(1024);

    private final Context mContext;
    private Context mUserContext; // made when first needed
    private boolean mUserContextMade;
    private final String mOwner;
    private final int mUserId;
    private final int mIconPx;
    private final int mPictureWidth;
    private final int mPictureHeight;
    private final ImageBudget mBudget;
    private final AppResourceImages mResources;
    private final RemoteViewsImages.Images mLayoutImages = new LayoutImages();
    private Context mAppContext; // the gated context of the posting app, once made
    private boolean mLayoutsAllowed;
    private boolean mStrip; // remove every app image, decode nothing
    private boolean mTemplateOnly; // the caller shows no custom layout
    private BooleanSupplier mCancelled; // the work is no longer wanted
    private final boolean mReserved; // the budget's time was taken off the app's quota
    private boolean mFinished;
    private String mSourceDir; // the posting app's APK, for the known clean icons
    private int mResourceLoads;
    private int mLayoutResourceIcons;
    private int mPrewarmDrawables;
    private int mPrewarmLayouts;
    private final java.util.Set<String> mPeopleIconKeys = new java.util.HashSet<>();

    private NotificationImageGate(Context context, String ownerPackage, int userId, int iconPx, int pictureWidth,
            int pictureHeight, ImageBudget budget, boolean reserved, AppResourceImages previous) {
        mContext = context;
        mReserved = reserved;
        mOwner = ownerPackage == null ? "" : ownerPackage;
        mUserId = userId;
        mIconPx = Math.max(1, iconPx);
        mPictureWidth = Math.max(1, pictureWidth);
        mPictureHeight = Math.max(1, pictureHeight);
        mBudget = budget;
        if (previous != null && mOwner.equals(previous.owner())) {
            // The same row: its views keep the resources this gate is set on, so it carries on with a new round.
            previous.startRound(budget, mPictureWidth, mPictureHeight);
            mResources = previous;
        } else {
            mResources = new AppResourceImages(context, mOwner, userId < 0 ? context.getUserId() : userId, budget,
                    mPictureWidth, mPictureHeight, false);
        }
    }

    // URIs of the notification are opened as its user, with SystemUI's grants for that user. Made when first
    // needed, so a gate that decodes nothing (the group header on the main thread) never makes one.
    private Context userContext() {
        if (!mUserContextMade) {
            mUserContextMade = true;
            if (mUserId < 0 || mContext.getUserId() == mUserId) {
                mUserContext = mContext;
            } else {
                try {
                    mUserContext = mContext.createContextAsUser(UserHandle.of(mUserId), 0);
                } catch (RuntimeException e) {
                    Log.w(TAG, "no context for user " + mUserId + ": " + e);
                    mUserContext = null;
                }
            }
        }
        return mUserContext;
    }

    // A fresh budget for image work on an image thread: its time is reserved off the posting app's quota now, before
    // the work, and settled after it (settle). An app in debt gets no time: only images already made.
    static ImageBudget budgetFor(String owner) {
        final String key = owner == null ? "" : owner;
        final long time = sQuota.reserve(key, ImageBudget.TIME_MS, SystemClock.uptimeMillis());
        return new ImageBudget(time, ImageBudget.MAX_IMAGES, ImageBudget.MAX_PIXELS, ImageBudget.CLOCK, sPixels, key);
    }

    // A budget for work off the image threads, where nothing is decoded: the same caps and wall-clock time, not taken
    // off the app's quota.
    private static ImageBudget budgetOffImageThreads(String owner) {
        final String key = owner == null ? "" : owner;
        return new ImageBudget(ImageBudget.TIME_MS, ImageBudget.MAX_IMAGES, ImageBudget.MAX_PIXELS,
                ImageBudget.CLOCK, sPixels, key);
    }

    /**
     * Image work done with {@code budget} (from {@link #budgetFor}) is over: the time it really took is settled with
     * {@code ownerPackage}'s quota, the part it did not use is given back, an overrun becomes debt. Work in the urgent
     * lane counts twice.
     */
    static void settle(String ownerPackage, ImageBudget budget, boolean urgent) {
        final long used = budget.elapsedMs() * (urgent ? 2 : 1);
        sQuota.settle(ownerPackage == null ? "" : ownerPackage, budget.grantedMs(), used,
                SystemClock.uptimeMillis());
    }

    /**
     * The image work of this gate is over (call it once, also after a failure): the time it took is settled with the
     * app's quota when it was taken from it. {@code urgent}: it ran in the urgent lane, which counts twice.
     */
    public void finish(boolean urgent) {
        if (mReserved && !mFinished) {
            mFinished = true;
            settle(mOwner, mBudget, urgent);
        }
    }

    /** Where the image work of a notification runs. */
    public enum Lane {
        /** No app image at all: no image thread needed. */
        NONE,
        /** Calls, alarms and other urgent notifications: the urgent image threads. */
        URGENT,
        /** Everything else: the normal image threads. */
        NORMAL,
    }

    /**
     * The lane for {@code n} posted by {@code ownerPackage} ({@code uid}) for {@code userId}. Cheap: reads the
     * notification and what is known about its icons, loads nothing. NONE when no app image needs an image thread:
     * pixels, framework icons and app resource icons known to name no bitmap file of the app. URGENT only for what
     * the app cannot claim for itself: a system process, or a call or alarm of an app on the system image.
     * {@code context} is SystemUI's (its configuration picks the resources).
     */
    public static Lane lane(Context context, Notification n, String ownerPackage, int uid, int userId) {
        try {
            final Clean clean = new Clean(context, ownerPackage, userId, sourceDir(n));
            if (!hasAppImages(n, 0, clean)) {
                return Lane.NONE;
            }
        } catch (RuntimeException e) {
            // Extras that cannot be read: the image work removes them, on an image thread.
            Log.w(TAG, "notification images not looked at here: " + e);
        }
        return isUrgent(context, n, ownerPackage, uid, userId) ? Lane.URGENT : Lane.NORMAL;
    }

    // Only what an app cannot claim for itself: a system process, or a call or an alarm of an app on the system
    // image (the phone, the clock). Nothing an app can set or a normal permission can give counts: not a category,
    // a call person, a full-screen intent (on this build any app that lists USE_FULL_SCREEN_INTENT holds it), a
    // foreground service, or a self-managed call (MANAGE_OWN_CALLS is a normal permission). No binder call here.
    private static boolean isUrgent(Context context, Notification n, String ownerPackage, int uid, int userId) {
        if (UserHandle.getAppId(uid) < Process.FIRST_APPLICATION_UID) {
            // A system notification that shows another app's icon (the overlay window notice, the accessibility
            // warning) is posted at a moment that app chooses: its image is normal work.
            return !showsOtherAppsIcon(n, ownerPackage);
        }
        final boolean callOrAlarm = n.isStyle(Notification.CallStyle.class)
                || Notification.CATEGORY_CALL.equals(n.category)
                || Notification.CATEGORY_ALARM.equals(n.category);
        return callOrAlarm && isSystemImageApp(n);
    }

    // A small or large icon that is a resource of a package other than the framework and the poster.
    private static boolean showsOtherAppsIcon(Notification n, String ownerPackage) {
        for (Icon icon : new Icon[] {n.getSmallIcon(), n.getLargeIcon()}) {
            if (icon != null && icon.getType() == Icon.TYPE_RESOURCE) {
                final String pkg = icon.getResPackage();
                if (pkg != null && !pkg.isEmpty() && !"android".equals(pkg) && !pkg.equals(ownerPackage)) {
                    return true;
                }
            }
        }
        return false;
    }

    // The posting app is on the system image, as the notification service recorded it in the notification.
    private static boolean isSystemImageApp(Notification n) {
        if (n.extras == null) {
            return false;
        }
        try {
            final ApplicationInfo ai = n.extras.getParcelable(Notification.EXTRA_BUILDER_APPLICATION_INFO,
                    ApplicationInfo.class);
            return ai != null && (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
        } catch (RuntimeException e) {
            return false;
        }
    }

    // The APK of the posting app, as the notification service and SystemUI recorded it in the notification.
    private static String sourceDir(Notification n) {
        if (n.extras == null) {
            return null;
        }
        try {
            final ApplicationInfo ai = n.extras.getParcelable(Notification.EXTRA_BUILDER_APPLICATION_INFO,
                    ApplicationInfo.class);
            return ai == null ? null : ai.sourceDir;
        } catch (RuntimeException e) {
            return null;
        }
    }

    // What is needed to look up and record icons known to name no app bitmap file.
    private static final class Clean {
        final String owner;
        final int userId;
        final String sourceDir;
        final String configKey;

        Clean(Context context, String owner, int userId, String sourceDir) {
            this.owner = owner == null ? "" : owner;
            this.userId = userId;
            this.sourceDir = sourceDir;
            this.configKey = AppIcons.configKey(context.getResources().getConfiguration());
        }

        // Only the posting app's own resources: its APK is known.
        String key(Icon icon) {
            final String pkg = icon.getResPackage();
            if (sourceDir == null || (pkg != null && !pkg.isEmpty() && !pkg.equals(owner))) {
                return null;
            }
            return userId + ":" + owner + ":" + icon.getResId() + ":" + sourceDir + ":" + configKey;
        }

        boolean known(Icon icon) {
            final String key = key(icon);
            return key != null && sClean.get(key) != null;
        }
    }

    // Any image that needs an image thread: an icon that is not pixels, the framework's or known clean, a custom
    // layout, people and messages.
    private static boolean hasAppImages(Notification n, int depth, Clean clean) {
        if (n.contentView != null || n.bigContentView != null || n.headsUpContentView != null
                || n.tickerView != null) {
            return true;
        }
        if (needsImageThread(n.getSmallIcon(), clean) || needsImageThread(n.getLargeIcon(), clean)) {
            return true;
        }
        if (n.actions != null) {
            for (Notification.Action action : n.actions) {
                if (action != null && needsImageThread(action.getIcon(), clean)) {
                    return true;
                }
            }
        }
        if (n.extras != null && !n.extras.isEmpty()) {
            for (String key : ICON_EXTRAS) {
                if (n.extras.containsKey(key)
                        && needsImageThread(n.extras.getParcelable(key, Icon.class), clean)) {
                    return true;
                }
            }
            // People and messages: looked at by the image work, not here.
            if (n.extras.containsKey(Notification.EXTRA_MESSAGES)
                    || n.extras.containsKey(Notification.EXTRA_HISTORIC_MESSAGES)
                    || n.extras.containsKey(Notification.EXTRA_MESSAGING_PERSON)
                    || n.extras.containsKey(Notification.EXTRA_CALL_PERSON)
                    || n.extras.containsKey(Notification.EXTRA_PEOPLE_LIST)) {
                return true;
            }
        }
        return n.publicVersion != null && depth == 0 && hasAppImages(n.publicVersion, depth + 1, clean);
    }

    private static boolean needsImageThread(Icon icon, Clean clean) {
        if (icon == null || icon.getType() == Icon.TYPE_BITMAP || icon.getType() == Icon.TYPE_ADAPTIVE_BITMAP) {
            return false;
        }
        if (icon.getType() != Icon.TYPE_RESOURCE) {
            return true;
        }
        return !"android".equals(icon.getResPackage()) && !clean.known(icon);
    }

    /**
     * While {@code cancelled} is true, nothing more is decoded: the notification is gone or replaced. Only the flag is
     * kept, never whoever sets it: the gate lives as long as the row's views, and must not keep the row or its task.
     */
    public void setCancelled(AtomicBoolean cancelled) {
        final BooleanSupplier check = cancelled == null ? null : cancelled::get;
        mCancelled = check;
        mResources.setCancelled(check);
    }

    // True when the work should stop: cancelled, or its time is up.
    private boolean stopped() {
        return (mCancelled != null && mCancelled.getAsBoolean()) || mBudget.expired();
    }

    /**
     * The gate for one inflation of a notification posted by {@code ownerPackage} for {@code userId}.
     * {@code context} is SystemUI's own. {@code iconPx} bounds icons and people, {@code pictureWidth} x
     * {@code pictureHeight} the big picture and the images of the app's layouts.
     */
    public static NotificationImageGate forNotification(Context context, String ownerPackage, int userId,
            int iconPx, int pictureWidth, int pictureHeight) {
        return forNotification(context, ownerPackage, userId, iconPx, pictureWidth, pictureHeight,
                (AppResourceImages) null);
    }

    /**
     * {@link #forNotification(Context, String, int, int, int, int)} for a row that was inflated before:
     * {@code previous} is that row's {@link #resourceImages}, which goes on with a fresh budget (its views keep it).
     */
    public static NotificationImageGate forNotification(Context context, String ownerPackage, int userId,
            int iconPx, int pictureWidth, int pictureHeight, AppResourceImages previous) {
        // On an image thread the time is reserved off the app's quota (call finish). Elsewhere nothing is decoded and
        // the quota is left alone, the wall-clock limit applies all the same.
        final boolean imageThread = ImageThreads.isImageThread();
        final ImageBudget budget = imageThread ? budgetFor(ownerPackage) : budgetOffImageThreads(ownerPackage);
        try {
            return new NotificationImageGate(context, ownerPackage, userId, iconPx, pictureWidth, pictureHeight,
                    budget, imageThread, previous);
        } catch (RuntimeException | Error e) {
            // The time taken off the quota goes back when no gate is there to settle it.
            if (imageThread) {
                settle(ownerPackage, budget, false);
            }
            throw e;
        }
    }

    /** {@link #forNotification(Context, String, int, int, int, int)} with a budget of the caller's (tests). */
    public static NotificationImageGate forNotification(Context context, String ownerPackage, int userId,
            int iconPx, int pictureWidth, int pictureHeight, ImageBudget budget) {
        return new NotificationImageGate(context, ownerPackage, userId, iconPx, pictureWidth, pictureHeight, budget,
                false, null);
    }

    /**
     * A copy of {@code notification} with every app image removed and nothing decoded, for when making the checked
     * copy failed: the notification still shows with its text and a neutral small icon, without custom layouts.
     */
    public static Notification strippedCopy(Context context, Notification notification) {
        final NotificationImageGate gate = new NotificationImageGate(context, "", -1, 1, 1, 1,
                new ImageBudget(0, 0, 0), false, null);
        gate.mStrip = true;
        final Notification copy = notification.clone();
        gate.sanitize(copy, 0);
        return copy;
    }

    /**
     * The copy a group header is made from, on the main thread: the small icon as {@link AppIcons#forView} gives it
     * (its image already made, else the neutral icon), every other app image removed without being loaded, and no
     * custom layout. Nothing is decoded or inflated for the other fields. If that fails, {@link #strippedCopy}.
     */
    public static Notification headerCopy(Context context, Notification notification, String ownerPackage,
            int userId) {
        try {
            return new NotificationImageGate(context, ownerPackage, userId, 1, 1, 1, new ImageBudget(0, 0, 0),
                    false, null).templateOnly().sanitizedCopy(notification);
        } catch (Throwable e) {
            Log.w(TAG, "group header images not checked, removing them: " + e);
        }
        try {
            return strippedCopy(context, notification);
        } catch (Throwable e) {
            Log.e(TAG, "group header images not removed, plain header", e);
            return new Notification.Builder(context, notification.getChannelId())
                    .setSmallIcon(AppIcons.neutral())
                    .build();
        }
    }

    /**
     * This gate's copies are only used for templates (a group header): custom layouts are not looked at and not
     * kept, so a notification with one is not copied for it. The small icon comes from {@link AppIcons} and every
     * other app image is removed without being loaded.
     */
    public NotificationImageGate templateOnly() {
        mTemplateOnly = true;
        return this;
    }

    /**
     * The context to apply the notification's views with: {@code packageContext} (the posting app's) with resources
     * of its own whose image files go through the VM. Call it before {@link #sanitizedCopy}. When it cannot be made,
     * {@code packageContext} comes back and the copy holds no custom layout.
     */
    public Context packageContext(Context packageContext) {
        if (mStrip) {
            return packageContext;
        }
        try {
            mAppContext = VmImageContext.wrap(packageContext, mResources);
            mLayoutsAllowed = true;
            return mAppContext;
        } catch (RuntimeException e) {
            Log.e(TAG, "no image gate for the app's resources: custom layouts are dropped", e);
            mAppContext = null;
            mLayoutsAllowed = false;
            return packageContext;
        }
    }

    /**
     * A checked copy of {@code views} (a widget's, with the items of its lists), its images re-made like a
     * notification's custom layout, or null when it cannot be checked. Call {@link #packageContext} first. On an image
     * thread the VM is asked.
     */
    public RemoteViews layoutCopy(RemoteViews views) {
        if (views == null) {
            return null;
        }
        final RemoteViews copy = views.clone();
        return mLayoutsAllowed && RemoteViewsImages.convert(copy, mLayoutImages) ? copy : null;
    }

    /**
     * {@code icon}, a URI, data or app resource icon of the app's views, as pixels made within this gate's caps (in
     * the VM on an image thread, else only what was made before), or an empty icon. Call {@link #packageContext}
     * first.
     */
    public Icon layoutIcon(Icon icon) {
        return mLayoutImages.convert(icon);
    }

    /** Makes ahead, on an image thread, the drawable {@code resId} of the app as it will be loaded. */
    public void prewarmDrawable(Context packageContext, int resId) {
        if (!mLayoutsAllowed || mStrip || !decodes()) {
            return;
        }
        prewarmDrawable(packageContext, resId, new java.util.HashSet<>());
    }

    /** Makes ahead, on an image thread, the app resource images {@code views} (a {@link #layoutCopy}) will load. */
    public void prewarmViews(RemoteViews views, Context packageContext) {
        if (!mLayoutsAllowed || mStrip || views == null || !decodes()) {
            return;
        }
        prewarm(views, packageContext, new java.util.HashSet<>());
    }

    /** What this notification spent, for tests and logs. */
    public ImageBudget budget() {
        return mBudget;
    }

    /** The image file gate of the app's resources, for tests. */
    public AppResourceImages resourceImages() {
        return mResources;
    }

    /**
     * The checked copy of {@code notification}, or {@code notification} itself when it holds nothing to check. The
     * original is not changed.
     */
    public Notification sanitizedCopy(Notification notification) {
        mSourceDir = sourceDir(notification);
        if (!needsWork(notification, 0)) {
            return notification;
        }
        final Notification copy = notification.clone();
        sanitize(copy, 0);
        return copy;
    }

    private boolean decodes() {
        if (mStrip || !ImageThreads.isImageThread()) {
            return false;
        }
        if (mCancelled != null && mCancelled.getAsBoolean()) {
            return false;
        }
        return userContext() != null;
    }

    /**
     * True for an icon this gate re-makes: a URI or data icon (decoded in the VM), or a resource icon of any package
     * but the framework, which is loaded through resources behind the image gate in whatever configuration it is shown
     * (its bitmap files decoded in the VM, XML parsed as usual).
     */
    static boolean needsGate(Icon icon) {
        return VmIcons.needsVm(icon) || isAppResource(icon);
    }

    private static boolean isAppResource(Icon icon) {
        return icon != null && icon.getType() == Icon.TYPE_RESOURCE && !"android".equals(icon.getResPackage());
    }

    /**
     * {@code icon}, a resource icon of an app, as a copy that loads through resources behind the image gate, or null
     * when it cannot be shown. It is loaded once here: on an image thread its bitmap files are decoded in the VM now,
     * so the views find them made, anywhere else only images already made are found. An icon that cannot be made is
     * removed (a small icon becomes the neutral icon).
     */
    private Icon gatedResource(Icon icon) {
        if (mStrip || mTemplateOnly) {
            return null;
        }
        // The first (the small icon) is always tried. The others stop at the cap or when the work has to stop.
        if (mResourceLoads++ > 0 && (mResourceLoads > MAX_RESOURCE_LOADS || stopped())) {
            return null;
        }
        final String pkg = resourcePackage(icon);
        final android.content.res.Resources res = pkg.equals(mOwner) && mAppContext != null
                ? mAppContext.getResources() : mResources.resourcesFor(pkg);
        if (res == null) {
            return null;
        }
        final Icon gated = icon.copyWithResources(res);
        AppResourceImages.beginTracking();
        final Drawable drawable;
        try {
            drawable = gated.loadDrawable(mContext);
        } catch (RuntimeException e) {
            Log.w(TAG, "app resource icon not loaded: " + e);
            return null;
        }
        final boolean missed = AppResourceImages.missed();
        final String cleanKey = new Clean(mContext, mOwner, mUserId, mSourceDir).key(icon);
        if (cleanKey != null) {
            if (missed || AppResourceImages.touchedAppFile()) {
                sClean.remove(cleanKey);
            } else if (drawable != null) {
                // Loaded whole without any bitmap file of the app: the next notification with it needs no image
                // thread.
                sClean.put(cleanKey, Boolean.TRUE);
            }
        }
        return drawable == null || missed ? null : gated;
    }

    /**
     * Loads ahead, on an image thread, the app resource images the custom layouts of {@code copy} (this gate's
     * {@link #sanitizedCopy}) will load: the drawables their layout XML names (included layouts and theme attributes
     * too) and the ones their actions set by ID, through {@code packageContext} (this gate's {@link #packageContext}).
     * Their bitmap files are decoded in the VM here, so the inflation that follows on its own thread finds them made
     * and never waits. Nothing is inflated or applied. Skipped when the app has no time left.
     */
    public void prewarm(Notification copy, Context packageContext) {
        if (!mLayoutsAllowed || mStrip || copy == null || !decodes()) {
            return;
        }
        final java.util.Set<Integer> done = new java.util.HashSet<>();
        prewarm(copy.contentView, packageContext, done);
        prewarm(copy.bigContentView, packageContext, done);
        prewarm(copy.headsUpContentView, packageContext, done);
        if (copy.publicVersion != null) {
            prewarm(copy.publicVersion.contentView, packageContext, done);
            prewarm(copy.publicVersion.bigContentView, packageContext, done);
            prewarm(copy.publicVersion.headsUpContentView, packageContext, done);
        }
    }

    private static final int MAX_PREWARM_DEPTH = 4;
    private static final int MAX_PREWARM_NODES = 2_000;

    private void prewarm(RemoteViews views, Context packageContext, java.util.Set<Integer> done) {
        if (views == null || stopped()) {
            return;
        }
        final RemoteViewsImages.Targets targets = RemoteViewsImages.resources(views);
        final int[] nodes = {0};
        for (int layout : targets.layouts) {
            prewarmLayout(packageContext, layout, 0, nodes, done);
        }
        for (int drawable : targets.drawables) {
            prewarmDrawable(packageContext, drawable, done);
        }
    }

    // The drawables a layout XML names, read with the resource parser only (no view is made).
    private void prewarmLayout(Context context, int layout, int depth, int[] nodes, java.util.Set<Integer> done) {
        if (layout == 0 || depth > MAX_PREWARM_DEPTH || mPrewarmLayouts >= MAX_PREWARM_LAYOUTS || !done.add(layout)
                || !decodes()) {
            return;
        }
        mPrewarmLayouts++;
        final android.content.res.Resources res = context.getResources();
        try (android.content.res.XmlResourceParser parser = res.getLayout(layout)) {
            int event;
            while ((event = parser.next()) != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                if (event != org.xmlpull.v1.XmlPullParser.START_TAG) {
                    continue;
                }
                if (++nodes[0] > MAX_PREWARM_NODES || stopped()) {
                    return;
                }
                for (int i = 0; i < parser.getAttributeCount(); i++) {
                    final int id = parser.getAttributeResourceValue(i, 0);
                    if (id == 0) {
                        continue;
                    }
                    final String type = typeName(res, id);
                    if ("layout".equals(type)) {
                        prewarmLayout(context, id, depth + 1, nodes, done);
                    } else if ("drawable".equals(type) || "mipmap".equals(type)) {
                        prewarmDrawable(context, id, done);
                    } else if ("attr".equals(type)) {
                        final android.util.TypedValue value = new android.util.TypedValue();
                        if (context.getTheme().resolveAttribute(id, value, true) && value.resourceId != 0) {
                            final String target = typeName(res, value.resourceId);
                            if ("drawable".equals(target) || "mipmap".equals(target)) {
                                prewarmDrawable(context, value.resourceId, done);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            // The real inflation reports what is wrong with the layout.
            Log.w(TAG, "layout images not made ahead: " + e);
        }
    }

    private static String typeName(android.content.res.Resources res, int id) {
        try {
            return res.getResourceTypeName(id);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private void prewarmDrawable(Context context, int id, java.util.Set<Integer> done) {
        if (id == 0 || mPrewarmDrawables >= MAX_PREWARM_DRAWABLES || stopped() || !done.add(id)) {
            return;
        }
        mPrewarmDrawables++;
        try {
            context.getDrawable(id);
        } catch (RuntimeException e) {
            Log.w(TAG, "layout image not made ahead: " + e);
        }
    }

    /** True when {@code n} holds an app image to decode, or a custom layout to convert or drop. */
    private boolean needsWork(Notification n, int depth) {
        if (needsGate(n.getSmallIcon()) || needsGate(n.getLargeIcon())) {
            return true;
        }
        if (n.extras != null) {
            try {
                // Typed reads: a value of another class is never made, it reads as null.
                for (String key : ICON_EXTRAS) {
                    if (needsGate(n.extras.getParcelable(key, Icon.class))) {
                        return true;
                    }
                }
                if (needsVm(n.extras.getParcelable(Notification.EXTRA_MESSAGING_PERSON, Person.class))
                        || needsVm(n.extras.getParcelable(Notification.EXTRA_CALL_PERSON, Person.class))) {
                    return true;
                }
                final ArrayList<Person> people = n.extras.getParcelableArrayList(Notification.EXTRA_PEOPLE_LIST,
                        Person.class);
                if (people != null) {
                    for (Person p : people) {
                        if (needsVm(p)) {
                            return true;
                        }
                    }
                }
                if (sendersNeedVm(n.extras, Notification.EXTRA_MESSAGES)
                        || sendersNeedVm(n.extras, Notification.EXTRA_HISTORIC_MESSAGES)) {
                    return true;
                }
            } catch (RuntimeException e) {
                // A value that cannot be read: the copy removes it.
                return true;
            }
        }
        if (n.actions != null) {
            for (Notification.Action action : n.actions) {
                if (action != null && needsGate(action.getIcon())) {
                    return true;
                }
            }
        }
        if (layoutNeedsWork(n.contentView) || layoutNeedsWork(n.bigContentView)
                || layoutNeedsWork(n.headsUpContentView) || layoutNeedsWork(n.tickerView)) {
            return true;
        }
        return n.publicVersion != null && depth == 0 && needsWork(n.publicVersion, depth + 1);
    }

    private boolean layoutNeedsWork(RemoteViews views) {
        if (views == null || mTemplateOnly) {
            return false;
        }
        return mStrip || !mLayoutsAllowed || RemoteViewsImages.needsWork(views, mLayoutImages);
    }

    private static boolean needsVm(Person person) {
        return person != null && needsGate(person.getIcon());
    }

    private static boolean sendersNeedVm(Bundle extras, String key) {
        final Bundle[] messages = extras.getParcelableArray(key, Bundle.class);
        if (messages == null) {
            return false;
        }
        for (Bundle message : messages) {
            if (message != null && needsVm(message.getParcelable(KEY_SENDER_PERSON, Person.class))) {
                return true;
            }
        }
        return false;
    }

    private void sanitize(Notification n, int depth) {
        if (needsGate(n.getSmallIcon())) {
            if (mTemplateOnly) {
                // As the status bar shows it: an image already made, else the neutral icon. Nothing waits here.
                n.setSmallIcon(AppIcons.forView(mContext, n.getSmallIcon(), mUserId, mOwner, null));
            } else {
                final Icon safe = safe(n.getSmallIcon(), mIconPx);
                n.setSmallIcon(safe != null ? safe : AppIcons.neutral());
            }
        }
        if (n.extras != null) {
            sanitizeExtras(n.extras);
        }
        if (needsGate(n.getLargeIcon())) {
            // The large icon field has no setter of its own. An adopting builder sets the field and its extra.
            new Notification.Builder(mContext, n).setLargeIcon(safe(n.getLargeIcon(), mIconPx));
        }
        if (n.actions != null) {
            for (int i = 0; i < n.actions.length; i++) {
                final Notification.Action action = n.actions[i];
                if (action != null && needsGate(action.getIcon())) {
                    n.actions[i] = withIcon(action, safe(action.getIcon(), mIconPx));
                }
            }
        }
        n.contentView = layout(n.contentView);
        n.bigContentView = layout(n.bigContentView);
        n.headsUpContentView = layout(n.headsUpContentView);
        n.tickerView = layout(n.tickerView);
        if (n.publicVersion != null && depth == 0) {
            sanitize(n.publicVersion, depth + 1);
        }
    }

    // Every value is read typed and on its own: one that cannot be read is removed, the others are still checked.
    private void sanitizeExtras(Bundle extras) {
        for (String key : ICON_EXTRAS) {
            if (!extras.containsKey(key)) {
                continue;
            }
            try {
                final Icon value = extras.getParcelable(key, Icon.class);
                if (needsGate(value)) {
                    final Icon safe = Notification.EXTRA_PICTURE_ICON.equals(key)
                            ? safe(value, mPictureWidth, mPictureHeight)
                            : safe(value, mIconPx);
                    if (safe != null) {
                        extras.putParcelable(key, safe);
                    } else {
                        extras.remove(key);
                    }
                }
            } catch (RuntimeException e) {
                Log.w(TAG, "notification extra not readable, removed: " + key);
                extras.remove(key);
            }
        }
        sanitizePerson(extras, Notification.EXTRA_MESSAGING_PERSON);
        sanitizePerson(extras, Notification.EXTRA_CALL_PERSON);
        try {
            final ArrayList<Person> people = extras.getParcelableArrayList(Notification.EXTRA_PEOPLE_LIST,
                    Person.class);
            if (people != null) {
                final ArrayList<Person> safePeople = new ArrayList<>(people.size());
                for (Person p : people) {
                    safePeople.add(safe(p));
                }
                extras.putParcelableArrayList(Notification.EXTRA_PEOPLE_LIST, safePeople);
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "notification people not readable, removed");
            extras.remove(Notification.EXTRA_PEOPLE_LIST);
        }
        sanitizeMessages(extras, Notification.EXTRA_MESSAGES);
        sanitizeMessages(extras, Notification.EXTRA_HISTORIC_MESSAGES);
    }

    private void sanitizePerson(Bundle extras, String key) {
        try {
            final Person person = extras.getParcelable(key, Person.class);
            if (person != null && needsGate(person.getIcon())) {
                extras.putParcelable(key, safe(person));
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "notification person not readable, removed: " + key);
            extras.remove(key);
        }
    }

    // The message bundles are shared with the original after Notification.clone, so they are copied, not changed.
    private void sanitizeMessages(Bundle extras, String key) {
        final Bundle[] messages;
        try {
            messages = extras.getParcelableArray(key, Bundle.class);
        } catch (RuntimeException e) {
            Log.w(TAG, "notification messages not readable, removed: " + key);
            extras.remove(key);
            return;
        }
        if (messages == null) {
            return;
        }
        final Bundle[] copies = new Bundle[messages.length];
        for (int i = 0; i < messages.length; i++) {
            if (messages[i] == null) {
                continue;
            }
            final Bundle copy = new Bundle(messages[i]);
            try {
                final Person sender = copy.getParcelable(KEY_SENDER_PERSON, Person.class);
                if (sender != null && needsGate(sender.getIcon())) {
                    copy.putParcelable(KEY_SENDER_PERSON, safe(sender));
                }
            } catch (RuntimeException e) {
                copy.remove(KEY_SENDER_PERSON);
            }
            copies[i] = copy;
        }
        extras.putParcelableArray(key, copies);
    }

    private Person safe(Person person) {
        if (person == null || !needsGate(person.getIcon())) {
            return person;
        }
        // At most MAX_PEOPLE_ICONS different images of people and senders: past that, a person with another one shows
        // without it. The same sender in many messages counts once.
        if (mPeopleIconKeys.add(personIconKey(person.getIcon())) && mPeopleIconKeys.size() > MAX_PEOPLE_ICONS) {
            return person.toBuilder().setIcon(null).build();
        }
        return person.toBuilder().setIcon(safe(person.getIcon(), mIconPx)).build();
    }

    private Icon safe(Icon icon, int px) {
        return safe(icon, px, px);
    }

    // What makes two people's images the same image: the resource, the URI, or the bytes.
    private String personIconKey(Icon icon) {
        switch (icon.getType()) {
            case Icon.TYPE_RESOURCE:
                return "r:" + icon.getResPackage() + ':' + icon.getResId();
            case Icon.TYPE_URI:
            case Icon.TYPE_URI_ADAPTIVE_BITMAP:
                return "u:" + icon.getUri();
            case Icon.TYPE_DATA:
                final String key = VmIcons.cacheKey(icon, mContext, mOwner, "p");
                return key != null ? key : "d:" + System.identityHashCode(icon);
            default:
                return "o:" + System.identityHashCode(icon);
        }
    }

    // An icon of the standard fields: a URI or data icon decoded in the VM within the notification's time, an app
    // resource icon through the image gate, or null. An image already made is used even when no time is left.
    private Icon safe(Icon icon, int maxWidth, int maxHeight) {
        if (!needsGate(icon)) {
            return icon;
        }
        if (mStrip || mTemplateOnly) {
            return null;
        }
        if (icon.getType() == Icon.TYPE_RESOURCE) {
            return gatedResource(icon);
        }
        final Context user = userContext();
        final Icon cached = user == null ? null : VmIcons.cachedSafeIcon(icon, user, mOwner, maxWidth, maxHeight);
        if (cached != null) {
            return cached;
        }
        if (!decodes()) {
            return null;
        }
        final long left = mBudget.remainingMs();
        if (left <= 0 || stopped()) {
            return null;
        }
        final Icon made = VmIcons.toSafeIcon(icon, user, mOwner, maxWidth, maxHeight, VmDecode.deadlineIn(left));
        // The pixels count against what the app holds across its notifications.
        if (made != null && made.getBitmap() != null && !mBudget.chargePool(
                (long) made.getBitmap().getWidth() * made.getBitmap().getHeight())) {
            Log.w(TAG, "the app holds too many image pixels, image left out");
            return null;
        }
        return made;
    }

    private static Notification.Action withIcon(Notification.Action action, Icon icon) {
        final Notification.Action.Builder builder =
                new Notification.Action.Builder(icon, action.title, action.actionIntent)
                        .addExtras(action.getExtras())
                        .setAllowGeneratedReplies(action.getAllowGeneratedReplies())
                        .setSemanticAction(action.getSemanticAction())
                        .setContextual(action.isContextual())
                        .setEmphasisHint(action.getEmphasisHint())
                        .setStyleHint(action.getStyleHint())
                        .setAuthenticationRequired(action.isAuthenticationRequired());
        addAll(builder, action.getRemoteInputs());
        addAll(builder, action.getDataOnlyRemoteInputs());
        return builder.build();
    }

    private static void addAll(Notification.Action.Builder builder, RemoteInput[] inputs) {
        if (inputs != null) {
            for (RemoteInput input : inputs) {
                builder.addRemoteInput(input);
            }
        }
    }

    // The app's own layout, its images re-made in place, or null when it has to be dropped.
    private RemoteViews layout(RemoteViews views) {
        if (views == null) {
            return null;
        }
        if (mStrip || mTemplateOnly || !mLayoutsAllowed) {
            return null;
        }
        return RemoteViewsImages.convert(views, mLayoutImages) ? views : null;
    }

    /** The images inside the app's layouts: every one counted against the layout caps. */
    private class LayoutImages implements RemoteViewsImages.Images {
        @Override
        public boolean needsWork(Icon icon) {
            if (icon == null) {
                return false;
            }
            switch (icon.getType()) {
                case Icon.TYPE_BITMAP:
                case Icon.TYPE_ADAPTIVE_BITMAP:
                    return false;
                case Icon.TYPE_RESOURCE:
                    // Every app resource goes through the image gate, which tells system image files apart per
                    // file (an overlay from /data on a system package included). Only the framework stays.
                    return !"android".equals(icon.getResPackage());
                default:
                    return true;
            }
        }

        @Override
        public Icon convert(Icon icon) {
            Icon safe = null;
            try {
                safe = icon.getType() == Icon.TYPE_RESOURCE ? resourceIcon(icon) : vmIcon(icon);
            } catch (RuntimeException e) {
                Log.w(TAG, "layout image not made, empty: " + e);
            }
            return safe != null ? safe : VmIcons.emptyIcon();
        }

        @Override
        public Icon convert(Uri uri) {
            return convert(Icon.createWithContentUri(uri));
        }
    }

    private String resourcePackage(Icon icon) {
        final String pkg = icon.getResPackage();
        return pkg == null || pkg.isEmpty() ? mOwner : pkg;
    }

    // A URI or data icon of a layout, decoded in the VM, or null. The most pixels it can have (the box) are charged
    // before the VM is asked, and what is left over is given back.
    private Icon vmIcon(Icon icon) {
        final Context user = userContext();
        if (user == null || mStrip) {
            return null;
        }
        final String box = mPictureWidth + "x" + mPictureHeight;
        final String cacheKey = VmIcons.cacheKey(icon, user, mOwner, box);
        final String key = cacheKey != null ? cacheKey : "icon:" + System.identityHashCode(icon);
        // An image already made is used on any thread and with no time left, counted against the layout's caps.
        final Icon cached = VmIcons.cachedSafeIcon(icon, user, mOwner, mPictureWidth, mPictureHeight);
        if (cached != null) {
            final ImageBudget.Admission admission = mBudget.admit(key);
            if (admission == ImageBudget.Admission.REFUSED) {
                return null;
            }
            final Bitmap bitmap = cached.getBitmap();
            if (admission == ImageBudget.Admission.NEW
                    && !mBudget.charge(key, (long) bitmap.getWidth() * bitmap.getHeight())) {
                return null;
            }
            return cached;
        }
        if (!decodes()) {
            return null;
        }
        final ImageBudget.Admission admission = mBudget.admit(key);
        if (admission == ImageBudget.Admission.REFUSED || mBudget.hasFailed(key)) {
            return null;
        }
        final long boxPixels = (long) mPictureWidth * mPictureHeight;
        if (admission == ImageBudget.Admission.NEW && !mBudget.charge(key, boxPixels)) {
            return null;
        }
        final long left = mBudget.remainingMs();
        Icon safe = null;
        if (left > 0) {
            mBudget.countVmCall();
            safe = VmIcons.toSafeIcon(icon, user, mOwner, mPictureWidth, mPictureHeight, VmDecode.deadlineIn(left));
        }
        if (safe == null) {
            mBudget.failed(key);
        }
        if (admission == ImageBudget.Admission.NEW) {
            final Bitmap bitmap = safe == null ? null : safe.getBitmap();
            mBudget.refund(boxPixels - (bitmap == null ? 0 : (long) bitmap.getWidth() * bitmap.getHeight()));
        }
        return safe;
    }

    // An app resource icon of a layout: drawn from the app's resources behind the image gate (bitmap files in the
    // VM, XML parsed as usual), as a bitmap.
    private Icon resourceIcon(Icon icon) {
        // Capped and timed before anything is inflated: a layout of thousands of resource icons inflates at most
        // MAX_IMAGES of them, within the notification's time.
        if (!decodes() || stopped() || ++mLayoutResourceIcons > ImageBudget.MAX_IMAGES) {
            return null;
        }
        final String pkg = resourcePackage(icon);
        final String box = mPictureWidth + "x" + mPictureHeight;
        final String key = "ricon:" + mUserId + ':' + pkg + ':' + icon.getResId() + ':' + box;
        final Context appContext = contextFor(pkg);
        if (appContext == null) {
            return null;
        }
        final Drawable drawable;
        AppResourceImages.beginTracking();
        try {
            drawable = appContext.getDrawable(icon.getResId());
        } catch (RuntimeException e) {
            Log.w(TAG, "layout resource icon not loaded: " + e);
            return null;
        }
        if (drawable == null || AppResourceImages.missed()) {
            // Never a picture with holes, made while an image of it could not be made: empty instead.
            return null;
        }
        // A bitmap file comes back as the bitmap the image gate made in the VM, already admitted and charged there.
        // Anything else (XML) is drawn here, and counts as an image of its own.
        Bitmap bitmap = madeBitmap(drawable);
        if (bitmap == null) {
            final ImageBudget.Admission admission = mBudget.admit(key);
            if (admission == ImageBudget.Admission.REFUSED) {
                return null;
            }
            bitmap = draw(drawable);
            if (admission == ImageBudget.Admission.NEW
                    && !mBudget.charge(key, (long) bitmap.getWidth() * bitmap.getHeight())) {
                return null;
            }
        }
        return VmIcons.iconLike(icon, bitmap,
                drawable instanceof android.graphics.drawable.AdaptiveIconDrawable);
    }

    // The gated context of the posting app, or of another app whose resource an icon names.
    private Context contextFor(String pkg) {
        if (mAppContext != null && pkg.equals(mAppContext.getPackageName())) {
            return mAppContext;
        }
        try {
            final ApplicationInfo ai =
                    mContext.getPackageManager().getApplicationInfoAsUser(pkg, 0, mUserId);
            return VmImageContext.wrap(mContext.createApplicationContext(ai, Context.CONTEXT_RESTRICTED),
                    mResources);
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            Log.w(TAG, "no resources for " + pkg + ": " + e);
            return null;
        }
    }

    // The bitmap of a drawable the image gate made from a bitmap file (decoded in the VM), else null.
    private Bitmap madeBitmap(Drawable drawable) {
        if (drawable instanceof BitmapDrawable) {
            final Bitmap bitmap = ((BitmapDrawable) drawable).getBitmap();
            if (bitmap != null && bitmap.getWidth() <= mPictureWidth && bitmap.getHeight() <= mPictureHeight) {
                return bitmap;
            }
        }
        return null;
    }

    // The drawable drawn into a bitmap within the layout box, a small one (a 24 dp vector) drawn at the size of a large
    // icon, so it stays sharp when the app's view shows it bigger.
    private Bitmap draw(Drawable drawable) {
        return VmIcons.draw(drawable, mIconPx, mPictureWidth, mPictureHeight);
    }
}
