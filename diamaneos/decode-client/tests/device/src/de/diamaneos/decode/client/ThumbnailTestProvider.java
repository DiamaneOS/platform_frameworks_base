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
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.List;

/**
 * A provider of this test APK (not exported) whose replies are thumbnails, as a DocumentsProvider gives them:
 * <ul>
 * <li>{@code png/RRGGBB}: a 7 x 3 PNG of one colour, the whole file
 * <li>{@code part/RRGGBB}: the same PNG as a part of a larger file, as an EXIF thumbnail is handed out
 * <li>{@code pipe/RRGGBB}: the same PNG through a pipe
 * <li>{@code turned/DEGREES}: an 8 x 4 PNG, red left half and blue right half, with the orientation extra
 * <li>{@code exif/ORIENTATION}: a 64 x 32 JPEG of four coloured quarters whose EXIF orientation is given
 * <li>{@code exifturned/DEGREES}: a 64 x 32 JPEG of four coloured quarters as a part of a larger file, with the
 * orientation extra, as a provider hands out an EXIF thumbnail
 * <li>{@code junk}: bytes that are no image
 * </ul>
 */
public class ThumbnailTestProvider extends ContentProvider {
    public static final String AUTHORITY = "de.diamaneos.decode.client.tests.thumbnails";
    public static final int WIDTH = 7;
    public static final int HEIGHT = 3;
    // Bytes around the PNG in the file a part is cut from.
    private static final int BEFORE = 100;
    private static final int AFTER = 50;

    public static Uri uri(String kind, String arg) {
        final Uri.Builder b = new Uri.Builder().scheme("content").authority(AUTHORITY).appendPath(kind);
        if (arg != null) {
            b.appendPath(arg);
        }
        return b.build();
    }

    /** A bitmap, red on the left half and blue on the right half. */
    public static Bitmap twoTone(int width, int height) {
        final Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                bitmap.setPixel(x, y, x < width / 2 ? 0xffff0000 : 0xff0000ff);
            }
        }
        return bitmap;
    }

    /** A bitmap of four quarters: red, blue on top, green, yellow below. */
    public static Bitmap quarters(int width, int height) {
        final Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                final boolean left = x < width / 2;
                final boolean top = y < height / 2;
                bitmap.setPixel(x, y, top ? (left ? 0xffff0000 : 0xff0000ff) : (left ? 0xff00ff00 : 0xffffff00));
            }
        }
        return bitmap;
    }

    private static byte[] encode(Bitmap bitmap, Bitmap.CompressFormat format) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        bitmap.compress(format, 100, out);
        return out.toByteArray();
    }

    private static byte[] solidPng(String rgb) {
        final Bitmap bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(0xff000000 | Integer.parseInt(rgb, 16));
        return encode(bitmap, Bitmap.CompressFormat.PNG);
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    private File write(String name, byte[]... parts) throws IOException {
        final File file = new File(getContext().getCacheDir(), name);
        try (OutputStream out = new FileOutputStream(file)) {
            for (byte[] part : parts) {
                out.write(part);
            }
        }
        return file;
    }

    private static AssetFileDescriptor whole(File file, Bundle extras) throws FileNotFoundException {
        return new AssetFileDescriptor(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY), 0,
                AssetFileDescriptor.UNKNOWN_LENGTH, extras);
    }

    @Override
    public AssetFileDescriptor openTypedAssetFile(Uri uri, String mimeTypeFilter, Bundle opts)
            throws FileNotFoundException {
        final List<String> path = uri.getPathSegments();
        final String kind = path.isEmpty() ? "" : path.get(0);
        final String arg = path.size() > 1 ? path.get(1) : "";
        try {
            switch (kind) {
                case "png":
                    return whole(write("png-" + arg, solidPng(arg)), null);
                case "part": {
                    final byte[] png = solidPng(arg);
                    final File file = write("part-" + arg, new byte[BEFORE], png, new byte[AFTER]);
                    return new AssetFileDescriptor(
                            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY), BEFORE, png.length,
                            null);
                }
                case "pipe": {
                    final byte[] png = solidPng(arg);
                    final ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
                    new Thread(() -> {
                        try (OutputStream out = new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])) {
                            out.write(png);
                        } catch (IOException e) {
                            // The reader went away.
                        }
                    }, "thumbnail-pipe").start();
                    return new AssetFileDescriptor(pipe[0], 0, AssetFileDescriptor.UNKNOWN_LENGTH);
                }
                case "turned": {
                    final Bundle extras = new Bundle();
                    extras.putInt(DocumentsContract.EXTRA_ORIENTATION, Integer.parseInt(arg));
                    return whole(write("turned", encode(twoTone(8, 4), Bitmap.CompressFormat.PNG)), extras);
                }
                case "exif": {
                    final File file = write("exif-" + arg, encode(quarters(64, 32), Bitmap.CompressFormat.JPEG));
                    final ExifInterface exif = new ExifInterface(file);
                    exif.setAttribute(ExifInterface.TAG_ORIENTATION, arg);
                    exif.saveAttributes();
                    return whole(file, null);
                }
                case "exifturned": {
                    final byte[] jpeg = encode(quarters(64, 32), Bitmap.CompressFormat.JPEG);
                    final File file = write("exifturned", new byte[BEFORE], jpeg, new byte[AFTER]);
                    final Bundle extras = new Bundle();
                    extras.putInt(DocumentsContract.EXTRA_ORIENTATION, Integer.parseInt(arg));
                    return new AssetFileDescriptor(
                            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY), BEFORE, jpeg.length,
                            extras);
                }
                case "junk": {
                    final byte[] junk = new byte[200];
                    java.util.Arrays.fill(junk, (byte) 0x55);
                    return whole(write("junk", junk), null);
                }
                default:
                    throw new FileNotFoundException("no " + kind);
            }
        } catch (IOException e) {
            throw new FileNotFoundException(e.toString());
        }
    }

    @Override
    public String getType(Uri uri) {
        return "image/*";
    }

    @Override
    public String[] getStreamTypes(Uri uri, String mimeTypeFilter) {
        return new String[] {"image/png"};
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
