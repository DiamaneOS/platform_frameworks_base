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
import android.os.Binder;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Slog;

import de.diamaneos.decode.client.OwnerQuota;
import de.diamaneos.decode.client.VmDecode;
import de.diamaneos.decode.client.VmIcons;

import java.util.ArrayList;
import java.util.concurrent.Semaphore;

/**
 * At post time, before any listener sees the notification, the images an app packs into it as bytes are re-made from
 * pixels decoded in the protected VM: resource icons that are bitmap files in the app's own APK, and icons made with
 * createWithData. Nothing is decoded in the system server: resource bytes are read as an opaque blob.
 *
 * <p>That is the smallest decode surface for these icons. Every listener (SystemUI, the shell, other notification
 * listeners) then gets bitmaps, and SystemUI's status bar, which draws small icons on the main thread, never meets an
 * app's compressed image. Icons given as URIs are left to SystemUI, which decodes them in the VM with the poster's URI
 * grants. Resources of the system image are kept as they are.
 *
 * <p>Nothing of the app is parsed or drawn here either: an XML resource icon (a vector, or XML that names a bitmap
 * file) is kept as it is, and SystemUI loads it through its image gate in whatever configuration it shows it.
 *
 * <p>All images of one notification share {@link #BUDGET_MS}, one posted on a thread of the system server itself (a
 * foreground service notification from the activity manager's thread) only {@link #SYSTEM_THREAD_MS}. Each app has a quota across its notifications
 * and everyone shares one more ({@link OwnerQuota}): the time is reserved before the work and settled after it, so an
 * app that overran waits its debt out. At most {@link #MAX_CONCURRENT} notifications wait for the VM at once: any
 * others use only images decoded before. An image that cannot be made in time is removed, a small icon becomes a
 * neutral system icon. Decoded resources are cached per package version.
 */
final class NotificationVmIcons {
    private static final String TAG = "NotificationVmIcons";
    static final long BUDGET_MS = 2_000;
    /** The most a notification posted on a system server thread waits (the activity manager's, for services). */
    static final long SYSTEM_THREAD_MS = 250;
    /** Notifications that may wait for the VM at the same time. */
    static final int MAX_CONCURRENT = 2;

    // Time each app may make the posting threads wait for the VM, across its notifications, and all apps together.
    private static final OwnerQuota sQuota = new OwnerQuota();
    private static final OwnerQuota sAll = new OwnerQuota(8_000, 1_000);
    private static final String ALL = "*";
    private static final Semaphore sWaiting = new Semaphore(MAX_CONCURRENT);

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
    private final boolean mStripOnly;

    // deadline: an uptime by which waiting for the VM ends, or one already passed for images decoded before only.
    private NotificationVmIcons(Context context, String pkg, int userId, boolean stripOnly, long deadline) {
        mContext = context;
        mPkg = pkg;
        mUserId = userId;
        mDeadline = deadline;
        mStripOnly = stripOnly;
    }

