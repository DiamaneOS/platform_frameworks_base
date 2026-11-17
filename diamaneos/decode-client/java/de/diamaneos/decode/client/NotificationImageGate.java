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
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Bundle;
import android.os.Looper;
import android.os.Parcelable;
import android.os.SystemClock;
import android.util.Log;
import android.widget.RemoteViews;

import java.util.ArrayList;

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
 * <p>All images of one notification share {@link #BUDGET_MS} of waiting for the VM: a VM that hangs costs images,
 * not a stuck or missing notification. The images of its layouts are capped ({@link ImageBudget#MAX_IMAGES} and
 * {@link ImageBudget#MAX_PIXELS}), so one app cannot starve others: anything over the caps is empty. Every decode is
 * charged to the posting app. On the main thread nothing is decoded: app images are empty or removed.
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

    private final Context mContext;
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

    private NotificationImageGate(Context context, String ownerPackage, int userId, int iconPx, int pictureWidth,
            int pictureHeight, ImageBudget budget) {
        mContext = context;
        mOwner = ownerPackage == null ? "" : ownerPackage;
        mUserId = userId;
        mIconPx = Math.max(1, iconPx);
        mPictureWidth = Math.max(1, pictureWidth);
        mPictureHeight = Math.max(1, pictureHeight);
        mBudget = budget;
        mResources = new AppResourceImages(context, mOwner, budget, mPictureWidth, mPictureHeight);
    }

    /**
     * The gate for one inflation of a notification posted by {@code ownerPackage} for {@code userId}.
     * {@code context} is SystemUI's own. {@code iconPx} bounds icons and people, {@code pictureWidth} x
     * {@code pictureHeight} the big picture and the images of the app's layouts.
     */
    public static NotificationImageGate forNotification(Context context, String ownerPackage, int userId,
            int iconPx, int pictureWidth, int pictureHeight) {
        return forNotification(context, ownerPackage, userId, iconPx, pictureWidth, pictureHeight,
                new ImageBudget());
    }

    /** {@link #forNotification(Context, String, int, int, int, int)} with a budget of the caller's (tests). */
    public static NotificationImageGate forNotification(Context context, String ownerPackage, int userId,
            int iconPx, int pictureWidth, int pictureHeight, ImageBudget budget) {
        return new NotificationImageGate(context, ownerPackage, userId, iconPx, pictureWidth, pictureHeight, budget);
    }

    /**
     * The context to apply the notification's views with: {@code packageContext} (the posting app's) with resources
     * of its own whose image files go through the VM. Call it before {@link #sanitizedCopy}. When it cannot be made,
     * {@code packageContext} comes back and the copy holds no custom layout.
     */
    public Context packageContext(Context packageContext) {
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
        return Looper.myLooper() != Looper.getMainLooper();
    }

    /** True when {@code n} holds an app image to decode, or a custom layout to convert or drop. */
    private boolean needsWork(Notification n, int depth) {
        if (VmIcons.needsVm(n.getSmallIcon()) || VmIcons.needsVm(n.getLargeIcon())) {
            return true;
        }
        if (n.extras != null) {
            for (String key : ICON_EXTRAS) {
                final Parcelable value = n.extras.getParcelable(key, Parcelable.class);
                if (value instanceof Icon && VmIcons.needsVm((Icon) value)) {
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
                if (action != null && VmIcons.needsVm(action.getIcon())) {
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
        if (views == null) {
            return false;
        }
        return !mLayoutsAllowed || RemoteViewsImages.needsWork(views, mLayoutImages);
    }

    private static boolean needsVm(Person person) {
        return person != null && VmIcons.needsVm(person.getIcon());
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
        if (VmIcons.needsVm(n.getSmallIcon())) {
            final Icon safe = safe(n.getSmallIcon(), mIconPx);
            n.setSmallIcon(safe != null ? safe
                    : Icon.createWithResource("android", android.R.drawable.ic_dialog_info));
        }
        if (n.extras != null) {
            sanitizeExtras(n.extras);
        }
        if (VmIcons.needsVm(n.getLargeIcon())) {
            // The large icon field has no setter of its own. An adopting builder sets the field and its extra.
            new Notification.Builder(mContext, n).setLargeIcon(safe(n.getLargeIcon(), mIconPx));
        }
        if (n.actions != null) {
            for (int i = 0; i < n.actions.length; i++) {
                final Notification.Action action = n.actions[i];
                if (action != null && VmIcons.needsVm(action.getIcon())) {
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
            if (value instanceof Icon && VmIcons.needsVm((Icon) value)) {
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
        if (person != null && VmIcons.needsVm(person.getIcon())) {
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
            if (sender != null && VmIcons.needsVm(sender.getIcon())) {
                copy.putParcelable(KEY_SENDER_PERSON, safe(sender));
            }
            copies[i] = copy;
        }
        extras.putParcelableArray(key, copies);
    }

    private Person safe(Person person) {
        if (person == null || !VmIcons.needsVm(person.getIcon())) {
            return person;
        }
        return person.toBuilder().setIcon(safe(person.getIcon(), mIconPx)).build();
    }

    private Icon safe(Icon icon, int px) {
        return safe(icon, px, px);
    }

    // A URI or data icon of the standard fields: decoded in the VM within the notification's time, or null.
    private Icon safe(Icon icon, int maxWidth, int maxHeight) {
        if (!VmIcons.needsVm(icon)) {
            return icon;
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
            return VmIcons.toSafeIcon(icon, mContext, mOwner, maxWidth, maxHeight, VmDecode.deadlineIn(left));
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
        if (!mLayoutsAllowed) {
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
                    return !isSystemResource(icon);
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

    // A resource of the framework or of a package in the system image loads as before.
    private boolean isSystemResource(Icon icon) {
        final String pkg = resourcePackage(icon);
        if ("android".equals(pkg)) {
            return true;
        }
        try {
            return VmIcons.isSystemImage(
                    mContext.getPackageManager().getApplicationInfoAsUser(pkg, 0, mUserId));
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            return false;
        }
    }

    private String resourcePackage(Icon icon) {
        final String pkg = icon.getResPackage();
        return pkg == null || pkg.isEmpty() ? mOwner : pkg;
    }

    // A URI or data icon of a layout, decoded in the VM, or null.
    private Icon vmIcon(Icon icon) {
        final String box = mPictureWidth + "x" + mPictureHeight;
        final String key = VmIcons.cacheKey(icon, mContext, mOwner, box);
        final ImageBudget.Admission admission =
                mBudget.admit(key != null ? key : "icon:" + System.identityHashCode(icon));
        if (admission == ImageBudget.Admission.REFUSED || !decodes()) {
            return null;
        }
        final long left = mBudget.remainingMs();
        if (left <= 0) {
            return null;
        }
        final long start = SystemClock.uptimeMillis();
        final Icon safe;
        try {
            mBudget.countVmCall();
            safe = VmIcons.toSafeIcon(icon, mContext, mOwner, mPictureWidth, mPictureHeight,
                    VmDecode.deadlineIn(left));
        } finally {
            mBudget.spent(SystemClock.uptimeMillis() - start);
        }
        return charged(admission, key, safe);
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
        final ImageBudget.Admission admission = mBudget.admit(key);
        if (admission == ImageBudget.Admission.REFUSED) {
            return null;
        }
        final Context appContext = contextFor(pkg);
        if (appContext == null) {
            return null;
        }
        final Drawable drawable;
        try {
            drawable = appContext.getDrawable(icon.getResId());
        } catch (RuntimeException e) {
            Log.w(TAG, "layout resource icon not loaded: " + e);
            return null;
        }
        // A bitmap file comes back as the bitmap the image gate made in the VM, already charged. Anything else (XML)
        // is drawn here, and its pixels are charged.
        final Bitmap made = madeBitmap(drawable);
        final Bitmap bitmap = made != null ? made : draw(drawable);
        if (bitmap == null) {
            return null;
        }
        final Icon safe = Icon.createWithBitmap(bitmap);
        if (icon.hasTint()) {
            safe.setTintList(icon.getTintList());
            safe.setTintBlendMode(icon.getTintBlendMode());
        }
        return made != null ? safe : charged(admission, key, safe);
    }

    private Icon charged(ImageBudget.Admission admission, String key, Icon safe) {
        if (safe == null || admission != ImageBudget.Admission.NEW || key == null) {
            return safe;
        }
        final Bitmap bitmap = safe.getBitmap();
        final long pixels = bitmap == null ? 0 : (long) bitmap.getWidth() * bitmap.getHeight();
        return mBudget.charge(key, pixels) ? safe : null;
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

    // The drawable drawn into a bitmap within the layout box.
    private Bitmap draw(Drawable drawable) {
        if (drawable == null) {
            return null;
        }
        int width = drawable.getIntrinsicWidth();
        int height = drawable.getIntrinsicHeight();
        if (width <= 0 || height <= 0) {
            width = mIconPx;
            height = mIconPx;
        }
        final float scale = Math.min(1f, Math.min((float) mPictureWidth / width, (float) mPictureHeight / height));
        width = Math.max(1, Math.round(width * scale));
        height = Math.max(1, Math.round(height * scale));
        final Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        drawable.setBounds(0, 0, width, height);
        drawable.draw(new Canvas(bitmap));
        return VmIcons.immutable(bitmap);
    }
}
