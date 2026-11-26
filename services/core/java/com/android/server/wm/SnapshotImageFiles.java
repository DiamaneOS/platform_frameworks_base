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

package com.android.server.wm;

import android.annotation.Nullable;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Process;
import android.os.SELinux;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import android.util.Log;
import android.util.Slog;

import com.android.internal.diamaneos.cleancopies.CleanFileStream;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Set;

/**
 * The task and activity snapshot images the system server writes and reads back. Each image is encoded in memory, the
 * SHA-256 of exactly the bytes written is kept in the snapshot's proto, and on load a file whose bytes are those is
 * decoded here as it is, without the VM ({@link CleanFileStream}). Every other file goes to the VM through the decode
 * gate as before: no recorded hash (a proto from before this change), other bytes, another owner or label, a link, a
 * file anyone else may write, or one larger than {@link #MAX_BYTES}.
 *
 * <p>The pixels are the task's own window content and so the app's choice, but every byte of the image's structure
 * (markers, tables, sizes, metadata) comes from the system server's own encoder. Only the snapshot loader uses this.
 */
final class SnapshotImageFiles {
    /** The label of the snapshot directories and files (/data/system_ce). */
    static final String LABEL = "u:object_r:system_data_file:s0";

    /**
     * Debug lines saying which way each snapshot image was decoded, only when log.tag.SnapshotImageFiles is DEBUG
     * (device tests).
     */
    private static final String TAG = "SnapshotImageFiles";

    /** The largest image decoded here: far above a full-size snapshot of a phone display. */
    static final long MAX_BYTES = 32L * 1024 * 1024;

    private SnapshotImageFiles() {}

    /**
     * Encodes {@code bitmap} into memory, writes those bytes to {@code file} and returns their SHA-256 (lowercase
     * hex), or an empty string when the encoder reported a failure (the file is then decoded in the VM).
     */
    static String write(Bitmap bitmap, Bitmap.CompressFormat format, int quality, File file) throws IOException {
        final ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        final boolean done = bitmap.compress(format, quality, encoded);
        final byte[] bytes = encoded.toByteArray();
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(bytes);
        }
        final String hash = done ? CleanFileStream.sha256(bytes) : null;
        return hash == null ? "" : hash;
    }

    /**
     * The snapshot image {@code file}: decoded here when it is the system server's own file with the recorded
     * {@code sha256}, else decoded as an image of {@code userId} through the decode gate, as before.
     */
    static Bitmap decode(File file, @Nullable String sha256, BitmapFactory.Options options, int userId) {
        final CleanFileStream own = openOwn(file, sha256);
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Slog.d(TAG, file.getName() + (own != null ? " decoded here, the recorded bytes" : " decoded in the VM"));
        }
        if (own != null) {
            return BitmapFactory.decodeStream(own, null, options);
        }
        try (var owner = android.graphics.DecodeGate.ownerScope(userId)) {
            return BitmapFactory.decodeFile(file.getPath(), options);
        }
    }

    /**
     * {@code file} read whole, when it is a regular file (not followed through a link) with one name, owned by the
     * system user, writable by no other user, labelled {@link #LABEL}, of at most {@link #MAX_BYTES}, and the SHA-256
     * of the bytes read is {@code sha256}. Null otherwise. The path only opens the file: every check is made on the
     * open descriptor, and the bytes hashed are the bytes decoded.
     */
    static @Nullable CleanFileStream openOwn(File file, @Nullable String sha256) {
        if (sha256 == null || sha256.length() != 64) {
            return null;
        }
        FileDescriptor fd = null;
        try {
            fd = Os.open(file.getPath(), OsConstants.O_RDONLY | OsConstants.O_NOFOLLOW | OsConstants.O_CLOEXEC
                    | OsConstants.O_NONBLOCK, 0);
            final StructStat st = Os.fstat(fd);
            if (!OsConstants.S_ISREG(st.st_mode) || st.st_uid != Process.SYSTEM_UID || st.st_nlink != 1
                    || (st.st_mode & (OsConstants.S_IWGRP | OsConstants.S_IWOTH)) != 0
                    || st.st_size <= 0 || st.st_size > MAX_BYTES
                    || !LABEL.equals(SELinux.getFileContext(fd))) {
                return null;
            }
            return CleanFileStream.read(fd, Set.of(sha256));
        } catch (ErrnoException | RuntimeException e) {
            return null;
        } finally {
            if (fd != null) {
                try {
                    Os.close(fd);
                } catch (ErrnoException e) {
                    // Read already, or not used.
                }
            }
        }
    }
}