    /**
     * Re-makes the app's packed images in {@code n} in place. Call without holding any lock: it may wait for the VM.
     * {@code systemThread}: posted on a thread of the system server itself, which waits {@link #SYSTEM_THREAD_MS} at
     * most.
     */
    static void convert(Context context, Notification n, String pkg, int userId, boolean systemThread) {
        // As the system server, not as the posting app whose binder call this may be: the decode client binds the
        // service and reads packages with the system's identity. The app is the owner charged, as data.
        final long identity = Binder.clearCallingIdentity();
        try {
            convertAsSystem(context, n, pkg, userId, systemThread);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    private static void convertAsSystem(Context context, Notification n, String pkg, int userId,
            boolean systemThread) {
        final long now = SystemClock.uptimeMillis();
        final long want = systemThread ? SYSTEM_THREAD_MS : BUDGET_MS;
        final boolean slot = sWaiting.tryAcquire();
        // Reserved before the work, from the app's quota and from everyone's. Without a slot nothing waits.
        final long mine = slot ? sQuota.reserve(pkg, want, now) : 0;
        final long all = mine > 0 ? sAll.reserve(ALL, mine, now) : 0;
        final long time = Math.min(mine, all);
        final long start = SystemClock.uptimeMillis();
        try {
            // No time: images decoded before only (a deadline already passed never reaches the VM).
            final long deadline = time > 0 ? VmDecode.deadlineIn(time) : 1;
            new NotificationVmIcons(context, pkg, userId, false, deadline).convert(n, 0);
        } finally {
            final long used = SystemClock.uptimeMillis() - start;
            final long end = SystemClock.uptimeMillis();
            if (slot) {
                sQuota.settle(pkg, mine, used, end);
                sWaiting.release();
            }
            if (all > 0) {
                sAll.settle(ALL, all, used, end);
            }
        }
    }

    /**
     * After {@link #convert} threw: removes every app image that would need the VM, decoding nothing and reading no
     * package, so the notification still shows with its text. A small icon becomes the neutral system icon. A value
     * that cannot be read is removed too.
     */
    static void strip(Context context, Notification n, String pkg, int userId) {
        final long identity = Binder.clearCallingIdentity();
        try {
            new NotificationVmIcons(context, pkg, userId, true, 1).convert(n, 0);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    private void convert(Notification n, int depth) {
        final Icon small = n.getSmallIcon();
        final Icon safeSmall = safe(small, ICON_PX, ICON_PX);
        if (safeSmall != small) {
            n.setSmallIcon(safeSmall != null ? safeSmall
                    : Icon.createWithResource("android", android.R.drawable.ic_dialog_info));
        }
        if (n.extras != null) {
            // Typed reads, each on its own: a value of another class is never made, one that cannot be read is
            // removed and the others are still converted.
            for (String key : ICON_EXTRAS) {
                if (!n.extras.containsKey(key)) {
                    continue;
                }
                try {
                    final Icon value = n.extras.getParcelable(key, Icon.class);
                    if (value != null) {
                        final int px = Notification.EXTRA_PICTURE_ICON.equals(key) ? PICTURE_PX : ICON_PX;
                        final Icon safe = safe(value, px, px);
                        if (safe == null) {
                            n.extras.remove(key);
                        } else if (safe != value) {
                            n.extras.putParcelable(key, safe);
                        }
                    }
                } catch (RuntimeException e) {
                    Slog.w(TAG, "notification extra not readable, removed: " + key);
                    n.extras.remove(key);
                }
            }
            convertPerson(n.extras, Notification.EXTRA_MESSAGING_PERSON);
            convertPerson(n.extras, Notification.EXTRA_CALL_PERSON);
            try {
                final ArrayList<Person> people = n.extras.getParcelableArrayList(Notification.EXTRA_PEOPLE_LIST,
                        Person.class);
                if (people != null) {
                    final ArrayList<Person> safePeople = new ArrayList<>(people.size());
                    for (Person p : people) {
                        safePeople.add(safe(p));
                    }
                    n.extras.putParcelableArrayList(Notification.EXTRA_PEOPLE_LIST, safePeople);
                }
            } catch (RuntimeException e) {
                Slog.w(TAG, "notification people not readable, removed");
                n.extras.remove(Notification.EXTRA_PEOPLE_LIST);
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
        // The bubble icon: made into pixels like the others. Its Icon is this notification's own, so it is changed
        // in place. One that cannot be made becomes the neutral icon (a bubble needs one).
        final Notification.BubbleMetadata bubble = n.getBubbleMetadata();
        final Icon bubbleIcon = bubble == null ? null : bubble.getIcon();
        if (bubbleIcon != null) {
            final Icon safe = safe(bubbleIcon, ICON_PX, ICON_PX);
            if (safe != bubbleIcon) {
                bubbleIcon.setTo(safe != null ? safe
                        : Icon.createWithResource("android", android.R.drawable.ic_dialog_info));
            }
        }
        if (n.publicVersion != null && depth == 0) {
            convert(n.publicVersion, depth + 1);
        }
    }

    private void convertPerson(Bundle extras, String key) {
        try {
            final Person person = extras.getParcelable(key, Person.class);
            final Person safe = safe(person);
            if (safe != person) {
                extras.putParcelable(key, safe);
            }
        } catch (RuntimeException e) {
            Slog.w(TAG, "notification person not readable, removed: " + key);
            extras.remove(key);
        }
    }

    private void convertMessages(Bundle extras, String key) {
        final Bundle[] messages;
        try {
            messages = extras.getParcelableArray(key, Bundle.class);
        } catch (RuntimeException e) {
            Slog.w(TAG, "notification messages not readable, removed: " + key);
            extras.remove(key);
            return;
        }
        if (messages == null) {
            return;
        }
        boolean changed = false;
        final Bundle[] copies = new Bundle[messages.length];
        for (int i = 0; i < messages.length; i++) {
            if (messages[i] == null) {
                continue;
            }
            final Bundle copy = new Bundle(messages[i]);
            try {
                final Person sender = copy.getParcelable(KEY_SENDER_PERSON, Person.class);
                final Person safe = safe(sender);
                if (safe != sender) {
                    copy.putParcelable(KEY_SENDER_PERSON, safe);
                    changed = true;
                }
            } catch (RuntimeException e) {
                copy.remove(KEY_SENDER_PERSON);
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
        if (mStripOnly) {
            return VmIcons.withoutAppImage(icon);
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
