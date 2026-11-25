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
import android.content.ContentValues;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.UserHandle;
import android.util.SparseBooleanArray;

import com.android.internal.diamaneos.cleancopies.CleanCopyClient;
import com.android.internal.diamaneos.cleancopies.CleanCopyGate;

import de.diamaneos.decode.client.CleanImageStore;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.OutputStream;
import java.util.List;

/**
 * DiamaneOS clean-copy readback and resource requests for admitted system processes. Direct reads are local to a
 * user. Cross-profile resource reads require an item grant, a current rendering role and a visible profile.
 * Shortcut input comes from the actual LauncherApps operation, never an attested caller-supplied descriptor.
 */

public final class CleanImageProvider extends ContentProvider {
    private static final String CLIENT_TESTS = "de.diamaneos.decode.client.tests";
    private final SparseBooleanArray mAllowed = new SparseBooleanArray(); // allowed uids, guarded by itself
    private int mAllowedGeneration; // the packages generation mAllowed was made in, guarded by mAllowed

    @Override
    public boolean onCreate() {
        return true;
    }

    private void enforceSystemCaller() {
        final int uid = Binder.getCallingUid();
        final int appId = UserHandle.getAppId(uid);
        if (appId == Process.SYSTEM_UID || appId == Process.PHONE_UID) {
            return;
        }
        if (Build.IS_DEBUGGABLE && uid == Process.ROOT_UID) {
            return; // adb root, for the phone checks.
        }
        final int generation = CleanImageMaker.get(getContext()).packagesGeneration();
        synchronized (mAllowed) {
            if (mAllowedGeneration != generation) {
                // Packages changed: a uid may now run another package.
                mAllowed.clear();
                mAllowedGeneration = generation;
            }
            if (mAllowed.get(uid)) {
                return;
            }
        }
        final boolean allowed = admitted(getContext(), uid);
        if (!allowed) {
            throw new SecurityException("clean images are for system processes only");
        }
        synchronized (mAllowed) {
            if (mAllowedGeneration == generation) {
                mAllowed.put(uid, true);
            }
        }
    }

