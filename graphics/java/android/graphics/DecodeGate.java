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

import android.annotation.Nullable;
import android.app.ActivityThread;
import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.IRemoteCallback;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.SystemClock;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import android.util.Log;

import com.android.internal.diamaneos.cleancopies.CleanCopyGate;
import com.android.internal.diamaneos.cleancopies.VerifiedSystemFiles;

import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * DiamaneOS: the decode gate. In a gated process (a system app's process with the clean copy gate, SystemUI among
 * them, and the system server: {@link CleanCopyGate#isActive}), every image decode at the Java API boundary
 * (BitmapFactory, ImageDecoder, BitmapRegionDecoder, Movie, and through them Drawable.createFrom and
 * AnimatedImageDrawable) takes its bytes to the protected VM: the system server's provider (VmDecodeProvider, authority
 * {@link #AUTHORITY}) has them probed or decoded by the decode service and sends the pixels back. Decoded here as
 * before are only a regular file on a verified read-only system partition, decided from the open file
 * ({@link VerifiedSystemFiles}), never from a path, an asset of an APK of the system image or of the process's own
 * ({@link #gatesStream}), and a clean copy's image. Other streams, byte arrays, buffers and memory are never exempt.
 *
 * <p>The bytes go over as a memory file of at most {@link #MAX_BYTES}, known by their SHA-256, handed to the system
 * server on the gate's own thread, so a caller never blocks in that call. Nothing is asked before the phone has
 * booted, while a known global lock is held, or in the system server before its provider runs: such a decode fails at
 * once. A thread waits only when it may ({@link #allowedWait}): the main thread of an app at most {@link #MAIN_MS},
 * an incoming binder call {@link #BINDER_MS}, a shared looper thread of an app {@link #LOOPER_MS}, another thread of an
 * app {@link #WORKER_MS}, another thread of the system server {@link #SYSTEM_WORKER_MS}. The system server's main
 * thread and shared looper threads never wait, no thread waits for {@link #NO_WAIT_MS} after a wait ran out, and at
 * most {@link #MAX_WAITING} threads of the process wait at once. A request not waited for still finishes in the
 * background, and its answer is kept ({@link #CACHE_BYTES}, an answer of more than half that is not), so the same call
 * for the same bytes is answered at once next time. A decode that fails, is refused or is not had in time gives what
 * the API gives for an image it cannot decode (null, or its decode exception). Nothing falls back to a decode in this
 * process. The gate's own threads (and the provider's) never go through it again.
 *
 * @hide
 */
public final class DecodeGate {
    private static final String TAG = "DecodeGate";

    /** The system server's provider that has images probed and decoded in the VM. */
    public static final String AUTHORITY = "de.diamaneos.vmdecode";
    public static final Uri URI = new Uri.Builder().scheme(ContentResolver.SCHEME_CONTENT).authority(AUTHORITY)
            .build();
    /** Decodes the bytes to fit a box: EXTRA_BITMAP (upright) and EXTRA_ROTATION come back. */
    public static final String METHOD_DECODE = "decode";
    /** Reads the header: the stored size (EXTRA_WIDTH, EXTRA_HEIGHT), EXTRA_FORMAT and EXTRA_ANIMATED. */
    public static final String METHOD_PROBE = "probe";
    /** {@link #METHOD_PROBE} and, for JPEG, HEIF and AVIF, the turn the file asks for (EXTRA_ROTATION). */
    public static final String METHOD_HEADER = "header";
    /**
     * Decodes a region of the stored image at full detail: EXTRA_LEFT, EXTRA_TOP, EXTRA_WIDTH and EXTRA_HEIGHT in its
     * pixels, EXTRA_SAMPLE the sample size. EXTRA_BITMAP comes back, never turned.
     */
    public static final String METHOD_REGION = "region";
    public static final String EXTRA_LEFT = "left";
    public static final String EXTRA_TOP = "top";
    public static final String EXTRA_SAMPLE = "sample";
    /** The largest region sample size (IDecodeService.MAX_REGION_SAMPLE). */
    public static final int MAX_REGION_SAMPLE = 64;
    /** Decodes an animation's frames to fit a box: EXTRA_BITMAP (frames one below the other) and the frame extras. */
    public static final String METHOD_FRAMES = "frames";
    /** The decode service only: the system server's pid (EXTRA_PID), so it can tell the system server apart. */
    public static final String METHOD_BROKER = "broker";
    public static final String EXTRA_PID = "pid";
    public static final String EXTRA_FD = "fd";
    public static final String EXTRA_WIDTH = "width";
    public static final String EXTRA_HEIGHT = "height";
    public static final String EXTRA_CALLBACK = "callback";
    /** Milliseconds the caller has left. */
    public static final String EXTRA_TIME = "time";
    /** The authority of a content URI the bytes came from (with its user, as the URI has it). */
    public static final String EXTRA_AUTHORITY = "authority";
    /** The package of the calling process, for the lane of bytes without a provider. */
    public static final String EXTRA_CALLER = "caller";
    public static final String EXTRA_BITMAP = "bitmap";
    public static final String EXTRA_ROTATION = "rotation";
    /** True when the file's orientation mirrors the image left to right before the turn. */
    public static final String EXTRA_MIRROR = "mirror";
    /** Probe: IDecodeService.FORMAT_* and whether the image is animated. */
    public static final String EXTRA_FORMAT = "format";
    public static final String EXTRA_ANIMATED = "animated";
    /** Frames: their count, times in milliseconds, and how often they play (0: forever). */
    public static final String EXTRA_FRAME_COUNT = "frameCount";
    public static final String EXTRA_DURATIONS = "durations";
    public static final String EXTRA_LOOPS = "loops";
    public static final String EXTRA_ERROR = "error";
    /** True when the VM refused the bytes themselves (broken, unsupported). */
    public static final String EXTRA_REFUSED = "refused";
    /** In the method's own reply: true when the call was taken and the callback will be called. */
    public static final String EXTRA_TAKEN = "taken";

    /** The decode service's limit for bytes it holds in memory (IDecodeService.MAX_BUFFERED_BYTES). */
    public static final int MAX_BYTES = 32 * 1024 * 1024;
    /** The decode service's pixel limit for one image (IDecodeService.MAX_PIXELS). */
    public static final int MAX_VM_PIXELS = 8 * 1024 * 1024;
    /** The largest box a decode asks for (IDecodeService.MAX_DIMENSION). */
    public static final int MAX_BOX = 65535;
    /** The most time the system server takes for one call. */
    public static final long SERVER_MS = 4_000;

    /** The longest a call waits on the main thread of an app. */
    public static final long MAIN_MS = 200;
    /** After a wait ran out on any thread, no thread of the process waits for this long. */
    public static final long NO_WAIT_MS = 5_000;
    /** The longest a call waits in an incoming binder call. */
    public static final long BINDER_MS = 1_000;
    /** The longest a call waits on a shared looper thread of an app (its BackgroundThread, SystemUI's background). */
    public static final long LOOPER_MS = 1_000;
    /** The longest a call waits on another thread of an app. */
    public static final long WORKER_MS = 4_000;
    /** The longest a call waits on another thread of the system server (not its main or a shared looper). */
    public static final long SYSTEM_WORKER_MS = 1_000;
    /** Threads of one process that may wait for the VM at once (well below a binder pool of 15 or 31). */
    public static final int MAX_WAITING = 4;
    /** The cache of answers: pixels at most this many bytes, other answers by count. */
    public static final int CACHE_BYTES = 24 * 1024 * 1024;

    /** The system server's provider, called directly there (no binder, no activity manager). */
    public interface Local {
        /** Takes {@code method} with {@code extras} as the provider's call does. */
        Bundle call(String method, Bundle extras);
    }

    private static volatile Boolean sForced;
    private static volatile Local sLocal;
    private static volatile long sNoWaitUntil;
    private static volatile boolean sBooted;
    private static final AtomicInteger sDecodes = new AtomicInteger();
    private static final AtomicInteger sProbes = new AtomicInteger();
    private static final AtomicInteger sFailures = new AtomicInteger();
    private static final AtomicInteger sNotWaited = new AtomicInteger();
    private static final AtomicInteger sCacheHits = new AtomicInteger();
    private static final Object sLogLock = new Object();
    private static long sLogMinute; // guarded by sLogLock
    private static int sLogLines; // guarded by sLogLock
    // The gate's own threads (and the provider's), where nothing may go through the gate again.
    private static final ThreadLocal<Boolean> sInside = ThreadLocal.withInitial(() -> Boolean.FALSE);
    // Set when a decode on this thread failed for the moment only (see takeNotNow).
    private static final ThreadLocal<Boolean> sNotNow = ThreadLocal.withInitial(() -> Boolean.FALSE);
    private static final java.util.concurrent.Semaphore sWaiting =
            new java.util.concurrent.Semaphore(MAX_WAITING);
    // Global locks of the system server no thread may wait for the VM while holding, and where to find more (by
    // name), looked for at most once a second and given up LOCK_SOURCE_MS after boot.
    private static final java.util.List<Object> sLocks = new java.util.concurrent.CopyOnWriteArrayList<>();
    private static final java.util.Map<String, java.util.function.Supplier<Object>> sLockSources =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final long LOCK_SOURCE_MS = 60_000;
    private static volatile long sLockSourcesAt;
    private static volatile long sBootedAt;
    // Answers by request and bytes (see Call), and requests under way. An answer is charged its pixels, and for its
    // key, its bundle and the rest a fixed amount that covers them.
    private static final int ENTRY_BYTES = 2048;
    private static final android.util.LruCache<String, Bundle> sAnswers =
            new android.util.LruCache<String, Bundle>(CACHE_BYTES) {
                @Override
                protected int sizeOf(String key, Bundle value) {
                    return entryBytes(value);
                }
            };
    // The gate's own threads that hand requests to the system server, so no caller blocks in that call.
    private static java.util.concurrent.ThreadPoolExecutor sSenders; // guarded by DecodeGate.class
    private static final int SENDERS = 2;
    private static final int MAX_SENDS_WAITING = 64;
    private static final java.util.HashMap<String, Call> sCalls = new java.util.HashMap<>(); // guarded by itself
    private static volatile String sForcedPackage;
    private static volatile Long sWaitForTest;
    private static volatile long sForcedReadAt = -10_000;

    private DecodeGate() {
    }

    /** True when this process takes image bytes to the VM. */
    public static boolean gates() {
        final Boolean forced = sForced;
        if (forced != null) {
            return forced;
        }
        return CleanCopyGate.isActive() || forcedPackage();
    }

    /**
     * Debuggable builds only: the package {@code debug.diamaneos.decode_gate.force} names is gated too, so a test
     * suite of its own (CTS) can run through the gate. It can only add a process to the gate, never take one out.
     */
    private static boolean forcedPackage() {
        if (!android.os.Build.IS_DEBUGGABLE) {
            return false;
        }
        final long now = SystemClock.uptimeMillis();
        if (now - sForcedReadAt > 1_000) {
            sForcedPackage = android.os.SystemProperties.get("debug.diamaneos.decode_gate.force", "");
            sForcedReadAt = now;
        }
        final String name = sForcedPackage;
        return name != null && !name.isEmpty() && name.equals(ActivityThread.currentPackageName());
    }

    /** The system server: a global lock (the activity manager's, the window manager's) no wait may happen under. */
    public static void registerGlobalLock(Object lock) {
        if (lock != null && !sLocks.contains(lock)) {
            sLocks.add(lock);
        }
    }

    /**
     * The system server: where a global lock made later is found (Telecom's), asked until it gives one. One still
     * missing a minute after boot is logged as an error and no longer asked.
     */
    public static void addGlobalLockSource(String name, java.util.function.Supplier<Object> source) {
        sLockSources.put(name, source);
    }

    /** Marks this thread as the gate's own (the provider's): nothing on it goes through the gate again. */
    public static void markGateThread() {
        sInside.set(Boolean.TRUE);
    }

    /** True when {@code fd} is a regular file of a verified read-only system partition: decoded here as before. */
    public static boolean exempt(FileDescriptor fd) {
        return VerifiedSystemFiles.holds(fd);
    }

    /**
     * True when this process gates and takes {@code in}'s bytes to the VM: every stream but an asset of an APK of the
     * system image or of this process's own (every APK it can come from, as the clean copy gate decides) and a clean
     * copy's image (made in this process from pixels of the VM).
     */
    public static boolean gatesStream(InputStream in) {
        if (in == null || !gates()) {
            return false; // no stream, no bytes: the API answers it as before (null, or its own exception)
        }
        if (in instanceof CleanCopyGate.CleanImageStream) {
            return false;
        }
        if (in instanceof android.content.res.AssetManager.AssetInputStream) {
            final String[] apks;
            try {
                apks = ((android.content.res.AssetManager.AssetInputStream) in).getSourceApkPaths();
            } catch (RuntimeException e) {
                return true;
            }
            if (apks.length == 0) {
                return true;
            }
            for (String apk : apks) {
                if (!CleanCopyGate.apkDecodesHere(apk)) {
                    return true;
                }
            }
            return false;
        }
        return true;
    }

    /** True when the calling binder is the system server (or this process): for the answers' callbacks. */
    public static boolean fromSystemServer() {
        final int uid = Binder.getCallingUid();
        return android.os.UserHandle.getAppId(uid) == Process.SYSTEM_UID || uid == Process.myUid();
    }

    /** The system server: its provider takes calls directly. */
    public static void setLocal(Local local) {
        sLocal = local;
    }

    /** Tests on debuggable builds: this process gates (true), does not (false), or as its clean copy gate says (null). */
    public static void setForcedForTest(@Nullable Boolean forced) {
        debuggableOnly();
        sForced = forced;
    }

    private static void debuggableOnly() {
        if (!android.os.Build.IS_DEBUGGABLE) {
            throw new SecurityException("debuggable builds only");
        }
    }

    /** Tests: decodes and probes answered by the VM, failures, calls not waited for, answers from the cache. */
    public static int[] countsForTest() {
        return new int[] {sDecodes.get(), sProbes.get(), sFailures.get(), sNotWaited.get(), sCacheHits.get()};
    }

    /**
     * Tests on debuggable builds: every call of this process waits this long (0: not at all), or as the thread may
     * (null).
     */
    public static void setWaitForTest(@Nullable Long waitMs) {
        debuggableOnly();
        sWaitForTest = waitMs;
    }

    /** Tests on debuggable builds: threads wait again at once and nothing is cached. */
    public static void resetForTest() {
        debuggableOnly();
        sNoWaitUntil = 0;
        sAnswers.evictAll();
    }

    /** Why there is no image: refused (the bytes) or not had (the VM down, out of time, too large). */
    static final class Failure extends Exception {
        final boolean refused;

        Failure(boolean refused, String message) {
            super(message);
            this.refused = refused;
        }
    }

    /** A decoded image: upright, the clockwise turn the file asked for, and whether it asked for a mirror first. */
    static final class Decoded {
        final Bitmap bitmap;
        final int rotation;
        final boolean mirrored;

        Decoded(Bitmap bitmap, int rotation, boolean mirrored) {
            this.bitmap = bitmap;
            this.rotation = rotation;
            this.mirrored = mirrored;
        }
    }

    /** The bytes of one image in a memory file of this process, read from its start by the VM. */
    static final class Bytes implements AutoCloseable {
        private final ParcelFileDescriptor mFd;
        private final byte[] mHead;
        private final String mDigest;

        private Bytes(ParcelFileDescriptor fd, byte[] head, String digest) {
            mFd = fd;
            mHead = head;
            mDigest = digest;
        }

        // SHA-256 of what goes into the memory file, for the cache.
        private static java.security.MessageDigest newDigest() throws Failure {
            try {
                return java.security.MessageDigest.getInstance("SHA-256");
            } catch (java.security.NoSuchAlgorithmException e) {
                throw new Failure(false, "no SHA-256");
            }
        }

        // Bytes of a caller's array, a piece at a time copied first, so what is hashed is what is sent.
        static Bytes of(byte[] data, int offset, int length) throws Failure {
            mayAsk();
            if (length > MAX_BYTES) {
                throw new Failure(true, "larger than the decode limit");
            }
            if (length <= 0) {
                throw new Failure(true, "no bytes");
            }
            final java.security.MessageDigest digest = newDigest();
            final FileDescriptor memory = memory();
            try {
                final byte[] piece = new byte[Math.min(length, 64 * 1024)];
                byte[] head = null;
                int done = 0;
                while (done < length) {
                    final int n = Math.min(piece.length, length - done);
                    System.arraycopy(data, offset + done, piece, 0, n);
                    if (head == null) {
                        head = java.util.Arrays.copyOf(piece, Math.min(n, 32));
                    }
                    digest.update(piece, 0, n);
                    int written = 0;
                    while (written < n) {
                        written += Os.write(memory, piece, written, n - written);
                    }
                    done += n;
                }
                return done(memory, head, digest);
            } catch (ErrnoException | java.io.InterruptedIOException e) {
                closeQuietly(memory);
                throw new Failure(false, "bytes not copied: " + e.getMessage());
            }
        }

        static Bytes of(ByteBuffer buffer) throws Failure {
            mayAsk();
            final ByteBuffer view = buffer.duplicate();
            final int length = view.remaining();
            if (length > MAX_BYTES) {
                throw new Failure(true, "larger than the decode limit");
            }
            final byte[] data = new byte[length];
            view.get(data);
            return of(data, 0, length);
        }

        /** Reads {@code in} to its end (at most {@link #MAX_BYTES}). The stream is left open. */
        static Bytes of(InputStream in) throws Failure {
            mayAsk();
            final java.security.MessageDigest digest = newDigest();
            final FileDescriptor memory = memory();
            try {
                final byte[] buffer = new byte[64 * 1024];
                final byte[] head = new byte[32];
                int total = 0;
                int n;
                while ((n = in.read(buffer)) > 0) {
                    if (total < head.length) {
                        System.arraycopy(buffer, 0, head, total, Math.min(n, head.length - total));
                    }
                    total += n;
                    if (total > MAX_BYTES) {
                        throw new Failure(true, "larger than the decode limit");
                    }
                    digest.update(buffer, 0, n);
                    int written = 0;
                    while (written < n) {
                        written += Os.write(memory, buffer, written, n - written);
                    }
                }
                if (total == 0) {
                    throw new Failure(true, "no bytes");
                }
                return done(memory, java.util.Arrays.copyOf(head, Math.min(total, head.length)), digest);
            } catch (IOException | ErrnoException e) {
                closeQuietly(memory);
                throw new Failure(false, "stream not read: " + e.getMessage());
            } catch (Failure e) {
                closeQuietly(memory);
                throw e;
            }
        }

        /**
         * The bytes of {@code fd} from {@code offset} ({@code -1}: its current position) for {@code length} bytes
         * ({@code -1}: to its end), read without moving its position. A descriptor that cannot be read at an offset
         * (a pipe, a socket) is read as a stream from where it is.
         */
        static Bytes of(FileDescriptor fd, long offset, long length) throws Failure {
            mayAsk();
            final StructStat st;
            final long start;
            try {
                st = Os.fstat(fd);
                start = offset >= 0 ? offset : Os.lseek(fd, 0, OsConstants.SEEK_CUR);
            } catch (ErrnoException e) {
                return of(new java.io.FileInputStream(fd) {
                    @Override
                    public void close() {
                        // The caller owns the descriptor.
                    }
                });
            }
            if (!OsConstants.S_ISREG(st.st_mode)) {
                return of(new java.io.FileInputStream(fd) {
                    @Override
                    public void close() {
                        // The caller owns the descriptor.
                    }
                });
            }
            final long end = length >= 0 ? Math.min(st.st_size, start + length) : st.st_size;
            if (end - start > MAX_BYTES) {
                throw new Failure(true, "larger than the decode limit");
            }
            if (end <= start) {
                throw new Failure(true, "no bytes");
            }
            final java.security.MessageDigest digest = newDigest();
            final FileDescriptor memory = memory();
            try {
                final byte[] buffer = new byte[(int) Math.min(64 * 1024, end - start)];
                byte[] head = null;
                long at = start;
                while (at < end) {
                    final int n = Os.pread(fd, buffer, 0, (int) Math.min(buffer.length, end - at), at);
                    if (n <= 0) {
                        break;
                    }
                    if (head == null) {
                        head = java.util.Arrays.copyOf(buffer, Math.min(n, 32));
                    }
                    digest.update(buffer, 0, n);
                    int written = 0;
                    while (written < n) {
                        written += Os.write(memory, buffer, written, n - written);
                    }
                    at += n;
                }
                if (head == null) {
                    throw new Failure(true, "no bytes");
                }
                return done(memory, head, digest);
            } catch (ErrnoException | java.io.InterruptedIOException e) {
                closeQuietly(memory);
                throw new Failure(false, "file not read: " + e.getMessage());
            } catch (Failure e) {
                closeQuietly(memory);
                throw e;
            }
        }

        private static FileDescriptor memory() throws Failure {
            try {
                return Os.memfd_create("decode-gate", OsConstants.MFD_CLOEXEC);
            } catch (ErrnoException e) {
                throw new Failure(false, "no memory file: " + e.getMessage());
            }
        }

        private static Bytes done(FileDescriptor memory, byte[] head, java.security.MessageDigest digest)
                throws Failure {
            try {
                Os.lseek(memory, 0, OsConstants.SEEK_SET);
                final StringBuilder hex = new StringBuilder(64);
                for (byte b : digest.digest()) {
                    hex.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
                }
                return new Bytes(ParcelFileDescriptor.dup(memory), head, hex.toString());
            } catch (ErrnoException | IOException e) {
                throw new Failure(false, "no memory file: " + e.getMessage());
            } finally {
                closeQuietly(memory);
            }
        }

        /** The first bytes, for the MIME type. */
        byte[] head() {
            return mHead;
        }

        /**
         * True when the image is encoded with one channel only (a grey PNG without alpha, a JPEG with one component),
         * as Android's decoders take it for an alpha mask. Read from the bytes' headers in this process, no pixels.
         */
        boolean singleChannel() {
            final byte[] h = mHead;
            if (h.length >= 26 && (h[0] & 0xff) == 0x89 && h[1] == 'P' && h[12] == 'I' && h[15] == 'R') {
                return h[25] == 0; // IHDR colour type: greyscale
            }
            if (h.length < 3 || (h[0] & 0xff) != 0xff || (h[1] & 0xff) != 0xd8) {
                return false;
            }
            // A JPEG: the components of its first frame header (SOFn), within its first 64 KB.
            final byte[] b = new byte[64 * 1024];
            final int n;
            try {
                n = Os.pread(mFd.getFileDescriptor(), b, 0, b.length, 0);
            } catch (ErrnoException | java.io.InterruptedIOException e) {
                return false;
            }
            int at = 2;
            while (at + 4 <= n) {
                if ((b[at] & 0xff) != 0xff) {
                    return false;
                }
                final int m = b[at + 1] & 0xff;
                if (m == 0xff) {
                    at++;
                    continue;
                }
                if (m == 0xd8 || (m >= 0xd0 && m <= 0xd7) || m == 0x01) {
                    at += 2;
                    continue;
                }
                if (m == 0xda || m == 0xd9) {
                    return false;
                }
                final int length = ((b[at + 2] & 0xff) << 8) | (b[at + 3] & 0xff);
                if (length < 2) {
                    return false;
                }
                if (m >= 0xc0 && m <= 0xcf && m != 0xc4 && m != 0xc8 && m != 0xcc) {
                    return at + 9 < n && (b[at + 9] & 0xff) == 1;
                }
                at += 2 + length;
            }
            return false;
        }

        /** The SHA-256 of the bytes, in hex. */
        String digest() {
            return mDigest;
        }

        ParcelFileDescriptor fd() {
            return mFd;
        }

        @Override
        public void close() {
            try {
                mFd.close();
            } catch (IOException ignored) {
                // Closed.
            }
        }
    }

    /**
     * What the VM's probe found: the stored size, the format, animation, and the turn the file asks for and whether it
     * asks for a mirror (left to right, before the turn).
     */
    static final class Info {
        final int width;
        final int height;
        final String mimeType;
        final boolean animated;
        final int rotation;
        final boolean mirrored;

        Info(int width, int height, String mimeType, boolean animated, int rotation, boolean mirrored) {
            this.width = width;
            this.height = height;
            this.mimeType = mimeType;
            this.animated = animated;
            this.rotation = rotation;
            this.mirrored = mirrored;
        }
    }

    /** An animation from the VM: frames one below the other in one bitmap. */
    static final class Frames {
        final Bitmap frames;
        final int count;
        final int width;
        final int height;
        final int[] durations;
        final int loops;

        Frames(Bitmap frames, int count, int width, int height, int[] durations, int loops) {
            this.frames = frames;
            this.count = count;
            this.width = width;
            this.height = height;
            this.durations = durations;
            this.loops = loops;
        }
    }

    /** The probe of {@code bytes} in the VM, with the turn the file asks for when {@code turn} (else 0). */
    static Info probe(Bytes bytes, boolean turn) throws Failure {
        final Bundle r = request(turn ? METHOD_HEADER : METHOD_PROBE, bytes, 0, 0);
        final int width = r.getInt(EXTRA_WIDTH);
        final int height = r.getInt(EXTRA_HEIGHT);
        final int rotation = r.getInt(EXTRA_ROTATION);
        if (width < 1 || height < 1 || width > MAX_BOX || height > MAX_BOX || !quarterTurn(rotation)) {
            throw failed(false, "bad probe reply");
        }
        return new Info(width, height, mimeTypeOf(r.getInt(EXTRA_FORMAT), bytes.head()),
                r.getBoolean(EXTRA_ANIMATED), rotation, r.getBoolean(EXTRA_MIRROR));
    }

    /** {@code bytes} decoded in the VM to fit {@code maxWidth} x {@code maxHeight}, upright. */
    static Decoded decode(Bytes bytes, int maxWidth, int maxHeight) throws Failure {
        final int boxW = Math.max(1, Math.min(MAX_BOX, maxWidth));
        final int boxH = Math.max(1, Math.min(MAX_BOX, maxHeight));
        final Bundle r = request(METHOD_DECODE, bytes, boxW, boxH);
        final Bitmap bitmap = r.getParcelable(EXTRA_BITMAP, Bitmap.class);
        final int rotation = r.getInt(EXTRA_ROTATION);
        if (bitmap == null || !quarterTurn(rotation) || bitmap.getWidth() > boxW || bitmap.getHeight() > boxH) {
            throw failed(false, "bad decode reply");
        }
        return new Decoded(bitmap, rotation, r.getBoolean(EXTRA_MIRROR));
    }

    /**
     * The region {@code left}, {@code top}, {@code width} x {@code height} of the stored image decoded in the VM at full
     * detail and sampled by {@code sample} as Android's region decoder samples it (at most ceil(width / sample) x
     * ceil(height / sample)), never turned.
     */
    static Bitmap region(Bytes bytes, int left, int top, int width, int height, int sample) throws Failure {
        final int outW = (int) (((long) width + sample - 1) / sample);
        final int outH = (int) (((long) height + sample - 1) / sample);
        if (left < 0 || top < 0 || width < 1 || height < 1 || sample < 1 || sample > MAX_REGION_SAMPLE
                || left >= MAX_BOX || top >= MAX_BOX || width > MAX_BOX - left || height > MAX_BOX - top
                || (long) outW * outH > MAX_VM_PIXELS) {
            throw failed(true, "region out of bounds");
        }
        final Bundle r = request(METHOD_REGION, bytes, width, height, left, top, sample);
        final Bitmap bitmap = r.getParcelable(EXTRA_BITMAP, Bitmap.class);
        if (bitmap == null || bitmap.getWidth() > outW || bitmap.getHeight() > outH) {
            throw failed(false, "bad region reply");
        }
        return bitmap;
    }

    /** The frames of an animation (GIF, WebP) decoded in the VM, each fitting the box. */
    static Frames frames(Bytes bytes, int maxWidth, int maxHeight) throws Failure {
        final int boxW = Math.max(1, Math.min(MAX_BOX, maxWidth));
        final int boxH = Math.max(1, Math.min(MAX_BOX, maxHeight));
        final Bundle r = request(METHOD_FRAMES, bytes, boxW, boxH);
        final Bitmap frames = r.getParcelable(EXTRA_BITMAP, Bitmap.class);
        final int count = r.getInt(EXTRA_FRAME_COUNT);
        final int[] durations = r.getIntArray(EXTRA_DURATIONS);
        if (frames == null || count < 1 || frames.getWidth() > boxW || frames.getHeight() % count != 0
                || frames.getHeight() / count > boxH || durations == null || durations.length != count) {
            throw failed(false, "bad frames reply");
        }
        return new Frames(frames, count, frames.getWidth(), frames.getHeight() / count, durations,
                Math.max(0, r.getInt(EXTRA_LOOPS)));
    }

    private static boolean quarterTurn(int rotation) {
        return rotation == 0 || rotation == 90 || rotation == 180 || rotation == 270;
    }

    /** One request under way, shared by every caller of the same request for the same bytes. */
    private static final class Call {
        final String key;
        final long end;
        private Bundle mAnswer; // guarded by this

        Call(String key, long end) {
            this.key = key;
            this.end = end;
        }

        synchronized void answer(Bundle answer) {
            if (mAnswer == null) {
                mAnswer = answer != null ? answer : new Bundle();
                notifyAll();
            }
        }

        synchronized Bundle await(long until) {
            while (mAnswer == null) {
                final long left = until - SystemClock.uptimeMillis();
                if (left <= 0) {
                    return null;
                }
                try {
                    wait(left);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
            return mAnswer;
        }
    }

    /**
     * Fails at once when nothing may be asked here: before the phone has booted (the decode service may not run yet,
     * and boot is never slowed), while a known global lock is held, and in the system server before its provider runs.
     * Checked before any bytes are copied.
     */
    static void mayAsk() throws Failure {
        if (sInside.get()) {
            throw failed(false, "the gate's own thread");
        }
        if (sWaitForTest != null) {
            return;
        }
        if (!booted()) {
            throw notNow("not asked before boot");
        }
        if (holdsGlobalLock()) {
            throw notNow("not asked under a global lock");
        }
        if (sLocal == null && ActivityThread.isSystem()) {
            throw notNow("not asked before the provider runs");
        }
    }

    /**
     * How long this thread may wait for the VM now. In an app: the main thread MAIN_MS, an incoming binder call
     * BINDER_MS, a shared looper thread LOOPER_MS, any other thread WORKER_MS. In the system server: none on the main
     * thread and the shared looper threads, BINDER_MS in an incoming binder call, SYSTEM_WORKER_MS on any other thread
     * (a service's own thread, a worker). None for NO_WAIT_MS after a wait ran out, and none when MAX_WAITING threads
     * of the process wait already (taken in {@link #request}), so at most MAX_WAITING binder threads of a pool of 15
     * (an app) or 31 (the system server) ever wait.
     */
    private static long allowedWait() {
        final Long forced = sWaitForTest;
        if (forced != null) {
            return forced;
        }
        if (SystemClock.uptimeMillis() < sNoWaitUntil) {
            return 0;
        }
        final boolean system = ActivityThread.isSystem();
        final Looper looper = Looper.myLooper();
        if (looper == Looper.getMainLooper()) {
            return system ? 0 : MAIN_MS;
        }
        if (Binder.getCallingPid() != Process.myPid()) {
            return BINDER_MS;
        }
        if (looper != null && SHARED_LOOPERS.contains(Thread.currentThread().getName())) {
            return system ? 0 : LOOPER_MS;
        }
        return system ? SYSTEM_WORKER_MS : WORKER_MS;
    }

    // Looper threads that many callers share (the system server's UiThread, DisplayThread, AnimationThread, FgThread,
    // IoThread, BackgroundThread, an app's BackgroundThread, SystemUI's background loopers): a wait there holds up
    // everything queued behind it. A looper thread of its own (the wallpaper service's, SystemUI's long running one,
    // which loads the wallpaper) waits as any other thread.
    private static final java.util.Set<String> SHARED_LOOPERS = java.util.Set.of("android.ui", "android.display",
            "android.anim", "android.anim.lf", "android.fg", "android.io", "android.bg", "SysUiBg", "NotifInflation",
            "BroadcastRunning", "BackPanelUiThread", "TimeTick");

    private static boolean booted() {
        if (!sBooted) {
            sBooted = "1".equals(android.os.SystemProperties.get("sys.boot_completed"));
            if (sBooted) {
                sBootedAt = SystemClock.uptimeMillis();
            }
        }
        return sBooted;
    }

    private static boolean holdsGlobalLock() {
        if (!sLockSources.isEmpty()) {
            findLocks();
        }
        for (Object lock : sLocks) {
            if (Thread.holdsLock(lock)) {
                return true;
            }
        }
        return false;
    }

    // Asks the lock sources, at most once a second. One still missing LOCK_SOURCE_MS after boot is given up, loudly.
    private static void findLocks() {
        final long now = SystemClock.uptimeMillis();
        if (now - sLockSourcesAt < 1_000) {
            return;
        }
        sLockSourcesAt = now;
        for (java.util.Map.Entry<String, java.util.function.Supplier<Object>> source : sLockSources.entrySet()) {
            Object lock = null;
            try {
                lock = source.getValue().get();
            } catch (RuntimeException e) {
                // Not there yet.
            }
            if (lock != null) {
                registerGlobalLock(lock);
                sLockSources.remove(source.getKey());
            } else if (sBooted && now - sBootedAt > LOCK_SOURCE_MS) {
                sLockSources.remove(source.getKey());
                Log.e(TAG, source.getKey() + " was not found: its holders may wait for the VM");
            }
        }
    }

    /**
     * The answer of the system server's provider to {@code method} for {@code bytes}: from the cache when this request
     * was answered before, else asked (unless it is under way already) and waited for as long as this thread may
     * ({@link #allowedWait}). A request not waited for, or not answered in time, still finishes in the background and
     * its answer is kept, so the same call is answered at once next time.
     */
    private static Bundle request(String method, Bytes bytes, int width, int height) throws Failure {
        return request(method, bytes, width, height, 0, 0, 0);
    }

    // As above, with a region's left, top and sample size (0 for other methods), all of them in the cache key.
    private static Bundle request(String method, Bytes bytes, int width, int height, int left, int top, int sample)
            throws Failure {
        mayAsk();
        final String key = method + ':' + width + 'x' + height + '@' + left + ',' + top + '/' + sample + ':'
                + bytes.digest();
        final Bundle cached = sAnswers.get(key);
        if (cached != null) {
            sCacheHits.incrementAndGet();
            return answerOf(cached);
        }
        final Call call;
        boolean send = false;
        synchronized (sCalls) {
            final Call running = sCalls.get(key);
            if (running != null && SystemClock.uptimeMillis() < running.end) {
                call = running;
            } else {
                call = new Call(key, SystemClock.uptimeMillis() + SERVER_MS + 500);
                sCalls.put(key, call);
                send = true;
            }
        }
        if (send) {
            send(method, bytes, width, height, left, top, sample, call);
        }
        long wait = allowedWait();
        boolean permit = false;
        if (wait > 0) {
            permit = sWaiting.tryAcquire();
            if (!permit) {
                wait = 0;
            }
        }
        final Bundle answer;
        sInside.set(Boolean.TRUE);
        try {
            answer = wait > 0 ? call.await(SystemClock.uptimeMillis() + wait) : call.await(0);
        } finally {
            sInside.set(Boolean.FALSE);
            if (permit) {
                sWaiting.release();
            }
        }
        if (answer != null && answer.getString(EXTRA_ERROR) == null) {
            (METHOD_PROBE.equals(method) || METHOD_HEADER.equals(method) ? sProbes : sDecodes).incrementAndGet();
        }
        if (answer == null) {
            if (wait == 0) {
                sNotWaited.incrementAndGet();
                throw notNow("not waited for here, finishing in the background");
            }
            if (sWaitForTest == null) {
                sNoWaitUntil = SystemClock.uptimeMillis() + NO_WAIT_MS;
            }
            throw notNow("missed its time, finishing in the background");
        }
        return answerOf(answer);
    }

    // An answer for one caller: an error as a Failure, pixels as a copy of their own (the cache keeps the original).
    private static Bundle answerOf(Bundle answer) throws Failure {
        final String error = answer.getString(EXTRA_ERROR);
        if (error != null) {
            throw answer.getBoolean(EXTRA_REFUSED) ? failed(true, error) : notNow(error);
        }
        final Bitmap bitmap = answer.getParcelable(EXTRA_BITMAP, Bitmap.class);
        if (bitmap == null) {
            return answer;
        }
        final Bitmap copy = bitmap.copy(bitmap.getConfig(), false);
        if (copy == null) {
            throw failed(false, "no memory for the pixels");
        }
        final Bundle mine = new Bundle(answer);
        mine.putParcelable(EXTRA_BITMAP, copy);
        return mine;
    }

    // Hands the request to the system server on a sender thread, whose callback answers {@code call} and fills the
    // cache. The caller's bytes are its own until it returns, so the descriptor is copied here.
    private static void send(String method, Bytes bytes, int width, int height, int left, int top, int sample,
            Call call) {
        final ParcelFileDescriptor fd;
        try {
            fd = bytes.fd().dup();
        } catch (IOException e) {
            finish(call, error("descriptor not passed"));
            return;
        }
        final String caller = ActivityThread.currentPackageName();
        try {
            senders().execute(() -> sendNow(method, fd, width, height, left, top, sample, caller, call));
        } catch (java.util.concurrent.RejectedExecutionException e) {
            closeQuietly(fd);
            finish(call, error("too many requests waiting to be sent"));
        }
    }

    private static synchronized java.util.concurrent.ThreadPoolExecutor senders() {
        if (sSenders == null) {
            sSenders = new java.util.concurrent.ThreadPoolExecutor(SENDERS, SENDERS, 30,
                    java.util.concurrent.TimeUnit.SECONDS,
                    new java.util.concurrent.LinkedBlockingQueue<>(MAX_SENDS_WAITING), r -> {
                        final Thread t = new Thread(() -> {
                            markGateThread();
                            r.run();
                        }, "DecodeGateSend");
                        t.setDaemon(true);
                        return t;
                    });
            sSenders.allowCoreThreadTimeOut(true);
        }
        return sSenders;
    }

    private static void sendNow(String method, ParcelFileDescriptor fd, int width, int height, int left, int top,
            int sample, String caller, Call call) {
        final Bundle extras = new Bundle();
        extras.putParcelable(EXTRA_FD, fd);
        extras.putInt(EXTRA_WIDTH, width);
        extras.putInt(EXTRA_HEIGHT, height);
        if (METHOD_REGION.equals(method)) {
            extras.putInt(EXTRA_LEFT, left);
            extras.putInt(EXTRA_TOP, top);
            extras.putInt(EXTRA_SAMPLE, sample);
        }
        extras.putLong(EXTRA_TIME, Math.max(1, Math.min(SERVER_MS, call.end - 500 - SystemClock.uptimeMillis())));
        if (caller != null) {
            extras.putString(EXTRA_CALLER, caller);
        }
        extras.putBinder(EXTRA_CALLBACK, new IRemoteCallback.Stub() {
            @Override
            public void sendResult(Bundle result) {
                if (fromSystemServer()) {
                    finish(call, result);
                }
            }
        }.asBinder());
        Bundle taken = null;
        try {
            final Local local = sLocal;
            if (local != null) {
                taken = local.call(method, extras);
            } else if (!ActivityThread.isSystem()) {
                final Context context = context();
                if (context != null) {
                    taken = context.getContentResolver().call(URI, method, null, extras);
                }
            }
        } catch (RuntimeException e) {
            taken = error("the system server did not take it: " + e);
        } finally {
            closeQuietly(fd);
        }
        if (taken == null || !taken.getBoolean(EXTRA_TAKEN)) {
            finish(call, taken != null && taken.getString(EXTRA_ERROR) != null ? taken
                    : error("the system server did not take it"));
        }
    }

    private static Bundle error(String why) {
        final Bundle b = new Bundle();
        b.putString(EXTRA_ERROR, why);
        return b;
    }

    // What an answer is charged in the cache.
    private static int entryBytes(Bundle answer) {
        final Bitmap b = answer.getParcelable(EXTRA_BITMAP, Bitmap.class);
        return ENTRY_BYTES + (b == null ? 0 : b.getAllocationByteCount());
    }

    // An answer arrived: kept (pixels, sizes, and the VM's refusals, never a passing failure, never one above half the
    // cache, which would push out everything else and then itself), then given out.
    private static void finish(Call call, Bundle answer) {
        final Bundle a = answer != null ? answer : error("no answer");
        if ((a.getString(EXTRA_ERROR) == null || a.getBoolean(EXTRA_REFUSED)) && entryBytes(a) <= CACHE_BYTES / 2) {
            sAnswers.put(call.key, a);
        }
        synchronized (sCalls) {
            if (sCalls.get(call.key) == call) {
                sCalls.remove(call.key);
            }
        }
        call.answer(a);
    }

    // A failure of the moment (the VM not asked yet, not answered in time, unavailable): the same image can be had later.
    private static Failure notNow(String why) {
        sNotNow.set(Boolean.TRUE);
        return failed(false, why);
    }

    /**
     * True when a decode on this thread failed since the last call because the VM could not answer then (before boot,
     * out of time, unavailable), not because it refused the bytes: the same image can be had later, so a caller that
     * showed something else meanwhile (the default wallpaper) can load it again. Clears the mark.
     */
    public static boolean takeNotNow() {
        final boolean notNow = sNotNow.get();
        sNotNow.set(Boolean.FALSE);
        return notNow;
    }

    static Failure failed(boolean refused, String why) {
        sFailures.incrementAndGet();
        final long minute = SystemClock.uptimeMillis() / 60_000;
        synchronized (sLogLock) {
            if (minute != sLogMinute) {
                sLogMinute = minute;
                sLogLines = 0;
            }
            if (++sLogLines <= 20) {
                Log.w(TAG, "image not decoded in the protected VM, none shown: " + why);
            }
        }
        return new Failure(refused, why);
    }

    private static Context context() {
        final Context app = ActivityThread.currentApplication();
        if (app != null) {
            return app;
        }
        final ActivityThread thread = ActivityThread.currentActivityThread();
        return thread != null ? thread.getSystemContext() : null;
    }

    // The MIME type of the VM's format (IDecodeService.FORMAT_*), else what the first bytes name.
    private static String mimeTypeOf(int format, byte[] head) {
        switch (format) {
            case 1: return "image/jpeg";
            case 2: return "image/png";
            case 3: return "image/gif";
            case 4: return "image/webp";
            case 5: return "image/bmp";
            case 6: return "image/heif";
            case 7: return "image/avif";
            case 8: return "image/x-adobe-dng";
            case 9: return "image/x-ico";
            case 10: return "image/vnd.wap.wbmp";
            default: return mimeType(head);
        }
    }

    /** The MIME type a file's first bytes name, or null when they name none Android decodes. */
    static String mimeType(byte[] h) {
        if (h == null || h.length < 4) {
            return null;
        }
        if (at(h, 0, 0x89, 'P', 'N', 'G')) {
            return "image/png";
        }
        if (at(h, 0, 0xff, 0xd8, 0xff)) {
            return "image/jpeg";
        }
        if (at(h, 0, 'G', 'I', 'F', '8')) {
            return "image/gif";
        }
        if (h.length >= 12 && at(h, 0, 'R', 'I', 'F', 'F') && at(h, 8, 'W', 'E', 'B', 'P')) {
            return "image/webp";
        }
        if (at(h, 0, 'B', 'M')) {
            return "image/bmp";
        }
        if (at(h, 0, 0, 0, 1, 0) || at(h, 0, 0, 0, 2, 0)) {
            return "image/x-ico";
        }
        if (at(h, 0, 'I', 'I', '*', 0) || at(h, 0, 'M', 'M', 0, '*')) {
            return "image/x-adobe-dng";
        }
        if (h.length >= 12 && at(h, 4, 'f', 't', 'y', 'p')) {
            if (at(h, 8, 'a', 'v', 'i', 'f') || at(h, 8, 'a', 'v', 'i', 's')) {
                return "image/avif";
            }
            return "image/heif";
        }
        if (h[0] == 0 && h[1] == 0) {
            return "image/vnd.wap.wbmp";
        }
        return null;
    }

    private static boolean at(byte[] h, int offset, int... bytes) {
        if (h.length < offset + bytes.length) {
            return false;
        }
        for (int i = 0; i < bytes.length; i++) {
            if ((h[offset + i] & 0xff) != bytes[i]) {
                return false;
            }
        }
        return true;
    }

    private static void closeQuietly(FileDescriptor fd) {
        try {
            Os.close(fd);
        } catch (ErrnoException ignored) {
            // Closed.
        }
    }

    private static void closeQuietly(ParcelFileDescriptor fd) {
        try {
            fd.close();
        } catch (IOException ignored) {
            // Closed.
        }
    }
}
