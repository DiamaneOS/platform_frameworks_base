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
 * DiamaneOS: the clean copies of apps' image files, served to system processes (see {@code CleanCopyClient} in
 * the framework). A copy is opened read-only by its file name, a file without a copy can be asked for (the system
 * server then makes it in the VM, {@link CleanImageMaker}), and a shortcut's icon picture can be decoded in the VM from
 * a descriptor the caller opened. Callers are the system uid, the phone uid and the processes of system apps whose
 * images come from clean copies ({@code CleanCopyGate.gatedApp}), and on debuggable builds root and the decode client's
 * tests. Apps, the shell and native services get nothing. Only allowed callers are remembered.
 */
public final class CleanImageProvider extends ContentProvider {
    private static final String CLIENT_TESTS = "de.diamaneos.decode.client.tests";
    private final SparseBooleanArray mAllowed = new SparseBooleanArray(); // allowed uids, guarded by itself

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
        if (Build.IS_DEBUGGABLE && appId == Process.ROOT_UID) {
            return; // adb root, for the phone checks.
        }
        synchronized (mAllowed) {
            if (mAllowed.get(uid)) {
                return;
            }
        }
        boolean allowed = false;
        final long identity = Binder.clearCallingIdentity();
        try {
            final PackageManager pm = getContext().getPackageManager();
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
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
        if (!allowed) {
            throw new SecurityException("clean images are for system processes only");
        }
        synchronized (mAllowed) {
            mAllowed.put(uid, true);
        }
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        enforceSystemCaller();
        if (!"r".equals(mode)) {
            throw new SecurityException("clean images are read-only");
        }
        final List<String> path = uri.getPathSegments();
        final CleanImageStore store = CleanImageMaker.get(getContext()).store();
        final File file = path.size() == 1 ? store.fileNamed(path.get(0)) : null;
        if (file == null || !file.isFile()) {
            throw new FileNotFoundException("no clean copy");
        }
        store.touch(file.getName());
        // Handed out through a pipe the system server writes (as the text classifier's icon provider does), so the
        // caller needs no access to the system server's files.
        try {
            return openPipeHelper(uri, "application/octet-stream", null, file, (out, u, type, opts, f) -> {
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
        if (CleanCopyClient.METHOD_REQUEST.equals(method)) {
            maker.request(extras.getString(CleanCopyClient.EXTRA_APK), extras.getString(CleanCopyClient.EXTRA_FILE),
                    extras.getInt(CleanCopyClient.EXTRA_DENSITY), extras.getInt(CleanCopyClient.EXTRA_WIDTH),
                    extras.getInt(CleanCopyClient.EXTRA_HEIGHT), extras.getString(CleanCopyClient.EXTRA_RESOURCE),
                    extras.getInt(CleanCopyClient.EXTRA_USER), true, 0);
        } else if (CleanCopyClient.METHOD_FALLBACK.equals(method)) {
            final Bundle reply = new Bundle();
            reply.putString(CleanCopyClient.EXTRA_KEY, maker.fallbackKey(
                    extras.getString(CleanCopyClient.EXTRA_APK), extras.getString(CleanCopyClient.EXTRA_FILE),
                    extras.getInt(CleanCopyClient.EXTRA_DENSITY), extras.getInt(CleanCopyClient.EXTRA_WIDTH),
                    extras.getInt(CleanCopyClient.EXTRA_HEIGHT), extras.getString(CleanCopyClient.EXTRA_RESOURCE),
                    extras.getInt(CleanCopyClient.EXTRA_USER)));
            return reply;
        } else if (CleanCopyClient.METHOD_WATCH.equals(method)) {
            final Bundle reply = new Bundle();
            reply.putBoolean(CleanCopyClient.METHOD_WATCH,
                    maker.watch(extras.getBinder(CleanCopyClient.EXTRA_CALLBACK)));
            return reply;
        } else if (CleanCopyClient.METHOD_SHORTCUT.equals(method)) {
            // The caller opened the icon's URI with its own grant (LauncherApps checked it may): only the decode
            // happens here, in the VM, and only for the home app, the share sheet and SystemUI.
            final ParcelFileDescriptor fd = extras.getParcelable(CleanCopyClient.EXTRA_FD,
                    ParcelFileDescriptor.class);
            if (!maker.shortcutCaller(Binder.getCallingUid())) {
                closeQuietly(fd);
                throw new SecurityException("shortcut icons are for the home app, the share sheet and SystemUI");
            }
            try {
                final Bundle reply = new Bundle();
                reply.putParcelable(CleanCopyClient.EXTRA_BITMAP, maker.shortcutIcon(fd,
                        extras.getString(CleanCopyClient.EXTRA_PACKAGE), extras.getInt(CleanCopyClient.EXTRA_USER)));
                return reply;
            } finally {
                closeQuietly(fd);
            }
        } else if (com.android.internal.diamaneos.cleancopies.VmThumbnails.METHOD_THUMBNAIL.equals(method)) {
            // A provider's thumbnail for the system process that loads it, decoded in the VM (VmThumbnailDecoder).
            return VmThumbnailDecoder.get(getContext()).thumbnail(extras, Binder.getCallingUid());
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
            reply.putInt(CleanCopyClient.EXTRA_OPEN, keys == null ? 0 : maker.open(keys));
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
