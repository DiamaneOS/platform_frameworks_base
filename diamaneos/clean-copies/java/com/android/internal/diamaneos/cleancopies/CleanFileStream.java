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

package com.android.internal.diamaneos.cleancopies;

import android.annotation.Nullable;
import android.graphics.DecodeGate;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import android.util.Log;

import java.io.ByteArrayInputStream;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Set;

/**
 * An image file the system server wrote itself with its own encoder, read whole into memory and decoded here as it is:
 * the decode gate (android.graphics.DecodeGate) does not take it to the VM. Only {@link #read} makes one, and only when
 * the SHA-256 of exactly the bytes it read is one of the hashes the system server recorded for files it wrote. The
 * bytes hashed are the bytes decoded, so nothing can change between the check and the decode. A file of any other
 * origin, an app's descriptor among them, never matches.
 *
 * <p>Two kinds of file, one trust argument. A wallpaper made clean, or its crop: the pixels of the VM's decode of the
 * app's image (WallpaperVmSanitizer). A task or activity snapshot: the pixels of the task's own window, captured by
 * the system server (com.android.server.wm.SnapshotImageFiles), also checked there for owner system, label
 * system_data_file and size on the open descriptor. In both the pixel values are the app's choice and in both every
 * byte of the file's structure (markers, tables, chunk and segment lengths, sizes, metadata) was written by the
 * system server's own encoder. A decoder bug needs malformed structure that encoder never emits, so the native decode
 * here parses encoder output of untrusted pixels, not untrusted bytes.
 *
 * @hide
 */
public final class CleanFileStream extends ByteArrayInputStream {
    private static final String TAG = "CleanFileStream";

    /**
     * The key under which the system server names the SHA-256 of a wallpaper file it made and hands out (the out
     * parameters of IWallpaperManager.getWallpaperWithFeature). Set only by the system server.
     */
    public static final String EXTRA_SHA256 = "diamaneos.clean_sha256";

    /** The largest file read (a PNG of at most the decode service's pixel limit is far smaller). */
    public static final long MAX_BYTES = 64L * 1024 * 1024;

    private final String mSha256;

    private CleanFileStream(byte[] bytes, String sha256) {
        super(bytes);
        mSha256 = sha256;
    }

    /** The SHA-256 of the bytes, as lowercase hex. */
    public String sha256() {
        return mSha256;
    }

    /** The same bytes from their start, for a second decode. */
    public CleanFileStream again() {
        return new CleanFileStream(buf, mSha256);
    }

    /**
     * The regular file behind {@code fd}, read whole from its start with positioned reads (the descriptor's offset is
     * left as it was), when the SHA-256 of what was read is in {@code hashes}. Null otherwise: not a regular file,
     * empty or larger than {@link #MAX_BYTES}, not readable, or bytes the system server did not record.
     */
    public static @Nullable CleanFileStream read(@Nullable FileDescriptor fd, @Nullable Set<String> hashes) {
        if (fd == null || hashes == null || hashes.isEmpty()) {
            return null;
        }
        try {
            final StructStat st = Os.fstat(fd);
            if (!OsConstants.S_ISREG(st.st_mode) || st.st_size <= 0 || st.st_size > MAX_BYTES) {
                return null;
            }
            final byte[] bytes = new byte[(int) st.st_size];
            int done = 0;
            while (done < bytes.length) {
                final int n = Os.pread(fd, bytes, done, bytes.length - done, done);
                if (n <= 0) {
                    return null;
                }
                done += n;
            }
            final String hash = sha256(bytes);
            return hash != null && hashes.contains(hash) ? new CleanFileStream(bytes, hash) : null;
        } catch (ErrnoException | IOException | RuntimeException | OutOfMemoryError e) {
            return null;
        }
    }

    /**
     * The wallpaper file {@code pfd} that the system server handed out with {@code params}: in a process that gates
     * its decodes, the bytes it named as its own ({@link #EXTRA_SHA256}), decoded here. Otherwise, or when the bytes
     * read differ, a plain stream of the descriptor, which the decode gate takes to the VM. Either way the caller
     * closes the stream, which closes {@code pfd}.
     */
    public static InputStream openWallpaper(ParcelFileDescriptor pfd, @Nullable Bundle params) {
        final String hash = params == null ? null : params.getString(EXTRA_SHA256);
        if (hash != null && DecodeGate.gates()) {
            final CleanFileStream clean = read(pfd.getFileDescriptor(), Set.of(hash));
            if (clean != null) {
                try {
                    pfd.close();
                } catch (IOException e) {
                    // The bytes are read.
                }
                return clean;
            }
            Log.w(TAG, "wallpaper bytes differ from the file the system server named, decoded in the VM");
        }
        return new ParcelFileDescriptor.AutoCloseInputStream(pfd);
    }

    /** The SHA-256 of {@code bytes} as lowercase hex, or null. */
    public static @Nullable String sha256(byte[] bytes) {
        try {
            final byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            final StringBuilder hex = new StringBuilder(64);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            return null;
        }
    }
}
