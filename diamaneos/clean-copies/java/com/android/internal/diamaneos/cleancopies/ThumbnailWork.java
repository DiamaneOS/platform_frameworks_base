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

import android.content.ContentInterface;
import android.content.ContentResolver;
import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.OperationCanceledException;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.provider.DocumentsContract;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructPollfd;
import android.system.StructStat;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/** All source I/O is admitted before open and has the same monotonic deadline as the broker call. */
final class ThumbnailWork {
    private static final long MAX_INPUT = 256L * 1024 * 1024;
    private static final int BUFFER = 64 * 1024;
    private static final String STATUS = "de.diamaneos.vmdecode.thumbnail_status";
    private static final int GONE = 1;
    private static final ThumbnailAdmission sReadBudget = new ThumbnailAdmission(8, 2);
    private static final ThumbnailAdmission sProviderBudget = new ThumbnailAdmission(8, 2);
    private static final ThumbnailAdmission sCallBudget = new ThumbnailAdmission(3, 2);
    private static final ThreadPoolExecutor sReads = pool("VmThumbnailSource", 8);
    private static final ThreadPoolExecutor sCalls = pool("VmThumbnailCall", 3);
    private static final ThreadPoolExecutor sCancels = new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS,
            new java.util.concurrent.ArrayBlockingQueue<>(8), task -> {
                final Thread thread = new Thread(task, "VmThumbnailCancel");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.DiscardPolicy());

    static { sCancels.allowCoreThreadTimeOut(true); }

    private static ThreadPoolExecutor pool(String name, int threads) {
        return new ThreadPoolExecutor(0, threads, 30, TimeUnit.SECONDS, new SynchronousQueue<>(), task -> {
            final Thread thread = new Thread(task, name);
            thread.setDaemon(true);
            return thread;
        });
    }

    private static boolean trustedStatus(ContentInterface source, Uri uri, int uid) throws IOException {
        final Context context = VmThumbnails.context();
        final String authority = android.content.ContentProvider.getAuthorityWithoutUserId(uri.getAuthority());
        if (!"media".equals(authority)
                && !"com.android.providers.media.documents".equals(authority)) return false;
        final int user = android.content.ContentProvider.getUserIdFromUri(uri, -1);
        if (user < 0) return false;
        final android.content.pm.ProviderInfo media = context.getPackageManager()
                .resolveContentProviderAsUser("media", 0, user);
        final android.content.pm.ProviderInfo opened = context.getPackageManager()
                .resolveContentProviderAsUser(authority, 0, user);
        if (media == null || opened == null || media.applicationInfo == null
                || opened.applicationInfo == null || media.applicationInfo.uid != uid
                || opened.applicationInfo.uid != uid || !media.applicationInfo.isSystemApp()
                || !java.util.Objects.equals(media.packageName, opened.packageName)) return false;
        for (int depth = 0; source instanceof ContentResolver; depth++) {
            final ContentInterface next = ContentResolver.getThumbnailSource((ContentResolver) source);
            if (next == source) break;
            if (depth >= 16) return false;
            source = next;
        }
        if (source == context.getContentResolver() || (source instanceof ContentResolver
                && source.getClass() == context.getContentResolver().getClass())) return true;
        try (android.content.ContentProviderClient acquired = context.getContentResolver()
                .acquireContentProviderClient(uri)) {
            if (acquired == null) return false;
            if (source instanceof android.content.ContentProviderClient) {
                return android.content.ContentProviderClient.hasSameThumbnailTransport(
                        (android.content.ContentProviderClient) source, acquired);
            }
            if (source instanceof android.content.ContentProvider) {
                return acquired.getLocalContentProvider() == source;
            }
            return false;
        }
    }

    static final class Source implements AutoCloseable {
        final ParcelFileDescriptor bytes;
        final int orientation;
        final int mediaUser;
        final int providerUid;
        final Job job;
        Source(ParcelFileDescriptor bytes, int orientation, int mediaUser, Job job) {
            this.bytes = bytes;
            this.orientation = orientation;
            this.mediaUser = mediaUser;
            this.providerUid = job.providerUid;
            this.job = job;
        }
        IOException failure() { return job.failure; }
        public void close() {
            job.cancel();
            closeQuietly(bytes);
        }
    }

    static Source open(ContentInterface content, Uri uri, Bundle options, CancellationSignal requested,
            long end) throws IOException {
        checkDeadline(end, requested);
        final ThumbnailAdmission.Ticket ticket = sReadBudget.take(uri.getAuthority());
        if (ticket == null) throw later("Thumbnail source admission full");
        final Job job = new Job(content, uri, options, requested, end, ticket);
        try {
            sReads.execute(job);
        } catch (RuntimeException e) {
            ticket.close();
            throw later("Thumbnail source workers full");
        }
        try {
            return await(job.ready, end, requested);
        } catch (IOException | RuntimeException e) {
            job.cancel();
            if (!job.ready.cancel(false)) job.ready.thenAccept(Source::close);
            throw e;
        }
    }

