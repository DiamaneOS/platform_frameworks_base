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
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.UserHandle;

import com.android.server.LocalServices;
import com.android.server.pm.UserManagerInternal;

/**
 * DiamaneOS: whose image a caller of the clean image providers may say a request is. Its own user always. Another
 * user only when the caller may act for it: any existing user with INTERACT_ACROSS_USERS_FULL, a user of its own
 * profile group (a work profile, Private Space) with INTERACT_ACROSS_USERS, or without it where the platform has
 * already checked that profile access (a shortcut the home app opened through LauncherApps). The decode service
 * checks the same relation again for the app the system server decodes for.
 */
final class OwnerUsers {
    private OwnerUsers() {
    }

    /**
     * The owner user of a request of the binder caller {@code uid}: {@code named} when it may name it (or a negative
     * {@code named}: its own user), else -1. Call on the binder thread, with the caller's identity.
     */
    static int of(Context context, int uid, int named) {
        return of(context, uid, named, false);
    }

    /** {@link #of}, and with {@code profileChecked} a user of the caller's profile group without a permission. */
    static int of(Context context, int uid, int named, boolean profileChecked) {
        final int own = UserHandle.getUserId(uid);
        if (named < 0 || named == own) {
            return own;
        }
        // Check the authenticated UID for both local and remote provider calls.
        final boolean full = context.checkPermission(Manifest.permission.INTERACT_ACROSS_USERS_FULL, -1, uid)
                == PackageManager.PERMISSION_GRANTED;
        final boolean across = full || context.checkPermission(Manifest.permission.INTERACT_ACROSS_USERS, -1, uid)
                == PackageManager.PERMISSION_GRANTED;
        if (!across && !profileChecked) {
            return -1;
        }
        final UserManagerInternal users = LocalServices.getService(UserManagerInternal.class);
        if (users == null || !users.exists(named)) {
            return -1;
        }
        if (!full && users.getProfileParentId(named) != users.getProfileParentId(own)) {
            return -1;
        }
        return named;
    }
}
