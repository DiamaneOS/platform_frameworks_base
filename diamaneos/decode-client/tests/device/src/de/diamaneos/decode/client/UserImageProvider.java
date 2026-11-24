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
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.SystemClock;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * A provider of this test APK whose image is red in the system user and blue in every other user (a work profile): a
 * test sees which user's provider an image was opened from. Exported, so a process of another user can open it as an
 * app with a grant would. {@code ?color=AARRGGBB} asks for another colour, {@code ?delay=MS} answers that much later.
 */
public class UserImageProvider extends ContentProvider {
    public static final String AUTHORITY = "de.diamaneos.decode.client.tests.user";
    public static final Uri IMAGE = Uri.parse("content://" + AUTHORITY + "/image");
    public static final int PERSONAL = 0xffff0000;
    public static final int WORK = 0xff0000ff;
    private static final int SIDE = 48;

    /** The colour this user's provider serves. */
    public static int colorOf(int userId) {
        return userId == 0 ? PERSONAL : WORK;
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    /** The image in {@code color}, opened {@code delayMs} late. */
    public static Uri image(int color, long delayMs) {
        return IMAGE.buildUpon().appendQueryParameter("color", Integer.toHexString(color))
                .appendQueryParameter("delay", Long.toString(delayMs)).build();
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        final String color = uri.getQueryParameter("color");
        final String delay = uri.getQueryParameter("delay");
        if (delay != null) {
            SystemClock.sleep(Long.parseLong(delay));
        }
        final Bitmap bitmap = Bitmap.createBitmap(SIDE, SIDE, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(color != null ? (int) Long.parseLong(color, 16)
                : colorOf(Process.myUserHandle().getIdentifier()));
        try {
            final File file = File.createTempFile("user-image", ".png", getContext().getCacheDir());
            try (FileOutputStream out = new FileOutputStream(file)) {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
            }
            final ParcelFileDescriptor fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
            file.delete();
            return fd;
        } catch (IOException e) {
            throw new FileNotFoundException("user image: " + e);
        }
    }

    @Override
    public String getType(Uri uri) {
        return "image/png";
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
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
