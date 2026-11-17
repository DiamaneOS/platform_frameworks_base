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
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.os.Looper;
import android.os.Parcelable;
import android.widget.RemoteViews;

import java.util.ArrayList;

/**
 * Makes a copy of a notification in which no image needs decoding from another app's compressed bytes: every icon
 * given as a URI or as createWithData bytes is replaced by a bitmap icon decoded in the VM, or removed when that
 * fails. The copy is what SystemUI inflates its views from, so the framework's own image loading (Icon.loadDrawable,
 * LocalImageResolver) never sees such an icon.
 *
 * <p>Covered: the small and large icons, the big picture and big large icon, the conversation, verification and
 * progress icons, every Person (messaging user, senders, call person, people list), action icons, and the public
 * version. A custom layout (RemoteViews) is kept only when it references no URI and every Icon in it is a bitmap:
 * otherwise it is dropped and the standard layout is shown. Image messages (MessagingStyle data URIs and remote input
 * history) are resolved by SystemUI's resolver, which uses the VM too.
 *
 * <p>All decoding for one notification shares one budget ({@link #BUDGET_MS}): a VM that hangs costs placeholders,
 * not a stuck notification. On the main thread nothing is decoded: VM icons are removed straight away.
 */
public final class NotificationImageGate {
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

    /** The time all images of one notification may take in the VM together. */
    public static final long BUDGET_MS = 2_000;

    private final Context mContext;
    private final String mOwner;
    private final int mIconPx;
    private final int mPictureWidth;
    private final int mPictureHeight;
    private final boolean mDecode;
    private final long mDeadline;

    private NotificationImageGate(Context context, String ownerPackage, int iconPx, int pictureWidth,
            int pictureHeight) {
        mContext = context;
        mOwner = ownerPackage;
        mIconPx = iconPx;
        mPictureWidth = pictureWidth;
        mPictureHeight = pictureHeight;
        mDecode = Looper.myLooper() != Looper.getMainLooper();
        mDeadline = VmDecode.deadlineIn(BUDGET_MS);
    }

    /**
     * The checked copy of {@code notification}, or {@code notification} itself when it holds nothing to check.
     * {@code iconPx} bounds icons and people, {@code pictureWidth} x {@code pictureHeight} the big picture (the size
     * the template shows it at, not the screen). {@code ownerPackage} is the app that posted it. The original is not
     * changed.
     */
    public static Notification sanitizedCopy(Notification notification, Context context, String ownerPackage,
            int iconPx, int pictureWidth, int pictureHeight) {
        if (!needsWork(notification, 0)) {
            return notification;
        }
        final Notification copy = notification.clone();
        new NotificationImageGate(context, ownerPackage, iconPx, pictureWidth, pictureHeight).sanitize(copy, 0);
        return copy;
    }

    /** True when {@code n} holds an app image to decode or a custom layout with a URI. */
    private static boolean needsWork(Notification n, int depth) {
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
        if (!isSafe(n.contentView) || !isSafe(n.bigContentView) || !isSafe(n.headsUpContentView)
                || !isSafe(n.tickerView)) {
            return true;
        }
        return n.publicVersion != null && depth == 0 && needsWork(n.publicVersion, depth + 1);
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

    /**
     * True for no layout, or a layout that references no URI and holds only bitmap Icons. Any other Icon (a URI,
     * createWithData bytes, an app resource) would be decoded by SystemUI when the layout is applied.
     */
    private static boolean isSafe(RemoteViews views) {
        if (views == null) {
            return true;
        }
        final boolean[] unsafe = {false};
        views.visitUris(uri -> unsafe[0] = true);
        views.visitIcons(icon -> {
            if (icon != null && icon.getType() != Icon.TYPE_BITMAP
                    && icon.getType() != Icon.TYPE_ADAPTIVE_BITMAP) {
                unsafe[0] = true;
            }
        });
        return !unsafe[0];
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
        n.contentView = withoutUris(n.contentView);
        n.bigContentView = withoutUris(n.bigContentView);
        n.headsUpContentView = withoutUris(n.headsUpContentView);
        n.tickerView = withoutUris(n.tickerView);
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

    private Icon safe(Icon icon, int maxWidth, int maxHeight) {
        if (!VmIcons.needsVm(icon)) {
            return icon;
        }
        return mDecode ? VmIcons.toSafeIcon(icon, mContext, mOwner, maxWidth, maxHeight, mDeadline) : null;
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

    private static RemoteViews withoutUris(RemoteViews views) {
        return isSafe(views) ? views : null;
    }
}
