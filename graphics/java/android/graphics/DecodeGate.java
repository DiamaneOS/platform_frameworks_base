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
import com.android.internal.diamaneos.cleancopies.CleanFileStream;
import com.android.internal.diamaneos.cleancopies.GateLocks;
import com.android.internal.diamaneos.cleancopies.GateOwner;
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
 * ({@link #gatesStream}), a clean copy's image and a file the system server made from pixels of the VM
 * ({@link CleanFileStream}). Other streams, byte arrays, buffers and memory are never exempt.
 *
 * <p>The bytes go over as a memory file of at most {@link #MAX_BYTES}, known by their SHA-256, handed to the system
 * server on the gate's own thread, so a caller never blocks in that call. Nothing is asked before the phone has
 * booted, while a known global lock is held, or in the system server before its provider runs: such a decode fails at
 * once. A public call has a time of its own, counted from its entry ({@link Op}), and every wait of it, for its input
 * and for each answer it needs, ends within that time: the main thread of an app {@link #MAIN_MS}, an incoming binder
 * call {@link #BINDER_MS}, a shared looper thread of an app {@link #LOOPER_MS}, another thread of an app
 * {@link #WORKER_MS}, another thread of the system server {@link #SYSTEM_WORKER_MS} ({@link #allowedWait}). The system
 * server's main thread and shared looper threads never wait, critical threads take no wait for {@link #NO_WAIT_MS} after a critical wait ran
 * out, and at most {@link #MAX_WAITING} threads of the process wait at once. On the main thread, in a binder call and
 * on a shared looper the input (a provider's open, a file's read, the hashing) runs on the gate's input threads within
 * that time ({@link GateInput}). The memory a decode needs is reserved before it is allocated ({@link DecodeMemory}):
 * one that does not fit now is a failure of the moment. A request not waited for still finishes in the background,
 * and its answer is kept ({@link #CACHE_BYTES}, an answer of more than half that is not), so the same call for the
 * same bytes is answered at once next time. A consumer that shows a placeholder meanwhile loads through a
 * {@link GateRefill} and is told when the answer is in. A decode that fails, is refused or is not had in time gives
 * what the API gives for an image it cannot decode (null, or its decode exception). Nothing falls back to a decode in
 * this process. The gate's own threads (and the provider's) never go through it again.
 *
 * <p>Whose image a request is ({@link GateOwner}) is taken on the calling thread when it is made: the user a scope
 * names ({@link #ownerScope}), in the system server also the caller of an incoming binder call. It travels with the
 * request to the provider, and answers kept and requests under way are never shared between owners.
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
    public static final String METHOD_GAINMAP = "gainmap-pixels";
    /** A region of an Ultra HDR JPEG's gain image in its stored pixels, clipped to it in the VM. */
    public static final String METHOD_GAIN_REGION = "gainmap-region";
    /** Reads the header: the stored size (EXTRA_WIDTH, EXTRA_HEIGHT), EXTRA_FORMAT and EXTRA_ANIMATED. */
    public static final String METHOD_PROBE = "probe";
    /** {@link #METHOD_PROBE} and, for JPEG, HEIF and AVIF, the turn the file asks for (EXTRA_ROTATION). */
    public static final String METHOD_HEADER = "header";
    /**
     * Decodes a region of the stored image at full detail: EXTRA_LEFT, EXTRA_TOP, EXTRA_WIDTH and EXTRA_HEIGHT in its
     * pixels, EXTRA_SAMPLE the sample size. EXTRA_BITMAP comes back, never turned.
     */
    public static final String METHOD_REGION = "region";
    public static final String METHOD_REGION_WIDE = "region-f16";
    public static final String METHOD_REGION_STRAIGHT = "region-straight";
    public static final String METHOD_REGION_STRAIGHT_WIDE = "region-straight-f16";
    public static final String METHOD_STRAIGHT_WEBP = "straight-webp";
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
    /**
     * The Android user whose image it is, when the caller knows it (a scope, a URI of another user): the provider
     * takes it only from a caller that may act for that user.
     */
    public static final String EXTRA_OWNER_USER = "ownerUser";
    /**
     * DiamaneOS: where the system server acquires an image of {@link #EXTRA_OWNER_USER} that is not the caller's own
     * user (GateOwner#source). The caller's descriptor is then not used.
     */
    public static final String EXTRA_SOURCE = "source";
    /** The system server only (never over binder): the uid of the app it decodes for, from an incoming call. */
    public static final String EXTRA_FOR_UID = "forUid";
    /** The package of the calling process, for the lane of bytes without a provider. */
    public static final String EXTRA_CALLER = "caller";
    public static final String EXTRA_BITMAP = "bitmap";
    public static final String EXTRA_ROTATION = "rotation";
    /** True when the file's orientation mirrors the image left to right before the turn. */
    public static final String EXTRA_MIRROR = "mirror";
    /** Probe: IDecodeService.FORMAT_* and whether the image is animated. */
    public static final String EXTRA_FORMAT = "format";
    public static final String EXTRA_COLOR_SPACE = "colorSpace";
    public static final String EXTRA_COLOR_DESCRIPTION = "colorDescription";
    public static final String EXTRA_BIT_DEPTH = "bitDepth";
    public static final String EXTRA_HAS_ALPHA = "hasAlpha";
    public static final String EXTRA_ANIMATED = "animated";
    /** Frames: their count, times in milliseconds, and how often they play (0: forever). */
    public static final String EXTRA_FRAME_COUNT = "frameCount";
    public static final String EXTRA_DURATIONS = "durations";
    public static final String EXTRA_LOOPS = "loops";
    public static final String EXTRA_ERROR = "error";
    public static final String EXTRA_SOURCE_ERROR = "sourceError";
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
    /** Cooldown for critical threads after a critical wait runs out. */
    public static final long NO_WAIT_MS = 5_000;
    /** The longest a call waits in an incoming binder call. */
    public static final long BINDER_MS = 1_000;
    /** The longest a call waits on a shared looper thread of an app (its BackgroundThread, SystemUI's background). */
    public static final long LOOPER_MS = 1_000;
    /** The longest a call waits on another thread of an app. */
    public static final long WORKER_MS = 15_000;
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
    private static final java.util.concurrent.Semaphore sWaiting =
            new java.util.concurrent.Semaphore(MAX_WAITING);
    // Global locks of the system server no thread may wait for the VM while holding. A required one not found yet is
    // looked for at most once a second for as long as it is missing, and meanwhile no system server thread waits
    // (GateLocks). One still missing LOCK_SOURCE_MS after boot is logged once.
    private static final GateLocks sLocks = new GateLocks();
    private static final long LOCK_SOURCE_MS = 60_000;
    private static volatile long sBootedAt;
    private static volatile boolean sLocksLogged;
    // The user a scope names on this thread (ownerScope), GateOwner.UNKNOWN for none.
    private static final ThreadLocal<Integer> sScopeUser = ThreadLocal.withInitial(() -> GateOwner.UNKNOWN);
    // The source a scope names on this thread (userIconScope), null for none.
    private static final ThreadLocal<String> sScopeSource = new ThreadLocal<>();
    // Answers by request and bytes (see Call), and requests under way. An answer is charged its pixels, and for its
    // key, its bundle and the rest a fixed amount that covers them.
    private static final int ENTRY_BYTES = 2048;
    private static final android.util.LruCache<String, Answer> sAnswers =
            new android.util.LruCache<String, Answer>(CACHE_BYTES) {
                @Override
                protected int sizeOf(String key, Answer value) {
                    return entryBytes(value.bundle);
                }

                @Override
                protected void entryRemoved(boolean evicted, String key, Answer old, Answer replacement) {
                    old.release();
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

    static volatile Runnable inputFault;

    public static void setInputFaultForTest(Runnable fault) {
        debuggableOnly();
        inputFault = fault;
    }

    public static int inputReadsForTest() {
        debuggableOnly();
        return GateInput.readsForTest();
    }

    public static int waitingForTest() {
        debuggableOnly();
        return MAX_WAITING - sWaiting.availablePermits();
    }

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
        sLocks.register(lock);
    }

    /**
     * The system server: a required global lock, found by {@code source} (null while it does not exist yet, as
     * Telecom's before Telecom runs). Asked until it gives one, never given up: until then no system server thread
     * waits for the VM, its decodes finish in the background.
     */
    public static void addGlobalLockSource(String name, java.util.function.Supplier<Object> source) {
        sLocks.require(name, source);
    }

    /** A scope of {@link #ownerScope}: closing it ends it. */
    public static final class OwnerScope implements AutoCloseable {
        private final int mPrevious;
        private final String mPreviousSource;

        private OwnerScope(int previous, String previousSource) {
            mPrevious = previous;
            mPreviousSource = previousSource;
        }

        @Override
        public void close() {
            sScopeUser.set(mPrevious);
            sScopeSource.set(mPreviousSource);
        }
    }

    /**
     * The images this thread decodes until the scope is closed are of Android user {@code userId}: for a component
     * that knows whose image it loads (the user switching dialog, another user's icon). Outside the system server the
     * provider accepts an unverified descriptor only for the submitting process's own user. Nested scopes restore
     * the outer one.
     */
    public static OwnerScope ownerScope(int userId) {
        final OwnerScope scope = new OwnerScope(sScopeUser.get(), sScopeSource.get());
        sScopeUser.set(userId >= 0 ? userId : GateOwner.UNKNOWN);
        sScopeSource.set(null);
        return scope;
    }

    /**
     * DiamaneOS: {@link #ownerScope} for the photo of user {@code userId} (UserManager.getUserIcon). For another
     * user's photo outside the system server, the system server opens the photo itself with the caller's access.
     */
    public static OwnerScope userIconScope(int userId) {
        final OwnerScope scope = ownerScope(userId);
        sScopeSource.set(userIconSource(userId));
        return scope;
    }

    // DiamaneOS: the photo of user {@code user} as the system server acquires it, when this process is not the
    // system server and the user is not its own, else null.
    private static String userIconSource(int user) {
        if (user < 0 || sLocal != null || ActivityThread.isSystem() || user == android.os.UserHandle.myUserId()) {
            return null;
        }
        return "usericon:" + user;
    }

    // Whose image a request made on this thread now is: the scope's user, and in the system server the incoming
    // binder call's caller (the delegate, and the user when no scope names one).
    private static GateOwner owner() {
        final int calling = sLocal != null && Binder.getCallingPid() != Process.myPid()
                ? Binder.getCallingUid() : GateOwner.UNKNOWN;
        return GateOwner.of(sScopeUser.get(), calling, android.os.UserHandle.PER_USER_RANGE)
                .withSource(sScopeSource.get());
    }

    /** Marks this thread as the gate's own (the provider's): nothing on it goes through the gate again. */
    public static void markGateThread() {
        sInside.set(Boolean.TRUE);
    }

    /** True when {@code fd} is a regular file of a verified read-only system partition: decoded here as before. */
    public static boolean exempt(FileDescriptor fd) {
        // Descriptor metadata may enter a provider. Short-wait callers leave that decision to the input worker.
        return (!critical() || sInside.get()) && VerifiedSystemFiles.holds(fd);
    }

    /**
     * True when this process gates and takes {@code in}'s bytes to the VM: every stream but an asset of an APK of the
     * system image or of this process's own (every APK it can come from, as the clean copy gate decides), a clean
     * copy's image (made in this process from pixels of the VM) and a file the system server made from pixels of the
     * VM, read and checked against its recorded hash ({@link CleanFileStream}).
     */
    public static boolean gatesStream(InputStream in) {
        if (in == null || !gates()) {
            return false; // no stream, no bytes: the API answers it as before (null, or its own exception)
        }
        if (in instanceof CleanCopyGate.CleanImageStream || in instanceof CleanFileStream) {
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
        synchronized (sAnswers) { sAnswers.evictAll(); }
    }

    /**
     * Why there is no image: refused (the bytes, or too large ever), not had for now (the VM down, out of time, no
     * room now: {@link #again}), or not had in this way (a stream this thread may not read, neither).
     */
    static final class Failure extends Exception {
        final boolean refused;
        final boolean again;
        long memory;
        final int sourceError;

        Failure(boolean refused, boolean again, String message) {
            this(refused, again, message, 0);
        }

        Failure(boolean refused, boolean again, String message, int sourceError) {
            super(message);
            this.sourceError = sourceError;
            this.refused = refused;
            this.again = again;
        }
    }

    /**
     * One public decode call (a BitmapFactory or BitmapRegionDecoder call, an ImageDecoder from its source to its
     * close): its entry deadline, captured user and refill registration. Allocation helpers keep the memory charges
     * with the pixels they create. Every
     * wait of the call, for its input and for each of its answers, ends at {@link #end}: on a thread that must not wait
     * long ({@link #allowedWait}) the whole call is that short, whatever it reads and asks.
     */
    static final class Op implements AutoCloseable {
        final long end;
        /** The main thread, an incoming binder call or a shared looper: input is read off this thread. */
        final boolean critical;
        final GateRefill refill;
        final GateOwner owner;
        private final String mInputUser;
        private volatile boolean mPermit;
        int sourceError;

        Op(long wait, boolean critical, GateRefill refill) {
            this(wait, critical, refill, DecodeGate.owner());
        }

        Op(long wait, boolean critical, GateRefill refill, GateOwner owner) {
            this.owner = owner;
            mInputUser = owner.key();
            this.end = SystemClock.uptimeMillis() + Math.max(0, wait);
            this.critical = critical;
            this.refill = refill;
            mPermit = wait > 0 && sWaiting.tryAcquire();
        }

        /** Milliseconds left to wait, 0 when none. */
        long left() {
            return mPermit ? Math.max(0, end - SystemClock.uptimeMillis()) : 0;
        }

        String inputKey(String source) {
            return refill != null ? mInputUser + ':' + source : null;
        }

        String inputKey(Object source, String part) {
            return refill != null ? refill.inputKey(source, mInputUser + ':' + part) : null;
        }

        Failure received(Failure failure) {
            if (refill != null) {
                refill.failed(failure.again);
                if (failure.memory > 0) refill.needs(failure.memory);
            }
            return failure;
        }

        /** Checks the complete plan before allocation, each allocation carries its own charge. */
        void checkFit(long bytes) throws Failure {
            try {
                DecodeMemory.checkFit(bytes);
            } catch (DecodeMemory.NoRoomException e) {
                throw received(noRoom(e));
            }
        }

        @Override
        public synchronized void close() {
            if (mPermit) {
                mPermit = false;
                sWaiting.release();
            }
        }
    }

    /** A public decode call starts on this thread now: its time is what this thread may wait. */
    static Op begin() {
        return new Op(allowedWait(), critical(), GateRefill.current());
    }

    /** Begins with the user of a source acquired through a resolver. */
    static Op beginForUser(int user) {
        final GateOwner current = owner();
        return new Op(allowedWait(), critical(), GateRefill.current(),
                GateOwner.of(user, current.forUid, android.os.UserHandle.PER_USER_RANGE));
    }

    /** A retained source keeps its user, and a new Binder consumer supplies its own delegate. */
    static Op beginForSource(GateOwner source) {
        return new Op(allowedWait(), critical(), GateRefill.current(),
                source.forSource(owner().forUid, android.os.UserHandle.PER_USER_RANGE));
    }

    // The main thread, an incoming binder call or a shared looper thread: the threads with short waits.
    private static boolean critical() {
        final Looper looper = Looper.myLooper();
        return ActivityThread.isSystem() || looper == Looper.getMainLooper() || Binder.getCallingPid() != Process.myPid()
                || (looper != null && SHARED_LOOPERS.contains(Thread.currentThread().getName()));
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

    /**
     * The bytes of one image in a memory file of this process, read from its start by the VM ({@link GateInput} reads
     * them). The decode that read them holds them and so does each send to the system server under way: their memory
     * stays reserved ({@link DecodeMemory}) until the last of them closes.
     */
    static final class Bytes implements AutoCloseable {
        private final ParcelFileDescriptor mFd;
        private final byte[] mHead;
        private final String mDigest;
        private final DecodeMemory.Reservation mMemory;
        private final AtomicInteger mHolders; // shared by every holder of these bytes
        private final java.util.concurrent.atomic.AtomicBoolean mClosed =
                new java.util.concurrent.atomic.AtomicBoolean();

        Bytes(ParcelFileDescriptor fd, byte[] head, String digest, DecodeMemory.Reservation memory) {
            mFd = fd;
            mHead = head;
            mDigest = digest;
            mMemory = memory;
            mHolders = new AtomicInteger(1);
        }

        private Bytes(Bytes shared) {
            mFd = shared.mFd;
            mHead = shared.mHead;
            mDigest = shared.mDigest;
            mMemory = shared.mMemory;
            mHolders = shared.mHolders;
            mHolders.incrementAndGet();
        }

        /** The memory reserved for the bytes. */
        long reserved() {
            return mMemory.bytes();
        }

        /** The same bytes for another holder, which closes its own. */
        Bytes share() {
            return new Bytes(this);
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

        // A send under way holds the bytes until it handed them over.
        void retain() {
            mHolders.incrementAndGet();
        }

        void release() {
            if (mHolders.decrementAndGet() == 0) {
                try {
                    mFd.close();
                } catch (IOException ignored) {
                    // Closed.
                } finally {
                    mMemory.close();
                }
            }
        }

        /** The decode that read them is done with them (once, however often it is called). */
        @Override
        public void close() {
            if (mClosed.compareAndSet(false, true)) {
                release();
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
        final ColorSpace colorSpace;
        final int bitDepth;
        final boolean hasAlpha;

        Info(int width, int height, String mimeType, boolean animated, int rotation, boolean mirrored) {
            this(width, height, mimeType, animated, rotation, mirrored, 0);
        }

        Info(int width, int height, String mimeType, boolean animated, int rotation, boolean mirrored,
                int colorSpace) {
            this(width, height, mimeType, animated, rotation, mirrored, colorSpace, 8, true);
        }

        Info(int width, int height, String mimeType, boolean animated, int rotation, boolean mirrored,
                int colorSpace, int bitDepth, boolean hasAlpha) {
            this(width, height, mimeType, animated, rotation, mirrored, colorSpace, bitDepth, hasAlpha, null);
        }

        Info(int width, int height, String mimeType, boolean animated, int rotation, boolean mirrored,
                int colorSpace, int bitDepth, boolean hasAlpha, float[] description) {
            this.bitDepth = bitDepth;
            this.hasAlpha = hasAlpha;
            this.width = width;
            this.height = height;
            this.mimeType = mimeType;
            this.animated = animated;
            this.rotation = rotation;
            this.mirrored = mirrored;
            this.colorSpace = GateColorSpaces.of(colorSpace, description);
        }
    }

    static Movie trustedMovie(InputStream stream) {
        final boolean inside = sInside.get();
        sInside.set(Boolean.TRUE);
        try { return Movie.decodeStream(stream); }
        finally { sInside.set(inside); }
    }

    // Only an independently opened descriptor verified by the input worker reaches this path.
    static Bitmap trustedBitmap(FileDescriptor fd, BitmapFactory.Options opts) {
        final boolean inside = sInside.get();
        sInside.set(Boolean.TRUE);
        try {
            return BitmapFactory.decodeFileDescriptor(fd, null, opts);
        } finally {
            sInside.set(inside);
        }
    }

    /** Captures a URI or data source user and the incoming server delegate before any worker hop. */
    public static GateOwner iconUser(int sourceUser) {
        return GateOwner.of(sourceUser, ActivityThread.isSystem() ? owner().forUid : GateOwner.UNKNOWN,
                android.os.UserHandle.PER_USER_RANGE);
    }

    /** Data icons retain a scoped source user or the incoming server user's capture. */
    public static GateOwner iconDataUser(int defaultUser) {
        final GateOwner captured = owner();
        return GateOwner.of(captured.user >= 0 ? captured.user : defaultUser, captured.forUid,
                android.os.UserHandle.PER_USER_RANGE);
    }

    /** Local icon dispatch preserves the captured server delegate. Remote calls use transport authentication. */
    public static Bundle submitIcon(ContentResolver resolver, GateOwner user, Bundle extras) {
        final Local local = sLocal;
        if (local != null) {
            extras.putInt(EXTRA_OWNER_USER, user.user);
            extras.putInt(EXTRA_FOR_UID, user.forUid);
            return local.call(METHOD_DECODE, extras);
        }
        return resolver.call(URI, METHOD_DECODE, null, extras);
    }

    /** A public icon call shares the gate's process wait permit and entry deadline. */
    public static final class Wait implements AutoCloseable {
        private final Op op;
        private Wait(Op op) { this.op = op; }
        public long left() { return op.left(); }
        @Override public void close() { op.close(); }
    }

    public static Wait waiting() {
        final Op op = begin();
        try {
            return new Wait(op);
        } catch (RuntimeException | Error e) {
            op.close();
            throw e;
        }
    }

    /** Shared caller and real-provider admission for a dedicated icon input worker. */
    public static final class ProviderInput implements AutoCloseable {
        private final GateInput.ProviderPlace caller;
        private final GateInput.ProviderPlace provider;
        private ProviderInput(GateInput.ProviderPlace caller, GateInput.ProviderPlace provider) {
            this.caller = caller;
            this.provider = provider;
        }
        private boolean closed;
        @Override public synchronized void close() {
            if (closed) return;
            closed = true;
            provider.close();
            caller.close();
        }
    }

    private static void snapshotThread() throws IOException {
        final Looper looper = Looper.myLooper();
        if (looper == Looper.getMainLooper() || Binder.getCallingPid() != Process.myPid()
                || (looper != null && SHARED_LOOPERS.contains(Thread.currentThread().getName()))) {
            throw new IOException("a dedicated input worker is required");
        }
    }

    public static ProviderInput provider(ContentResolver resolver, Uri uri, GateOwner user) throws IOException {
        snapshotThread();
        GateInput.ProviderPlace caller = null;
        GateInput.ProviderPlace provider = null;
        boolean transferred = false;
        try {
            caller = GateInput.caller(user.forUid >= 0 ? user.forUid : Process.myUid());
            provider = GateInput.provider(resolver, uri);
            final ProviderInput input = new ProviderInput(caller, provider);
            transferred = true;
            return input;
        } catch (Failure e) {
            throw new IOException(e.getMessage(), e);
        } finally {
            if (!transferred) {
                if (provider != null) provider.close();
                if (caller != null) caller.close();
            }
        }
    }

    /** An immutable staged input, charged until the producer has handed its descriptor over. */
    public static final class Input implements AutoCloseable {
        private final Bytes bytes;
        private Input(Bytes bytes) { this.bytes = bytes; }
        public ParcelFileDescriptor fd() { return bytes.fd(); }
        @Override public void close() { bytes.close(); }
    }

    public static Input snapshot(android.content.res.AssetFileDescriptor fd) throws IOException {
        snapshotThread();
        try (Op op = new Op(0, false, null)) {
            return snapshotInput(GateInput.of(op, fd.getFileDescriptor(), fd.getStartOffset(), fd.getDeclaredLength()));
        } catch (Failure e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    public static Input snapshot(byte[] data) throws IOException {
        snapshotThread();
        try (Op op = new Op(0, false, null)) {
            return snapshotInput(GateInput.of(op, data, 0, data.length));
        } catch (Failure e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    private static Input snapshotInput(Bytes bytes) {
        try {
            return new Input(bytes);
        } catch (RuntimeException | Error e) {
            bytes.close();
            throw e;
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
    static Info probe(Op op, Bytes bytes, boolean turn) throws Failure {
        final Bundle r = request(op, turn ? METHOD_HEADER : METHOD_PROBE, bytes, 0, 0);
        if (r.getInt(EXTRA_SOURCE_ERROR) != 0) throw failed(false, "partial probe reply");
        final int width = r.getInt(EXTRA_WIDTH);
        final int height = r.getInt(EXTRA_HEIGHT);
        final int rotation = r.getInt(EXTRA_ROTATION);
        final int colorSpace = r.getInt(EXTRA_COLOR_SPACE);
        final float[] description = r.getFloatArray(EXTRA_COLOR_DESCRIPTION);
        final int bitDepth = r.getInt(EXTRA_BIT_DEPTH, 8);
        if (!GateColorSpaces.isBitDepth(bitDepth)) throw failed(false, "bad source depth");
        if (width < 1 || height < 1 || width > MAX_BOX || height > MAX_BOX || !quarterTurn(rotation)) {
            throw failed(false, "bad probe reply");
        }
        if (!GateColorSpaces.isValid(colorSpace, description)) {
            throw failed(false, "bad probe colour space");
        }
        return new Info(width, height, mimeTypeOf(r.getInt(EXTRA_FORMAT), bytes.head()),
                r.getBoolean(EXTRA_ANIMATED), rotation, r.getBoolean(EXTRA_MIRROR), colorSpace, bitDepth, r.getBoolean(EXTRA_HAS_ALPHA, true), description);
    }

    /** {@code bytes} decoded in the VM to fit {@code maxWidth} x {@code maxHeight}, upright. */
    static Decoded decode(Op op, Bytes bytes, int maxWidth, int maxHeight) throws Failure {
        final int boxW = Math.max(1, Math.min(MAX_BOX, maxWidth));
        final int boxH = Math.max(1, Math.min(MAX_BOX, maxHeight));
        final Bundle r = request(op, METHOD_DECODE, bytes, boxW, boxH);
        final Bitmap bitmap = r.getParcelable(EXTRA_BITMAP, Bitmap.class);
        final int rotation = r.getInt(EXTRA_ROTATION);
        if (bitmap == null || !quarterTurn(rotation) || bitmap.getWidth() > boxW || bitmap.getHeight() > boxH) {
            throw failed(false, "bad decode reply");
        }
        op.sourceError = Math.max(op.sourceError, r.getInt(EXTRA_SOURCE_ERROR));
        return new Decoded(bitmap, rotation, r.getBoolean(EXTRA_MIRROR));
    }

    static Bitmap gainmap(Op op, Bytes bytes, int baseWidth, int baseHeight) throws Failure {
        if (baseWidth < 1 || baseHeight < 1 || baseWidth > MAX_BOX || baseHeight > MAX_BOX) {
            throw failed(true, "gain map base geometry");
        }
        final Bundle reply = request(op, METHOD_GAINMAP, bytes, baseWidth, baseHeight);
        return gainPixels(reply, baseWidth, baseHeight);
    }

    /**
     * The region {@code left}, {@code top}, {@code width} x {@code height} of the gain image, clipped to it in the
     * VM and sampled as {@link #region} samples: at most ceil(width / sample) x ceil(height / sample).
     */
    static Bitmap gainRegion(Op op, Bytes bytes, int left, int top, int width, int height, int sample)
            throws Failure {
        final int outW = (int) (((long) width + sample - 1) / sample);
        final int outH = (int) (((long) height + sample - 1) / sample);
        if (left < 0 || top < 0 || width < 1 || height < 1 || sample < 1 || sample > MAX_REGION_SAMPLE
                || left >= MAX_BOX || top >= MAX_BOX || width > MAX_BOX - left || height > MAX_BOX - top
                || (long) outW * outH > MAX_VM_PIXELS) {
            throw failed(true, "gain region out of bounds");
        }
        return gainPixels(request(op, METHOD_GAIN_REGION, bytes, width, height, left, top, sample), outW, outH);
    }

    // Gain pixels within the box: opaque RGBA, or ALPHA_8 for a greyscale gain image, never turned or partial.
    private static Bitmap gainPixels(Bundle reply, int maxWidth, int maxHeight) throws Failure {
        final Bitmap bitmap = reply.getParcelable(EXTRA_BITMAP, Bitmap.class);
        if (bitmap == null || bitmap.getWidth() > maxWidth || bitmap.getHeight() > maxHeight
                || (long) bitmap.getWidth() * bitmap.getHeight() > MAX_VM_PIXELS
                || (bitmap.getConfig() != Bitmap.Config.ARGB_8888 && bitmap.getConfig() != Bitmap.Config.ALPHA_8)
                || bitmap.hasGainmap() || reply.getInt(EXTRA_ROTATION) != 0 || reply.getBoolean(EXTRA_MIRROR)
                || reply.getInt(EXTRA_SOURCE_ERROR) != 0) {
            DecodeMemory.recycle(bitmap, null);
            throw failed(false, "bad gain map reply");
        }
        return bitmap;
    }

    /**
     * The region {@code left}, {@code top}, {@code width} x {@code height} of the stored image decoded in the VM at full
     * detail and sampled by {@code sample} as Android's region decoder samples it (at most ceil(width / sample) x
     * ceil(height / sample)), never turned.
     */
    static Bitmap region(Op op, Bytes bytes, int left, int top, int width, int height, int sample)
            throws Failure {
        return region(op, bytes, left, top, width, height, sample, 0);
    }

    static Bitmap region(Op op, Bytes bytes, int left, int top, int width, int height, int sample, int pixelFormat)
            throws Failure {
        if (pixelFormat < 0 || pixelFormat > 3) throw failed(true, "pixel format");
        final int outW = (int) (((long) width + sample - 1) / sample);
        final int outH = (int) (((long) height + sample - 1) / sample);
        if (left < 0 || top < 0 || width < 1 || height < 1 || sample < 1 || sample > MAX_REGION_SAMPLE
                || left >= MAX_BOX || top >= MAX_BOX || width > MAX_BOX - left || height > MAX_BOX - top
                || (long) outW * outH > ((pixelFormat & 1) != 0 ? MAX_VM_PIXELS / 2 : MAX_VM_PIXELS)) {
            throw failed(true, "region out of bounds");
        }
        final String method = pixelFormat == 3 ? METHOD_REGION_STRAIGHT_WIDE
                : pixelFormat == 2 ? METHOD_REGION_STRAIGHT
                : pixelFormat == 1 ? METHOD_REGION_WIDE : METHOD_REGION;
        final Bundle r = request(op, method, bytes, width, height, left, top, sample);
        final Bitmap bitmap = r.getParcelable(EXTRA_BITMAP, Bitmap.class);
        if (bitmap == null || bitmap.getWidth() > outW || bitmap.getHeight() > outH
                || ((pixelFormat & 1) != 0 && bitmap.getConfig() != Bitmap.Config.RGBA_F16)
                || ((pixelFormat & 2) != 0 && bitmap.hasAlpha() && bitmap.isPremultiplied())) {
            throw failed(false, "bad region reply");
        }
        op.sourceError = Math.max(op.sourceError, r.getInt(EXTRA_SOURCE_ERROR));
        return bitmap;
    }

    static Bitmap straightWebp(Op op, Bytes bytes, int width, int height) throws Failure {
        if (width < 1 || height < 1 || (long) width * height > MAX_VM_PIXELS) {
            throw failed(true, "straight WebP dimensions");
        }
        final Bundle reply = request(op, METHOD_STRAIGHT_WEBP, bytes, width, height);
        final Bitmap bitmap = reply.getParcelable(EXTRA_BITMAP, Bitmap.class);
        if (bitmap == null || bitmap.getWidth() != width || bitmap.getHeight() != height
                || bitmap.getConfig() != Bitmap.Config.ARGB_8888
                || bitmap.hasAlpha() && bitmap.isPremultiplied()) {
            throw failed(false, "straight WebP reply");
        }
        op.sourceError = Math.max(op.sourceError, reply.getInt(EXTRA_SOURCE_ERROR));
        return bitmap;
    }

    /** The frames of an animation (GIF, WebP) decoded in the VM, each fitting the box. */
    static Frames frames(Op op, Bytes bytes, int maxWidth, int maxHeight) throws Failure {
        final int boxW = Math.max(1, Math.min(MAX_BOX, maxWidth));
        final int boxH = Math.max(1, Math.min(MAX_BOX, maxHeight));
        final Bundle r = request(op, METHOD_FRAMES, bytes, boxW, boxH);
        final Bitmap frames = r.getParcelable(EXTRA_BITMAP, Bitmap.class);
        final int count = r.getInt(EXTRA_FRAME_COUNT);
        final int[] durations = r.getIntArray(EXTRA_DURATIONS);
        if (frames == null || count < 1 || count > DecodeMemory.MAX_FRAMES || frames.getWidth() > boxW || frames.getHeight() % count != 0
                || frames.getHeight() / count > boxH || durations == null || durations.length != count) {
            throw failed(false, "bad frames reply");
        }
        return new Frames(frames, count, frames.getWidth(), frames.getHeight() / count, durations,
                Math.max(0, r.getInt(EXTRA_LOOPS)));
    }

    private static boolean quarterTurn(int rotation) {
        return rotation == 0 || rotation == 90 || rotation == 180 || rotation == 270;
    }

    // Only private copies leave the gate. Cache entries, calls and refills share the original
    // through explicit references so large uncached replies do not wait for GC between stripes.
    static final class Answer {
        final Bundle bundle;
        private int mHolders = 1;

        Answer(Bundle bundle) { this.bundle = bundle; }

        synchronized Answer retain() {
            if (mHolders == 0) throw new IllegalStateException("reply already released");
            mHolders++;
            return this;
        }

        void release() {
            synchronized (this) {
                if (mHolders == 0) throw new IllegalStateException("reply released twice");
                if (--mHolders != 0) return;
            }
            DecodeMemory.recycle(bundle.getParcelable(EXTRA_BITMAP, Bitmap.class), null);
        }
    }

    /** One request under way, shared by every caller of the same request for the same bytes. */
    private static final class Call {
        final String key;
        final long end;
        private Answer mAnswer; // guarded by this
        private int mReaders; // request callers, guarded by this
        private boolean mSettled;
        // Refill registrations of callers that were not answered in time. Guarded by this.
        private java.util.ArrayList<GateRefill> mRefills;

        Call(String key, long end) {
            this.key = key;
            this.end = end;
        }

        synchronized void addReader() {
            if (mSettled) throw new IllegalStateException("reply already settled");
            mReaders++;
        }

        void releaseReader() {
            final Answer answer;
            synchronized (this) {
                if (mReaders == 0) throw new IllegalStateException("reader released twice");
                answer = --mReaders == 0 ? mAnswer : null;
                if (mReaders == 0) mAnswer = null;
            }
            if (answer != null) answer.release();
        }

        // Null means already answered. Otherwise the returned refills receive the producer's answer.
        synchronized java.util.List<GateRefill> answer(Answer answer) {
            if (mSettled) return null;
            mSettled = true;
            if (mReaders != 0) mAnswer = answer.retain();
            notifyAll();
            final java.util.List<GateRefill> refills = mRefills;
            mRefills = null;
            return refills != null ? refills : java.util.Collections.emptyList();
        }

        // A refill waits for this call's answer: null, or the answer when it came already.
        synchronized Bundle addRefill(GateRefill refill) {
            if (mAnswer != null) {
                return mAnswer.bundle;
            }
            if (mRefills == null) {
                mRefills = new java.util.ArrayList<>(1);
            }
            if (!mRefills.contains(refill)) {
                mRefills.add(refill);
                refill.waitsFor();
            }
            return null;
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
            return mAnswer.bundle;
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
     * The time of a public call starting on this thread now. In an app: the main thread MAIN_MS, an incoming binder
     * call BINDER_MS, a shared looper thread LOOPER_MS, any other thread WORKER_MS. In the system server: none on the
     * main thread and the shared looper threads, BINDER_MS in an incoming binder call, SYSTEM_WORKER_MS on any other
     * thread (a service's own thread, a worker). Critical threads take no wait during NO_WAIT_MS after a critical
     * wait ran out, and no wait when MAX_WAITING threads of the process wait already (taken in {@link #request}), so at most MAX_WAITING binder
     * threads of a pool of 15 (an app) or 31 (the system server) ever wait.
     */
    private static long allowedWait() {
        final Long forced = sWaitForTest;
        if (forced != null) {
            return forced;
        }
        if (critical() && SystemClock.uptimeMillis() < sNoWaitUntil) {
            return 0;
        }
        final boolean system = ActivityThread.isSystem();
        if (system && !sLocks.complete()) {
            return 0; // a required global lock is not known yet: no system server thread waits (GateLocks)
        }
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

    private static void timedOut(Op op) {
        if (sWaitForTest == null && op.critical) {
            sNoWaitUntil = SystemClock.uptimeMillis() + NO_WAIT_MS;
        }
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
        if (!sLocks.complete()) {
            final long now = SystemClock.uptimeMillis();
            sLocks.find(now);
            if (!sLocks.complete() && !sLocksLogged && sBooted && now - sBootedAt > LOCK_SOURCE_MS) {
                sLocksLogged = true;
                Log.e(TAG, "not found yet: " + sLocks.missing() + ", no system server thread waits for the VM");
            }
        }
        return sLocks.heldHere();
    }

    /**
     * The answer of the system server's provider to {@code method} for {@code bytes}: from the cache (or held for the
     * call's refill registration) when this request was answered before, else asked (unless it is under way already)
     * and waited for until the call's end ({@link Op#end}). A request not waited for, or not answered in time, still
     * finishes in the background and its answer is kept, so the same call is answered at once next time, and the
     * call's refill registration (if any) is told.
     */
    private static Bundle request(Op op, String method, Bytes bytes, int width, int height) throws Failure {
        return request(op, method, bytes, width, height, 0, 0, 0);
    }

    // As above, with a region's left, top and sample size (0 for other methods), all of them in the cache key.
    private static Bundle request(Op op, String method, Bytes bytes, int width, int height, int left, int top,
            int sample) throws Failure {
        mayAsk();
        // Whose image this is, taken here on the calling thread: part of the key, so an answer made for one owner is
        // never given to another, and sent with the request.
        final GateOwner owner = op.owner;
        final String key = owner.key() + ':' + method + ':' + width + 'x' + height + '@' + left + ',' + top + '/'
                + sample + ':' + bytes.digest();
        Answer cached;
        synchronized (sAnswers) {
            cached = sAnswers.get(key);
            if (cached != null) cached.retain();
        }
        if (cached == null && op.refill != null) {
            cached = op.refill.held(key);
        }
        if (cached != null) {
            try {
                sCacheHits.incrementAndGet();
                return answerOf(cached.bundle);
            } finally { cached.release(); }
        }
        final Call call;
        boolean send = false;
        final java.util.List<Call> expired;
        synchronized (sCalls) {
            final long now = SystemClock.uptimeMillis();
            final Call running = sCalls.get(key);
            if (running != null && now < running.end) {
                call = running;
                expired = null;
            } else {
                expired = dropExpiredCallsLocked(now);
                call = new Call(key, now + SERVER_MS + 500);
                sCalls.put(key, call);
                send = true;
            }
            call.addReader();
        }
        try {
            if (expired != null) {
                for (Call gone : expired) {
                    finish(gone, error("no answer in time")); // its waiters and refills learn it now
                }
            }
            if (send) {
                final java.lang.ref.WeakReference<Call> expiration = new java.lang.ref.WeakReference<>(call);
                GateRefill.timerForGate().postAtTime(() -> {
                    final Call started = expiration.get();
                    if (started == null) return;
                    boolean pending;
                    synchronized (sCalls) {
                        pending = sCalls.get(started.key) == started;
                        if (pending) sCalls.remove(started.key);
                    }
                    if (pending) finish(started, error("no answer in time"));
                }, call.end);
                send(method, bytes, width, height, left, top, sample, owner, call);
            }
            long wait = op.left();
            Bundle answer;
            sInside.set(Boolean.TRUE);
            try {
                answer = wait > 0 ? call.await(op.end) : call.await(0);
            } finally {
                sInside.set(Boolean.FALSE);
            }
            if (answer == null && op.refill != null) {
                // Its answer comes to the refill, which reloads then (or it came just now).
                answer = call.addRefill(op.refill);
            }
            if (answer != null && answer.getString(EXTRA_ERROR) == null) {
                (METHOD_PROBE.equals(method) || METHOD_HEADER.equals(method) ? sProbes : sDecodes).incrementAndGet();
            }
            if (answer == null) {
                if (wait == 0) {
                    sNotWaited.incrementAndGet();
                    throw notNow("not waited for here, finishing in the background");
                }
                timedOut(op);
                throw notNow("missed its time, finishing in the background");
            }
            return answerOf(answer);
        } finally { call.releaseReader(); }
    }

    // Requests whose time is over (their callback never came) leave the table: each only as itself, so a newer call
    // of the same key stays. Returns them (null: none). Guarded by sCalls.
    private static java.util.List<Call> dropExpiredCallsLocked(long now) {
        java.util.List<Call> expired = null;
        final java.util.Iterator<java.util.Map.Entry<String, Call>> it = sCalls.entrySet().iterator();
        while (it.hasNext()) {
            final Call call = it.next().getValue();
            if (call.end <= now) {
                it.remove();
                if (expired == null) {
                    expired = new java.util.ArrayList<>();
                }
                expired.add(call);
            }
        }
        return expired;
    }

    /** Tests: requests under way in this process. */
    public static int callsForTest() {
        synchronized (sCalls) {
            return sCalls.size();
        }
    }

    // An answer for one caller: an error as a Failure, pixels as a copy of their own (the cache keeps the original).
    private static Bundle answerOf(Bundle answer) throws Failure {
        final String error = answer.getString(EXTRA_ERROR);
        final int sourceError = answer.getInt(EXTRA_SOURCE_ERROR);
        if (sourceError != 0 && sourceError != 2 && sourceError != 3) {
            throw failed(false, "bad source error");
        }
        if (error != null) {
            if (answer.getBoolean(EXTRA_REFUSED) && sourceError != 0) {
                throw new Failure(true, false, error, sourceError);
            }
            throw answer.getBoolean(EXTRA_REFUSED) ? failed(true, error) : notNow(error);
        }
        final Bitmap bitmap = answer.getParcelable(EXTRA_BITMAP, Bitmap.class);
        if (bitmap == null) {
            return answer;
        }
        try (DecodeMemory.Reservation memory = DecodeMemory.reserve(DecodeMemory.bitmapBytes(bitmap))) {
            final boolean straight = bitmap.hasAlpha() && !bitmap.isPremultiplied();
            final Bitmap copy = bitmap.copy(bitmap.getConfig(), false);
            if (copy == null) throw notNow("no memory for the pixels");
            try {
                // Parcel keeps raw straight pixels but its Java copy request defaults to premultiplied.
                if (straight) copy.setPremultiplied(false);
                final Bundle mine = new Bundle(answer);
                mine.putParcelable(EXTRA_BITMAP, copy);
                DecodeMemory.keep(copy, memory);
                return mine;
            } catch (RuntimeException | Error e) {
                DecodeMemory.recycle(copy, null);
                throw e;
            }
        } catch (DecodeMemory.NoRoomException e) {
            throw e.later ? notNow(e.getMessage()) : failed(true, e.getMessage());
        } catch (OutOfMemoryError e) {
            throw notNow("no memory for the copied pixels");
        }
    }

    // Hands the request to the system server on a sender thread, whose callback answers {@code call} and fills the
    // cache. The caller's bytes are its own until it returns, so the descriptor is copied here.
    private static void send(String method, Bytes bytes, int width, int height, int left, int top, int sample,
            GateOwner owner, Call call) {
        final java.util.concurrent.atomic.AtomicInteger dispatch = new java.util.concurrent.atomic.AtomicInteger();
        final String caller = ActivityThread.currentPackageName();
        final ParcelFileDescriptor fd;
        try {
            fd = bytes.fd().dup();
        } catch (IOException e) {
            finish(call, error("descriptor not passed"));
            return;
        }
        bytes.retain(); // their memory stays reserved while the send holds them
        try {
            senders().execute(() -> {
                if (!dispatch.compareAndSet(0, 1)) return;
                try {
                    sendNow(method, fd, width, height, left, top, sample, caller, owner, call);
                } finally {
                    if (dispatch.compareAndSet(1, 2)) {
                        bytes.release();
                        closeQuietly(fd);
                    }
                }
            });
        } catch (RuntimeException | Error e) {
            if (dispatch.compareAndSet(0, 2)) {
                bytes.release();
                closeQuietly(fd);
            }
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
            int sample, String caller, GateOwner owner, Call call) {
        final Bundle extras = new Bundle();
        extras.putParcelable(EXTRA_FD, fd);
        extras.putInt(EXTRA_WIDTH, width);
        extras.putInt(EXTRA_HEIGHT, height);
        if (METHOD_REGION.equals(method) || METHOD_REGION_WIDE.equals(method)
                || METHOD_REGION_STRAIGHT.equals(method) || METHOD_REGION_STRAIGHT_WIDE.equals(method)
                || METHOD_GAIN_REGION.equals(method)) {
            extras.putInt(EXTRA_LEFT, left);
            extras.putInt(EXTRA_TOP, top);
            extras.putInt(EXTRA_SAMPLE, sample);
        }
        extras.putLong(EXTRA_TIME, Math.max(1, Math.min(SERVER_MS, call.end - 500 - SystemClock.uptimeMillis())));
        if (caller != null) {
            extras.putString(EXTRA_CALLER, caller);
        }
        if (owner.user >= 0) {
            extras.putInt(EXTRA_OWNER_USER, owner.user);
        }
        if (owner.source != null) {
            extras.putString(EXTRA_SOURCE, owner.source); // DiamaneOS
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
                extras.putInt(EXTRA_FOR_UID, owner.forUid); // same process only: never sent over binder
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

    // What an answer is charged in the cache: its pixels, its gain map's pixels, and a fixed amount for the rest.
    private static int entryBytes(Bundle answer) {
        final int[] durations = answer.getIntArray(EXTRA_DURATIONS);
        final long metadata = ENTRY_BYTES + (durations != null ? (long) durations.length * Integer.BYTES : 0);
        final Bitmap b = answer.getParcelable(EXTRA_BITMAP, Bitmap.class);
        if (b == null) {
            return (int) Math.min(Integer.MAX_VALUE, metadata);
        }
        long bytes = metadata + b.getAllocationByteCount();
        final Gainmap gainmap = b.hasGainmap() ? b.getGainmap() : null;
        if (gainmap != null && gainmap.getGainmapContents() != null) {
            bytes += gainmap.getGainmapContents().getAllocationByteCount();
        }
        return (int) Math.min(Integer.MAX_VALUE, bytes);
    }

    /** Tests: what an answer is charged in the gate's cache of answers. */
    public static int cacheChargeForTest(Bundle answer) {
        return entryBytes(answer);
    }

    // An answer arrived: kept (pixels, sizes, and the VM's refusals, never a passing failure, never one above half the
    // cache, which would push out everything else and then itself), then given out.
    private static void finish(Call call, Bundle answer) {
        Bundle a = answer != null ? answer : error("no answer");
        final Bitmap bitmap = a.getParcelable(EXTRA_BITMAP, Bitmap.class);
        if (bitmap != null) {
            try {
                DecodeMemory.keep(bitmap);
            } catch (DecodeMemory.NoRoomException | OutOfMemoryError e) {
                DecodeMemory.recycle(bitmap, null);
                a = error("no room to retain the reply pixels");
            }
        }
        final int[] durations = a.getIntArray(EXTRA_DURATIONS);
        if (durations != null) {
            if (durations.length < 1 || durations.length > DecodeMemory.MAX_FRAMES) {
                DecodeMemory.recycle(bitmap, null);
                a = error("bad frame duration count");
            } else {
                try (DecodeMemory.Reservation memory = DecodeMemory.reserve((long) durations.length * Integer.BYTES)) {
                    DecodeMemory.keepDurations(durations, memory);
                } catch (DecodeMemory.NoRoomException | OutOfMemoryError e) {
                    DecodeMemory.recycle(bitmap, null);
                    a = error("no room to retain frame durations");
                }
            }
        }
        final Answer shared;
        try { shared = new Answer(a); }
        catch (RuntimeException | Error e) {
            DecodeMemory.recycle(bitmap, null);
            throw e;
        }
        try {
            final java.util.List<GateRefill> refills;
            synchronized (sCalls) {
                if (sCalls.get(call.key) == call) sCalls.remove(call.key);
                refills = call.answer(shared);
            }
            if (refills == null) return;
            if ((a.getString(EXTRA_ERROR) == null || a.getBoolean(EXTRA_REFUSED))
                    && entryBytes(a) <= CACHE_BYTES / 2) {
                synchronized (sAnswers) {
                    shared.retain();
                    try {
                        sAnswers.put(call.key, shared);
                    } catch (RuntimeException | Error e) {
                        if (sAnswers.get(call.key) != shared) shared.release();
                        throw e;
                    }
                }
            }
            for (GateRefill refill : refills) refill.answered(call.key, shared);
        } finally { shared.release(); }
    }

    // A stream that failed to read: a failure of the moment that keeps the stream's exception as its cause, which
    // ImageDecoder passes on as stock does.
    static Failure inputFailed(Throwable cause) {
        final Failure failure = notNow("stream not read: " + cause.getMessage());
        failure.initCause(cause);
        return failure;
    }

    // A failure of the moment (the VM not asked yet, not answered in time, unavailable): the same image can be had later.
    static Failure notNow(String why) {
        return failure(false, true, why);
    }

    static Failure noRoom(DecodeMemory.NoRoomException e) {
        final Failure failure = e.later ? notNow(e.getMessage()) : failed(true, e.getMessage());
        failure.memory = e.later ? e.bytes : 0;
        final GateRefill refill = GateRefill.current();
        if (refill != null && e.later) refill.needs(e.bytes);
        return failure;
    }

    // The image is not had this way (asking again the same way gives nothing), though the bytes were not refused.
    static Failure notThisWay(String why) {
        return failure(false, false, why);
    }

    static Failure failed(boolean refused, String why) {
        return failure(refused, !refused, why);
    }

    private static Failure failure(boolean refused, boolean again, String why) {
        final GateRefill refill = GateRefill.current();
        if (refill != null) {
            refill.failed(again);
        }
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
        return new Failure(refused, again, why);
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
