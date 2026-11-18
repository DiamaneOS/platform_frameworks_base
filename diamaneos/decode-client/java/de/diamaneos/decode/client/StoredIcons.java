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
import android.app.RemoteInput;
import android.content.pm.ApplicationInfo;
import android.graphics.drawable.Icon;
import android.service.notification.StatusBarNotification;

/**
 * Notification icons that the system server keeps and hands to other system processes outside the notification
 * listeners: the notification history (on disk too, read by Settings) and the archive and active list behind
 * {@code getActiveNotifications} and {@code getHistoricalNotifications} for callers with ACCESS_NOTIFICATIONS (the
 * Settings notification log). Settings runs as the system uid and decodes what it gets natively, so only icons that
 * need no decode of an app's bytes are kept: resource icons and pixels. A data or URI icon is replaced by the app's
 * own icon (a resource of the app, from the application info the notification service recorded), or by the generic
 * app icon. A type check only: nothing is decoded or read.
 */
public final class StoredIcons {
    private StoredIcons() {
    }

    /** True for an icon a system process may load without decoding an app's compressed bytes from it. */
    public static boolean needsNoAppBytes(Icon icon) {
        if (icon == null) {
            return true;
        }
        switch (icon.getType()) {
            case Icon.TYPE_RESOURCE:
            case Icon.TYPE_BITMAP:
            case Icon.TYPE_ADAPTIVE_BITMAP:
                return true;
            default:
                return false;
        }
    }

    /**
     * {@code icon} of a notification of {@code packageName} as the history and the archive keep it: itself when it
     * needs no app bytes, else the app's own icon ({@code appInfo}'s, when it is that app's), else the generic app
     * icon.
     */
    public static Icon forSystemReaders(Icon icon, String packageName, ApplicationInfo appInfo) {
        if (needsNoAppBytes(icon)) {
            return icon;
        }
        if (appInfo != null && appInfo.icon != 0 && packageName != null
                && packageName.equals(appInfo.packageName)) {
            return Icon.createWithResource(packageName, appInfo.icon);
        }
        return Icon.createWithResource("android", android.R.drawable.sym_def_app_icon);
    }

    /** The application info the notification service recorded in {@code n}, or null. Typed read. */
    public static ApplicationInfo appInfoOf(Notification n) {
        if (n == null || n.extras == null) {
            return null;
        }
        try {
            return n.extras.getParcelable(Notification.EXTRA_BUILDER_APPLICATION_INFO, ApplicationInfo.class);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * {@code sbn} as handed to a system process outside the listeners: itself when every icon in it needs no app
     * bytes, else a light copy (no large icon, no Parcelable extras) whose small icon, public version's small icon
     * and action icons are replaced as {@link #forSystemReaders} does. The original is not changed.
     */
    public static StatusBarNotification forSystemReaders(StatusBarNotification sbn) {
        if (sbn == null || clean(sbn.getNotification(), 0)) {
            return sbn;
        }
        final ApplicationInfo appInfo = appInfoOf(sbn.getNotification());
        final StatusBarNotification copy = sbn.cloneLight();
        replace(copy.getNotification(), sbn.getPackageName(), appInfo, 0);
        return copy;
    }

    // A light copy keeps the small icon, the actions and the public version: those are what is checked.
    private static boolean clean(Notification n, int depth) {
        if (n == null) {
            return true;
        }
        if (!needsNoAppBytes(n.getSmallIcon()) || !needsNoAppBytes(n.getLargeIcon())) {
            return false;
        }
        if (n.actions != null) {
            for (Notification.Action action : n.actions) {
                if (action != null && !needsNoAppBytes(action.getIcon())) {
                    return false;
                }
            }
        }
        return depth > 0 || clean(n.publicVersion, depth + 1);
    }

    private static void replace(Notification n, String packageName, ApplicationInfo appInfo, int depth) {
        if (n == null) {
            return;
        }
        if (n.getSmallIcon() != null) {
            n.setSmallIcon(forSystemReaders(n.getSmallIcon(), packageName, appInfo));
        }
        if (n.actions != null) {
            for (int i = 0; i < n.actions.length; i++) {
                final Notification.Action action = n.actions[i];
                if (action != null && !needsNoAppBytes(action.getIcon())) {
                    n.actions[i] = withoutIcon(action);
                }
            }
        }
        if (depth == 0) {
            replace(n.publicVersion, packageName, appInfo, depth + 1);
        }
    }

    private static Notification.Action withoutIcon(Notification.Action action) {
        final Notification.Action.Builder builder =
                new Notification.Action.Builder((Icon) null, action.title, action.actionIntent)
                        .setAllowGeneratedReplies(action.getAllowGeneratedReplies())
                        .setSemanticAction(action.getSemanticAction())
                        .setContextual(action.isContextual())
                        .setAuthenticationRequired(action.isAuthenticationRequired());
        if (action.getRemoteInputs() != null) {
            for (RemoteInput input : action.getRemoteInputs()) {
                builder.addRemoteInput(input);
            }
        }
        return builder.build();
    }
}