    private static final class Job implements Runnable {
        final ContentInterface content;
        final Uri uri;
        final Bundle options;
        final CancellationSignal requested;
        final CancellationSignal openSignal = new CancellationSignal();
        final AtomicBoolean cancelled = new AtomicBoolean();
        final long end;
        final ThumbnailAdmission.Ticket ticket;
        ThumbnailAdmission.Ticket providerTicket;
        int providerUid = -1;
        final CompletableFuture<Source> ready = new CompletableFuture<>();
        volatile IOException failure;

        Job(ContentInterface content, Uri uri, Bundle options, CancellationSignal requested, long end,
                ThumbnailAdmission.Ticket ticket) {
            this.content = content;
            this.uri = uri;
            this.options = options;
            this.requested = requested;
            this.end = end;
            this.ticket = ticket;
        }

        void cancel() {
            if (cancelled.compareAndSet(false, true)) sCancels.execute(openSignal::cancel);
        }

        void check() throws IOException {
            if (cancelled.get() || (requested != null && requested.isCanceled())) {
                throw new OperationCanceledException();
            }
            if (SystemClock.uptimeMillis() >= end) throw later("Thumbnail source deadline expired");
        }

        private AssetFileDescriptor openAsset() throws IOException, android.os.RemoteException {
            check();
            final int user = android.content.ContentProvider.getUserIdFromUri(uri, -1);
            if (user < 0) throw new IOException("No concrete thumbnail source user");
            final android.content.pm.ProviderInfo provider = VmThumbnails.context().getPackageManager()
                    .resolveContentProviderAsUser(
                    android.content.ContentProvider.getAuthorityWithoutUserId(uri.getAuthority()), 0, user);
            check();
            if (provider == null || provider.applicationInfo == null || provider.applicationInfo.uid < 0) {
                throw later("Thumbnail provider identity unavailable");
            }
            providerUid = provider.applicationInfo.uid;
            providerTicket = sProviderBudget.take(Integer.toString(providerUid));
            if (providerTicket == null) throw later("Thumbnail provider reads full");
            check();
            return content.openTypedAssetFile(uri, "image/*", options, openSignal);
        }

        public void run() {
            ParcelFileDescriptor output = null;
            try (AssetFileDescriptor asset = openAsset()) {
                check();
                if (asset == null) throw new IOException("No thumbnail source");
                final Bundle extras = asset.getExtras();
                if (extras != null && extras.getInt(STATUS, 0) == GONE
                        && trustedStatus(content, uri, providerUid)) {
                    check();
                    throw new VmThumbnails.MissingException();
                }
                final boolean retry = extras != null
                        && extras.getBoolean("de.diamaneos.vmdecode.thumbnail_later");
                final int orientation = extras == null ? 0
                        : extras.getInt(DocumentsContract.EXTRA_ORIENTATION, 0);
                final int mediaUser = extras == null ? -1 : extras.getInt(VmThumbnails.EXTRA_MEDIA_USER, -1);
                if (extras != null) extras.clear();
                if (retry) throw later("Thumbnail source temporarily unavailable");
                final ParcelFileDescriptor input = asset.getParcelFileDescriptor();
                final StructStat stat = Os.fstat(input.getFileDescriptor());
                final boolean regular = OsConstants.S_ISREG(stat.st_mode);
                final long offset = asset.getStartOffset();
                long length = asset.getDeclaredLength();
                if (length < 0 && regular) length = stat.st_size - offset;
                if (offset < 0 || length < -1 || length == 0 || length > MAX_INPUT
                        || (!regular && offset != 0)
                        || (regular && (offset > stat.st_size || length > stat.st_size - offset))) {
                    throw new IOException("Invalid thumbnail asset extent");
                }
                check();
                final ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createReliablePipe();
                output = pipe[1];
                final Source source = new Source(pipe[0], orientation, mediaUser, this);
                if (!ready.complete(source)) {
                    source.close();
                    return;
                }
                final int flags = Os.fcntlInt(output.getFileDescriptor(), OsConstants.F_GETFL, 0);
                Os.fcntlInt(output.getFileDescriptor(), OsConstants.F_SETFL, flags | OsConstants.O_NONBLOCK);
                final byte[] buffer = new byte[BUFFER];
                long copied = 0;
                while (length < 0 || copied < length) {
                    check();
                    if (copied >= MAX_INPUT) {
                        final byte[] one = new byte[1];
                        if (Os.read(input.getFileDescriptor(), one, 0, 1) > 0) {
                            throw new IOException("Thumbnail source exceeds input limit");
                        }
                        break;
                    }
                    final int want = (int) Math.min(buffer.length,
                            length < 0 ? MAX_INPUT - copied : length - copied);
                    final int count = regular
                            ? Os.pread(input.getFileDescriptor(), buffer, 0, want, offset + copied)
                            : Os.read(input.getFileDescriptor(), buffer, 0, want);
                    if (count <= 0) {
                        if (length >= 0 && copied != length) throw new IOException("Short thumbnail asset");
                        break;
                    }
                    int written = 0;
                    while (written < count) {
                        check();
                        final StructPollfd poll = new StructPollfd();
                        poll.fd = output.getFileDescriptor();
                        poll.events = (short) OsConstants.POLLOUT;
                        if (Os.poll(new StructPollfd[] {poll},
                                (int) Math.min(50, Math.max(1, end - SystemClock.uptimeMillis()))) <= 0) continue;
                        if ((poll.revents & (OsConstants.POLLERR | OsConstants.POLLHUP)) != 0) {
                            throw new IOException("Thumbnail reader closed");
                        }
                        try {
                            written += Os.write(output.getFileDescriptor(), buffer, written, count - written);
                        } catch (ErrnoException e) {
                            if (e.errno != OsConstants.EAGAIN) throw e;
                        }
                    }
                    copied += count;
                }
                try {
                    input.checkError();
                } catch (IOException pipeFailure) {
                    if (pipeFailure.getMessage() != null && pipeFailure.getMessage()
                            .equals("Remote error: decode image rejected: image not accepted")) throw pipeFailure;
                    throw later("Thumbnail pipe temporarily unavailable");
                }
            } catch (Throwable error) {
                failure = retryable(error) ? later("Thumbnail source temporarily unavailable")
                        : error instanceof IOException ? (IOException) error : new IOException(error);
                ready.completeExceptionally(failure);
                if (output != null) {
                    try { output.closeWithError(failure.getMessage()); } catch (IOException ignored) { }
                }
            } finally {
                closeQuietly(output);
                if (providerTicket != null) providerTicket.close();
                ticket.close();
            }
        }
    }

