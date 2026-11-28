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
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ILauncherApps;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Binder;
import android.os.ParcelFileDescriptor;
import android.os.ServiceManager;

/**
 * Server-held input from the actual shortcut operation authorized by LauncherApps for the Binder caller: the icon file
 * LauncherApps opens, or the icon URI it names, which the system server opens itself as the URI's user once the
 * caller's read access is checked. Both are bound to the shortcut, so its decodes share the shortcut's own item
 * domain.
 */
final class ShortcutAccess implements AutoCloseable {
    // How long the open of an icon URI may take (CleanImageMaker SHORTCUT_MS).
    private static final long OPEN_MS = 1_500;
    final String packageName;
    final String id;
    final int user;
    final int sourceUser;
    private final ParcelFileDescriptor mFile;
    private final Uri mUri;

    private ShortcutAccess(String pkg, String shortcutId, int sourceUser, ParcelFileDescriptor file, Uri uri) {
        packageName = pkg;
        id = shortcutId;
        user = sourceUser;
        mFile = file;
        mUri = uri;
        this.sourceUser = uri == null ? user : ContentProvider.getUserIdFromUri(uri, user);
    }

    static ShortcutAccess resolve(Context context, int uid, String callerPackage, String pkg, String id, int user) {
        if (callerPackage == null || pkg == null || id == null || id.isEmpty() || id.length() > 1000 || user < 0) {
            return null;
        }
        final int callerPid = Binder.getCallingPid();
        final String[] packages = context.getPackageManager().getPackagesForUid(uid);
        if (packages == null || !java.util.List.of(packages).contains(callerPackage)) {
            return null;
        }
        try {
            // This service is local to system_server. Keep the incoming Binder identity for its profile, hidden
            // profile, host role, package visibility and shortcut checks before obtaining either input.
            final ILauncherApps apps = ILauncherApps.Stub.asInterface(
                    ServiceManager.getService(Context.LAUNCHER_APPS_SERVICE));
            if (apps == null) {
                return null;
            }
            final ParcelFileDescriptor file = apps.getShortcutIconFd(callerPackage, pkg, id, user);
            if (file != null) {
                return new ShortcutAccess(pkg, id, user, file, null);
            }
            final String uri = apps.getShortcutIconUri(callerPackage, pkg, id, user);
            if (uri == null) {
                return null;
            }
            final Uri granted = Uri.parse(uri);
            // LauncherApps grants content access. Other schemes confer no input capability.
            if (!ContentResolver.SCHEME_CONTENT.equals(granted.getScheme())) {
                return null;
            }
            final Uri input = ContentProvider.maybeAddUserId(granted, user);
            final long identity = Binder.clearCallingIdentity();
            try {
                if (context.checkContentUriPermissionFull(input, callerPid, uid,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION) != PackageManager.PERMISSION_GRANTED) {
                    return null;
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
            // DiamaneOS: opened here (open), never the caller's own descriptor.
            return new ShortcutAccess(pkg, id, user, null, input);
        } catch (android.os.RemoteException | RuntimeException e) {
            return null;
        }
    }

    /**
     * The lane of {@link OwnerLanes} the URI's open takes: its provider (authority) and user. Null for a file, which
     * LauncherApps opened already.
     */
    String lane() {
        return mUri == null ? null : mUri.getAuthority() + "/" + sourceUser;
    }

    /**
     * The icon's bytes: the file, or the URI opened as its user and copied. The provider is asked to stop after
     * {@link #OPEN_MS}. {@code holds} gets the provider's descriptor as soon as it is open, so a caller that stops
     * waiting can close it and end the read (OwnerLanes).
     */
    ParcelFileDescriptor open(Context context, java.util.function.Consumer<AutoCloseable> holds)
            throws java.io.IOException {
        if (mFile != null) {
            return mFile.dup();
        }
        final android.os.CancellationSignal cancel = new android.os.CancellationSignal();
        final Object token = new Object();
        final long end = android.os.SystemClock.uptimeMillis() + OPEN_MS;
        com.android.internal.os.BackgroundThread.getHandler().postAtTime(cancel::cancel, token, end);
        final long identity = Binder.clearCallingIdentity();
        try (android.content.res.AssetFileDescriptor afd = context.createContextAsUser(
                android.os.UserHandle.of(sourceUser), 0).getContentResolver().openAssetFileDescriptor(
                        ContentProvider.getUriWithoutUserId(mUri), "r", cancel)) {
            if (afd == null) {
                return null;
            }
            holds.accept(afd);
            try (android.graphics.DecodeGate.Input bytes = android.graphics.DecodeGate.snapshot(afd, end)) {
                return bytes.fd().dup();
            }
        } finally {
            Binder.restoreCallingIdentity(identity);
            com.android.internal.os.BackgroundThread.getHandler().removeCallbacksAndMessages(token);
        }
    }

    @Override
    public void close() {
        try {
            if (mFile != null) {
                mFile.close();
            }
        } catch (java.io.IOException e) {
            // The input is retired.
        }
    }
}
