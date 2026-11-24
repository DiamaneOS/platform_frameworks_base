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
 * cannot make the thumbnail, the load fails with the IOException callers already handle. When the decode service did
 * not answer, the load waits on the caller's (background) thread until the system server says the service answers
 * again, at most {@link #MAX_WAIT_MS} and only while the caller has not cancelled it, and loads once more: callers ask
 * for a thumbnail once, so what showed a placeholder during an outage fills in when it ends. Nothing of it is decoded
 * in this process.
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
    /** With no thumbnail: the decode service did not answer, ask again once it does. */
    public static final String EXTRA_LATER = "later";

    /** How long a load waits for the decode service to answer again before it fails. */
    static final long MAX_WAIT_MS = 60_000;

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
        final int mark = CleanCopyGate.decodeAnswersMark();
        try {
            return loadOnce(content, uri, size, signal, allocator);
        } catch (LaterException e) {
            // The decode service did not answer: once it does (the system server tells this process), the provider
            // is asked again, the bytes it gave may have been a stream that is spent.
            if (!CleanCopyGate.awaitDecodeAnswers(mark, MAX_WAIT_MS,
                    () -> signal != null && signal.isCanceled())) {
                throw e;
            }
            if (signal != null) {
                signal.throwIfCanceled();
            }
            return loadOnce(content, uri, size, signal, allocator);
        }
    }

    /** No thumbnail because the decode service did not answer: worth asking once more when it does. */
    private static final class LaterException extends IOException {
        LaterException(String message) {
            super(message);
        }
    }

    private static @NonNull Bitmap loadOnce(@NonNull ContentInterface content, @NonNull Uri uri,
            @NonNull Size size, @Nullable CancellationSignal signal, int allocator) throws IOException {
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
                final String why = "thumbnail not made in the VM: "
                        + (reply == null ? "no reply" : reply.getString(EXTRA_ERROR));
                if (reply != null && reply.getBoolean(EXTRA_LATER)) {
                    throw new LaterException(why);
                }
                throw new IOException(why);
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
     * The bytes as the decode service takes them: a stream as it is (the service reads it with its own deadline and
     * limit), a regular file or a part of one (an EXIF thumbnail) copied into a memory file here, or fed into a pipe
     * when it is larger than a memory file may be. The provider's file itself never leaves this process: the system
     * server and the decode service read only what this process copied (a file on shared storage is a FUSE file that
     * neither may read).
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
        if (length < 0) {
            length = st.st_size - offset;
        }
        if (offset < 0 || length <= 0 || offset > st.st_size - length) {
            throw new IOException("thumbnail part out of range");
        }
        if (length > MAX_PART_BYTES) {
            if (length > MAX_PIPED_BYTES) {
                throw new IOException("thumbnail file too large");
            }
            return pipeFrom(pfd.dup(), offset, length);
        }
        return copyToMemory(pfd.getFileDescriptor(), offset, (int) length);
    }

    // A file larger than this is not handed over at all: the decode service's limit for what it reads
    // (IDecodeService.MAX_INPUT_BYTES).
    private static final long MAX_PIPED_BYTES = 256L * 1024 * 1024;

    // A pipe fed with {@code length} bytes of {@code source} from {@code offset} by a thread of its own, which owns and
    // closes {@code source}. The decode service reads it under its own deadline: when it stops reading, the write
    // fails and the thread ends.
    private static ParcelFileDescriptor pipeFrom(ParcelFileDescriptor source, long offset, long length)
            throws IOException {
        final ParcelFileDescriptor[] pipe;
        try {
            pipe = ParcelFileDescriptor.createPipe();
        } catch (IOException e) {
            source.close();
            throw e;
        }
        final Thread feed = new Thread(() -> {
            try (ParcelFileDescriptor in = source; ParcelFileDescriptor out = pipe[1]) {
                final byte[] buffer = new byte[64 * 1024];
                long done = 0;
                while (done < length) {
                    final int n = Os.pread(in.getFileDescriptor(), buffer, 0,
                            (int) Math.min(buffer.length, length - done), offset + done);
                    if (n <= 0) {
                        return;
                    }
                    int written = 0;
                    while (written < n) {
                        written += Os.write(out.getFileDescriptor(), buffer, written, n - written);
                    }
                    done += n;
                }
            } catch (ErrnoException | IOException e) {
                // The reader stopped (its deadline) or the file went: the service sees a short stream.
            }
        }, "VmThumbnailFeed");
        feed.setDaemon(true);
        try {
            feed.start();
        } catch (Throwable e) {
            source.close();
            pipe[1].close();
            pipe[0].close();
            throw new IOException("thumbnail not handed over", e);
        }
        return pipe[0];
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
