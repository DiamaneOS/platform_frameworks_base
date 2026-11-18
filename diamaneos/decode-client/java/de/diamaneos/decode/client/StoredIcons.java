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
 * Settings notification log). Settings runs as the system uid and decodes what it gets natively. So only pixels, the
 * framework's resources and the app's own launcher icon (from the application info the notification service
 * recorded) are kept: every other icon (data, URI, any other resource of an app) is replaced by that launcher icon,
 * or by the generic app icon. Settings then decodes at most the app's launcher icon, the file the launcher and
 * Settings' app list decode anyway. A type check only: nothing is decoded or read.
 */
public final class StoredIcons {
    private StoredIcons() {
    }

    /**
     * True for an icon a system process may load without decoding compressed bytes an app handed over at post time:
     * pixels and resource icons. History files on disk keep that (they are written from what
     * {@link #forSystemReaders(Icon, String, ApplicationInfo)} kept).
     */
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

    // True for an icon kept for system readers of a notification of packageName: pixels, a framework resource, or
    // the app's launcher icon. Without the application info only the type is checked (needsNoAppBytes).
    private static boolean kept(Icon icon, String packageName, ApplicationInfo appInfo) {
        if (appInfo == null || icon == null) {
            return needsNoAppBytes(icon);
        }
        switch (icon.getType()) {
            case Icon.TYPE_BITMAP:
            case Icon.TYPE_ADAPTIVE_BITMAP:
                return true;
            case Icon.TYPE_RESOURCE:
                final String resPackage = icon.getResPackage() == null || icon.getResPackage().isEmpty()
                        ? packageName : icon.getResPackage();
                if ("android".equals(resPackage)) {
                    return true;
                }
                return launcherIconOf(packageName, appInfo) && resPackage.equals(packageName)
                        && icon.getResId() == appInfo.icon;
            default:
                return false;
        }
    }

    private static boolean launcherIconOf(String packageName, ApplicationInfo appInfo) {
        return appInfo != null && appInfo.icon != 0 && packageName != null
                && packageName.equals(appInfo.packageName);
    }

    /**
     * {@code icon} of a notification of {@code packageName} as the history and the archive keep it: itself when it is
     * pixels, a framework resource or the app's launcher icon ({@code appInfo}'s, when it is that app's), else that
     * launcher icon, else the generic app icon. Without {@code appInfo} (history files read back) resource icons are
     * kept and data and URI icons become the generic app icon.
     */
    public static Icon forSystemReaders(Icon icon, String packageName, ApplicationInfo appInfo) {
        if (kept(icon, packageName, appInfo)) {
            return icon;
        }
        if (launcherIconOf(packageName, appInfo)) {
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
     * {@code sbn} as handed to a system process outside the listeners: itself when every icon in it is kept, else a
     * light copy (no large icon, no Parcelable extras) whose small icon, public version's small icon and action icons
     * are replaced as {@link #forSystemReaders} does. The original is not changed. Never throws on what an app put in
     * a notification: if the copy cannot be made, a light copy with the generic icon and no actions or public version.
     */
    public static StatusBarNotification forSystemReaders(StatusBarNotification sbn) {
        if (sbn == null) {
            return null;
        }
        try {
            final ApplicationInfo appInfo = appInfoOf(sbn.getNotification());
            if (clean(sbn.getNotification(), sbn.getPackageName(), appInfo, 0)) {
                return sbn;
            }
            final StatusBarNotification copy = sbn.cloneLight();
            replace(copy.getNotification(), sbn.getPackageName(), appInfo, 0);
            return copy;
        } catch (RuntimeException e) {
            final StatusBarNotification copy = sbn.cloneLight();
            final Notification n = copy.getNotification();
            n.setSmallIcon(Icon.createWithResource("android", android.R.drawable.sym_def_app_icon));
            n.actions = null;
            n.publicVersion = null;
            return copy;
        }
    }

    // A light copy keeps the small icon, the actions and the public version: those are what is checked.
    private static boolean clean(Notification n, String packageName, ApplicationInfo appInfo, int depth) {
        if (n == null) {
            return true;
        }
        if (!kept(n.getSmallIcon(), packageName, appInfo) || !kept(n.getLargeIcon(), packageName, appInfo)) {
            return false;
        }
        if (n.actions != null) {
            for (Notification.Action action : n.actions) {
                if (action != null && !kept(action.getIcon(), packageName, appInfo)) {
                    return false;
                }
            }
        }
        return depth > 0 || clean(n.publicVersion, packageName, appInfo, depth + 1);
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
                if (action != null && !kept(action.getIcon(), packageName, appInfo)) {
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
