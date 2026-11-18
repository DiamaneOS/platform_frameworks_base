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
import android.os.Parcelable;
import android.os.SystemClock;
import android.os.UserHandle;
import android.util.Log;
import android.widget.RemoteViews;

import java.util.ArrayList;
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
 * <p>All images of one notification share {@link #BUDGET_MS} of waiting for the VM, and each app shares an
 * {@link OwnerQuota} across its notifications: a VM or provider that hangs costs images, not a stuck or missing
 * notification. The images of its layouts are capped ({@link ImageBudget#MAX_IMAGES} and
 * {@link ImageBudget#MAX_PIXELS}): anything over the caps is empty. Every decode is charged to the posting app, and
 * URIs are opened as the notification's user.
 *
 * <p>The VM is asked only on {@link ImageThreads}: the caller makes the copy and {@link #prewarm}s the layouts there,
 * before it inflates on its own thread. Anywhere else (the inflation thread, the main thread) nothing is decoded:
 * images come from what the image threads made, or are empty or removed.
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

    /** The time all images of one notification may wait for the VM together. */
    public static final long BUDGET_MS = ImageBudget.TIME_MS;

    // Waiting for the VM per app across its notifications.
    private static final OwnerQuota sQuota = new OwnerQuota();

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

    private NotificationImageGate(Context context, String ownerPackage, int userId, int iconPx, int pictureWidth,
            int pictureHeight, ImageBudget budget, AppResourceImages previous) {
        mContext = context;
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

    // A fresh budget, bounded by what the posting app has left of its quota. The quota is charged with the whole
    // time of the image work (chargeTime), not only the waits for the VM.
    static ImageBudget budgetFor(String owner) {
        final String key = owner == null ? "" : owner;
        final long time = Math.min(ImageBudget.TIME_MS, sQuota.available(key, SystemClock.uptimeMillis()));
        return new ImageBudget(time, ImageBudget.MAX_IMAGES, ImageBudget.MAX_PIXELS);
    }

    /** Charges {@code ms} of image work (on an image thread) to {@code ownerPackage}'s quota. */
    public static void chargeTime(String ownerPackage, long ms) {
        sQuota.spend(ownerPackage == null ? "" : ownerPackage, ms, SystemClock.uptimeMillis());
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

    /** The lane for {@code n} posted by {@code ownerPackage}. Cheap: reads the notification only. */
    public static Lane lane(Notification n, String ownerPackage) {
        if (!hasAppImages(n, 0)) {
            return Lane.NONE;
        }
        final String category = n.category;
        if (n.fullScreenIntent != null || Notification.CATEGORY_CALL.equals(category)
                || Notification.CATEGORY_ALARM.equals(category) || Notification.CATEGORY_REMINDER.equals(category)
                || Notification.CATEGORY_STOPWATCH.equals(category)
                || Notification.CATEGORY_NAVIGATION.equals(category)
                || (n.extras != null && n.extras.containsKey(Notification.EXTRA_CALL_PERSON))) {
            return Lane.URGENT;
        }
        return Lane.NORMAL;
    }

    // Any icon that is not pixels or the framework's, or a custom layout.
    private static boolean hasAppImages(Notification n, int depth) {
        if (n.contentView != null || n.bigContentView != null || n.headsUpContentView != null
                || n.tickerView != null) {
            return true;
        }
        if (isAppImage(n.getSmallIcon()) || isAppImage(n.getLargeIcon())) {
            return true;
        }
        if (n.actions != null) {
            for (Notification.Action action : n.actions) {
                if (action != null && isAppImage(action.getIcon())) {
                    return true;
                }
            }
        }
        if (n.extras != null && !n.extras.isEmpty()) {
            // Extras may hold icons and people: looked at by the image work, not here.
            for (String key : ICON_EXTRAS) {
                if (n.extras.containsKey(key)) {
                    return true;
                }
            }
            if (n.extras.containsKey(Notification.EXTRA_MESSAGES)
                    || n.extras.containsKey(Notification.EXTRA_HISTORIC_MESSAGES)
                    || n.extras.containsKey(Notification.EXTRA_MESSAGING_PERSON)
                    || n.extras.containsKey(Notification.EXTRA_CALL_PERSON)
                    || n.extras.containsKey(Notification.EXTRA_PEOPLE_LIST)) {
                return true;
            }
        }
        return n.publicVersion != null && depth == 0 && hasAppImages(n.publicVersion, depth + 1);
    }

    private static boolean isAppImage(Icon icon) {
        return icon != null && icon.getType() != Icon.TYPE_BITMAP && icon.getType() != Icon.TYPE_ADAPTIVE_BITMAP
                && !(icon.getType() == Icon.TYPE_RESOURCE && "android".equals(icon.getResPackage()));
    }

    /** While {@code cancelled} answers true, nothing more is decoded: the notification is gone or replaced. */
    public void setCancelled(BooleanSupplier cancelled) {
        mCancelled = cancelled;
        mResources.setCancelled(cancelled);
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
        return new NotificationImageGate(context, ownerPackage, userId, iconPx, pictureWidth, pictureHeight,
                budgetFor(ownerPackage), previous);
    }

    /** {@link #forNotification(Context, String, int, int, int, int)} with a budget of the caller's (tests). */
    public static NotificationImageGate forNotification(Context context, String ownerPackage, int userId,
            int iconPx, int pictureWidth, int pictureHeight, ImageBudget budget) {
        return new NotificationImageGate(context, ownerPackage, userId, iconPx, pictureWidth, pictureHeight, budget,
                null);
    }

    /**
     * A copy of {@code notification} with every app image removed and nothing decoded, for when making the checked
     * copy failed: the notification still shows with its text and a neutral small icon, without custom layouts.
     */
    public static Notification strippedCopy(Context context, Notification notification) {
        final NotificationImageGate gate = new NotificationImageGate(context, "", -1, 1, 1, 1,
                new ImageBudget(0, 0, 0), null);
        gate.mStrip = true;
        final Notification copy = notification.clone();
        gate.sanitize(copy, 0);
        return copy;
    }

    /**
     * This gate's copies are only used for templates (a group header): custom layouts are not looked at and not
     * kept, so a notification with one is not copied for it.
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
        if (mStrip) {
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
        return drawable == null || AppResourceImages.missed() ? null : gated;
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
        if (views == null) {
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
        if (layout == 0 || depth > MAX_PREWARM_DEPTH || !done.add(layout) || !decodes()) {
            return;
        }
        final android.content.res.Resources res = context.getResources();
        try (android.content.res.XmlResourceParser parser = res.getLayout(layout)) {
            int event;
            while ((event = parser.next()) != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                if (event != org.xmlpull.v1.XmlPullParser.START_TAG) {
                    continue;
                }
                if (++nodes[0] > MAX_PREWARM_NODES || mBudget.remainingMs() <= 0) {
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
        if (id == 0 || !done.add(id) || mBudget.remainingMs() <= 0) {
            return;
        }
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
            for (String key : ICON_EXTRAS) {
                final Parcelable value = n.extras.getParcelable(key, Parcelable.class);
                if (value instanceof Icon && needsGate((Icon) value)) {
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
        final Parcelable[] messages = extras.getParcelableArray(key, Parcelable.class);
        if (messages == null) {
            return false;
        }
        for (Parcelable message : messages) {
            if (message instanceof Bundle
                    && needsVm(((Bundle) message).getParcelable(KEY_SENDER_PERSON, Person.class))) {
                return true;
            }
        }
        return false;
    }

    private void sanitize(Notification n, int depth) {
        if (needsGate(n.getSmallIcon())) {
            final Icon safe = safe(n.getSmallIcon(), mIconPx);
            n.setSmallIcon(safe != null ? safe
                    : Icon.createWithResource("android", android.R.drawable.ic_dialog_info));
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

    private void sanitizeExtras(Bundle extras) {
        for (String key : ICON_EXTRAS) {
            final Parcelable value = extras.getParcelable(key, Parcelable.class);
            if (value instanceof Icon && needsGate((Icon) value)) {
                final Icon safe = Notification.EXTRA_PICTURE_ICON.equals(key)
                        ? safe((Icon) value, mPictureWidth, mPictureHeight)
                        : safe((Icon) value, mIconPx);
                if (safe != null) {
                    extras.putParcelable(key, safe);
                } else {
                    extras.remove(key);
                }
            }
        }
        sanitizePerson(extras, Notification.EXTRA_MESSAGING_PERSON);
        sanitizePerson(extras, Notification.EXTRA_CALL_PERSON);
        final ArrayList<Person> people = extras.getParcelableArrayList(Notification.EXTRA_PEOPLE_LIST,
                Person.class);
        if (people != null) {
            final ArrayList<Person> safePeople = new ArrayList<>(people.size());
            for (Person p : people) {
                safePeople.add(safe(p));
            }
            extras.putParcelableArrayList(Notification.EXTRA_PEOPLE_LIST, safePeople);
        }
        sanitizeMessages(extras, Notification.EXTRA_MESSAGES);
        sanitizeMessages(extras, Notification.EXTRA_HISTORIC_MESSAGES);
    }

    private void sanitizePerson(Bundle extras, String key) {
        final Person person = extras.getParcelable(key, Person.class);
        if (person != null && needsGate(person.getIcon())) {
            extras.putParcelable(key, safe(person));
        }
    }

    // The message bundles are shared with the original after Notification.clone, so they are copied, not changed.
    private void sanitizeMessages(Bundle extras, String key) {
        final Parcelable[] messages = extras.getParcelableArray(key, Parcelable.class);
        if (messages == null) {
            return;
        }
        final Bundle[] copies = new Bundle[messages.length];
        for (int i = 0; i < messages.length; i++) {
            if (!(messages[i] instanceof Bundle)) {
                continue;
            }
            final Bundle copy = new Bundle((Bundle) messages[i]);
            final Person sender = copy.getParcelable(KEY_SENDER_PERSON, Person.class);
            if (sender != null && needsGate(sender.getIcon())) {
                copy.putParcelable(KEY_SENDER_PERSON, safe(sender));
            }
            copies[i] = copy;
        }
        extras.putParcelableArray(key, copies);
    }

    private Person safe(Person person) {
        if (person == null || !needsGate(person.getIcon())) {
            return person;
        }
        return person.toBuilder().setIcon(safe(person.getIcon(), mIconPx)).build();
    }

    private Icon safe(Icon icon, int px) {
        return safe(icon, px, px);
    }

    // An icon of the standard fields: a URI or data icon decoded in the VM within the notification's time, an app
    // resource icon through the image gate, or null. An image already made is used even when no time is left.
    private Icon safe(Icon icon, int maxWidth, int maxHeight) {
        if (!needsGate(icon)) {
            return icon;
        }
        if (mStrip) {
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
        if (left <= 0) {
            return null;
        }
        final long start = SystemClock.uptimeMillis();
        try {
            return VmIcons.toSafeIcon(icon, user, mOwner, maxWidth, maxHeight, VmDecode.deadlineIn(left));
        } finally {
            mBudget.spent(SystemClock.uptimeMillis() - start);
        }
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
    private final class LayoutImages implements RemoteViewsImages.Images {
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
            final long start = SystemClock.uptimeMillis();
            try {
                mBudget.countVmCall();
                safe = VmIcons.toSafeIcon(icon, user, mOwner, mPictureWidth, mPictureHeight,
                        VmDecode.deadlineIn(left));
            } finally {
                mBudget.spent(SystemClock.uptimeMillis() - start);
            }
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
        if (!decodes()) {
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
