/*
 * Copyright (C) 2020 The Android Open Source Project
 * Copyright (C) 2026 The DiamaneOS Project
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

package com.android.systemui.toast;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.Application;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.graphics.drawable.Drawable;
import android.os.UserHandle;
import android.util.Log;

import com.android.systemui.notifications.content.icon.AppIconProvider;

/** The app icon of a Tally toast. */
final class TallyToastIcon {
    private static final String TOAST_ICON_KEY = "TOAST";

    /**
     * Tally: the app's icon in the user's icon style, as Home and the notifications draw it, where
     * {@link SystemUIToast#getBadgedIcon} would show one.
     */
    @Nullable
    static Drawable getStyledIcon(@NonNull Context context,
            @NonNull AppIconProvider appIconProvider, String packageName, int userId) {
        if (!(context.getApplicationContext() instanceof Application)) {
            return null;
        }
        try {
            final PackageManager packageManager = context.getPackageManager();
            final ApplicationInfo appInfo = packageManager.getApplicationInfoAsUser(
                    packageName, PackageManager.ApplicationInfoFlags.of(0), userId);
            if (appInfo == null || !SystemUIToast.showApplicationIcon(appInfo, packageManager)) {
                return null;
            }
            final Drawable icon = appIconProvider.getOrFetchAppIcon(packageName,
                    UserHandle.getUserHandleForUid(appInfo.uid), TOAST_ICON_KEY);
            if (icon == null) {
                return null;
            }
            // The cache shares one drawable per key, and two toasts can show at once.
            final Drawable.ConstantState state = icon.getConstantState();
            return state != null ? state.newDrawable() : icon;
        } catch (NameNotFoundException e) {
            Log.e(SystemUIToast.TAG, "Couldn't find application info for packageName=" + packageName
                    + " userId=" + userId);
            return null;
        }
    }
}
