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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityThread;
import android.content.ContentInterface;
import android.content.ContentResolver;
import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.graphics.ImageDecoder;
import android.graphics.Matrix;
import android.graphics.Point;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import android.util.Log;
import android.util.Size;

import java.io.FileDescriptor;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Thumbnails a system process loads from a content provider ({@code ContentResolver.loadThumbnail}, and through it
 * {@code DocumentsContract.getDocumentThumbnail} and MediaStore's thumbnail calls). In a process whose apps' images
 * come from clean copies ({@link CleanCopyGate#isActive}), the provider's reply is opened here with this process's own
 * grants, as Android opens it, and decoded in the protected VM by the system server (CleanImageProvider, method
 * {@link #METHOD_THUMBNAIL}), charged to the provider's package. The provider's orientation extra is applied here as
 * Android applies it, and the file's own orientation is applied in the VM as Android's decoder applies it. When the VM
 * cannot make the thumbnail, the load fails with the IOException callers already handle. Nothing of it is decoded in
 * this process.
 *
 * @hide
 */
public final class VmThumbnails {
    private static final String TAG = "VmThumbnails";

    /** The system server's method (CleanImageProvider) that decodes a thumbnail in the VM. */
    public static final String METHOD_THUMBNAIL = "thumbnail";
    /** The authority of the URI the thumbnail came from (with its user, as the URI has it). */
    public static final String EXTRA_AUTHORITY = "authority";
    /** Why the system server gave no thumbnail. */
    public static final String EXTRA_ERROR = "error";

    // A part of a regular file (an EXIF thumbnail) is copied into a memory file of at most this size: the decode
    // service's limit for bytes it holds in memory (IDecodeService.MAX_BUFFERED_BYTES).
    private static final int MAX_PART_BYTES = 32 * 1024 * 1024;

    private static volatile boolean sForced;
    private static final AtomicInteger sMade = new AtomicInteger();

    private VmThumbnails() {
    }

    /** True when this process has provider thumbnails decoded in the VM. */
    public static boolean applies() {
        return sForced || CleanCopyGate.isActive();
    }

    /** Tests: this process has thumbnails decoded in the VM as a system process does, or as before again. */
    public static void setForcedForTest(boolean forced) {
        sForced = forced;
    }

    /** Tests: how many thumbnails this process got from the VM. */
    public static int madeForTest() {
        return sMade.get();
    }

    /**
     * {@code ContentResolver.loadThumbnail} in a process where {@link #applies}: the same open, the same orientation
     * extra and allocator, the decode in the VM.
     */
    public static @NonNull Bitmap load(@NonNull ContentInterface content, @NonNull Uri uri, @NonNull Size size,
            @Nullable CancellationSignal signal, int allocator) throws IOException {
        final Bundle opts = new Bundle();
        opts.putParcelable(ContentResolver.EXTRA_SIZE, new Point(size.getWidth(), size.getHeight()));
        final AssetFileDescriptor afd;
        try {
            afd = content.openTypedAssetFile(uri, "image/*", opts, signal);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            // As ImageDecoder's source hands on a failed open.
            throw new IOException(e);
        }
        if (afd == null) {
            throw new FileNotFoundException("no thumbnail");
        }
        final int orientation;
        Bitmap bitmap;
        try (AssetFileDescriptor asset = afd) {
            final Bundle extras = asset.getExtras();
            orientation = extras != null ? extras.getInt(DocumentsContract.EXTRA_ORIENTATION, 0) : 0;
            if (signal != null) {
                signal.throwIfCanceled();
            }
            bitmap = decode(asset, uri.getAuthority(), size);
        }

        // The provider's orientation extra, as ContentResolver.loadThumbnail applies it (EXIF thumbnails do not
        // carry the rotation of the image they belong to).
        if (orientation != 0) {
            final int width = bitmap.getWidth();
            final int height = bitmap.getHeight();
            final Matrix m = new Matrix();
            m.setRotate(orientation, width / 2, height / 2);
            bitmap = Bitmap.createBitmap(bitmap, 0, 0, width, height, m, false);
        }
        if (allocator == ImageDecoder.ALLOCATOR_HARDWARE) {
            final Bitmap hardware = bitmap.copy(Bitmap.Config.HARDWARE, false);
            if (hardware == null) {
                throw new IOException("thumbnail not made a hardware bitmap");
            }
            bitmap = hardware;
        } else if (allocator == ImageDecoder.ALLOCATOR_SHARED_MEMORY) {
            bitmap = bitmap.asShared();
        }
        return bitmap;
    }

    // The bytes behind {@code asset} decoded in the VM by the system server, at most {@code size}.
    private static Bitmap decode(AssetFileDescriptor asset, String authority, Size size) throws IOException {
        final Context context = context();
        try (ParcelFileDescriptor bytes = handOver(asset)) {
            final Bundle request = new Bundle();
            request.putParcelable(CleanCopyClient.EXTRA_FD, bytes);
            request.putInt(CleanCopyClient.EXTRA_WIDTH, size.getWidth());
            request.putInt(CleanCopyClient.EXTRA_HEIGHT, size.getHeight());
            request.putString(EXTRA_AUTHORITY, authority);
            final Bundle reply;
            try {
                reply = context.getContentResolver().call(CleanCopyClient.URI, METHOD_THUMBNAIL, null, request);
            } catch (RuntimeException e) {
                throw new IOException("thumbnail not made in the VM: " + e, e);
            }
            final Bitmap bitmap = reply == null ? null
                    : reply.getParcelable(CleanCopyClient.EXTRA_BITMAP, Bitmap.class);
            if (bitmap == null) {
                throw new IOException("thumbnail not made in the VM: "
                        + (reply == null ? "no reply" : reply.getString(EXTRA_ERROR)));
            }
            sMade.incrementAndGet();
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "thumbnail from " + authority + " made in the VM: " + bitmap.getWidth() + "x"
                        + bitmap.getHeight());
            }
            return bitmap;
        }
    }

    private static Context context() throws IOException {
        Context context = ActivityThread.currentApplication();
        if (context == null) {
            final ActivityThread thread = ActivityThread.currentActivityThread();
            context = thread != null ? thread.getSystemContext() : null;
        }
        if (context == null) {
            throw new IOException("thumbnail not made in the VM: no context");
        }
        return context;
    }

    /**
     * The bytes as the decode service takes them: a whole file or a stream as it is (the service reads it with its
     * own deadline and limit), a part of a regular file (an EXIF thumbnail) copied into a memory file here.
     */
    private static ParcelFileDescriptor handOver(AssetFileDescriptor asset) throws IOException {
        final ParcelFileDescriptor pfd = asset.getParcelFileDescriptor();
        final long offset = asset.getStartOffset();
        long length = asset.getDeclaredLength();
        final StructStat st;
        try {
            st = Os.fstat(pfd.getFileDescriptor());
        } catch (ErrnoException e) {
            throw new IOException("thumbnail not readable", e);
        }
        if (!OsConstants.S_ISREG(st.st_mode)) {
            if (offset != 0) {
                throw new IOException("thumbnail at an offset into a stream");
            }
            return pfd.dup();
        }
        if (offset == 0 && (length < 0 || length == st.st_size)) {
            return pfd.dup();
        }
        if (length < 0) {
            length = st.st_size - offset;
        }
        if (offset < 0 || length <= 0 || length > MAX_PART_BYTES || offset > st.st_size - length) {
            throw new IOException("thumbnail part out of range");
        }
        return copyToMemory(pfd.getFileDescriptor(), offset, (int) length);
    }

    private static ParcelFileDescriptor copyToMemory(FileDescriptor source, long offset, int length)
            throws IOException {
        FileDescriptor memory = null;
        try {
            memory = Os.memfd_create("thumbnail", OsConstants.MFD_CLOEXEC);
            final byte[] buffer = new byte[Math.min(64 * 1024, length)];
            int done = 0;
            while (done < length) {
                final int n = Os.pread(source, buffer, 0, Math.min(buffer.length, length - done), offset + done);
                if (n <= 0) {
                    throw new IOException("thumbnail shorter than declared");
                }
                int written = 0;
                while (written < n) {
                    written += Os.write(memory, buffer, written, n - written);
                }
                done += n;
            }
            Os.lseek(memory, 0, OsConstants.SEEK_SET);
            return ParcelFileDescriptor.dup(memory);
        } catch (ErrnoException e) {
            throw new IOException("thumbnail not copied", e);
        } finally {
            if (memory != null) {
                try {
                    Os.close(memory);
                } catch (ErrnoException ignored) {
                    // Closed.
                }
            }
        }
    }
}
