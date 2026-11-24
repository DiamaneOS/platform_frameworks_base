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

package com.android.server.cleanimages;

import android.content.ContentProvider;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.pm.UserInfo;
import android.os.Binder;
import android.os.Process;
import android.os.UserHandle;
import android.os.UserManager;

/** A shared MediaProvider may label its thumbnail with an independently verified clone user. */
final class MediaThumbnailUsers {
    private static final String MEDIA = "com.android.providers.media.module";

    static int of(Context context, String authority, int providerUser, int callingUid, int mediaUser) {
        if (mediaUser < 0 || mediaUser == providerUser) return providerUser;
        if (authority == null || authority.length() > 1024 || providerUser < 0) return -1;
        final long identity = Binder.clearCallingIdentity();
        try {
            final PackageManager packages = context.getPackageManager();
            final ProviderInfo provider = packages.resolveContentProviderAsUser(
                    ContentProvider.getAuthorityWithoutUserId(authority), 0, providerUser);
            if (provider == null || !MEDIA.equals(provider.packageName) || provider.applicationInfo == null) {
                return -1;
            }
            final ApplicationInfo app = packages.getApplicationInfoAsUser(MEDIA, 0, providerUser);
            final String[] names = packages.getPackagesForUid(app.uid);
            if (provider.applicationInfo.uid != app.uid || UserHandle.getUserId(app.uid) != providerUser
                    || UserHandle.getAppId(app.uid) == Process.SYSTEM_UID
                    || (app.flags & ApplicationInfo.FLAG_SYSTEM) == 0 || !app.isPrivilegedApp()
                    || names == null || names.length != 1 || !MEDIA.equals(names[0])) return -1;
            final UserManager users = context.getSystemService(UserManager.class);
            final UserHandle named = UserHandle.of(mediaUser);
            final UserHandle parent = users.getProfileParent(named);
            final UserInfo info = users.getUserInfo(mediaUser);
            if (parent == null || parent.getIdentifier() != providerUser || info == null
                    || !info.isCloneProfile() || !users.getUserProperties(named).isMediaSharedWithParent()) return -1;
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            return -1;
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
        // Keep the caller's existing cross-user permission and profile-group checks.
        return OwnerUsers.of(context, callingUid, mediaUser);
    }
}
