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

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import android.os.SystemClock;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A provider of this test APK (not exported) that counts every open: a test sees whether a URI was opened at all.
 * It has no data, an open gets nothing to read, except for the icons a test puts in {@link #ICONS}: {@code icon/NAME}
 * opens their bytes, {@code icon-slow/NAME} opens them after {@link #SLOW_MS}, {@code icon-late/NAME} after
 * {@link #LATE_MS} (past an icon load's end).
 */
public class ProbeProvider extends ContentProvider {
    public static final String AUTHORITY = "de.diamaneos.decode.client.tests.probe";
    public static final AtomicInteger OPENS = new AtomicInteger();
    public static final Map<String, java.util.concurrent.atomic.AtomicLongArray> TIMINGS = new ConcurrentHashMap<>();
    public static final Map<String, byte[]> ICONS = new ConcurrentHashMap<>();
    public static final Map<String, Integer> ORIENTATIONS = new ConcurrentHashMap<>();
    public static final long SLOW_MS = 1_000;
    public static final long LATE_MS = com.android.internal.diamaneos.cleancopies.VmIconLoads.LOAD_MS + 1_000;

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        OPENS.incrementAndGet();
        final java.util.List<String> path = uri.getPathSegments();
        final byte[] icon = path.size() == 2 ? ICONS.get(path.get(1)) : null;
        if (path.size() == 2 && path.get(0).equals("icon-denied") && icon == null) {
            throw new SecurityException("the test grant is not available yet");
        }
        if (icon != null && (path.get(0).equals("icon") || path.get(0).equals("icon-slow")
                || path.get(0).equals("icon-late") || path.get(0).equals("icon-denied"))) {
            final java.util.concurrent.atomic.AtomicLongArray timing = TIMINGS.get(path.get(1));
            if (timing != null) {
                timing.set(0, SystemClock.uptimeMillis());
                timing.set(2, android.os.Binder.getCallingUid());
                timing.set(3, android.os.Binder.getCallingPid());
            }
            if (path.get(0).equals("icon-late")) {
                SystemClock.sleep(LATE_MS);
            } else if (path.get(0).equals("icon-slow")) {
                SystemClock.sleep(SLOW_MS);
            }
            if (timing != null) timing.set(1, SystemClock.uptimeMillis());
            try {
                final File file = File.createTempFile("probe-icon", ".bin", getContext().getCacheDir());
                try (FileOutputStream out = new FileOutputStream(file)) {
                    out.write(icon);
                }
                final ParcelFileDescriptor fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
                file.delete();
                return fd;
            } catch (IOException e) {
                throw new FileNotFoundException("probe icon: " + e);
            }
        }
        throw new FileNotFoundException("probe: " + uri);
    }

    @Override
    public android.content.res.AssetFileDescriptor openAssetFile(Uri uri, String mode) throws FileNotFoundException {
        final android.os.Bundle extras = new android.os.Bundle();
        extras.putInt(android.provider.DocumentsContract.EXTRA_ORIENTATION,
                ORIENTATIONS.getOrDefault(uri.getLastPathSegment(), 0));
        return new android.content.res.AssetFileDescriptor(openFile(uri, mode), 0,
                android.content.res.AssetFileDescriptor.UNKNOWN_LENGTH, extras);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return "image/png";
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