    static Bundle broker(Context context, int providerUid, Bundle request, long end,
            CancellationSignal signal) throws IOException {
        checkDeadline(end, signal);
        if (providerUid < 0) throw new IOException("No verified thumbnail provider UID");
        final ThumbnailAdmission.Ticket ticket = sCallBudget.take(Integer.toString(providerUid));
        if (ticket == null) throw later("Thumbnail broker admission full");
        final ParcelFileDescriptor descriptor = request.getParcelable(CleanCopyClient.EXTRA_FD,
                ParcelFileDescriptor.class);
        final ParcelFileDescriptor owned;
        try {
            owned = descriptor.dup();
        } catch (IOException | RuntimeException e) {
            ticket.close();
            throw e;
        }
        final CompletableFuture<Bundle> result = new CompletableFuture<>();
        try {
            sCalls.execute(() -> {
                try (ParcelFileDescriptor input = owned) {
                    checkDeadline(end, signal);
                    final Bundle args = new Bundle(request);
                    args.putParcelable(CleanCopyClient.EXTRA_FD, input);
                    final Bundle reply = context.getContentResolver().call(CleanCopyClient.URI,
                            VmThumbnails.METHOD_THUMBNAIL, null, args);
                    if (!result.complete(reply)) recycle(reply);
                } catch (Throwable error) {
                    result.completeExceptionally(error);
                } finally {
                    ticket.close();
                }
            });
        } catch (RuntimeException e) {
            closeQuietly(owned);
            ticket.close();
            throw later("Thumbnail broker workers full");
        }
        try {
            return await(result, end, signal);
        } catch (IOException | RuntimeException e) {
            if (!result.cancel(false)) result.thenAccept(ThumbnailWork::recycle);
            throw e;
        }
    }

    private static void checkDeadline(long end, CancellationSignal signal) throws IOException {
        if (signal != null) signal.throwIfCanceled();
        if (SystemClock.uptimeMillis() >= end) throw later("Thumbnail deadline expired");
    }

    private static <T> T await(CompletableFuture<T> future, long end, CancellationSignal signal)
            throws IOException {
        while (true) {
            if (signal != null) signal.throwIfCanceled();
            final long left = end - SystemClock.uptimeMillis();
            if (left <= 0) throw later("Thumbnail deadline expired");
            try {
                return future.get(Math.min(left, 50), TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                // Cancellation and the entry deadline are sampled while the worker owns all I/O.
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw later("Thumbnail wait interrupted");
            } catch (ExecutionException e) {
                if (retryable(e.getCause())) throw later("Thumbnail temporarily unavailable");
                if (e.getCause() instanceof IOException) throw (IOException) e.getCause();
                throw new IOException("Thumbnail work failed", e.getCause());
            }
        }
    }

    static boolean retryable(Throwable error) {
        return error instanceof VmThumbnails.LaterException || (error != null && error.getMessage() != null
                && error.getMessage().contains("decode service unavailable: "));
    }

    private static VmThumbnails.LaterException later(String message) {
        return new VmThumbnails.LaterException(message);
    }

    private static void recycle(Bundle reply) {
        if (reply == null) return;
        final Bitmap bitmap = reply.getParcelable(CleanCopyClient.EXTRA_BITMAP, Bitmap.class);
        if (bitmap != null) bitmap.recycle();
    }

    private static void closeQuietly(ParcelFileDescriptor descriptor) {
        if (descriptor != null) try { descriptor.close(); } catch (IOException ignored) { }
    }
}
