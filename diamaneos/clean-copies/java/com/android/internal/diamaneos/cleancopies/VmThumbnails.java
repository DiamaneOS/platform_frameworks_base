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
import android.graphics.Bitmap;
import android.graphics.ImageDecoder;
import android.graphics.Matrix;
import android.graphics.Point;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.util.Log;
import android.util.Size;

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

    /** Total retry window including all attempts, each with its own five second entry deadline. */
    static final long MAX_WAIT_MS = 60_000;

    public static final String EXTRA_DEADLINE = "thumbnailDeadline";
    public static final String EXTRA_DELEGATE = "thumbnailDelegate";
    public static final String EXTRA_MEDIA_USER = "de.diamaneos.vmdecode.media_user";
    private static final long ATTEMPT_MS = 5000;

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
        final long end = android.os.SystemClock.uptimeMillis() + MAX_WAIT_MS;
        long wait = 1000;
        while (true) {
            final int mark = CleanCopyGate.decodeAnswersMark();
            try {
                return loadOnce(content, uri, size, signal, allocator,
                        Math.min(end, android.os.SystemClock.uptimeMillis() + ATTEMPT_MS));
            } catch (LaterException e) {
                final long left = end - android.os.SystemClock.uptimeMillis();
                if (left <= 0) throw e;
                CleanCopyGate.awaitDecodeAnswers(mark, Math.min(wait, left),
                        () -> signal != null && signal.isCanceled());
                if (signal != null) signal.throwIfCanceled();
                wait = Math.min(wait * 2, 5000);
            }
        }
    }

    /** No thumbnail because the decode service did not answer: worth asking once more when it does. */
    static final class LaterException extends IOException {
        LaterException(String message) {
            super(message);
        }
    }

    static final class MissingException extends java.io.FileNotFoundException {
        MissingException() { super("Thumbnail item no longer exists"); }
    }

    private static @NonNull Bitmap loadOnce(@NonNull ContentInterface content, @NonNull Uri uri,
            @NonNull Size size, @Nullable CancellationSignal signal, int allocator, long end) throws IOException {
        final Uri sourceUri = sourceUri(content, uri);
        final Bundle opts = new Bundle();
        opts.putParcelable(ContentResolver.EXTRA_SIZE, new Point(size.getWidth(), size.getHeight()));
        final int delegate = android.app.ActivityThread.isSystem()
                && android.os.Binder.getCallingPid() != android.os.Process.myPid()
                ? android.os.Binder.getCallingUid() : android.os.Process.myUid();
        final GateOwner principal = GateOwner.of(
                android.content.ContentProvider.getUserIdFromUri(sourceUri), delegate, 100000);
        final int orientation;
        Bitmap bitmap;
        try (ThumbnailWork.Source source = ThumbnailWork.open(content, sourceUri, opts, signal, end)) {
            orientation = source.orientation;
            bitmap = decode(source, sourceUri.getAuthority(), size, end, signal, principal.forUid);
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

    // The admitted source reaches the broker under the same deadline and captured delegate.
    private static Bitmap decode(ThumbnailWork.Source source, String authority, Size size, long end,
            CancellationSignal signal, int delegate) throws IOException {
        final Bundle request = new Bundle();
        request.putParcelable(CleanCopyClient.EXTRA_FD, source.bytes);
        request.putInt(CleanCopyClient.EXTRA_WIDTH, size.getWidth());
        request.putInt(CleanCopyClient.EXTRA_HEIGHT, size.getHeight());
        request.putString(EXTRA_AUTHORITY, authority);
        request.putLong(EXTRA_DEADLINE, end);
        request.putInt(EXTRA_DELEGATE, delegate);
        if (source.mediaUser >= 0) request.putInt(EXTRA_MEDIA_USER, source.mediaUser);
        final Bundle reply = ThumbnailWork.broker(context(), source.providerUid, request, end, signal);
        final Bitmap bitmap = reply == null ? null
                : reply.getParcelable(CleanCopyClient.EXTRA_BITMAP, Bitmap.class);
        if (source.failure() != null) {
            if (bitmap != null) bitmap.recycle();
            throw source.failure();
        }
        if (bitmap == null) {
            if (reply != null && reply.getBoolean(EXTRA_LATER)) throw new LaterException("Thumbnail unavailable");
            throw new IOException("Thumbnail not made in the VM");
        }
        sMade.incrementAndGet();
        return bitmap;
    }

    private static Uri sourceUri(ContentInterface content, Uri uri) throws IOException {
        if (!ContentResolver.SCHEME_CONTENT.equals(uri.getScheme())
                || uri.getAuthority() == null || uri.getAuthority().isEmpty()) {
            throw new IOException("Thumbnail source must name a content provider");
        }
        for (int depth = 0; content instanceof ContentResolver; depth++) {
            final ContentInterface source = ContentResolver.getThumbnailSource((ContentResolver) content);
            if (source == content) break;
            if (depth >= 16) throw new IOException("Thumbnail source wrappers exceed the limit");
            content = source;
        }
        int user = android.content.ContentProvider.getUserIdFromUri(uri, -1);
        if (uri.getUserInfo() != null && user < 0) {
            throw new IOException("Thumbnail source user must be concrete");
        }
        final int captured;
        final boolean bound;
        if (content instanceof android.content.ContentProviderClient) {
            captured = android.content.ContentProviderClient.getThumbnailUserId((android.content.ContentProviderClient) content);
            bound = true;
        } else if (content instanceof android.content.ContentProvider) {
            final Context context = ((android.content.ContentProvider) content).getContext();
            if (context == null) throw new IOException("Thumbnail provider is not attached");
            captured = context.getUserId();
            bound = true;
        } else if (content instanceof ContentResolver) {
            captured = ContentResolver.getThumbnailSourceUser((ContentResolver) content);
            bound = false;
        } else {
            throw new IOException("Thumbnail source capability is not identified");
        }
        if (bound && user >= 0 && user != captured) {
            throw new IOException("Thumbnail URI user conflicts with its provider capability");
        }
        if (user < 0) user = captured;
        if (user < 0) throw new IOException("Thumbnail source user is not explicit");
        return android.content.ContentProvider.maybeAddUserId(
                android.content.ContentProvider.getUriWithoutUserId(uri), user);
    }

    static Context context() throws IOException {
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

}
