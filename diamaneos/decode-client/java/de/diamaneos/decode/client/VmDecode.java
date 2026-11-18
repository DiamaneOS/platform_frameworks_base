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

import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.ProviderInfo;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.Point;
import android.net.Uri;
import android.os.Bundle;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.SystemClock;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructPollfd;
import android.util.Log;
import android.util.Size;

import de.diamaneos.sanitizer.decode.DecodeRequest;
import de.diamaneos.sanitizer.decode.IDecodeService;
import de.diamaneos.sanitizer.decode.ImageInfo;
import de.diamaneos.sanitizer.decode.Thumbnail;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Decodes images supplied by other apps inside the protected VM of the decode service. The caller hands over the
 * bytes unopened (a file, a content URI or a byte range) and gets back a bitmap built from checked pixels only.
 *
 * <p>Fail closed: {@link UnavailableException} means "try again later" and {@link RejectedException} means "this file
 * cannot be shown". In both cases the caller shows a placeholder. There is no way to decode the file in the calling
 * process instead, and no setting that turns this off.
 *
 * <p>Every call has a deadline: the default ones of the client, or a shorter one the caller gives. Never call this on
 * the main thread: there every call fails at once with {@link UnavailableException}.
 */
public final class VmDecode {
    private static final String TAG = "VmDecode";

    public static final int PRIORITY_USER_WAITING = IDecodeService.PRIORITY_USER_WAITING;
    public static final int PRIORITY_BACKGROUND = IDecodeService.PRIORITY_BACKGROUND;

    /** The largest box worth asking for: the service scales down to its pixel limit by itself. */
    public static final int MAX_BOX = StripeValidator.MAX_DIMENSION;

    /** No deadline of the caller's own: the client's defaults apply. */
    public static final long NO_DEADLINE = 0;

    // A pump that gets nothing from the provider for this long gives up, whatever the call's deadline.
    private static final long PUMP_IDLE_MS = 5_000;
    private static final long PUMP_TOTAL_MS = IDecodeService.HANDLE_TIMEOUT_MILLIS;

    /** The VM could not do the work now. Show a placeholder and ask again later. */
    public static final class UnavailableException extends Exception {
        public UnavailableException(String message) {
            super(message);
        }

        public UnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** The file is broken, unsupported or hostile, or a reply broke the contract. Show a placeholder, no retry. */
    public static final class RejectedException extends Exception {
        public RejectedException(String message) {
            super(message);
        }
    }

    /** A decoded image: the bitmap, already turned upright, and the quarter turns the file asked for. */
    public static final class Decoded {
        public final Bitmap bitmap;
        /** 0, 90, 180 or 270: the clockwise turn applied to the stored pixels. */
        public final int rotation;

        Decoded(Bitmap bitmap, int rotation) {
            this.bitmap = bitmap;
            this.rotation = rotation;
        }
    }

    private static final Object sLock = new Object();
    private static DecodeClient sClient; // guarded by sLock

    // Each copy into a pipe runs on its own thread with a deadline. Pipes are read with poll(). A file is read with
    // pread(), which can block for good on a FUSE file served by an app (a StorageManager proxy): such a thread is
    // left behind and counted against the owner, and an owner with too many of them gets no new copies.
    private static final int MAX_STUCK_PER_OWNER = 2;
    private static final int MAX_STUCK = 16;
    private static final Object sStuckLock = new Object();
    private static final java.util.Map<String, Integer> sStuck = new java.util.HashMap<>(); // guarded by sStuckLock
    private static int sStuckTotal; // guarded by sStuckLock

    private VmDecode() {
    }

    private static DecodeClient client(Context context) {
        synchronized (sLock) {
            if (sClient == null) {
                sClient = new DecodeClient(context);
            }
            return sClient;
        }
    }

    private static void checkNotMainThread() throws UnavailableException {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Log.w(TAG, "decode requested on the main thread, refused", new Throwable());
            throw new UnavailableException("decode requested on the main thread");
        }
    }

