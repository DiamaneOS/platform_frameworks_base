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
import android.provider.DocumentsContract;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructPollfd;
import android.util.Log;
import android.util.Size;

import de.diamaneos.sanitizer.decode.DecodeChecks;
import de.diamaneos.sanitizer.decode.DecodeRequest;
import de.diamaneos.sanitizer.decode.IDecodeService;
import de.diamaneos.sanitizer.decode.ImageInfo;
import de.diamaneos.sanitizer.decode.Thumbnail;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileNotFoundException;
import java.io.IOException;

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
        private final boolean mNoRoom;
        private boolean mCallerLeft;

        public UnavailableException(String message) {
            super(message);
            mNoRoom = false;
        }

        public UnavailableException(String message, Throwable cause) {
            super(message, cause);
            mNoRoom = false;
        }

        private UnavailableException(String message, boolean noRoom) {
            super(message);
            mNoRoom = noRoom;
        }

        // No room in this process for the image now (DecodeMemory): asked for again shortly, as the service's.
        static UnavailableException noRoomHere(String message) {
            return new UnavailableException(message, true);
        }

        // The service answered, and the caller had left before the reply was read: a late answer, which the service
        // keeps for the next ask.
        static UnavailableException callerLeft(String message) {
            final UnavailableException e = new UnavailableException(message);
            e.mCallerLeft = true;
            return e;
        }

        boolean isCallerLeft() {
            return mCallerLeft;
        }

        /**
         * True when the service answered that it has no room for this image now (other large images hold its
         * memory). The service works: what was left out is asked for again shortly ({@link #addRecoveryListener}).
         */
        public boolean isNoRoom() {
            return mNoRoom;
        }
    }

    /**
     * The file is broken, unsupported or hostile, or a reply broke the contract: show a placeholder, no retry. Or the
     * image behind a URI could not be opened ({@link #openFailed}: not there yet, the provider not ready, no access),
     * which says nothing about the content and may work later.
     */
    public static final class RejectedException extends Exception {
        /** True when the URI could not be opened: not a verdict on the image, it may be tried again later. */
        public final boolean openFailed;

        public RejectedException(String message) {
            this(message, false);
        }

        public RejectedException(String message, boolean openFailed) {
            super(message);
            this.openFailed = openFailed;
        }
    }

    /** A decoded image: the bitmap, already turned upright, and the quarter turns the file asked for. */
    public static final class Decoded {
        public final Bitmap bitmap;
        /** 0, 90, 180 or 270: the clockwise turn applied to the stored pixels. */
        public final int rotation;
        /** True when the stored pixels were mirrored left to right before the turn (the file's orientation). */
        public final boolean mirrored;

        Decoded(Bitmap bitmap, int rotation) {
            this(bitmap, rotation, false);
        }

        Decoded(Bitmap bitmap, int rotation, boolean mirrored) {
            this.bitmap = bitmap;
            this.rotation = rotation;
            this.mirrored = mirrored;
        }
    }

    private static final Object sLock = new Object();
    private static DecodeClient sClient; // guarded by sLock

    // Each open of a URI and each copy into a pipe runs on its own thread with a deadline. Pipes are read with poll().
    // A file is read with pread(), which can block for good on a FUSE file served by an app (a StorageManager proxy):
    // such a thread is left behind and counted against the owner as stuck. A slot is taken before such a thread starts
    // and held until it ends (RunSlots): at most MAX_RUNNING_PER_OWNER living threads of one owner and MAX_RUNNING in
    // all. An owner with MAX_STUCK_PER_OWNER stuck ones gets no new ones, and past MAX_STUCK stuck in all only owners
    // without a stuck thread get them, so a few apps with hung providers cannot switch URI images off for everyone.
    private static final int MAX_RUNNING_PER_OWNER = 4;
    private static final int MAX_RUNNING = 64;
    private static final int MAX_STUCK_PER_OWNER = 2;
    private static final int MAX_STUCK = 16;
    private static final RunSlots sSlots =
            new RunSlots(MAX_RUNNING_PER_OWNER, MAX_RUNNING, MAX_STUCK_PER_OWNER, MAX_STUCK);

    private VmDecode() {
    }

    /** Told when the decode service answers again after calls failed for it. */
    public interface StateListener {
        /**
         * The service answers. {@code wasDown}: it did not answer a probe before (an outage that ended), so images
         * shown as placeholders meanwhile can be asked for again. False after a failure that was not the service's
         * (a slow image): nothing needs asking again. Called on a thread of its own: do no image work here.
         */
        void onServiceAnswers(boolean wasDown);
    }

    private static final java.util.List<StateListener> sStateListeners =
            new java.util.concurrent.CopyOnWriteArrayList<>();
    private static final java.util.concurrent.atomic.AtomicInteger sRecoveries =
            new java.util.concurrent.atomic.AtomicInteger();
    // Per thread: how many decode calls failed as unavailable (see unavailableCount).
    private static final ThreadLocal<int[]> sUnavailable = ThreadLocal.withInitial(() -> new int[1]);

    /** Adds a listener for as long as the process lives (components that live as long, never a view). */
    public static void addStateListener(StateListener listener) {
        if (listener != null) {
            sStateListeners.add(listener);
        }
    }

    private static final java.util.List<Runnable> sRecoveryListeners =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    /**
     * Adds {@code onBack}, run (on a thread of its own) each time the service answers again after it was down, after
     * it answered calls late, once their callers had given up (a slow service, its answers kept for the next ask),
     * and shortly after it had no room for an image ({@link UnavailableException#isNoRoom}): what was left as a
     * placeholder may be asked for again. For components that live as long as the process.
     */
    public static void addRecoveryListener(Runnable onBack) {
        if (onBack != null) {
            sRecoveryListeners.add(onBack);
        }
    }

    private static void runRecoveryListeners() {
        for (Runnable onBack : sRecoveryListeners) {
            try {
                onBack.run();
            } catch (RuntimeException e) {
                Log.w(TAG, "decode service recovery listener failed: " + e);
            }
        }
    }

    // Late and "no room now" answers: the recovery listeners run on one thread of their own, after a quiet time and
    // at most once per gap (LateRuns).
    private static final LateRuns sLate = new LateRuns();
    private static final CapacityLanes sCapacityLanes = new CapacityLanes();
    private static final ThreadLocal<java.util.ArrayList<CapacityLanes.Lane>> sFailedLanes =
            ThreadLocal.withInitial(java.util.ArrayList::new);
    private static final ThreadLocal<boolean[]> sCapacityTask = ThreadLocal.withInitial(() -> new boolean[1]);
    private static final java.util.concurrent.atomic.AtomicBoolean sCapacityTimer =
            new java.util.concurrent.atomic.AtomicBoolean();
    private static final java.util.concurrent.ScheduledExecutorService sCapacityClock =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                final Thread thread = new Thread(r, "VmDecodeCapacity");
                thread.setDaemon(true);
                return thread;
            });
    private static final java.util.concurrent.atomic.AtomicInteger sBindings =
            new java.util.concurrent.atomic.AtomicInteger();

    private static final java.util.concurrent.atomic.AtomicInteger sLateAnswers =
            new java.util.concurrent.atomic.AtomicInteger();

    // Late answers of this process's calls (StuckCalls), for tests: other events also run the recovery listeners.
    private static final java.util.concurrent.atomic.AtomicInteger sLateCalls =
            new java.util.concurrent.atomic.AtomicInteger();

    static void lateAnswer() {
        sLateCalls.incrementAndGet();
        later(false);
    }

    /** Calls answered after their caller left, for tests. */
    static int lateCallsForTest() {
        return sLateCalls.get();
    }

    static void noRoom() {
        noRoom("", 1);
    }

    static void noRoom(String lane, long bytes) {
        if (ImageThreads.isImageThread() && !sCapacityTask.get()[0]) {
            sCapacityTask.get()[0] = true;
            sCapacityLanes.taskStarted();
        }
        final CapacityLanes.Lane failed = sCapacityLanes.refused(lane, Math.max(1, bytes), SystemClock.uptimeMillis());
        final java.util.ArrayList<CapacityLanes.Lane> failures = sFailedLanes.get();
        if (!ImageThreads.isImageThread()) failures.clear();
        if (!failures.contains(failed)) failures.add(failed);
        capacityTimer();
    }

    static void served() {
        served("", Long.MAX_VALUE);
    }

    static void served(String lane, long bytes) {
        if (ImageThreads.isImageThread() && sCapacityLanes.interested(lane) && !sCapacityTask.get()[0]) {
            sCapacityTask.get()[0] = true;
            sCapacityLanes.taskStarted();
        }
        if (sCapacityLanes.completed(lane, bytes)) later(true);
    }

    /** The failed lanes of the current image task, for that consumer's next recovery selection. */
    public static java.util.function.IntSupplier capacityWakes() {
        return CapacityLanes.snapshot(sFailedLanes.get(), sBindings::get);
    }

    static void bindingChanged() {
        sBindings.incrementAndGet();
        android.graphics.GateRefill.recovered();
        later(false);
    }

    /** Global evidence is a new binding or a verified outage recovery, never an unrelated successful decode. */
    public static int wakes() {
        return sRecoveries.get() + sBindings.get();
    }

    static void imageTaskDone() {
        sFailedLanes.get().clear();
        if (sCapacityTask.get()[0]) {
            sCapacityTask.get()[0] = false;
            if (sCapacityLanes.taskEnded()) later(true);
        }
    }

    private static void capacityTimer() {
        final long delay = sCapacityLanes.delay(SystemClock.uptimeMillis());
        if (delay < 0 || !sCapacityTimer.compareAndSet(false, true)) return;
        try {
            sCapacityClock.schedule(() -> {
                sCapacityTimer.set(false);
                if (sCapacityLanes.tick(SystemClock.uptimeMillis())) later(true);
                capacityTimer();
            }, delay, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (RuntimeException | Error e) {
            sCapacityTimer.set(false);
        }
    }

    private static String capacityLane(DecodeRequest request) {
        return request.ownerPackage + ":u" + request.ownerUser + ":for" + request.forUid;
    }

    private static void later(boolean noRoom) {
        if (!sLate.event(SystemClock.uptimeMillis(), noRoom)) {
            return;
        }
        try {
            final Thread late = new Thread(VmDecode::lateAnswersSettled, "VmDecodeLate");
            late.setDaemon(true);
            late.start();
        } catch (Throwable e) {
            sLate.notWaiting();
        }
    }

    private static void lateAnswersSettled() {
        long due;
        while ((due = sLate.due(SystemClock.uptimeMillis())) > 0) {
            SystemClock.sleep(due);
        }
        if (!sCapacityLanes.mayPublish()) return;
        if (due == LateRuns.RUN_NO_ROOM) {
            // As after a check of the service that found it answering: a thumbnail that waits for it asks again.
            tellStateListeners(false);
        }
        sLateAnswers.incrementAndGet();
        runRecoveryListeners();
    }

    /** How often a settled late-answer batch notified the consumers of this process. */
    public static int lateAnswerRounds() {
        return sLateAnswers.get();
    }

    /**
     * A counter of the decode calls on this thread that failed as unavailable (the service down, slow, busy or without
     * room now, never a refusal of the image). It only grows: a caller compares it before and after its work to know
     * whether an image was left out for the service, to ask for it again after {@link #addRecoveryListener}.
     */
    public static int unavailableCount() {
        return sUnavailable.get()[0];
    }

    /** How many outages of the service ended in this process (it answered again after it was down). */
    public static int recoveries() {
        return sRecoveries.get();
    }

    /** True while this process knows the service is down (a probe got no answer, nothing answered since). */
    public static boolean isDown() {
        final DecodeClient client;
        synchronized (sLock) {
            client = sClient;
        }
        return client != null && client.isDown();
    }

    /** True while the service is down or a call that failed for it is being checked. */
    public static boolean isSuspect() {
        final DecodeClient client;
        synchronized (sLock) {
            client = sClient;
        }
        return client != null && client.isSuspect();
    }

    static void countUnavailable() {
        sUnavailable.get()[0]++;
    }

    // From DecodeClient: the service answers, after an outage when wasDown.
    static void serviceAnswers(boolean wasDown) {
        if (wasDown) {
            sRecoveries.incrementAndGet();
            android.graphics.GateRefill.recovered();
        }
        tellStateListeners(wasDown);
        if (wasDown) {
            runRecoveryListeners();
        }
    }

    private static void tellStateListeners(boolean wasDown) {
        for (StateListener listener : sStateListeners) {
            try {
                listener.onServiceAnswers(wasDown);
            } catch (RuntimeException e) {
                Log.w(TAG, "decode service state listener failed: " + e);
            }
        }
    }

    /** Tests: the listeners are told as if the service answered again after an outage. */
    public static void serviceBackForTest() {
        serviceAnswers(true);
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

    /** The bitmap-only entry used by cumulative image consumers. */
    public static Bitmap decodeBitmap(Context context, ParcelFileDescriptor fd, String ownerPackage, int ownerUser,
            int priority, int maxWidth, int maxHeight) throws UnavailableException, RejectedException {
        return decode(context, fd, ownerPackage, ownerUser, priority, maxWidth, maxHeight, NO_DEADLINE).bitmap;
    }

    /**
     * The tag of the line that says an image was made in the VM (or taken from what the VM made before): debug level,
     * only when log.tag.VmDecodeMade is DEBUG, so phone checks can tell a working VM path from a placeholder.
     */
    public static final String MADE_TAG = "VmDecodeMade";

    static void madeLine(String what) {
        Log.d(MADE_TAG, what);
    }

    // Whether the success line is on, asked of the log at most once a second (the callers are hot paths).
    private static volatile long sMadeCheckedAt = -1_000_000L;
    private static volatile boolean sMadeOn;

    /** True when the success line is on: callers build its text only then. */
    static boolean madeLogging() {
        final long now = SystemClock.uptimeMillis();
        if (now - sMadeCheckedAt > 1000) {
            sMadeOn = Log.isLoggable(MADE_TAG, Log.DEBUG);
            sMadeCheckedAt = now;
        }
        return sMadeOn;
    }

    /**
     * Decodes the bytes behind {@code fd} into a bitmap that fits in {@code maxWidth} x {@code maxHeight} once turned
     * by the file's orientation, giving the turn applied, with an optional deadline ({@link #deadlineIn}). The service
     * reads the descriptor as opaque bytes. The caller keeps and closes it.
     */
    public static Decoded decode(Context context, ParcelFileDescriptor fd, String ownerPackage, int ownerUser,
            int priority, int maxWidth, int maxHeight, long deadline) throws UnavailableException, RejectedException {
        checkNotMainThread();
        if (maxWidth < 1 || maxHeight < 1) {
            throw new RejectedException("bad box " + maxWidth + "x" + maxHeight);
        }
        final DecodeRequest request = request(fd, ownerPackage, ownerUser, priority);
        return client(context).call(priority, deadline, request.ownerPackage, capacityLane(request),
                Math.min((long) maxWidth * maxHeight, IDecodeService.MAX_PIXELS) * 5, service -> {
            final Thumbnail thumbnail = service.decodeThumbnail(request, maxWidth, maxHeight);
            if (thumbnail == null) {
                throw new RejectedException("no reply");
            }
            checkStatus(thumbnail.status);
            try {
                final Decoded decoded = toDecoded(service, thumbnail, maxWidth, maxHeight);
                if (madeLogging()) {
                    madeLine("made " + decoded.bitmap.getWidth() + "x" + decoded.bitmap.getHeight()
                            + " in the VM for " + request.ownerPackage);
                }
                return decoded;
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
    public static Size probeSize(Context context, ParcelFileDescriptor fd, String ownerPackage, int ownerUser,
            int priority, long deadline) throws UnavailableException, RejectedException {
        final Info info = probeInfo(context, fd, ownerPackage, ownerUser, priority, deadline);
        return new Size(info.width, info.height);
    }

    /** Decodes a file the caller may read. */
    public static Decoded decodeFile(Context context, File file, String ownerPackage, int ownerUser, int priority,
            int maxWidth, int maxHeight, long deadline) throws UnavailableException, RejectedException {
        try (ParcelFileDescriptor fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)) {
            return decode(context, fd, ownerPackage, ownerUser, priority, maxWidth, maxHeight, deadline);
        } catch (FileNotFoundException e) {
            throw new RejectedException("no file");
        } catch (IOException e) {
            throw new UnavailableException("could not close the file", e);
        }
    }

    /** The stored size of a file the caller may read, from its header in the VM. */
    public static Size probeFile(Context context, File file, String ownerPackage, int ownerUser, int priority,
            long deadline)
            throws UnavailableException, RejectedException {
        try (ParcelFileDescriptor fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)) {
            return probeSize(context, fd, ownerPackage, ownerUser, priority, deadline);
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
            int ownerUser, int priority, int maxWidth, int maxHeight) throws UnavailableException, RejectedException {
        return decodeUri(context, resolver, uri, ownerPackage, ownerUser, priority, maxWidth, maxHeight, NO_DEADLINE);
    }

    /** {@link #decodeUri(Context, ContentResolver, Uri, String, int, int, int, int)} with a deadline. */
    public static Bitmap decodeUri(Context context, ContentResolver resolver, Uri uri, String ownerPackage,
            int ownerUser, int priority, int maxWidth, int maxHeight, long deadline)
            throws UnavailableException, RejectedException {
        return decodeUri(context, resolver, uri, ownerPackage, ownerUser, priority, maxWidth, maxHeight, deadline,
                false);
    }

    /**
     * The thumbnail behind a URI as {@code ContentResolver.loadThumbnail} gives it: {@link #decodeUri}, then turned by
     * the provider's orientation extra ({@code DocumentsContract.EXTRA_ORIENTATION}, which a provider sends with an
     * EXIF thumbnail, as the thumbnail does not carry the turn of its image) as Android turns it.
     */
    public static Bitmap loadThumbnail(Context context, ContentResolver resolver, Uri uri, String ownerPackage,
            int ownerUser, int priority, int maxWidth, int maxHeight, long deadline)
            throws UnavailableException, RejectedException {
        return decodeUri(context, resolver, uri, ownerPackage, ownerUser, priority, maxWidth, maxHeight, deadline,
                true);
    }

    private static Bitmap decodeUri(Context context, ContentResolver resolver, Uri uri, String ownerPackage,
            int ownerUser, int priority, int maxWidth, int maxHeight, long deadline, boolean providerTurn)
            throws UnavailableException, RejectedException {
        checkNotMainThread();
        final String owner = owner(context, uri, ownerPackage);
        final int user = uriUser(uri, ownerUser);
        try (AssetFileDescriptor afd = openImageWithin(resolver, uri, maxWidth, maxHeight, owner, deadline)) {
            if (afd == null) {
                throw new RejectedException("no image behind the URI", true);
            }
            final Bundle extras = providerTurn ? afd.getExtras() : null;
            final int turn = ReplyPixels.quarterTurn(
                    extras != null ? extras.getInt(DocumentsContract.EXTRA_ORIENTATION, 0) : 0);
            final Pumper pumper = pipe(afd, owner, pumpDeadline(deadline));
            try (ParcelFileDescriptor read = pumper.read) {
                final Bitmap bitmap = decode(context, read, owner, user, priority, maxWidth, maxHeight, deadline)
                        .bitmap;
                if (turn == 0) {
                    return bitmap;
                }
                // The provider's turn holds the input and a complete HDR output at once.
                Bitmap turned = null;
                try (android.graphics.DecodeMemory.Reservation memory =
                        android.graphics.DecodeMemory.reserve(android.graphics.DecodeMemory.bitmapBytes(bitmap))) {
                    final Matrix m = new Matrix();
                    m.setRotate(turn, bitmap.getWidth() / 2, bitmap.getHeight() / 2);
                    turned = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), m, false);
                    android.graphics.DecodeMemory.keep(turned, memory);
                    android.graphics.DecodeMemory.recycle(bitmap, turned);
                    return turned;
                } catch (android.graphics.DecodeMemory.NoRoomException e) {
                    android.graphics.DecodeMemory.recycle(bitmap, null);
                    if (e.later) throw UnavailableException.noRoomHere(e.getMessage());
                    throw new RejectedException(e.getMessage());
                } catch (OutOfMemoryError e) {
                    android.graphics.DecodeMemory.recycle(turned, null);
                    android.graphics.DecodeMemory.recycle(bitmap, null);
                    throw UnavailableException.noRoomHere("no memory for the provider turn");
                }
            } finally {
                pumper.afterCall();
            }
        } catch (FileNotFoundException | SecurityException | IllegalArgumentException e) {
            throw new RejectedException("cannot open the URI: " + e.getClass().getSimpleName(), true);
        } catch (IOException e) {
            throw new UnavailableException("could not read the URI", e);
        }
    }

    /** The stored size of the image behind a URI, from its header in the VM. */
    public static Size probeUri(Context context, ContentResolver resolver, Uri uri, String ownerPackage, int ownerUser,
            int priority, long deadline) throws UnavailableException, RejectedException {
        checkNotMainThread();
        final String owner = owner(context, uri, ownerPackage);
        final int user = uriUser(uri, ownerUser);
        try (AssetFileDescriptor afd = openImageWithin(resolver, uri, 0, 0, owner, deadline)) {
            if (afd == null) {
                throw new RejectedException("no image behind the URI", true);
            }
            final Pumper pumper = pipe(afd, owner, pumpDeadline(deadline));
            try (ParcelFileDescriptor read = pumper.read) {
                return probeSize(context, read, owner, user, priority, deadline);
            } finally {
                pumper.afterCall();
            }
        } catch (FileNotFoundException | SecurityException | IllegalArgumentException e) {
            throw new RejectedException("cannot open the URI: " + e.getClass().getSimpleName(), true);
        } catch (IOException e) {
            throw new UnavailableException("could not read the URI", e);
        }
    }

    /**
     * The image files an app's splash screens and launcher icons name (the clean icon cache's warm-up), resolved in
     * the decode service's process: only names, checked here (same lengths, at most the service's maximum, plain
     * relative file names). Only the system server may ask. At background priority.
     */
    public static de.diamaneos.sanitizer.decode.AppImageFiles listAppImageFiles(Context context,
            de.diamaneos.sanitizer.decode.AppImagesRequest request, long deadline)
            throws UnavailableException, RejectedException {
        checkNotMainThread();
        return client(context).call(PRIORITY_BACKGROUND, deadline, null, service -> {
            final de.diamaneos.sanitizer.decode.AppImageFiles files = service.listAppImageFiles(request);
            if (files == null) {
                throw new RejectedException("no reply");
            }
            checkStatus(files.status);
            final int n = files.files == null ? -1 : files.files.length;
            if (n < 0 || n > IDecodeService.MAX_APP_IMAGE_FILES || files.apkPaths == null
                    || files.apkPaths.length != n || files.densities == null || files.densities.length != n
                    || (files.resources != null && files.resources.length != n)) {
                throw new RejectedException("app image list breaks the contract");
            }
            // Entries that break it are left out, one by one (the caller checks them against the app's APKs and
            // resource names again): one odd entry does not cost the app its other files.
            final java.util.List<Integer> kept = new java.util.ArrayList<>();
            for (int i = 0; i < n; i++) {
                final String file = files.files[i];
                final String apk = files.apkPaths[i];
                final String resource = files.resources == null ? null : files.resources[i];
                if (file != null && apk != null && !file.isEmpty() && file.charAt(0) != '/' && !file.contains("..")
                        && file.indexOf('\0') < 0 && file.length() < 1024 && apk.startsWith("/")
                        && !apk.contains("/../") && apk.indexOf('\0') < 0 && apk.length() < 1024
                        && files.densities[i] >= 0 && files.densities[i] <= 0xffff
                        && (resource == null || resource.length() <= 256)) {
                    kept.add(i);
                }
            }
            if (kept.size() == n) {
                return files;
            }
            final de.diamaneos.sanitizer.decode.AppImageFiles checked =
                    new de.diamaneos.sanitizer.decode.AppImageFiles();
            checked.status = files.status;
            checked.files = new String[kept.size()];
            checked.apkPaths = new String[kept.size()];
            checked.densities = new int[kept.size()];
            checked.resources = files.resources == null ? null : new String[kept.size()];
            for (int j = 0; j < kept.size(); j++) {
                final int i = kept.get(j);
                checked.files[j] = files.files[i];
                checked.apkPaths[j] = files.apkPaths[i];
                checked.densities[j] = files.densities[i];
                if (checked.resources != null) {
                    checked.resources[j] = files.resources[i];
                }
            }
            return checked;
        });
    }

    /** Decodes compressed image bytes held in memory, for example an Icon made with createWithData. */
    public static Bitmap decodeBytes(Context context, byte[] data, int offset, int length, String ownerPackage,
            int ownerUser, int priority, int maxWidth, int maxHeight, long deadline)
            throws UnavailableException, RejectedException {
        checkNotMainThread();
        if (data == null || offset < 0 || length < 0 || offset > data.length - length) {
            throw new RejectedException("bad byte range");
        }
        // Bytes reach the service through a pipe, which it holds in memory: the buffered limit.
        if (length > IDecodeService.MAX_BUFFERED_BYTES) {
            throw new RejectedException("larger than the decode limit");
        }
        final Pumper pumper;
        try {
            pumper = pipe(data, offset, length, ownerPackage, pumpDeadline(deadline));
        } catch (IOException e) {
            throw new UnavailableException("could not pass the bytes", e);
        }
        try (ParcelFileDescriptor read = pumper.read) {
            return decode(context, read, ownerPackage, ownerUser, priority, maxWidth, maxHeight, deadline).bitmap;
        } catch (IOException e) {
            throw new UnavailableException("could not pass the bytes", e);
        } finally {
            pumper.afterCall();
        }
    }

    /** {@link #decodeBytes} with the client's default deadline. */
    public static Bitmap decodeBytes(Context context, byte[] data, int offset, int length, String ownerPackage,
            int ownerUser, int priority, int maxWidth, int maxHeight) throws UnavailableException, RejectedException {
        return decodeBytes(context, data, offset, length, ownerPackage, ownerUser, priority, maxWidth, maxHeight,
                NO_DEADLINE);
    }

    /*
     * OWNER_USER: every call names ownerUser, the Android user whose image it is: the user of the app the image comes
     * from (UserHandle.getUserId of its uid), never the user of the process that asks. The decode service keeps the
     * VM to one user's images at a time and keeps answers per user. The system server and SystemUI ask for the apps
     * of every user: the service refuses their requests without one. An app of one user names its own user. A URI
     * that names its user (user@authority) is that user's image, whatever the caller names.
     */

    // The app the system server decodes for on this thread (DecodeRequest.forUid), -1 for its own decodes.
    /** Verified system-server installed assets, isolated by user and package in the service. */
    public static final int APK_ASSETS = -2;
    /** Verified system-server item exports, isolated by source, item and recipient in the service. */
    public static final int SHORTCUT_ITEM = -3;
    private static final ThreadLocal<Integer> FOR_APP = ThreadLocal.withInitial(() -> -1);

    /** A scope of {@link #forApp}: closing it ends it. */
    public static final class ForApp implements AutoCloseable {
        private final int mPrevious;

        private ForApp(int previous) {
            mPrevious = previous;
        }

        @Override
        public void close() {
            FOR_APP.set(mPrevious);
        }
    }

    /**
     * The system server, when it decodes for an app (its decode gate, a thumbnail another process loads): the decodes
     * this thread makes until the scope is closed name {@code uid} (DecodeRequest.forUid). The decode service decides
     * from that uid whether they are per-user content (the VM holds per-user content of one user at a time) or shared,
     * as it decides it for every other caller from the caller's own uid. Without a scope the system server's
     * decodes are its own: shared, as Android decodes them for every user in one process. Any other process's scope
     * is ignored by the service.
     */
    public static ForApp forApp(int uid) {
        final ForApp scope = new ForApp(FOR_APP.get());
        FOR_APP.set(uid);
        return scope;
    }

    // The user whose image is behind uri: the user the URI names, else ownerUser.
    private static int uriUser(Uri uri, int ownerUser) {
        return android.content.ContentProvider.getUserIdFromUri(uri, ownerUser);
    }

    // The package that supplied the bytes: the caller's word, else the URI's provider. Never the calling process
    // itself, so one app's images do not share a lane with everything the system decodes.
    /**
     * The owner to pass for a request whose app SystemUI does not know (a loader, the clipboard): its lane is the
     * feature's own and the URI's provider, never the lane of an app that posts, nor one shared by every caller.
     */
    public static String unknownOwner(String feature) {
        return "?" + (feature == null ? "" : feature);
    }

    /** True for an owner made by {@link #unknownOwner} (or none): not an app of its own. */
    static boolean isUnknownOwner(String owner) {
        return owner == null || owner.isEmpty() || owner.startsWith("?");
    }

    private static String owner(Context context, Uri uri, String ownerPackage) {
        if (ownerPackage != null && ownerPackage.startsWith("?")) {
            return ownerPackage + ":" + providerOf(context, uri);
        }
        if (ownerPackage != null && !ownerPackage.isEmpty()
                && !ownerPackage.equals(android.app.ActivityThread.currentPackageName())) {
            return ownerPackage;
        }
        return "?:" + providerOf(context, uri);
    }

    // The package of the URI's provider, or "".
    private static String providerOf(Context context, Uri uri) {
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
     * opens until they return. The thread's slot is taken before it starts and held until it ends (RunSlots).
     */
    private static AssetFileDescriptor openImageWithin(ContentResolver resolver, Uri uri, int maxWidth,
            int maxHeight, String owner, long deadline)
            throws FileNotFoundException, UnavailableException, RejectedException {
        final String scheme = uri.getScheme();
        if (!ContentResolver.SCHEME_CONTENT.equals(scheme) && !ContentResolver.SCHEME_ANDROID_RESOURCE.equals(scheme)) {
            // Never opened (see openImage): a verdict, not a passing failure.
            throw new RejectedException("only content and resource URIs are opened");
        }
        final long end = deadline > 0 ? deadline : SystemClock.uptimeMillis() + PUMP_TOTAL_MS;
        if (end <= SystemClock.uptimeMillis()) {
            // No time left: no open is started, so none is left behind and counted against the owner.
            throw new UnavailableException("deadline passed before the open");
        }
        final String lane = owner == null ? "" : owner;
        final RunSlots.Slot slot = sSlots.take(lane);
        if (slot == null) {
            throw new UnavailableException("too many opens or reads under way for " + lane);
        }
        final Opener opener;
        try {
            opener = new Opener(resolver, uri, maxWidth, maxHeight, slot);
            opener.start();
        } catch (Throwable e) {
            slot.end();
            throw new UnavailableException("no thread for the open", e);
        }
        return opener.await(end);
    }

    /** One open of a URI on its own thread, which holds its slot until it ends. */
    private static final class Opener extends Thread {
        private final ContentResolver mResolver;
        private final Uri mUri;
        private final int mMaxWidth;
        private final int mMaxHeight;
        private final RunSlots.Slot mSlot;
        private AssetFileDescriptor mResult; // guarded by this
        private Throwable mError; // guarded by this
        private boolean mDone; // guarded by this
        private boolean mAbandoned; // guarded by this
        // Cancelled at the deadline: a provider that honours it lets the open (and this thread) go.
        private final android.os.CancellationSignal mCancel = new android.os.CancellationSignal();

        Opener(ContentResolver resolver, Uri uri, int maxWidth, int maxHeight, RunSlots.Slot slot) {
            super("VmDecodeOpen");
            setDaemon(true);
            mResolver = resolver;
            mUri = uri;
            mMaxWidth = maxWidth;
            mMaxHeight = maxHeight;
            mSlot = slot;
        }

        @Override
        public void run() {
            try {
                AssetFileDescriptor result = null;
                Throwable error = null;
                try {
                    result = openImage(mResolver, mUri, mMaxWidth, mMaxHeight, mCancel);
                } catch (Throwable t) {
                    error = t;
                }
                final boolean abandoned;
                synchronized (this) {
                    mDone = true;
                    abandoned = mAbandoned;
                    if (!abandoned) {
                        mResult = result;
                        mError = error;
                    }
                    notifyAll();
                }
                if (abandoned) {
                    closeQuietly(result);
                }
            } finally {
                mSlot.end();
            }
        }

        AssetFileDescriptor await(long end) throws FileNotFoundException, UnavailableException {
            boolean interrupted = false;
            final boolean gaveUp;
            final AssetFileDescriptor result;
            final Throwable error;
            synchronized (this) {
                while (!mDone) {
                    final long left = end - SystemClock.uptimeMillis();
                    if (left <= 0) {
                        break;
                    }
                    try {
                        wait(left);
                    } catch (InterruptedException e) {
                        interrupted = true;
                        break;
                    }
                }
                gaveUp = !mDone;
                if (gaveUp) {
                    // Left behind: counted against its owner until the thread ends, its file closed then.
                    mAbandoned = true;
                    mSlot.abandon();
                }
                result = mResult;
                error = mError;
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
            if (gaveUp) {
                mCancel.cancel();
                throw new UnavailableException(interrupted ? "interrupted while the provider opened the image"
                        : "the provider did not open the image in time");
            }
            if (error instanceof FileNotFoundException) {
                throw (FileNotFoundException) error;
            }
            if (error instanceof RuntimeException) {
                throw (RuntimeException) error;
            }
            if (error != null) {
                throw new UnavailableException("could not open the URI", error);
            }
            return result;
        }
    }

    private static AssetFileDescriptor openImage(ContentResolver resolver, Uri uri, int maxWidth, int maxHeight,
            android.os.CancellationSignal cancel) throws FileNotFoundException {
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
            return resolver.openTypedAssetFileDescriptor(uri, "image/*", opts, cancel);
        }
        return resolver.openAssetFileDescriptor(uri, "r", cancel);
    }

    // ownerUser: the Android user whose image this is (see OWNER_USER). Below 0 the service refuses the request.
    private static DecodeRequest request(ParcelFileDescriptor fd, String ownerPackage, int ownerUser, int priority) {
        final DecodeRequest request = new DecodeRequest();
        request.file = fd;
        request.ownerPackage = ownerPackage == null ? "" : ownerPackage;
        request.ownerUser = ownerUser >= 0 ? ownerUser : IDecodeService.NO_USER;
        request.forUid = FOR_APP.get();
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
            case IDecodeService.STATUS_NO_ROOM:
                throw new UnavailableException("no room in the decode service now", true);
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
            throws RemoteException, RejectedException, UnavailableException {
        return ReplyPixels.thumbnail(service, thumbnail, maxWidth, maxHeight);
    }

    private static long pumpDeadline(long callDeadline) {
        final long cap = SystemClock.uptimeMillis() + PUMP_TOTAL_MS;
        return callDeadline > 0 ? Math.min(callDeadline, cap) : cap;
    }

    /** A copy into a pipe on its own thread, which holds its slot until it ends. */
    private static final class Pumper extends Thread {
        final ParcelFileDescriptor read;
        private final RunSlots.Slot mSlot;
        private final Runnable mCopy;

        Pumper(ParcelFileDescriptor read, RunSlots.Slot slot, Runnable copy) {
            super("VmDecodePump");
            setDaemon(true);
            this.read = read;
            mSlot = slot;
            mCopy = copy;
        }

        @Override
        public void run() {
            try {
                mCopy.run();
            } finally {
                mSlot.end();
            }
        }

        /** After the call: a copy still running now (a read that never returns) counts against its owner as stuck. */
        void afterCall() {
            mSlot.abandon();
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
        final RunSlots.Slot slot = sSlots.take(owner);
        if (slot == null) {
            closeQuietly(source);
            throw new IOException("too many opens or reads under way for " + owner);
        }
        ParcelFileDescriptor[] pair = null;
        try {
            pair = ParcelFileDescriptor.createReliablePipe();
            Os.fcntlInt(pair[1].getFileDescriptor(), OsConstants.F_SETFL, OsConstants.O_NONBLOCK);
            final ParcelFileDescriptor write = pair[1];
            final Pumper pumper = new Pumper(pair[0], slot, () -> pump.run(write));
            pumper.start();
            return pumper;
        } catch (ErrnoException | IOException | RuntimeException | OutOfMemoryError e) {
            // The thread never started: its slot is free again.
            slot.end();
            closeQuietly(source);
            if (pair != null) {
                closeQuietly(pair[0]);
                closeQuietly(pair[1]);
            }
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
                // A pipe is held in memory by the service: the buffered limit.
                if (copied + n > IDecodeService.MAX_BUFFERED_BYTES) {
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

    /**
     * A region of the stored image (never turned, as Android's region decoder reads it) decoded in the VM at full
     * detail and scaled down by {@code sampleSize} to at most ceil(width / sampleSize) x ceil(height / sampleSize).
     */
    public static Bitmap decodeRegion(Context context, ParcelFileDescriptor fd, String ownerPackage, int ownerUser,
            int priority, int left, int top, int width, int height, int sampleSize, long deadline)
            throws UnavailableException, RejectedException {
        return decodeRegion(context, fd, ownerPackage, ownerUser, priority, left, top, width, height,
                sampleSize, IDecodeService.PIXEL_FORMAT_RGBA_8888, deadline);
    }

    public static Bitmap decodeRegion(Context context, ParcelFileDescriptor fd, String ownerPackage, int ownerUser,
            int priority, int left, int top, int width, int height, int sampleSize, int pixelFormat, long deadline)
            throws UnavailableException, RejectedException {
        checkNotMainThread();
        if (DecodeChecks.bytesPerPixel(pixelFormat) == 0) throw new RejectedException("pixel format");
        if (left < 0 || top < 0 || width < 1 || height < 1 || sampleSize < 1
                || sampleSize > IDecodeService.MAX_REGION_SAMPLE) {
            throw new RejectedException("bad region");
        }
        final int maxWidth = (int) (((long) width + sampleSize - 1) / sampleSize);
        final int maxHeight = (int) (((long) height + sampleSize - 1) / sampleSize);
        final DecodeRequest request = request(fd, ownerPackage, ownerUser, priority);
        request.pixelFormat = pixelFormat;
        return client(context).call(priority, deadline, request.ownerPackage, capacityLane(request),
                Math.min((long) maxWidth * maxHeight, pixelFormat == 1
                        ? IDecodeService.MAX_HIGH_PRECISION_PIXELS : IDecodeService.MAX_PIXELS)
                        * (DecodeChecks.bytesPerPixel(pixelFormat) + 1), service -> {
            final Thumbnail thumbnail = service.decodeRegion(request, left, top, width, height, sampleSize);
            if (thumbnail == null) {
                throw new RejectedException("no reply");
            }
            checkStatus(thumbnail.status);
            try {
                if (thumbnail.rotation != 0 || thumbnail.mirrored) {
                    throw new RejectedException("a turned region");
                }
                return toDecoded(service, thumbnail, maxWidth, maxHeight).bitmap;
            } finally {
                try {
                    service.release(thumbnail.handle);
                } catch (RemoteException ignored) {
                    // The handle times out in the service anyway.
                }
            }
        });
    }

    /** What the VM's probe reads from an image's header. */
    public static final class Info {
        /** The stored size, before any turn the file asks for. */
        public final int width;
        public final int height;
        /** IDecodeService.FORMAT_*. */
        public final int format;
        public final boolean animated;
        public final int colorSpace;
        public final int bitDepth;
        public final boolean hasAlpha;

        Info(int width, int height, int format, boolean animated) {
            this(width, height, format, animated, IDecodeService.COLOR_SPACE_SRGB);
        }

        Info(int width, int height, int format, boolean animated, int colorSpace) {
            this(width, height, format, animated, colorSpace, 8, true);
        }

        Info(int width, int height, int format, boolean animated, int colorSpace, int bitDepth, boolean hasAlpha) {
            this.bitDepth = bitDepth;
            this.hasAlpha = hasAlpha;
            this.width = width;
            this.height = height;
            this.format = format;
            this.animated = animated;
            this.colorSpace = colorSpace;
        }
    }

    /** {@link #probeSize}, with the format and whether the image is animated. */
    public static Info probeInfo(Context context, ParcelFileDescriptor fd, String ownerPackage, int ownerUser,
            int priority, long deadline) throws UnavailableException, RejectedException {
        checkNotMainThread();
        final DecodeRequest request = request(fd, ownerPackage, ownerUser, priority);
        return client(context).call(priority, deadline, request.ownerPackage, capacityLane(request),
                0, service -> {
            final ImageInfo info = service.probe(request);
            if (info == null) {
                throw new RejectedException("no reply");
            }
            checkStatus(info.status);
            final String broken = DecodeChecks.check(info);
            if (broken != null) {
                throw new RejectedException("probe reply breaks the contract: " + broken);
            }
            try {
                StripeValidator.checkInfo(info.format, info.width, info.height, info.animated);
            } catch (StripeValidator.BadReplyException e) {
                throw new RejectedException(e.getMessage());
            }
            if (!DecodeChecks.isColorSpace(info.colorSpace)) throw new RejectedException("bad colour space");
            return new Info(info.width, info.height, info.format, info.animated, info.colorSpace, info.bitDepth, info.hasAlpha);
        });
    }

    /** An animation decoded in the VM: every frame, one below the other in one tall bitmap. */
    public static final class Animation {
        /** frameCount frames of width x height, frame i at rows i * height to (i + 1) * height. */
        public final Bitmap frames;
        public final int frameCount;
        public final int width;
        public final int height;
        /** Each frame's time in milliseconds. */
        public final int[] durations;
        /** How often it plays, 0: forever. */
        public final int loopCount;

        Animation(Bitmap frames, int frameCount, int width, int height, int[] durations, int loopCount) {
            this.frames = frames;
            this.frameCount = frameCount;
            this.width = width;
            this.height = height;
            this.durations = durations;
            this.loopCount = loopCount;
        }
    }

    /**
     * The frames of a GIF or WebP animation decoded in the VM, each fitting {@code maxWidth} x {@code maxHeight}, all
     * of them together at most IDecodeService.MAX_FRAMES_BYTES (the service makes them smaller to fit). A
     * RejectedException for another format.
     */
    public static Animation decodeFrames(Context context, ParcelFileDescriptor fd, String ownerPackage, int ownerUser,
            int priority, int maxWidth, int maxHeight, long deadline) throws UnavailableException, RejectedException {
        checkNotMainThread();
        if (maxWidth < 1 || maxHeight < 1) {
            throw new RejectedException("bad box " + maxWidth + "x" + maxHeight);
        }
        final DecodeRequest request = request(fd, ownerPackage, ownerUser, priority);
        return client(context).call(priority, deadline, request.ownerPackage, capacityLane(request),
                Math.min((long) maxWidth * maxHeight, IDecodeService.MAX_PIXELS) * 5, service -> {
            final de.diamaneos.sanitizer.decode.Frames f = service.decodeFrames(request, maxWidth, maxHeight);
            if (f == null) {
                throw new RejectedException("no reply");
            }
            checkStatus(f.status);
            try {
                final String broken = DecodeChecks.check(f, maxWidth, maxHeight);
                if (broken != null) {
                    throw new RejectedException("frames break the contract: " + broken);
                }
                return ReplyPixels.frames(service, f);
            } finally {
                if (f.handle != 0) {
                    try {
                        service.release(f.handle);
                    } catch (RemoteException ignored) {
                        // The handle times out in the service anyway.
                    }
                }
            }
        });
    }
}
