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

package android.graphics;

import android.content.ContentResolver;
import android.content.res.AssetFileDescriptor;
import android.net.Uri;
import android.os.Binder;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;

import libcore.io.IoBridge;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * DiamaneOS: the decode gate's input. Reads an image's bytes into a memory file and hashes them
 * ({@link DecodeGate.Bytes}), reserving their memory first ({@link DecodeMemory}). On a thread that must not wait long
 * ({@link DecodeGate.Op#critical}: the main thread, an incoming binder call, a shared looper) everything that can block
 * runs on the gate's input threads instead, within the call's time from its entry: opening a provider's URI or a file,
 * reading a descriptor (through a copy of it, so the caller's own stays the caller's), hashing a caller's array. When
 * the time is up the call fails at once and the read is stopped (best effort: its copy of the descriptor is closed).
 * A caller's stream cannot be read on another thread once the call returned, so on such a thread only a stream
 * that is a plain file's (read through its descriptor) or small and exactly in memory is read, any other stream gives
 * no image there. Other threads read their input themselves, as before. At most {@link #THREADS} reads run, at most
 * {@link #MAX_READS} run or wait, at most {@link #MAX_READS_PER_OWNER} of one owner (a provider's authority, else the
 * calling uid).
 */
final class GateInput {
    static final int THREADS = 3;
    static final int MAX_READS = 16;
    static final int MAX_READS_PER_OWNER = 2;
    private static final int PIECE = 64 * 1024;
    /** An array up to this long is hashed on the calling thread, whatever it is (no waiting, about a millisecond). */
    static final int INLINE_BYTES = 1 << 20;
    private static final Object sLock = new Object();
    private static int sReads; // guarded by sLock
    private static final Counts sReadsOf = new Counts(); // guarded by sLock

    // Fixed storage keeps quota transitions free of map-node construction after admission begins.
    private static final class Counts {
        private final String[] keys = new String[MAX_READS * 4];
        private final int[] counts = new int[MAX_READS * 4];
        int getOrDefault(String key, int fallback) {
            for (int i = 0; i < keys.length; i++) if (key.equals(keys[i])) return counts[i];
            return fallback;
        }
        void put(String key, int count) {
            int free = -1;
            for (int i = 0; i < keys.length; i++) {
                if (key.equals(keys[i])) { counts[i] = count; return; }
                if (keys[i] == null) free = i;
            }
            if (free < 0) throw new IllegalStateException("input accounting capacity");
            keys[free] = key;
            counts[free] = count;
        }
        void remove(String key) {
            for (int i = 0; i < keys.length; i++) {
                if (key.equals(keys[i])) { keys[i] = null; counts[i] = 0; return; }
            }
        }
        int total(String prefix) {
            int total = 0;
            for (int i = 0; i < keys.length; i++) {
                if (keys[i] != null && keys[i].startsWith(prefix)) total += counts[i];
            }
            return total;
        }
    }
    private static ThreadPoolExecutor sReaders; // guarded by sLock

    static int readsForTest() {
        synchronized (sLock) { return sReads; }
    }

    private static void allocationForTest() {
        final Runnable fault = DecodeGate.inputFault;
        if (fault != null) fault.run();
    }

    private GateInput() {
    }

    // Arrays and buffers

    /** The bytes {@code data[offset..offset+length)}, copied a piece at a time, so what is hashed is what is sent. */
    static DecodeGate.Bytes of(DecodeGate.Op op, byte[] data, int offset, int length) throws DecodeGate.Failure {
        DecodeGate.mayAsk();
        checkLength(length);
        if (!op.critical || length <= INLINE_BYTES) {
            return copyArray(data, offset, length, null);
        }
        final String source = op.inputKey(data, offset + ":" + length);
        final DecodeGate.Bytes kept = op.refill != null ? op.refill.input(source) : null;
        if (kept != null) return kept;
        return onReader(op, "uid:" + Binder.getCallingUid(), stop -> copyArray(data, offset, length, stop), null,
                source, new java.util.concurrent.atomic.AtomicBoolean());
    }

    /**
     * The bytes of {@code buffer} from its position to its limit. They are copied on the calling thread first (a
     * direct buffer's memory is the caller's only until the call returns), then hashed as an array.
     */
    static DecodeGate.Bytes of(DecodeGate.Op op, ByteBuffer buffer) throws DecodeGate.Failure {
        DecodeGate.mayAsk();
        final ByteBuffer view = buffer.duplicate();
        final int length = view.remaining();
        checkLength(length);
        if (!view.isDirect() && view.hasArray()) {
            return of(op, view.array(), view.arrayOffset() + view.position(), length);
        }
        if (op.critical && length > INLINE_BYTES) {
            throw DecodeGate.notThisWay("a caller-owned buffer of this size needs a worker thread");
        }
        final DecodeMemory.Reservation copy = reserve(length);
        try {
            final byte[] data = new byte[length];
            view.get(data);
            return of(op, data, 0, length);
        } finally {
            copy.close();
        }
    }

    // Streams

    /** One decode's stream, paused after a VM-valid header until its listener returns. */
    static final class HeaderStream implements AutoCloseable {
        private final InputStream input;
        private DecodeMemory.Reservation charge;
        private FileDescriptor out;
        private final MessageDigest digest;
        private final byte[] buffer;
        private final byte[] head;
        private final GatePngFrame png = new GatePngFrame();
        private int total;
        private boolean ended;

        HeaderStream(InputStream input) throws DecodeGate.Failure {
            this.input = input;
            charge = reserve(PIECE + 192);
            try {
                digest = newDigest(charge);
                out = memory(charge);
                buffer = new byte[PIECE];
                head = new byte[32];
            } catch (DecodeGate.Failure | RuntimeException | Error e) {
                if (out != null) closeQuietly(out);
                charge.close();
                throw e;
            }
        }

        /** Reads one bounded piece, never a later piece after a header has been returned. */
        boolean next() throws DecodeGate.Failure {
            if (ended || png.complete()) return false;
            final int count;
            try {
                count = input.read(buffer, 0, png.readLimit(buffer.length));
            } catch (IOException e) {
                throw DecodeGate.inputFailed(e);
            }
            if (count <= 0) {
                ended = true;
                return false;
            }
            if (total < head.length) {
                System.arraycopy(buffer, 0, head, total, Math.min(count, head.length - total));
            }
            if (count > DecodeGate.MAX_BYTES - total) {
                throw DecodeGate.failed(true, "larger than the decode limit");
            }
            grow(charge, count);
            total += count;
            png.accept(buffer, count);
            digest.update(buffer, 0, count);
            writeAll(out, buffer, count);
            return true;
        }

        /** An independently sealed prefix for the VM, never the mutable staging descriptor. */
        DecodeGate.Bytes prefix() throws DecodeGate.Failure {
            return copyFd(out, 0, total, null);
        }

        /** The listener has returned, so the rest may now be read for the pixel operation. */
        DecodeGate.Bytes finish() throws DecodeGate.Failure {
            while (next()) { }
            if (total == 0) throw DecodeGate.failed(true, "no bytes");
            final DecodeGate.Bytes bytes = done(out, java.util.Arrays.copyOf(head, Math.min(total, head.length)),
                    digest, charge);
            charge = null;
            return bytes;
        }

        @Override public void close() {
            if (out != null) {
                closeQuietly(out);
                out = null;
            }
            if (charge != null) {
                charge.close();
                charge = null;
            }
        }
    }

    /** Reads one complete PNG or to the stream end, at most {@link DecodeGate#MAX_BYTES}. The stream stays open. */
    static DecodeGate.Bytes of(DecodeGate.Op op, InputStream in) throws DecodeGate.Failure {
        DecodeGate.mayAsk();
        if (op.critical && in.getClass() == ByteArrayInputStream.class
                && ((ByteArrayInputStream) in).available() > INLINE_BYTES) {
            throw DecodeGate.notThisWay("a large caller-owned stream needs a worker thread");
        }
        if (!op.critical || in.getClass() == ByteArrayInputStream.class) {
            // This thread may read it, or it is the exact bounded in-memory stream type.
            return copyStream(in, null);
        }
        final Class<?> type = in.getClass();
        if (type == FileInputStream.class || type == ParcelFileDescriptor.AutoCloseInputStream.class) {
            // A plain file's stream: read through its descriptor from where the stream is, which then stands at
            // the end, as a decode that read it all leaves it.
            final FileDescriptor fd;
            try {
                fd = ((FileInputStream) in).getFD();
            } catch (IOException e) {
                throw DecodeGate.notThisWay("stream not readable: " + e.getMessage());
            }
            return of(op, fd, -1, -1, true);
        }
        throw DecodeGate.notThisWay("a stream of " + type.getName() + " is not read on this thread");
    }

    // Descriptors

    /**
     * The bytes of {@code fd} from {@code offset} ({@code -1}: its current position) for {@code length} bytes
     * ({@code -1}: to its end), read without moving its position. A descriptor that cannot be read at an offset
     * (a pipe, a socket) is read as a stream from where it is.
     */
    static DecodeGate.Bytes of(DecodeGate.Op op, FileDescriptor fd, long offset, long length)
            throws DecodeGate.Failure {
        return of(op, fd, offset, length, false);
    }

    private static DecodeGate.Bytes of(DecodeGate.Op op, FileDescriptor fd, long offset, long length,
            boolean moveToEnd) throws DecodeGate.Failure {
        DecodeGate.mayAsk();
        if (!op.critical) {
            final DecodeGate.Bytes bytes = copyFd(fd, offset, length, null);
            if (moveToEnd) {
                moveToEnd(fd);
            }
            return bytes;
        }
        // A regular file whose read goes on after its caller left is kept for the next call (and the caller's refill
        // registration), so that one need not read it again: a thread that never waits gets the image after all.
        final String source = op.inputKey(fd, offset + ":" + length);
        DecodeGate.Bytes kept = op.refill != null ? op.refill.input(source) : null;
        if (kept != null) {
            return kept;
        }
        final ParcelFileDescriptor own;
        try {
            own = ParcelFileDescriptor.dup(fd);
        } catch (IOException e) {
            throw DecodeGate.notThisWay("descriptor not readable: " + e.getMessage());
        }
        final DecodeGate.Bytes bytes;
        final java.util.concurrent.atomic.AtomicBoolean goesOn = new java.util.concurrent.atomic.AtomicBoolean();
        try {
            bytes = onReader(op, "uid:" + Binder.getCallingUid(),
                    stop -> copyFd(own.getFileDescriptor(), offset, length, stop),
                    () -> closeAndSignal(own), source, goesOn);
        } finally {
            if (!goesOn.get()) {
                closeQuietly(own); // else the read closes it when it ends
            }
        }
        return bytes;
    }

    static final class Acquired implements AutoCloseable {
        DecodeGate.Bytes bytes;
        FileInputStream stream;
        AssetFileDescriptor descriptor;

        Acquired(DecodeGate.Bytes bytes, FileInputStream stream, AssetFileDescriptor descriptor) {
            this.bytes = bytes;
            this.stream = stream;
            this.descriptor = descriptor;
        }

        DecodeGate.Bytes takeBytes() {
            final DecodeGate.Bytes result = bytes;
            bytes = null;
            return result;
        }

        @Override
        public void close() {
            if (bytes != null) bytes.close();
            closeQuietly(stream);
            if (descriptor != null) closeQuietly(descriptor);
        }
    }

    private static Acquired acquired(DecodeGate.Bytes bytes) {
        try {
            return new Acquired(bytes, null, null);
        } catch (RuntimeException | Error e) {
            bytes.close();
            throw e;
        }
    }

    static Acquired acquireFile(DecodeGate.Op op, File file) throws DecodeGate.Failure {
        return acquireFile(op, file, false);
    }

    static Acquired acquireFile(DecodeGate.Op op, File file, boolean forceVm) throws DecodeGate.Failure {
        final String source = op.inputKey("file:" + file.getAbsolutePath());
        final DecodeGate.Bytes kept = op.refill != null ? op.refill.input(source) : null;
        if (kept != null) return acquired(kept);
        return onReader(op, "files", stop -> {
            FileInputStream in = null;
            boolean transferred = false;
            try {
                try {
                    in = new FileInputStream(file);
                } catch (FileNotFoundException e) {
                    throw DecodeGate.sourceUnavailable(e); // as stock ImageDecoder.createFromFile
                }
                stopped(stop);
                if (!forceVm && DecodeGate.exempt(in.getFD()) && op.left() > 0) {
                    final Acquired input = new Acquired(null, in, null);
                    transferred = true;
                    return input;
                }
                return acquired(copyFd(in.getFD(), 0, -1, stop));
            } catch (IOException e) {
                throw DecodeGate.notNow("file not opened: " + e.getClass().getSimpleName());
            } finally {
                if (!transferred) closeQuietly(in);
            }
        }, null, source, new java.util.concurrent.atomic.AtomicBoolean());
    }

    static final class ProviderPlace implements AutoCloseable {
        final String key;
        private boolean closed;
        ProviderPlace(String key) { this.key = key; }
        @Override public void close() {
            synchronized (sLock) {
                if (closed) return;
                closed = true;
                if (key != null) releaseKeyLocked(key);
            }
        }
    }

    static ProviderPlace caller(int uid) throws DecodeGate.Failure {
        final String key = "uid:" + uid;
        final ProviderPlace place = new ProviderPlace(key);
        synchronized (sLock) {
            final int held = sReadsOf.getOrDefault(key, 0);
            if (held >= MAX_READS_PER_OWNER) throw DecodeGate.notNow("too many inputs for this caller");
            sReadsOf.put(key, held + 1);
        }
        return place;
    }

    static ProviderPlace provider(ContentResolver resolver, Uri uri) throws DecodeGate.Failure {
        if (!ContentResolver.SCHEME_CONTENT.equals(uri.getScheme())) return new ProviderPlace(null);
        final int user = GateSourceUsers.of(resolver, uri);
        final android.content.pm.ProviderInfo info;
        try {
            info = android.app.ActivityThread.getPackageManager().resolveContentProvider(
                    android.content.ContentProvider.getUriWithoutUserId(uri).getAuthority(), 0L, user);
        } catch (android.os.RemoteException | RuntimeException e) {
            throw DecodeGate.notNow("provider identity unavailable");
        }
        if (info == null) {
            // Stock: ContentResolver finds no provider and ImageDecoder passes its FileNotFoundException on.
            throw DecodeGate.sourceUnavailable(new FileNotFoundException("No content provider: " + uri));
        }
        if (info.applicationInfo == null) throw DecodeGate.notNow("provider identity unavailable");
        final String key = "provider-uid:" + info.applicationInfo.uid;
        final ProviderPlace place = new ProviderPlace(key);
        synchronized (sLock) {
            final int held = sReadsOf.getOrDefault(key, 0);
            if (held >= MAX_READS_PER_OWNER) throw DecodeGate.notNow("too many inputs from this provider");
            sReadsOf.put(key, held + 1);
        }
        return place;
    }

    static Acquired acquireUri(DecodeGate.Op op, ContentResolver resolver, Uri uri)
            throws DecodeGate.Failure {
        final String source = op.inputKey(resolver, "uri:" + uri);
        final DecodeGate.Bytes kept = op.refill != null ? op.refill.input(source) : null;
        if (kept != null) return acquired(kept);
        final long caller = callingIdentity();
        return onReader(op, ContentResolver.SCHEME_CONTENT.equals(uri.getScheme())
                ? "authority:" + uri.getAuthority() : "files", stop -> {
            final long own = Binder.clearCallingIdentity();
            Binder.restoreCallingIdentity(caller);
            AssetFileDescriptor afd = null;
            boolean transferred = false;
            try (ProviderPlace place = provider(resolver, uri)) {
                try {
                    afd = ContentResolver.SCHEME_CONTENT.equals(uri.getScheme())
                            ? resolver.openTypedAssetFileDescriptor(uri, "image/*", null)
                            : resolver.openAssetFileDescriptor(uri, "r");
                } catch (FileNotFoundException ignored) {
                    // ImageDecoder also tries a stream when no descriptor is offered.
                }
                if (afd != null) {
                    stopped(stop);
                    if (DecodeGate.exempt(afd.getFileDescriptor()) && op.left() > 0) {
                        final Acquired input = new Acquired(null, null, afd);
                        transferred = true;
                        return input;
                    }
                    return acquired(copyFd(afd.getFileDescriptor(), afd.getStartOffset(), afd.getDeclaredLength(), stop));
                }
                final InputStream opened;
                try {
                    opened = resolver.openInputStream(uri);
                } catch (FileNotFoundException e) {
                    throw DecodeGate.sourceUnavailable(e); // as stock ImageDecoder.java:300
                }
                try (InputStream in = opened) {
                    if (in == null) throw DecodeGate.sourceUnavailable(new FileNotFoundException(uri.toString()));
                    stopped(stop);
                    return acquired(copyStream(in, stop));
                }
            } catch (IOException | RuntimeException e) {
                throw DecodeGate.notNow("provider input not read: " + e.getClass().getSimpleName());
            } finally {
                if (!transferred && afd != null) closeQuietly(afd);
                Binder.restoreCallingIdentity(own);
            }
        }, null, source, new java.util.concurrent.atomic.AtomicBoolean());
    }

    // The reading itself

    /** One piece of work for an input thread. stop: true once the caller left, null on the caller's own thread. */
    private interface Job<T> {
        T run(java.util.concurrent.atomic.AtomicBoolean stop) throws DecodeGate.Failure;
    }

    /**
     * A job on an input thread, waited for until the call's end. When its caller leaves first, it is stopped, or (a
     * regular file's read) it goes on for at most {@link #BACKGROUND_MS} and what it read is kept for the next call
     * ({@link #KEPT_MS}) and for the caller's refill registration.
     */
    private static final class Read<T> implements Runnable {
        final Job<T> job;
        final String owner;
        final String caller;
        final GateRefill registration;
        final Runnable onLeave;
        final java.util.concurrent.atomic.AtomicBoolean stop = new java.util.concurrent.atomic.AtomicBoolean();
        final java.util.concurrent.atomic.AtomicInteger dispatch = new java.util.concurrent.atomic.AtomicInteger();
        private boolean mDone; // guarded by this
        private T mResult; // guarded by this
        private DecodeGate.Failure mFailure; // guarded by this
        private boolean mKeep; // guarded by this
        private GateRefill mKeepFor; // guarded by this
        private String mSource; // guarded by this

        Read(Job<T> job, String owner, String caller, GateRefill registration, Runnable onLeave) {
            this.job = job;
            this.owner = owner;
            this.caller = caller;
            this.registration = registration;
            this.onLeave = onLeave;
        }

        @Override
        public void run() {
            if (!dispatch.compareAndSet(0, 1)) return;
            T result = null;
            DecodeGate.Failure failure = null;
            try {
                stopped(stop);
                if (registration != null && !registration.isActive()) throw DecodeGate.notNow("the input demand ended");
                result = job.run(stop);
            } catch (DecodeGate.Failure e) {
                failure = e;
            } catch (RuntimeException | OutOfMemoryError e) {
                failure = DecodeGate.notNow("input not read: " + e.getClass().getSimpleName());
            } finally {
                if (dispatch.compareAndSet(1, 2)) ended(owner, caller);
            }
            final boolean taken;
            final boolean keep;
            final GateRefill keepFor;
            final String source;
            synchronized (this) {
                taken = !stop.get() && !mKeep;
                keep = mKeep;
                keepFor = mKeepFor;
                source = mSource;
                mDone = true;
                mResult = result;
                mFailure = failure;
                notifyAll();
            }
            if (keep) {
                if (onLeave != null) {
                    onLeave.run(); // the read's own descriptor
                }
                final DecodeGate.Bytes bytes = result instanceof Acquired ? ((Acquired) result).takeBytes()
                        : (DecodeGate.Bytes) result;
                if (result instanceof Acquired) ((Acquired) result).close();
                if (keepFor != null) {
                    keepFor.inputRead(source, bytes, failure);
                } else if (bytes != null) {
                    bytes.close();
                }
            } else if (!taken) {
                discard(result); // the caller left: nobody takes it
            }
        }

        // True when the read ended by end. Else the caller leaves: the read stops, or goes on to be kept as source
        // (when not null), also for keepFor.
        synchronized boolean await(long end, GateRefill keepFor, String source) {
            while (!mDone) {
                final long left = end - SystemClock.uptimeMillis();
                if (left <= 0) {
                    break;
                }
                try {
                    wait(left);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            if (!mDone) {
                if (source != null) {
                    mKeep = true;
                    mKeepFor = keepFor;
                    mSource = source;
                    if (keepFor != null) {
                        keepFor.waitsFor();
                    }
                    GateRefill.timerForGate().postDelayed(this::tooLong, BACKGROUND_MS);
                } else {
                    stop.set(true);
                    synchronized (sLock) {
                        if (sReaders != null && sReaders.remove(this) && dispatch.compareAndSet(0, 2)) endedLocked(owner, caller);
                    }
                }
            }
            return mDone;
        }

        // A read kept going for a registration ran out of its time: stopped, best effort.
        private void tooLong() {
            final boolean removed;
            final GateRefill refill;
            final String source;
            synchronized (this) {
                if (mDone) return;
                stop.set(true);
                synchronized (sLock) {
                    removed = sReaders != null && sReaders.remove(this);
                    if (removed && dispatch.compareAndSet(0, 2)) endedLocked(owner, caller);
                }
                refill = mKeepFor;
                source = mSource;
                if (removed) {
                    mDone = true;
                    notifyAll();
                }
            }
            if (onLeave != null) cancelLater(onLeave);
            if (removed && refill != null) refill.inputRead(source, null, DecodeGate.notNow("queued input expired"));
        }
    }

    /** A file read that is kept goes on at most this long after its caller left. */
    static final long BACKGROUND_MS = 10_000;

    private static <T> T onReader(DecodeGate.Op op, String owner, Job<T> job, Runnable onLeave)
            throws DecodeGate.Failure {
        return onReader(op, owner, job, onLeave, null, null);
    }

    // keptAs: the source the read is kept as when it goes on after the caller left (goesOn is then set), else null.
    private static <T> T onReader(DecodeGate.Op op, String owner, Job<T> job, Runnable onLeave, String keptAs,
            java.util.concurrent.atomic.AtomicBoolean goesOn) throws DecodeGate.Failure {
        final boolean uri = owner.startsWith("authority:");
        final String caller = "uid:" + Binder.getCallingUid() + (uri ? ":uri" : "");
        final Read<T> read = new Read<>(job, owner, caller, op.refill, keptAs != null ? onLeave : null);
        synchronized (sLock) {
            final int held = sReadsOf.getOrDefault(owner, 0);
            // URI opens leave a reader and separate caller slots for local inputs.
            if (sReads >= MAX_READS || (uri && sReadsOf.total("authority:") >= THREADS - 1)
                    || held >= MAX_READS_PER_OWNER
                    || sReadsOf.getOrDefault(caller, 0) >= MAX_READS_PER_OWNER) {
                throw DecodeGate.notNow("too many inputs being read");
            }
            sReadsOf.put(owner, held + 1);
            try {
                if (!caller.equals(owner)) sReadsOf.put(caller, sReadsOf.getOrDefault(caller, 0) + 1);
            } catch (RuntimeException | Error e) {
                releaseKeyLocked(owner);
                throw e;
            }
            sReads++;
            try {
                readers().execute(read);
            } catch (RuntimeException | Error e) {
                read.stop.set(true);
                if (read.dispatch.compareAndSet(0, 2)) endedLocked(owner, caller);
                synchronized (read) {
                    if (read.mDone) {
                        discard(read.mResult);
                        read.mResult = null;
                    }
                }
                if (e instanceof java.util.concurrent.RejectedExecutionException) {
                    throw DecodeGate.notNow("too many inputs being read");
                }
                throw e;
            }
        }
        if (!read.await(op.left() > 0 ? op.end : 0, op.refill, keptAs)) {
            if (keptAs != null) {
                goesOn.set(true);
                throw DecodeGate.notNow("input read on for the next call");
            }
            cancelLater(onLeave);
            throw DecodeGate.notNow("input not read in the call's time");
        }
        synchronized (read) {
            if (read.mFailure != null) {
                throw op.received(read.mFailure);
            }
            return read.mResult;
        }
    }

    private static void cancelLater(Runnable cancel) {
        if (cancel != null) {
            GateRefill.timerForGate().post(() -> {
                try {
                    synchronized (sLock) { readers().execute(cancel); }
                } catch (RuntimeException | Error ignored) {
                    // The bounded reader pool is unavailable. The read still owns its descriptor.
                }
            });
        }
    }

    private static void ended(String owner, String caller) {
        synchronized (sLock) {
            endedLocked(owner, caller);
        }
    }

    private static void endedLocked(String owner, String caller) {
        sReads--;
        releaseKeyLocked(owner);
        if (!owner.equals(caller)) releaseKeyLocked(caller);
    }

    private static void releaseKeyLocked(String owner) {
        final int left = sReadsOf.getOrDefault(owner, 1) - 1;
        if (left <= 0) {
            sReadsOf.remove(owner);
        } else {
            sReadsOf.put(owner, left);
        }
    }

    private static ThreadPoolExecutor readers() {
        if (sReaders == null) {
            sReaders = new ThreadPoolExecutor(THREADS, THREADS, 30, java.util.concurrent.TimeUnit.SECONDS,
                    new java.util.concurrent.LinkedBlockingQueue<>(MAX_READS), r -> {
                        final Thread t = new Thread(() -> {
                            DecodeGate.markGateThread();
                            r.run();
                        }, "DecodeGateInput");
                        t.setDaemon(true);
                        return t;
                    });
            sReaders.allowCoreThreadTimeOut(true);
        }
        return sReaders;
    }

    private static void discard(Object result) {
        if (result instanceof Acquired) {
            ((Acquired) result).close();
        } else if (result instanceof DecodeGate.Bytes) {
            ((DecodeGate.Bytes) result).close();
        } else if (result instanceof AssetFileDescriptor) {
            closeQuietly((AssetFileDescriptor) result);
        } else if (result instanceof java.io.Closeable) {
            closeQuietly((java.io.Closeable) result);
        }
    }

    private static DecodeGate.Bytes copyArray(byte[] data, int offset, int length,
            java.util.concurrent.atomic.AtomicBoolean stop) throws DecodeGate.Failure {
        final DecodeMemory.Reservation memory = reserve((long) length + PIECE + 64);
        FileDescriptor out = null;
        boolean transferred = false;
        try {
            final MessageDigest digest = newDigest(memory);
            out = memory(memory);
            allocationForTest();
            final byte[] piece = new byte[Math.min(length, PIECE)];
            byte[] head = null;
            int done = 0;
            while (done < length) {
                stopped(stop);
                final int n = Math.min(piece.length, length - done);
                System.arraycopy(data, offset + done, piece, 0, n);
                if (head == null) {
                    head = java.util.Arrays.copyOf(piece, Math.min(n, 32));
                }
                digest.update(piece, 0, n);
                writeAll(out, piece, n);
                done += n;
            }
            final DecodeGate.Bytes bytes = done(out, head, digest, memory);
            transferred = true;
            return bytes;
        } finally {
            try {
                if (out != null) closeQuietly(out);
            } finally {
                if (!transferred) memory.close();
            }
        }
    }

    private static DecodeGate.Bytes copyStream(InputStream in, java.util.concurrent.atomic.AtomicBoolean stop)
            throws DecodeGate.Failure {
        final DecodeMemory.Reservation memory = reserve(PIECE + 192);
        FileDescriptor out = null;
        boolean transferred = false;
        try {
            final MessageDigest digest = newDigest(memory);
            out = memory(memory);
            allocationForTest();
            final byte[] buffer = new byte[PIECE];
            final byte[] head = new byte[32];
            final GatePngFrame png = new GatePngFrame();
            int total = 0;
            int n;
            while (!png.complete()) {
                stopped(stop);
                try {
                    n = in.read(buffer, 0, png.readLimit(buffer.length));
                } catch (IOException e) {
                    throw DecodeGate.inputFailed(e);
                }
                if (n <= 0) {
                    break;
                }
                if (total < head.length) {
                    System.arraycopy(buffer, 0, head, total, Math.min(n, head.length - total));
                }
                total += n;
                if (total > DecodeGate.MAX_BYTES) {
                    throw DecodeGate.failed(true, "larger than the decode limit");
                }
                png.accept(buffer, n);
                grow(memory, n);
                digest.update(buffer, 0, n);
                writeAll(out, buffer, n);
            }
            if (total == 0) {
                throw DecodeGate.failed(true, "no bytes");
            }
            final DecodeGate.Bytes bytes = done(out, java.util.Arrays.copyOf(head, Math.min(total, head.length)), digest, memory);
            transferred = true;
            return bytes;
        } finally {
            try {
                if (out != null) closeQuietly(out);
            } finally {
                if (!transferred) memory.close();
            }
        }
    }

    private static DecodeGate.Bytes copyFd(FileDescriptor fd, long offset, long length,
            java.util.concurrent.atomic.AtomicBoolean stop) throws DecodeGate.Failure {
        final StructStat st;
        final long start;
        try {
            st = Os.fstat(fd);
            start = offset >= 0 ? offset : Os.lseek(fd, 0, OsConstants.SEEK_CUR);
        } catch (ErrnoException e) {
            return copyStream(new KeptStream(fd), stop);
        }
        if (!OsConstants.S_ISREG(st.st_mode)) {
            return copyStream(new KeptStream(fd), stop);
        }
        final long end = length >= 0 ? Math.min(st.st_size, start + length) : st.st_size;
        if (end - start > DecodeGate.MAX_BYTES) {
            throw DecodeGate.failed(true, "larger than the decode limit");
        }
        if (end <= start) {
            throw DecodeGate.failed(true, "no bytes");
        }
        final DecodeMemory.Reservation memory = reserve(end - start + PIECE + 64);
        FileDescriptor out = null;
        boolean transferred = false;
        try {
            final MessageDigest digest = newDigest(memory);
            out = memory(memory);
            allocationForTest();
            final byte[] buffer = new byte[(int) Math.min(PIECE, end - start)];
            byte[] head = null;
            long at = start;
            while (at < end) {
                stopped(stop);
                final int n;
                try {
                    n = Os.pread(fd, buffer, 0, (int) Math.min(buffer.length, end - at), at);
                } catch (ErrnoException | java.io.InterruptedIOException e) {
                    throw DecodeGate.notNow("file not read: " + e.getMessage());
                }
                if (n <= 0) {
                    break;
                }
                if (head == null) {
                    head = java.util.Arrays.copyOf(buffer, Math.min(n, 32));
                }
                digest.update(buffer, 0, n);
                writeAll(out, buffer, n);
                at += n;
            }
            if (head == null) {
                throw DecodeGate.failed(true, "no bytes");
            }
            final DecodeGate.Bytes bytes = done(out, head, digest, memory);
            transferred = true;
            return bytes;
        } finally {
            try {
                if (out != null) closeQuietly(out);
            } finally {
                if (!transferred) memory.close();
            }
        }
    }

    // A descriptor read as a stream that is the caller's (never closed here).
    private static final class KeptStream extends FileInputStream {
        KeptStream(FileDescriptor fd) {
            super(fd);
        }

        @Override
        public void close() {
            // The caller (or the read's own copy's owner) closes the descriptor.
        }
    }

    private static void stopped(java.util.concurrent.atomic.AtomicBoolean stop) throws DecodeGate.Failure {
        if (stop != null && stop.get()) {
            throw DecodeGate.notNow("the caller left");
        }
    }

    private static void checkLength(int length) throws DecodeGate.Failure {
        if (length > DecodeGate.MAX_BYTES) {
            throw DecodeGate.failed(true, "larger than the decode limit");
        }
        if (length <= 0) {
            throw DecodeGate.failed(true, "no bytes");
        }
    }

    private static DecodeMemory.Reservation reserve(long bytes) throws DecodeGate.Failure {
        try {
            return DecodeMemory.reserve(bytes);
        } catch (DecodeMemory.NoRoomException e) {
            throw DecodeGate.noRoom(e);
        }
    }

    private static void grow(DecodeMemory.Reservation memory, long more) throws DecodeGate.Failure {
        if (!memory.grow(more)) {
            throw DecodeGate.notNow("no room for the image's bytes now");
        }
    }

    private static MessageDigest newDigest(DecodeMemory.Reservation memory) throws DecodeGate.Failure {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException e) {
            memory.close();
            throw DecodeGate.notNow("no SHA-256");
        }
    }

    private static FileDescriptor memory(DecodeMemory.Reservation memory) throws DecodeGate.Failure {
        try {
            return Os.memfd_create("decode-gate", OsConstants.MFD_CLOEXEC | 2);
        } catch (ErrnoException e) {
            memory.close();
            throw DecodeGate.notNow("no memory file: " + e.getMessage());
        }
    }

    private static void writeAll(FileDescriptor out, byte[] buffer, int n) throws DecodeGate.Failure {
        try {
            int written = 0;
            while (written < n) {
                written += Os.write(out, buffer, written, n - written);
            }
        } catch (ErrnoException | java.io.InterruptedIOException e) {
            throw DecodeGate.notNow("bytes not copied: " + e.getMessage());
        }
    }

    private static DecodeGate.Bytes done(FileDescriptor out, byte[] head, MessageDigest digest,
            DecodeMemory.Reservation memory) throws DecodeGate.Failure {
        ParcelFileDescriptor fd = null;
        boolean transferred = false;
        try {
            // Linux memfd seals prevent writes, growth and shrinkage after publication.
            Os.fcntlInt(out, 1033, 1 | 2 | 4 | OsConstants.F_SEAL_WRITE);
            Os.lseek(out, 0, OsConstants.SEEK_SET);
            final StringBuilder hex = new StringBuilder(64);
            for (byte b : digest.digest()) {
                hex.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
            }
            fd = ParcelFileDescriptor.dup(out);
            final DecodeGate.Bytes bytes = new DecodeGate.Bytes(fd, head, hex.toString(), memory);
            transferred = true;
            return bytes;
        } catch (ErrnoException | IOException e) {
            throw DecodeGate.notNow("no memory file: " + e.getMessage());
        } finally {
            if (!transferred && fd != null) closeQuietly(fd);
        }
    }

    // Leaves a plain file's stream at its end, as a decode that read it all leaves it.
    private static void moveToEnd(FileDescriptor fd) {
        try {
            Os.lseek(fd, 0, OsConstants.SEEK_END);
        } catch (ErrnoException e) {
            // A pipe: reading it moved it.
        }
    }

    // The binder identity of the calling thread, for a provider opened on an input thread.
    private static long callingIdentity() {
        final long token = Binder.clearCallingIdentity();
        Binder.restoreCallingIdentity(token);
        return token;
    }

    private static void closeAndSignal(ParcelFileDescriptor fd) {
        try {
            IoBridge.closeAndSignalBlockedThreads(fd.getFileDescriptor());
        } catch (IOException ignored) {
            // Closed.
        }
    }

    private static void closeQuietly(FileDescriptor fd) {
        try {
            Os.close(fd);
        } catch (ErrnoException ignored) {
            // Closed.
        }
    }

    private static void closeQuietly(AssetFileDescriptor fd) {
        try {
            fd.close();
        } catch (IOException ignored) {
            // Closed.
        }
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (IOException ignored) {
            // Closed.
        }
    }
}