    /** The deadline {@code timeoutMs} from now, for the calls below. */
    public static long deadlineIn(long timeoutMs) {
        return SystemClock.uptimeMillis() + Math.max(1, timeoutMs);
    }

    /**
     * Decodes the bytes behind {@code fd} into a bitmap that fits in {@code maxWidth} x {@code maxHeight} once turned
     * by the file's orientation. The service reads the descriptor as opaque bytes. The caller keeps and closes it.
     */
    public static Bitmap decodeBitmap(Context context, ParcelFileDescriptor fd, String ownerPackage, int priority,
            int maxWidth, int maxHeight) throws UnavailableException, RejectedException {
        return decode(context, fd, ownerPackage, priority, maxWidth, maxHeight, NO_DEADLINE).bitmap;
    }

    /** {@link #decodeBitmap}, also giving the turn applied, and with an optional deadline ({@link #deadlineIn}). */
    public static Decoded decode(Context context, ParcelFileDescriptor fd, String ownerPackage, int priority,
            int maxWidth, int maxHeight, long deadline) throws UnavailableException, RejectedException {
        checkNotMainThread();
        if (maxWidth < 1 || maxHeight < 1) {
            throw new RejectedException("bad box " + maxWidth + "x" + maxHeight);
        }
        final DecodeRequest request = request(fd, ownerPackage, priority);
        return client(context).call(priority, deadline, request.ownerPackage, service -> {
            final Thumbnail thumbnail = service.decodeThumbnail(request, maxWidth, maxHeight);
            if (thumbnail == null) {
                throw new RejectedException("no reply");
            }
            checkStatus(thumbnail.status);
            try {
                return toDecoded(service, thumbnail, maxWidth, maxHeight);
            } finally {
                try {
                    service.release(thumbnail.handle);
                } catch (RemoteException ignored) {
                    // The handle times out in the service anyway.
                }
            }
        });
    }

    /** The image's stored size (before any turn) from its header, without decoding pixels. */
    public static Size probeSize(Context context, ParcelFileDescriptor fd, String ownerPackage, int priority,
            long deadline) throws UnavailableException, RejectedException {
        checkNotMainThread();
        final DecodeRequest request = request(fd, ownerPackage, priority);
        return client(context).call(priority, deadline, request.ownerPackage, service -> {
            final ImageInfo info = service.probe(request);
            if (info == null) {
                throw new RejectedException("no reply");
            }
            checkStatus(info.status);
            try {
                StripeValidator.checkInfo(info.format, info.width, info.height, info.animated);
            } catch (StripeValidator.BadReplyException e) {
                throw new RejectedException(e.getMessage());
            }
            return new Size(info.width, info.height);
        });
    }

