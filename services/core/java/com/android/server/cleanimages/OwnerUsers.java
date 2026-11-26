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

import android.Manifest;
import android.app.role.RoleManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.UserInfo;
import android.content.pm.UserProperties;
import android.os.Binder;
import android.os.UserHandle;
import android.os.UserManager;

import com.android.server.LocalServices;
import com.android.server.pm.UserManagerInternal;

import java.util.Collections;
import java.util.List;

/**
 * DiamaneOS: whose image a caller of the clean image providers may say a request is. Its own user always. Another
 * user only when the caller may act for it, with the profile rule of the clean copies (CleanImageMaker.mayRequest):
 * the user is enabled, unlocked and not in quiet mode, a hidden profile (Private Space) only for a caller with
 * ACCESS_HIDDEN_PROFILES_FULL or the home app with ACCESS_HIDDEN_PROFILES. Which users, as Android's
 * ActivityManager.handleIncomingUser lets a caller act for them: any user with INTERACT_ACROSS_USERS_FULL, a user of
 * the caller's profile group with INTERACT_ACROSS_USERS, none without a permission. The decode service checks the same
 * again for the app the system server decodes for.
 */
final class OwnerUsers {
    private OwnerUsers() {
    }

    /**
     * The owner user of a request of the binder caller {@code uid}: {@code named} when it may name it (or a negative
     * {@code named}: its own user), else -1. Call on the binder thread, with the caller's identity.
     */
    static int of(Context context, int uid, int named) {
        final int own = UserHandle.getUserId(uid);
        if (named < 0 || named == own) {
            return own;
        }
        // Check the authenticated UID for both local and remote provider calls.
        final boolean full = granted(context, Manifest.permission.INTERACT_ACROSS_USERS_FULL, uid);
        final boolean across = full || granted(context, Manifest.permission.INTERACT_ACROSS_USERS, uid);
        if (!across) {
            return -1;
        }
        final long identity = Binder.clearCallingIdentity();
        try {
            return mayName(context, uid, own, named, full) ? named : -1;
        } catch (RuntimeException e) {
            return -1;
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    /**
     * The owner user of an image whose bytes the caller sent itself (a data icon): {@code named} where Android lets the
     * caller act for that user (INTERACT_ACROSS_USERS_FULL for any user, INTERACT_ACROSS_USERS within its profile
     * group), else -1. The bytes are the caller's own memory, so naming a user only sets the answer's user and lane.
     * Never for a request that names a source: that is acquired by the system server ({@code ForeignSources}).
     */
    static int forBytes(Context context, int uid, int named, String source) {
        return source == null ? of(context, uid, named) : -1;
    }

    private static boolean mayName(Context context, int uid, int own, int named, boolean full) {
        final UserManager users = context.getSystemService(UserManager.class);
        final UserInfo info = users.getUserInfo(named);
        if (info == null || !info.isEnabled() || info.partial || users.isQuietModeEnabled(UserHandle.of(named))
                || !users.isUserUnlocked(named)) {
            return false;
        }
        if (!full) {
            // INTERACT_ACROSS_USERS: within the caller's profile group, as ActivityManager.handleIncomingUser lets it.
            final UserHandle parent = users.getProfileParent(UserHandle.of(named));
            final UserHandle ownParent = users.getProfileParent(UserHandle.of(own));
            final int group = parent == null ? named : parent.getIdentifier();
            if (group != (ownParent == null ? own : ownParent.getIdentifier())) {
                return false;
            }
        }
        final UserManagerInternal internal = LocalServices.getService(UserManagerInternal.class);
        final UserProperties properties = internal == null ? null : internal.getUserProperties(named);
        if (properties == null) {
            return false;
        }
        if (properties.getProfileApiVisibility() != UserProperties.PROFILE_API_VISIBILITY_HIDDEN
                || granted(context, Manifest.permission.ACCESS_HIDDEN_PROFILES_FULL, uid)) {
            return true;
        }
        final List<String> home = context.getSystemService(RoleManager.class)
                .getRoleHoldersAsUser(RoleManager.ROLE_HOME, UserHandle.getUserHandleForUid(uid));
        final String[] packages = context.getPackageManager().getPackagesForUid(uid);
        return packages != null && !Collections.disjoint(home, List.of(packages))
                && granted(context, Manifest.permission.ACCESS_HIDDEN_PROFILES, uid);
    }

    private static boolean granted(Context context, String permission, int uid) {
        return context.checkPermission(permission, -1, uid) == PackageManager.PERMISSION_GRANTED;
    }
}