    static boolean admitted(android.content.Context context, int uid) {
        final int appId = UserHandle.getAppId(uid);
        if (appId == Process.SYSTEM_UID || appId == Process.PHONE_UID || (Build.IS_DEBUGGABLE && uid == 0)) {
            return true;
        }
        boolean allowed = false;
        final long identity = Binder.clearCallingIdentity();
        try {
            final PackageManager pm = context.getPackageManager();
            final String[] packages = pm.getPackagesForUid(uid);
            if (packages != null) {
                for (String name : packages) {
                    try {
                        final ApplicationInfo ai = pm.getApplicationInfoAsUser(name, 0, UserHandle.getUserId(uid));
                        allowed |= CleanCopyGate.gatedApp(ai, name)
                                || (Build.IS_DEBUGGABLE && CLIENT_TESTS.equals(name));
                    } catch (PackageManager.NameNotFoundException e) {
                        // Not this one.
                    }
                }
            }
        } catch (RuntimeException e) {
            return false;
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
        return allowed;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        enforceSystemCaller();
        if (!"r".equals(mode)) {
            throw new SecurityException("clean images are read-only");
        }
        final List<String> path = uri.getPathSegments();
        final CleanImageMaker maker = CleanImageMaker.get(getContext());
        final CleanImageStore store = maker.store();
        final File file = path.size() == 1 ? store.fileNamed(path.get(0)) : null;
        // Only a copy of a user the caller may see: never another profile's, whatever name it asks for.
        if (file == null || !file.isFile()
                || !maker.mayRead(Binder.getCallingUid(), Binder.getCallingPid(), file.getName())) {
            throw new FileNotFoundException("no clean copy");
        }
        final int callerUid = Binder.getCallingUid();
        final int callerPid = Binder.getCallingPid();
        store.touch(file.getName());
        // Handed out through a pipe the system server writes (as the text classifier's icon provider does), so the
        // caller needs no access to the system server's files.
        try {
            return openPipeHelper(uri, "application/octet-stream", null, file, (out, u, type, opts, f) -> {
                if (!admitted(getContext(), callerUid) || !maker.mayRead(callerUid, callerPid, f.getName())) {
                    closeQuietly(out);
                    return;
                }
                try (FileInputStream in = new FileInputStream(f);
                        OutputStream os = new ParcelFileDescriptor.AutoCloseOutputStream(out)) {
                    final byte[] buffer = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buffer)) > 0) {
                        os.write(buffer, 0, n);
                    }
                } catch (IOException e) {
                    // The caller sees a short copy, which its check refuses.
                }
            });
        } catch (IOException e) {
            throw new FileNotFoundException("no pipe: " + e.getMessage());
        }
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        enforceSystemCaller();
        if (extras == null) {
            return null;
        }
        final CleanImageMaker maker = CleanImageMaker.get(getContext());
        final int uid = Binder.getCallingUid();
        // The user a copy is asked for: only one the caller may see.
        final int user = extras.getInt(CleanCopyClient.EXTRA_USER, -1);
        if (CleanCopyClient.METHOD_REQUEST.equals(method)) {
            if (maker.authorizeRequest(uid, Binder.getCallingPid(),
                    extras.getString(CleanCopyClient.EXTRA_APK), extras.getString(CleanCopyClient.EXTRA_FILE),
                    extras.getInt(CleanCopyClient.EXTRA_DENSITY), extras.getInt(CleanCopyClient.EXTRA_WIDTH),
                    extras.getInt(CleanCopyClient.EXTRA_HEIGHT), user)) {
                maker.request(extras.getString(CleanCopyClient.EXTRA_APK),
                        extras.getString(CleanCopyClient.EXTRA_FILE), extras.getInt(CleanCopyClient.EXTRA_DENSITY),
                        extras.getInt(CleanCopyClient.EXTRA_WIDTH), extras.getInt(CleanCopyClient.EXTRA_HEIGHT),
                        extras.getString(CleanCopyClient.EXTRA_RESOURCE), user, true, 0);
            }
        } else if (CleanCopyClient.METHOD_FALLBACK.equals(method)) {
            final Bundle reply = new Bundle();
            final String apk = extras.getString(CleanCopyClient.EXTRA_APK);
            final String member = extras.getString(CleanCopyClient.EXTRA_FILE);
            final int density = extras.getInt(CleanCopyClient.EXTRA_DENSITY);
            final int width = extras.getInt(CleanCopyClient.EXTRA_WIDTH);
            final int height = extras.getInt(CleanCopyClient.EXTRA_HEIGHT);
            final String fallback = maker.authorizeRequest(uid, Binder.getCallingPid(), apk, member,
                    density, width, height, user) ? maker.fallbackKey(apk, member, density, width, height,
                    extras.getString(CleanCopyClient.EXTRA_RESOURCE), user) : null;
            maker.grantFallback(uid, apk, user, fallback);
            reply.putString(CleanCopyClient.EXTRA_KEY, fallback);
            return reply;
        } else if (CleanCopyClient.METHOD_WATCH.equals(method)) {
            final Bundle reply = new Bundle();
            reply.putBoolean(CleanCopyClient.METHOD_WATCH,
                    maker.watch(extras.getBinder(CleanCopyClient.EXTRA_CALLBACK), uid, Binder.getCallingPid()));
            return reply;
        } else if (CleanCopyClient.METHOD_SHORTCUT.equals(method)) {
            // The caller's own copy of the icon is not used: ShortcutAccess opens the shortcut's input itself.
            try (ParcelFileDescriptor supplied = extras.getParcelable(CleanCopyClient.EXTRA_FD,
                    ParcelFileDescriptor.class)) {
                if (!maker.shortcutCaller(uid)) {
                    throw new SecurityException("shortcut icons are for the home app, the share sheet and SystemUI");
                }
                try (ShortcutAccess input = ShortcutAccess.resolve(getContext(), uid,
                        extras.getString("callingPackage"), extras.getString(CleanCopyClient.EXTRA_PACKAGE),
                        extras.getString("shortcutId"), user)) {
                    final Bundle reply = new Bundle();
                    if (input != null) {
                        reply.putParcelable(CleanCopyClient.EXTRA_BITMAP, maker.shortcutIcon(input, uid));
                    }
                    return reply;
                }
            } catch (IOException e) {
                return new Bundle();
            }
        } else if (com.android.internal.diamaneos.cleancopies.VmThumbnails.METHOD_THUMBNAIL.equals(method)) {
            // A provider's thumbnail for the system process that loads it, decoded in the VM (VmThumbnailDecoder).
            // The user the authority names is the image's: the caller may name it only when it may act for it.
            final String authority = extras.getString(
                    com.android.internal.diamaneos.cleancopies.VmThumbnails.EXTRA_AUTHORITY);
            final int named = authority == null ? -1
                    : android.content.ContentProvider.getUserIdFromAuthority(authority, UserHandle.getUserId(uid));
            final int owner = OwnerUsers.of(getContext(), uid, named);
            if (owner < 0) {
                closeQuietly(extras.getParcelable(CleanCopyClient.EXTRA_FD, ParcelFileDescriptor.class));
                final Bundle reply = new Bundle();
                reply.putString(com.android.internal.diamaneos.cleancopies.VmThumbnails.EXTRA_ERROR,
                        "may not name user " + named);
                return reply;
            }
            return VmThumbnailDecoder.get(getContext()).thumbnail(extras, uid, owner);
        } else if (CleanCopyClient.METHOD_FORGET.equals(method)) {
            // Tests only: a user build keeps every copy.
            if (!Build.IS_DEBUGGABLE) {
                throw new SecurityException("debuggable builds only");
            }
            maker.forget(extras.getString(CleanCopyClient.EXTRA_APK));
        } else if (CleanCopyClient.METHOD_STATUS.equals(method)) {
            List<String> keys = extras.getStringArrayList(CleanCopyClient.EXTRA_KEYS);
            if (keys != null && keys.size() > 1024) {
                keys = keys.subList(0, 1024);
            }
            final Bundle reply = new Bundle();
            reply.putInt(CleanCopyClient.EXTRA_OPEN, keys == null ? 0 : maker.open(keys, uid, Binder.getCallingPid()));
            return reply;
        }
        return null;
    }

    private static void closeQuietly(ParcelFileDescriptor fd) {
        try {
            if (fd != null) {
                fd.close();
            }
        } catch (IOException ignored) {
            // Closed.
        }
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
            String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