    /** Decodes a file the caller may read. */
    public static Decoded decodeFile(Context context, File file, String ownerPackage, int priority, int maxWidth,
            int maxHeight, long deadline) throws UnavailableException, RejectedException {
        try (ParcelFileDescriptor fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)) {
            return decode(context, fd, ownerPackage, priority, maxWidth, maxHeight, deadline);
        } catch (FileNotFoundException e) {
            throw new RejectedException("no file");
        } catch (IOException e) {
            throw new UnavailableException("could not close the file", e);
        }
    }

    /** The stored size of a file the caller may read, from its header in the VM. */
    public static Size probeFile(Context context, File file, String ownerPackage, int priority, long deadline)
            throws UnavailableException, RejectedException {
        try (ParcelFileDescriptor fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)) {
            return probeSize(context, fd, ownerPackage, priority, deadline);
        } catch (FileNotFoundException e) {
            throw new RejectedException("no file");
        } catch (IOException e) {
            throw new UnavailableException("could not close the file", e);
        }
    }

    /**
     * Decodes the image behind a URI (content, android.resource or file), opened through {@code resolver} the way
     * {@code ContentResolver.loadThumbnail} opens it, with the box as the provider's size hint. An empty
     * {@code ownerPackage} charges the request to the package of the URI's provider.
     */
    public static Bitmap decodeUri(Context context, ContentResolver resolver, Uri uri, String ownerPackage,
            int priority, int maxWidth, int maxHeight) throws UnavailableException, RejectedException {
        return decodeUri(context, resolver, uri, ownerPackage, priority, maxWidth, maxHeight, NO_DEADLINE);
    }

    /** {@link #decodeUri(Context, ContentResolver, Uri, String, int, int, int)} with a deadline. */
    public static Bitmap decodeUri(Context context, ContentResolver resolver, Uri uri, String ownerPackage,
            int priority, int maxWidth, int maxHeight, long deadline)
            throws UnavailableException, RejectedException {
        checkNotMainThread();
        final String owner = owner(context, uri, ownerPackage);
        try (AssetFileDescriptor afd = openImageWithin(resolver, uri, maxWidth, maxHeight, owner, deadline)) {
            if (afd == null) {
                throw new RejectedException("no image behind the URI");
            }
            final Pumper pumper = pipe(afd, owner, pumpDeadline(deadline));
            try (ParcelFileDescriptor read = pumper.read) {
                return decode(context, read, owner, priority, maxWidth, maxHeight, deadline).bitmap;
            } finally {
                pumper.afterCall();
            }
        } catch (FileNotFoundException | SecurityException | IllegalArgumentException e) {
            throw new RejectedException("cannot open the URI: " + e.getClass().getSimpleName());
        } catch (IOException e) {
            throw new UnavailableException("could not read the URI", e);
        }
    }

    /** The stored size of the image behind a URI, from its header in the VM. */
    public static Size probeUri(Context context, ContentResolver resolver, Uri uri, String ownerPackage,
            int priority, long deadline) throws UnavailableException, RejectedException {
        checkNotMainThread();
        final String owner = owner(context, uri, ownerPackage);
        try (AssetFileDescriptor afd = openImageWithin(resolver, uri, 0, 0, owner, deadline)) {
            if (afd == null) {
                throw new RejectedException("no image behind the URI");
            }
            final Pumper pumper = pipe(afd, owner, pumpDeadline(deadline));
            try (ParcelFileDescriptor read = pumper.read) {
                return probeSize(context, read, owner, priority, deadline);
            } finally {
                pumper.afterCall();
            }
        } catch (FileNotFoundException | SecurityException | IllegalArgumentException e) {
            throw new RejectedException("cannot open the URI: " + e.getClass().getSimpleName());
        } catch (IOException e) {
            throw new UnavailableException("could not read the URI", e);
        }
    }

    /** Decodes compressed image bytes held in memory, for example an Icon made with createWithData. */
    public static Bitmap decodeBytes(Context context, byte[] data, int offset, int length, String ownerPackage,
            int priority, int maxWidth, int maxHeight, long deadline)
            throws UnavailableException, RejectedException {
        checkNotMainThread();
        if (data == null || offset < 0 || length < 0 || offset > data.length - length) {
            throw new RejectedException("bad byte range");
        }
        if (length > IDecodeService.MAX_INPUT_BYTES) {
            throw new RejectedException("larger than the decode limit");
        }
        final Pumper pumper;
        try {
            pumper = pipe(data, offset, length, ownerPackage, pumpDeadline(deadline));
        } catch (IOException e) {
            throw new UnavailableException("could not pass the bytes", e);
        }
        try (ParcelFileDescriptor read = pumper.read) {
            return decode(context, read, ownerPackage, priority, maxWidth, maxHeight, deadline).bitmap;
        } catch (IOException e) {
            throw new UnavailableException("could not pass the bytes", e);
        } finally {
            pumper.afterCall();
        }
    }

    /** {@link #decodeBytes} with the client's default deadline. */
    public static Bitmap decodeBytes(Context context, byte[] data, int offset, int length, String ownerPackage,
            int priority, int maxWidth, int maxHeight) throws UnavailableException, RejectedException {
        return decodeBytes(context, data, offset, length, ownerPackage, priority, maxWidth, maxHeight,
                NO_DEADLINE);
    }

    // The package that supplied the bytes: the caller's word, else the URI's provider. Never the calling process
    // itself, so one app's images do not share a lane with everything the system decodes.
    private static String owner(Context context, Uri uri, String ownerPackage) {
        if (ownerPackage != null && !ownerPackage.isEmpty()
                && !ownerPackage.equals(android.app.ActivityThread.currentPackageName())) {
            return ownerPackage;
        }
        if (ContentResolver.SCHEME_CONTENT.equals(uri.getScheme()) && uri.getAuthority() != null) {
            try {
                final ProviderInfo provider = context.getPackageManager().resolveContentProvider(
                        uri.getAuthority(), 0);
                if (provider != null && provider.packageName != null) {
                    return provider.packageName;
                }
            } catch (RuntimeException ignored) {
                // Unknown owner.
            }
        }
        if (ContentResolver.SCHEME_ANDROID_RESOURCE.equals(uri.getScheme()) && uri.getAuthority() != null) {
            return uri.getAuthority();
        }
        return "";
    }

    /**
     * Opens the image behind {@code uri} on a thread of its own and waits for it until {@code deadline} (the pump's
     * total time when the caller has none). Opening is a call into the provider's app, which can take any time and
     * cannot be interrupted: an open that misses the deadline is left behind and counted against {@code owner} like a
     * read that never returned, its file is closed when it comes back, and an owner with too many of them gets no new
     * opens until they return.
     */
    private static AssetFileDescriptor openImageWithin(ContentResolver resolver, Uri uri, int maxWidth,
            int maxHeight, String owner, long deadline) throws FileNotFoundException, UnavailableException {
        final long end = deadline > 0 ? deadline : SystemClock.uptimeMillis() + PUMP_TOTAL_MS;
        if (end <= SystemClock.uptimeMillis()) {
            // No time left: no open is started, so none is left behind and counted against the owner.
            throw new UnavailableException("deadline passed before the open");
        }
        final String lane = owner == null ? "" : owner;
        synchronized (sStuckLock) {
            if (sStuck.getOrDefault(lane, 0) >= MAX_STUCK_PER_OWNER || sStuckTotal >= MAX_STUCK) {
                throw new UnavailableException("too many opens or reads that never returned for " + lane);
            }
        }
        final Opener opener = new Opener(resolver, uri, maxWidth, maxHeight, lane);
        opener.start();
        return opener.await(end);
    }

    /** One open of a URI on its own thread. */
    private static final class Opener extends Thread {
        private final ContentResolver mResolver;
        private final Uri mUri;
        private final int mMaxWidth;
        private final int mMaxHeight;
        private final String mOwner;
        private AssetFileDescriptor mResult; // guarded by sStuckLock
        private Throwable mError; // guarded by sStuckLock
        private boolean mDone; // guarded by sStuckLock
        private boolean mAbandoned; // guarded by sStuckLock

        Opener(ContentResolver resolver, Uri uri, int maxWidth, int maxHeight, String owner) {
            super("VmDecodeOpen");
            setDaemon(true);
            mResolver = resolver;
            mUri = uri;
            mMaxWidth = maxWidth;
            mMaxHeight = maxHeight;
            mOwner = owner;
        }

        @Override
        public void run() {
            AssetFileDescriptor result = null;
            Throwable error = null;
            try {
                result = openImage(mResolver, mUri, mMaxWidth, mMaxHeight);
            } catch (Throwable t) {
                error = t;
            }
            synchronized (sStuckLock) {
                mDone = true;
                if (mAbandoned) {
                    closeQuietly(result);
                    uncountStuckLocked(mOwner);
                } else {
                    mResult = result;
                    mError = error;
                }
                sStuckLock.notifyAll();
            }
        }

        AssetFileDescriptor await(long end) throws FileNotFoundException, UnavailableException {
            synchronized (sStuckLock) {
                while (!mDone) {
                    final long left = end - SystemClock.uptimeMillis();
                    if (left <= 0) {
                        break;
                    }
                    try {
                        sStuckLock.wait(left);
                    } catch (InterruptedException e) {
                        break;
                    }
                }
                if (!mDone) {
                    mAbandoned = true;
                    countStuckLocked(mOwner);
                    throw new UnavailableException("the provider did not open the image in time");
                }
            }
            if (mError instanceof FileNotFoundException) {
                throw (FileNotFoundException) mError;
            }
            if (mError instanceof RuntimeException) {
                throw (RuntimeException) mError;
            }
            if (mError != null) {
                throw new UnavailableException("could not open the URI", mError);
            }
            return mResult;
        }
    }

    private static void countStuckLocked(String owner) {
        sStuck.put(owner, sStuck.getOrDefault(owner, 0) + 1);
        sStuckTotal++;
    }

    private static void uncountStuckLocked(String owner) {
        final int n = sStuck.getOrDefault(owner, 1) - 1;
        if (n <= 0) {
            sStuck.remove(owner);
        } else {
            sStuck.put(owner, n);
        }
        sStuckTotal--;
    }

    private static AssetFileDescriptor openImage(ContentResolver resolver, Uri uri, int maxWidth, int maxHeight)
            throws FileNotFoundException {
        final String scheme = uri.getScheme();
        if (!ContentResolver.SCHEME_CONTENT.equals(scheme)
                && !ContentResolver.SCHEME_ANDROID_RESOURCE.equals(scheme)) {
            // A file: URI (or any other scheme) would be opened with this process's own file access, for whoever
            // named it. Only content URIs (opened through the provider, with grants) and resources are taken.
            throw new FileNotFoundException("only content and resource URIs are opened");
        }
        if (ContentResolver.SCHEME_CONTENT.equals(scheme)) {
            Bundle opts = null;
            if (maxWidth > 0 && maxHeight > 0) {
                opts = new Bundle();
                opts.putParcelable(ContentResolver.EXTRA_SIZE, new Point(maxWidth, maxHeight));
            }
            return resolver.openTypedAssetFileDescriptor(uri, "image/*", opts, null);
        }
        return resolver.openAssetFileDescriptor(uri, "r");
    }

    private static DecodeRequest request(ParcelFileDescriptor fd, String ownerPackage, int priority) {
        final DecodeRequest request = new DecodeRequest();
        request.file = fd;
        request.ownerPackage = ownerPackage == null ? "" : ownerPackage;
        request.priority = priority == PRIORITY_USER_WAITING ? PRIORITY_USER_WAITING : PRIORITY_BACKGROUND;
        request.mimeTypeHint = "";
        return request;
    }

    private static void checkStatus(int status) throws UnavailableException, RejectedException {
        switch (status) {
            case IDecodeService.STATUS_OK:
                return;
            case IDecodeService.STATUS_UNAVAILABLE:
                throw new UnavailableException("the VM is not available");
            case IDecodeService.STATUS_UNSUPPORTED:
                throw new RejectedException("unsupported format");
            case IDecodeService.STATUS_INVALID:
                throw new RejectedException("invalid file");
            default:
                throw new RejectedException("bad status " + status);
        }
    }

    /** Reads every stripe, checks it, and builds the bitmap. Everything the service sent is hostile until here. */
    private static Decoded toDecoded(IDecodeService service, Thumbnail thumbnail, int maxWidth, int maxHeight)
            throws RemoteException, RejectedException {
        final int width = thumbnail.width;
        final int height = thumbnail.height;
        final int stripes;
        try {
            stripes = StripeValidator.checkHeader(width, height, thumbnail.stripeRows, thumbnail.rotation,
                    maxWidth, maxHeight);
        } catch (StripeValidator.BadReplyException e) {
            throw new RejectedException(e.getMessage());
        }
        final byte[] frame = new byte[width * height * 4];
        for (int i = 0; i < stripes; i++) {
            try {
                StripeValidator.putStripe(frame, width, height, thumbnail.stripeRows, i,
                        service.readStripe(thumbnail.handle, i));
            } catch (StripeValidator.BadReplyException e) {
                throw new RejectedException(e.getMessage());
            }
        }
        final Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(frame));
        Bitmap result = bitmap;
        if (thumbnail.rotation != 0) {
            final Matrix matrix = new Matrix();
            matrix.setRotate(thumbnail.rotation);
            result = Bitmap.createBitmap(bitmap, 0, 0, width, height, matrix, true);
            if (result != bitmap) {
                bitmap.recycle();
            }
        }
        return new Decoded(result, thumbnail.rotation);
    }

    private static long pumpDeadline(long callDeadline) {
        final long cap = SystemClock.uptimeMillis() + PUMP_TOTAL_MS;
        return callDeadline > 0 ? Math.min(callDeadline, cap) : cap;
    }

    /** A copy into a pipe on its own thread. */
    private static final class Pumper extends Thread {
        final ParcelFileDescriptor read;
        private final String mOwner;
        private final Runnable mCopy;
        private boolean mDone; // guarded by sStuckLock
        private boolean mCounted; // guarded by sStuckLock

        Pumper(ParcelFileDescriptor read, String owner, Runnable copy) {
            super("VmDecodePump");
            setDaemon(true);
            this.read = read;
            mOwner = owner == null ? "" : owner;
            mCopy = copy;
        }

        @Override
        public void run() {
            try {
                mCopy.run();
            } finally {
                synchronized (sStuckLock) {
                    mDone = true;
                    if (mCounted) {
                        uncountStuckLocked(mOwner);
                    }
                }
            }
        }

        /** After the call: a copy still running now (a read that never returns) counts against its owner. */
        void afterCall() {
            synchronized (sStuckLock) {
                if (!mDone && !mCounted) {
                    mCounted = true;
                    countStuckLocked(mOwner);
                }
            }
        }
    }

    /** A pipe the service reads, filled from {@code afd}'s range on its own thread. */
    private static Pumper pipe(AssetFileDescriptor afd, String owner, long deadline) throws IOException {
        final ParcelFileDescriptor source = afd.getParcelFileDescriptor().dup();
        final long start = afd.getStartOffset();
        final long length = afd.getDeclaredLength();
        return startPump(owner, source, write -> pumpFd(source, start, length, write, deadline));
    }

    /** A pipe the service reads, filled from bytes in memory on its own thread. */
    private static Pumper pipe(byte[] data, int offset, int length, String owner, long deadline)
            throws IOException {
        return startPump(owner, null, write -> pumpBytes(data, offset, length, write, deadline));
    }

    private interface Pump {
        void run(ParcelFileDescriptor write);
    }

    private static Pumper startPump(String owner, ParcelFileDescriptor source, Pump pump) throws IOException {
        synchronized (sStuckLock) {
            if (sStuck.getOrDefault(owner == null ? "" : owner, 0) >= MAX_STUCK_PER_OWNER
                    || sStuckTotal >= MAX_STUCK) {
                closeQuietly(source);
                throw new IOException("too many reads that never returned for " + owner);
            }
        }
        final ParcelFileDescriptor[] pair = ParcelFileDescriptor.createReliablePipe();
        try {
            Os.fcntlInt(pair[1].getFileDescriptor(), OsConstants.F_SETFL, OsConstants.O_NONBLOCK);
            final Pumper pumper = new Pumper(pair[0], owner, () -> pump.run(pair[1]));
            pumper.start();
            return pumper;
        } catch (ErrnoException | RuntimeException e) {
            closeQuietly(source);
            closeQuietly(pair[0]);
            closeQuietly(pair[1]);
            throw new IOException("no pump", e);
        }
    }

    /**
     * Copies {@code length} bytes (or up to the end when negative) from {@code start} of {@code source}. Reads wait
     * with poll() and give up when the provider sends nothing for {@link #PUMP_IDLE_MS} or the deadline passes.
     */
    private static void pumpFd(ParcelFileDescriptor source, long start, long length, ParcelFileDescriptor write,
            long deadline) {
        final FileDescriptor in = source.getFileDescriptor();
        final byte[] buffer = new byte[64 * 1024];
        String error = null;
        try {
            boolean seekable;
            try {
                Os.lseek(in, 0, OsConstants.SEEK_CUR);
                seekable = true;
            } catch (ErrnoException e) {
                seekable = false;
            }
            if (!seekable && start != 0) {
                throw new IOException("a range of a pipe");
            }
            if (!seekable) {
                Os.fcntlInt(in, OsConstants.F_SETFL, OsConstants.O_NONBLOCK);
            }
            final long limit = length >= 0 ? length : Long.MAX_VALUE;
            long copied = 0;
            long lastData = SystemClock.uptimeMillis();
            while (copied < limit) {
                final long now = SystemClock.uptimeMillis();
                if (now >= deadline || now - lastData >= PUMP_IDLE_MS) {
                    throw new IOException("provider too slow");
                }
                final int want = (int) Math.min(buffer.length, limit - copied);
                int n;
                if (seekable) {
                    n = Os.pread(in, buffer, 0, want, start + copied);
                } else {
                    if (!poll(in, OsConstants.POLLIN, Math.min(deadline, lastData + PUMP_IDLE_MS))) {
                        continue;
                    }
                    try {
                        n = Os.read(in, buffer, 0, want);
                    } catch (ErrnoException e) {
                        if (e.errno == OsConstants.EAGAIN) {
                            continue;
                        }
                        throw e;
                    }
                }
                if (n <= 0) {
                    break;
                }
                lastData = SystemClock.uptimeMillis();
                if (copied + n > IDecodeService.MAX_INPUT_BYTES) {
                    throw new IOException("larger than the decode limit");
                }
                writeAll(write, buffer, n, deadline);
                copied += n;
            }
        } catch (IOException | ErrnoException e) {
            error = e.getMessage() == null ? "copy failed" : e.getMessage();
        } finally {
            closeQuietly(source);
            finish(write, error);
        }
    }

    private static void pumpBytes(byte[] data, int offset, int length, ParcelFileDescriptor write, long deadline) {
        String error = null;
        try {
            int done = 0;
            while (done < length) {
                final int n = Math.min(64 * 1024, length - done);
                final byte[] chunk = new byte[n];
                System.arraycopy(data, offset + done, chunk, 0, n);
                writeAll(write, chunk, n, deadline);
                done += n;
            }
        } catch (IOException | ErrnoException e) {
            error = e.getMessage() == null ? "copy failed" : e.getMessage();
        } finally {
            finish(write, error);
        }
    }

    // Writes with poll(), so a service that stops reading cannot hold the pump past the deadline.
    private static void writeAll(ParcelFileDescriptor write, byte[] buffer, int count, long deadline)
            throws IOException, ErrnoException {
        final FileDescriptor out = write.getFileDescriptor();
        int done = 0;
        while (done < count) {
            if (SystemClock.uptimeMillis() >= deadline) {
                throw new IOException("service too slow to read");
            }
            if (!poll(out, OsConstants.POLLOUT, deadline)) {
                continue;
            }
            try {
                done += Os.write(out, buffer, done, count - done);
            } catch (ErrnoException e) {
                if (e.errno != OsConstants.EAGAIN) {
                    throw e;
                }
            }
        }
    }

    // True when the descriptor is ready, false on a timeout (the caller checks its deadline again).
    private static boolean poll(FileDescriptor fd, int events, long until) throws ErrnoException {
        final StructPollfd pollfd = new StructPollfd();
        pollfd.fd = fd;
        pollfd.events = (short) events;
        final long wait = Math.max(0, Math.min(1_000, until - SystemClock.uptimeMillis()));
        try {
            return Os.poll(new StructPollfd[] {pollfd}, (int) wait) > 0;
        } catch (ErrnoException e) {
            if (e.errno == OsConstants.EINTR) {
                return false;
            }
            throw e;
        }
    }

    private static void finish(ParcelFileDescriptor write, String error) {
        try {
            if (error != null) {
                write.closeWithError(error);
            } else {
                write.close();
            }
        } catch (IOException ignored) {
            // Closed already.
        }
    }

    private static void closeQuietly(AutoCloseable c) {
        try {
            if (c != null) {
                c.close();
            }
        } catch (Exception ignored) {
            // Nothing to do.
        }
    }
}
