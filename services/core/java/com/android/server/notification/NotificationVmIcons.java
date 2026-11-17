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

package com.android.server.notification;

import android.app.Notification;
import android.app.Person;
import android.app.RemoteInput;
import android.content.Context;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.os.Parcelable;

import de.diamaneos.decode.client.VmDecode;
import de.diamaneos.decode.client.VmIcons;

import java.util.ArrayList;

/**
 * At post time, before any listener sees the notification, the images an app packs into it as bytes are re-made from
 * pixels decoded in the protected VM: resource icons that are bitmap files in the app's own APK, and icons made with
 * createWithData. Nothing is decoded in the system server: resource bytes are read as an opaque blob.
 *
 * <p>That is the smallest decode surface for these icons. Every listener (SystemUI, the shell, other notification
 * listeners) then gets bitmaps, and SystemUI's status bar, which draws small icons on the main thread, never meets an
 * app's compressed image. Icons given as URIs are left to SystemUI, which decodes them in the VM with the poster's URI
 * grants. Resources of the system image and XML drawables are kept as they are.
 *
 * <p>All images of one notification share {@link #BUDGET_MS}. An image that cannot be decoded in time is removed, a
 * small icon becomes a neutral system icon. Decoded resources are cached per package version.
 */
final class NotificationVmIcons {
    static final long BUDGET_MS = 2_000;

    // Icons and people are shown at most this big. The big picture at most this wide and high.
    private static final int ICON_PX = 512;
    private static final int PICTURE_PX = 2048;

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

    private final Context mContext;
    private final String mPkg;
    private final int mUserId;
    private final long mDeadline;

    private NotificationVmIcons(Context context, String pkg, int userId) {
        mContext = context;
        mPkg = pkg;
        mUserId = userId;
        mDeadline = VmDecode.deadlineIn(BUDGET_MS);
    }

    /** Re-makes the app's packed images in {@code n} in place. Call without holding any lock: it waits for the VM. */
    static void convert(Context context, Notification n, String pkg, int userId) {
        new NotificationVmIcons(context, pkg, userId).convert(n, 0);
    }

    private void convert(Notification n, int depth) {
        final Icon small = n.getSmallIcon();
        final Icon safeSmall = safe(small, ICON_PX, ICON_PX);
        if (safeSmall != small) {
            n.setSmallIcon(safeSmall != null ? safeSmall
                    : Icon.createWithResource("android", android.R.drawable.ic_dialog_info));
        }
        if (n.extras != null) {
            for (String key : ICON_EXTRAS) {
                final Parcelable value = n.extras.getParcelable(key, Parcelable.class);
                if (value instanceof Icon) {
                    final int px = Notification.EXTRA_PICTURE_ICON.equals(key) ? PICTURE_PX : ICON_PX;
                    final Icon safe = safe((Icon) value, px, px);
                    if (safe == null) {
                        n.extras.remove(key);
                    } else if (safe != value) {
                        n.extras.putParcelable(key, safe);
                    }
                }
            }
            convertPerson(n.extras, Notification.EXTRA_MESSAGING_PERSON);
            convertPerson(n.extras, Notification.EXTRA_CALL_PERSON);
            final ArrayList<Person> people = n.extras.getParcelableArrayList(Notification.EXTRA_PEOPLE_LIST,
                    Person.class);
            if (people != null) {
                final ArrayList<Person> safePeople = new ArrayList<>(people.size());
                for (Person p : people) {
                    safePeople.add(safe(p));
                }
                n.extras.putParcelableArrayList(Notification.EXTRA_PEOPLE_LIST, safePeople);
            }
            convertMessages(n.extras, Notification.EXTRA_MESSAGES);
            convertMessages(n.extras, Notification.EXTRA_HISTORIC_MESSAGES);
        }
        final Icon large = n.getLargeIcon();
        final Icon safeLarge = safe(large, ICON_PX, ICON_PX);
        if (safeLarge != large) {
            // The large icon field has no setter of its own. An adopting builder sets the field and its extra.
            new Notification.Builder(mContext, n).setLargeIcon(safeLarge);
        }
        if (n.actions != null) {
            for (int i = 0; i < n.actions.length; i++) {
                final Notification.Action action = n.actions[i];
                if (action == null) {
                    continue;
                }
                final Icon icon = action.getIcon();
                final Icon safe = safe(icon, ICON_PX, ICON_PX);
                if (safe != icon) {
                    n.actions[i] = withIcon(action, safe);
                }
            }
        }
        if (n.publicVersion != null && depth == 0) {
            convert(n.publicVersion, depth + 1);
        }
    }

    private void convertPerson(Bundle extras, String key) {
        final Person person = extras.getParcelable(key, Person.class);
        final Person safe = safe(person);
        if (safe != person) {
            extras.putParcelable(key, safe);
        }
    }

    private void convertMessages(Bundle extras, String key) {
        final Parcelable[] messages = extras.getParcelableArray(key, Parcelable.class);
        if (messages == null) {
            return;
        }
        boolean changed = false;
        final Bundle[] copies = new Bundle[messages.length];
        for (int i = 0; i < messages.length; i++) {
            if (!(messages[i] instanceof Bundle)) {
                continue;
            }
            final Bundle copy = new Bundle((Bundle) messages[i]);
            final Person sender = copy.getParcelable(KEY_SENDER_PERSON, Person.class);
            final Person safe = safe(sender);
            if (safe != sender) {
                copy.putParcelable(KEY_SENDER_PERSON, safe);
                changed = true;
            }
            copies[i] = copy;
        }
        if (changed) {
            extras.putParcelableArray(key, copies);
        }
    }

    private Person safe(Person person) {
        if (person == null) {
            return null;
        }
        final Icon icon = person.getIcon();
        final Icon safe = safe(icon, ICON_PX, ICON_PX);
        return safe == icon ? person : person.toBuilder().setIcon(safe).build();
    }

    /** The icon itself when nothing needs the VM, a bitmap icon from the VM, or null when that failed. */
    private Icon safe(Icon icon, int maxWidth, int maxHeight) {
        if (icon == null) {
            return null;
        }
        final int type = icon.getType();
        if (type == Icon.TYPE_URI || type == Icon.TYPE_URI_ADAPTIVE_BITMAP) {
            // SystemUI decodes these in the VM, with the poster's URI grants.
            return icon;
        }
        return VmIcons.toSafeIconWithResources(icon, mContext, mUserId, mPkg, maxWidth, maxHeight, mDeadline);
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
        if (action.getRemoteInputs() != null) {
            for (RemoteInput input : action.getRemoteInputs()) {
                builder.addRemoteInput(input);
            }
        }
        if (action.getDataOnlyRemoteInputs() != null) {
            for (RemoteInput input : action.getDataOnlyRemoteInputs()) {
                builder.addRemoteInput(input);
            }
        }
        return builder.build();
    }
}
