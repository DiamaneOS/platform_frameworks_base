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
import android.content.ContentResolver;
import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.graphics.BlendMode;
import android.graphics.Bitmap;
import android.graphics.ColorFilter;
import android.graphics.DecodeGate;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.DrawableWrapper;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.HandlerThread;
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
import android.util.LruCache;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Icons that hold compressed image bytes ({@link Icon#TYPE_DATA}, {@link Icon#TYPE_URI},
 * {@link Icon#TYPE_URI_ADAPTIVE_BITMAP}) loaded in the process of a system app whose apps' images come from clean
 * copies ({@link CleanCopyGate#isActive}, the system server excepted): the bytes, or the descriptor the URI opens with
 * this process's own access as {@code Icon} opens it, are decoded in the protected VM by the system server
 * (VmDecodeProvider, the decode gate's method {@link DecodeGate#METHOD_DECODE}) and the drawable is made from the
 * pixels it sends back. Nothing of them is decoded in this process. Beside the decode gate (which would give a blank
 * picture after its time on the main thread), this fills the picture in once it is made.
 *
 * <p>A load on the main thread or in an incoming binder call waits at most {@link #SHORT_WAIT_MS} (and the main thread
 * at most {@link #MAIN_BUDGET_MS} in any second, none for {@link #NO_WAIT_MS} after a wait ran out): an icon not made
 * by then is a blank drawable that fills itself in on the main thread once its pixels arrive. Other threads wait
 * until the load's end ({@link #LOAD_MS} from its start). An icon that cannot be made is blank (a data icon) or
 * null (a URI icon, as {@code Icon} gives for a URI it cannot open or decode), never a decode here. Made icons are
 * kept for reuse (data icons by their bytes, URI icons for up to a minute).
 *
 * @hide
 */
public final class VmIconLoads {
    private static final String TAG = "VmIcon";

    /** The largest side an icon is decoded to (a larger picture is made smaller, keeping its shape). */
    public static final int MAX_SIDE = 1024;
    /** The longest the system server takes for one icon from the call, waiting in its queue included. */
    public static final long SERVER_MS = 1_500;

    /** The longest a load waits on the main thread or in an incoming binder call. */
    public static final long SHORT_WAIT_MS = 100;
    /** The main thread waits at most this long within any MAIN_WINDOW_MS. */
    public static final long MAIN_BUDGET_MS = 250;
    private static final long MAIN_WINDOW_MS = 1_000;
    /** After a short wait ran out (the VM slow or down), short waits do not wait for this long. */
    public static final long NO_WAIT_MS = 10_000;
    /** A load ends this long after it started: its pixels, or none. */
    public static final long LOAD_MS = 2_500;

    // Bytes the decode service holds in memory at most (IDecodeService.MAX_BUFFERED_BYTES).
    private static final int MAX_DATA_BYTES = 32 * 1024 * 1024;
    // Data icons up to this size are known by a hash of their bytes, larger ones by their array.
    private static final int MAX_HASHED_BYTES = 4 * 1024 * 1024;
    private static final int MAX_URI_CHARS = 2048;
    // Opens of URIs that may run at once, in all and of one authority (file paths count as one): a provider that
    // never answers holds one each, so a hung provider holds at most its own.
    private static final int MAX_OPENS = 16;
    private static final int MAX_OPENS_PER_AUTHORITY = 2;
    // A URI icon is reused for at most two windows of this length (its content can change).
    private static final long URI_WINDOW_MS = 30_000;
    private static final int CACHE_BYTES = 8 * 1024 * 1024;
    private static final int LOG_LINES_PER_MINUTE = 20;

    private static volatile Boolean sForced;
    private static final AtomicInteger sMade = new AtomicInteger();
    private static final AtomicInteger sFailed = new AtomicInteger();

    private static final LruCache<String, Bitmap> sCache = new LruCache<String, Bitmap>(CACHE_BYTES) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getAllocationByteCount();
        }
    };
    // Icons the VM refused, by key without the URI window, and until when they are not sent again: data icons for
    // good (their key is their bytes), URI icons for one window (what a URI gives can change).
    private static final LruCache<String, Long> sRefused = new LruCache<>(256);
    private static final HashMap<String, Pending> sPending = new HashMap<>(); // guarded by itself
    private static int sOpens; // guarded by sPending
    private static final HashMap<String, Integer> sOpensOf = new HashMap<>(); // by authority, guarded by sPending
    private static final AtomicInteger sUnhashed = new AtomicInteger();

    private static final Object sMainLock = new Object();
    private static long sMainWindowStart; // guarded by sMainLock
    private static long sMainSpent; // guarded by sMainLock
    private static volatile long sNoWaitUntil;
    private static int sLogLines; // guarded by sMainLock
    private static long sLogMinute; // guarded by sMainLock

    private static ThreadPoolExecutor sWorkers; // guarded by VmIconLoads.class
    private static Handler sTimer; // guarded by VmIconLoads.class
    private static Handler sMain; // guarded by VmIconLoads.class

    private VmIconLoads() {
    }

    /** True when {@code type} is an icon type of compressed bytes and this process has them decoded in the VM. */
    public static boolean handles(int type) {
        return (type == Icon.TYPE_DATA || type == Icon.TYPE_URI || type == Icon.TYPE_URI_ADAPTIVE_BITMAP)
                && applies();
    }

    /** True when this process has compressed icons decoded in the VM. */
    public static boolean applies() {
        final Boolean forced = sForced;
        if (forced != null) {
            return forced;
        }
        // A system app's process with the clean copy gate, not the system server (which has a gate too).
        return CleanCopyGate.isActive() && ActivityThread.currentActivityThread() != null
                && !ActivityThread.isSystem();
    }

    /**
     * Tests on debuggable builds: this process has icons decoded in the VM (true), as before (false), or as its gate
     * says (null).
     */
    public static void setForcedForTest(@Nullable Boolean forced) {
        if (!android.os.Build.IS_DEBUGGABLE) {
            throw new SecurityException("debuggable builds only");
        }
        sForced = forced;
    }

    /** Tests: how many icons this process got from the VM. */
    public static int madeForTest() {
        return sMade.get();
    }

    /** Tests: how many icon loads ended without pixels. */
    public static int failedForTest() {
        return sFailed.get();
    }

    /** Tests on debuggable builds: forgets every icon made, refused or loading, and the waits that ran out. */
    public static void resetForTest() {
        if (!android.os.Build.IS_DEBUGGABLE) {
            throw new SecurityException("debuggable builds only");
        }
        sCache.evictAll();
        sRefused.evictAll();
        synchronized (sPending) {
            sPending.clear();
        }
        sNoWaitUntil = 0;
        synchronized (sMainLock) {
            sMainSpent = 0;
            sMainWindowStart = 0;
        }
    }

    /**
     * What {@code Icon.loadDrawable} gives for {@code icon} (data or URI) in a process where {@link #applies}: the
     * picture decoded in the VM, a blank drawable that fills itself in later (a short wait ran out), a blank drawable
     * (a data icon that could not be made), or null (a URI icon that could not be opened or made, as before).
     */
    public static @Nullable Drawable load(@NonNull Icon icon, @NonNull Context context) {
        final Resources res = context.getResources();
        final boolean adaptive = icon.getType() == Icon.TYPE_URI_ADAPTIVE_BITMAP;
        final Request request = Request.of(icon, context.getContentResolver(), context.getPackageName());
        if (request == null) {
            return failed(icon, res, false);
        }
        final Bitmap made = sCache.get(request.key);
        if (made != null) {
            return shaped(new BitmapDrawable(res, made), adaptive);
        }
        if (refused(request)) {
            return failed(icon, res, true);
        }
        final boolean shortWait = mustNotWaitLong();
        final Pending pending = start(request);
        final Result result = await(pending, shortWait);
        if (result != null) {
            if (result.bitmap != null) {
                return shaped(new BitmapDrawable(res, result.bitmap), adaptive);
            }
            return failed(icon, res, result.opened);
        }
        if (shortWait) {
            return shaped(new Filling(pending, res), adaptive);
        }
        return failed(icon, res, true);
    }

    /**
     * What {@code ImageView.setImageURI} shows for a content or file URI or a path in a process where
     * {@link #applies}: the picture opened as Icon opens a URI icon and decoded in the VM, loaded as {@link #load}
     * loads it.
     */
    public static @Nullable Drawable loadUri(@NonNull Uri uri, @NonNull Context context) {
        final String scheme = uri.getScheme();
        final Icon icon = ContentResolver.SCHEME_CONTENT.equals(scheme) || ContentResolver.SCHEME_FILE.equals(scheme)
                ? Icon.createWithContentUri(uri) : Icon.createWithFilePath(uri.toString());
        return load(icon, context);
    }

    /**
     * The picture of {@code icon} (data or URI) decoded in the VM, or null when it cannot be had: for code that
     * decoded an Icon's bytes itself. Waits as {@link #load} does, and gives null where it would fill in later.
     */
    public static @Nullable Bitmap bitmap(@NonNull Icon icon, @NonNull ContentResolver resolver) {
        final int type = icon.getType();
        if (type != Icon.TYPE_DATA && type != Icon.TYPE_URI && type != Icon.TYPE_URI_ADAPTIVE_BITMAP) {
            return null;
        }
        final Request request = Request.of(icon, resolver, ActivityThread.currentPackageName());
        if (request == null) {
            return null;
        }
        final Bitmap made = sCache.get(request.key);
        if (made != null || refused(request)) {
            return made;
        }
        final Result result = await(start(request), mustNotWaitLong());
        return result != null ? result.bitmap : null;
    }

    // True while the VM's refusal of what {@code request} names holds.
    private static boolean refused(Request request) {
        final Long until = sRefused.get(request.stableKey);
        return until != null && SystemClock.uptimeMillis() < until;
    }

    // The main thread, or an incoming binder call (another process waits for this thread).
    private static boolean mustNotWaitLong() {
        return Looper.myLooper() == Looper.getMainLooper() || Binder.getCallingPid() != Process.myPid();
    }

    private static Drawable shaped(Drawable drawable, boolean adaptive) {
        return adaptive ? new AdaptiveIconDrawable(null, drawable) : drawable;
    }

    // What a load that got no pixels gives: a blank picture for data, and for a URI null as Icon gives it for a URI
    // it could not open, or for one it could not decode where the platform's flag says so.
    private static Drawable failed(Icon icon, Resources res, boolean opened) {
        sFailed.incrementAndGet();
        if (icon.getType() != Icon.TYPE_DATA
                && (!opened || com.android.graphics.flags.Flags.iconLoadDrawableReturnNullWhenUriDecodeFails())) {
            return null;
        }
        return shaped(blank(res), icon.getType() == Icon.TYPE_URI_ADAPTIVE_BITMAP);
    }

    private static volatile Bitmap sBlank;

    // One transparent pixel.
    static Drawable blank(Resources res) {
        Bitmap blank = sBlank;
        if (blank == null) {
            blank = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).copy(Bitmap.Config.ARGB_8888, false);
            sBlank = blank;
        }
        return new BitmapDrawable(res, blank);
    }

    /** What to load: the bytes or the URI, and the keys it is kept under. */
    private static final class Request {
        final Icon icon;
        final ContentResolver resolver;
        final String caller;
        final String key;
        final String stableKey;

        private Request(Icon icon, ContentResolver resolver, String caller, String key, String stableKey) {
            this.icon = icon;
            this.resolver = resolver;
            this.caller = caller;
            this.key = key;
            this.stableKey = stableKey;
        }

        // The authority a URI icon is opened from ("" for a file or a path), for the opens under way.
        String authority() {
            final Uri uri = icon.getUri();
            final String authority = ContentResolver.SCHEME_CONTENT.equals(uri.getScheme())
                    ? uri.getEncodedAuthority() : null;
            return authority != null ? authority : "";
        }

        // Null when the icon is not loaded at all (too large, a URI too long, a scheme Icon does not open).
        static Request of(Icon icon, ContentResolver resolver, String caller) {
            if (icon.getType() == Icon.TYPE_DATA) {
                final int length = icon.getDataLength();
                if (length <= 0 || length > MAX_DATA_BYTES) {
                    logLimited("icon bytes not sent to the VM: " + length + " bytes");
                    return null;
                }
                final String key = "data:" + dataKey(icon);
                return new Request(icon, resolver, caller, key, key);
            }
            final String uri = icon.getUriString();
            if (uri == null || uri.length() > MAX_URI_CHARS
                    || ContentResolver.SCHEME_ANDROID_RESOURCE.equals(icon.getUri().getScheme())) {
                return null;
            }
            final String stable = "uri:" + resolver.getUserId() + ':' + icon.getType() + ':' + uri;
            final long window = SystemClock.uptimeMillis() / URI_WINDOW_MS;
            return new Request(icon, resolver, caller, stable + ":t" + window, stable);
        }

        private static String dataKey(Icon icon) {
            final int length = icon.getDataLength();
            if (length <= MAX_HASHED_BYTES) {
                try {
                    final MessageDigest digest = MessageDigest.getInstance("SHA-256");
                    digest.update(icon.getDataBytes(), icon.getDataOffset(), length);
                    final StringBuilder hex = new StringBuilder();
                    for (byte b : digest.digest()) {
                        hex.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
                    }
                    return hex.toString();
                } catch (NoSuchAlgorithmException e) {
                    // Known by its array below.
                }
            }
            // Larger icons are not hashed on the calling thread: a key of their own, never shared or found again.
            return "unhashed:" + sUnhashed.incrementAndGet();
        }
    }

    /** The outcome of a load. */
    private static final class Result {
        final Bitmap bitmap; // null when there is none
        final boolean opened; // false: a URI that could not be opened

        Result(Bitmap bitmap, boolean opened) {
            this.bitmap = bitmap;
            this.opened = opened;
        }
    }

    /** A load under way, shared by every caller of the same key, and what fills in once it ends. */
    static final class Pending {
        final String key;
        final String stableKey;
        final long end;
        final CancellationSignal cancel = new CancellationSignal();
        private Result mResult; // guarded by this
        private final List<WeakReference<Filling>> mFillings = new ArrayList<>(); // guarded by this

        Pending(Request request) {
            key = request.key;
            stableKey = request.stableKey;
            end = SystemClock.uptimeMillis() + LOAD_MS;
        }

        synchronized Result result() {
            return mResult;
        }

        synchronized Result await(long until) {
            while (mResult == null) {
                final long left = until - SystemClock.uptimeMillis();
                if (left <= 0) {
                    return null;
                }
                try {
                    wait(left);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return mResult;
                }
            }
            return mResult;
        }

        // A filling drawable to fill in once the load ends. False when it ended already.
        synchronized boolean listen(Filling filling) {
            if (mResult != null) {
                return false;
            }
            mFillings.add(new WeakReference<>(filling));
            return true;
        }

        void finish(Bitmap bitmap, boolean opened, boolean refused, String why) {
            final List<Filling> fillings = new ArrayList<>();
            synchronized (this) {
                if (mResult != null) {
                    return;
                }
                // Counted, kept and refused before a waiting caller wakes up, so what it reads next (the count, the
                // cache, the refusal) already holds this load.
                if (bitmap != null) {
                    sMade.incrementAndGet();
                    sCache.put(key, bitmap);
                } else if (refused) {
                    sRefused.put(stableKey, stableKey.startsWith("uri:")
                            ? SystemClock.uptimeMillis() + URI_WINDOW_MS : Long.MAX_VALUE);
                }
                mResult = new Result(bitmap, opened);
                notifyAll();
                for (WeakReference<Filling> ref : mFillings) {
                    final Filling f = ref.get();
                    if (f != null) {
                        fillings.add(f);
                    }
                }
                mFillings.clear();
            }
            synchronized (sPending) {
                if (sPending.get(key) == this) {
                    sPending.remove(key);
                }
            }
            if (bitmap == null) {
                logLimited("icon not made in the VM, blank shown: " + why);
            }
            if (!fillings.isEmpty() && bitmap != null) {
                main().post(() -> {
                    for (Filling f : fillings) {
                        f.fill(bitmap);
                    }
                });
            }
        }
    }

    // The load of {@code request}, started now unless one with its key is under way.
    private static Pending start(Request request) {
        final Pending pending;
        synchronized (sPending) {
            final Pending running = sPending.get(request.key);
            if (running != null && SystemClock.uptimeMillis() < running.end) {
                return running;
            }
            pending = new Pending(request);
            sPending.put(request.key, pending);
        }
        // Whatever happens, the load ends at its end.
        timer().postAtTime(() -> {
            pending.cancel.cancel();
            pending.finish(null, true, false, "out of time");
        }, pending, pending.end);
        if (request.icon.getType() == Icon.TYPE_DATA) {
            try {
                workers().execute(() -> send(pending, request, null));
            } catch (RejectedExecutionException e) {
                pending.finish(null, true, false, "too many icons waiting");
            }
        } else {
            final String authority = request.authority();
            synchronized (sPending) {
                final int ofAuthority = sOpensOf.getOrDefault(authority, 0);
                if (sOpens >= MAX_OPENS || ofAuthority >= MAX_OPENS_PER_AUTHORITY) {
                    pending.finish(null, true, false, "too many opens under way");
                    return pending;
                }
                sOpens++;
                sOpensOf.put(authority, ofAuthority + 1);
            }
            final Thread opener = new Thread(() -> {
                try {
                    open(pending, request);
                } finally {
                    synchronized (sPending) {
                        sOpens--;
                        final int left = sOpensOf.getOrDefault(authority, 1) - 1;
                        if (left <= 0) {
                            sOpensOf.remove(authority);
                        } else {
                            sOpensOf.put(authority, left);
                        }
                    }
                }
            }, "VmIconOpen");
            opener.setDaemon(true);
            opener.start();
        }
        return pending;
    }

    // Opens the URI with this process's own access, as Icon opens it, and sends what it gives.
    private static void open(Pending pending, Request request) {
        final Uri uri = request.icon.getUri();
        final String scheme = uri.getScheme();
        AssetFileDescriptor afd = null;
        try {
            if (ContentResolver.SCHEME_CONTENT.equals(scheme) || ContentResolver.SCHEME_FILE.equals(scheme)) {
                afd = request.resolver.openAssetFileDescriptor(uri, "r", pending.cancel);
            } else {
                afd = new AssetFileDescriptor(ParcelFileDescriptor.open(new File(request.icon.getUriString()),
                        ParcelFileDescriptor.MODE_READ_ONLY), 0, AssetFileDescriptor.UNKNOWN_LENGTH);
            }
        } catch (FileNotFoundException | RuntimeException e) {
            pending.finish(null, false, false, "the URI could not be opened: " + e.getClass().getSimpleName());
            return;
        }
        if (afd == null) {
            pending.finish(null, false, false, "the URI gave nothing");
            return;
        }
        if (pending.result() != null) {
            closeQuietly(afd);
            return; // Opened too late.
        }
        try {
            send(pending, request, afd);
        } finally {
            closeQuietly(afd);
        }
    }

    // Hands the bytes (data, or what the URI opened) to the system server with a callback for the pixels.
    private static void send(Pending pending, Request request, AssetFileDescriptor afd) {
        if (pending.result() != null) {
            return;
        }
        final ParcelFileDescriptor bytes;
        try {
            bytes = afd == null
                    ? memoryFile(request.icon.getDataBytes(), request.icon.getDataOffset(),
                            request.icon.getDataLength())
                    : handOver(afd);
        } catch (IOException e) {
            pending.finish(null, true, false, "bytes not handed over: " + e.getMessage());
            return;
        }
        try (ParcelFileDescriptor owned = bytes) {
            final Bundle extras = new Bundle();
            extras.putParcelable(DecodeGate.EXTRA_FD, bytes);
            extras.putInt(DecodeGate.EXTRA_WIDTH, MAX_SIDE);
            extras.putInt(DecodeGate.EXTRA_HEIGHT, MAX_SIDE);
            extras.putLong(DecodeGate.EXTRA_TIME, SERVER_MS);
            if (request.caller != null) {
                extras.putString(DecodeGate.EXTRA_CALLER, request.caller);
            }
            if (afd != null) {
                extras.putString(DecodeGate.EXTRA_AUTHORITY, request.authority());
            }
            extras.putBinder(DecodeGate.EXTRA_CALLBACK, new IRemoteCallback.Stub() {
                @Override
                public void sendResult(Bundle reply) {
                    if (!DecodeGate.fromSystemServer()) {
                        return;
                    }
                    final Bitmap bitmap = reply == null ? null
                            : reply.getParcelable(DecodeGate.EXTRA_BITMAP, Bitmap.class);
                    if (bitmap != null && (bitmap.getWidth() > MAX_SIDE || bitmap.getHeight() > MAX_SIDE)) {
                        pending.finish(null, true, false, "the reply is larger than asked for");
                        return;
                    }
                    pending.finish(bitmap, true, reply != null && reply.getBoolean(DecodeGate.EXTRA_REFUSED),
                            reply == null ? "no reply" : reply.getString(DecodeGate.EXTRA_ERROR));
                }
            }.asBinder());
            final Bundle reply;
            try {
                reply = request.resolver.call(DecodeGate.URI, DecodeGate.METHOD_DECODE, null, extras);
            } catch (RuntimeException e) {
                pending.finish(null, true, false, "the system server did not take it: " + e);
                return;
            }
            if (reply == null || !reply.getBoolean(DecodeGate.EXTRA_TAKEN)) {
                pending.finish(null, true, false,
                        reply == null ? "the system server did not take it" : reply.getString(DecodeGate.EXTRA_ERROR));
            }
        } catch (IOException e) {
            // Closing this process's copy of the descriptor failed: the system server has its own.
        }
    }

    /**
     * The bytes behind {@code asset} as the decode service takes them: a whole file or a stream as it is (the service
     * reads it with its own deadline and limit), a part of a regular file copied into a memory file here.
     */
    private static ParcelFileDescriptor handOver(AssetFileDescriptor asset) throws IOException {
        final ParcelFileDescriptor pfd = asset.getParcelFileDescriptor();
        final long offset = asset.getStartOffset();
        long length = asset.getDeclaredLength();
        final StructStat st;
        try {
            st = Os.fstat(pfd.getFileDescriptor());
        } catch (ErrnoException e) {
            throw new IOException("not readable", e);
        }
        if (!OsConstants.S_ISREG(st.st_mode)) {
            if (offset != 0) {
                throw new IOException("an offset into a stream");
            }
            return pfd.dup();
        }
        if (offset == 0 && (length < 0 || length == st.st_size)) {
            return pfd.dup();
        }
        if (length < 0) {
            length = st.st_size - offset;
        }
        if (offset < 0 || length <= 0 || length > MAX_DATA_BYTES || offset > st.st_size - length) {
            throw new IOException("a part out of range");
        }
        final byte[] part = new byte[(int) length];
        int done = 0;
        try {
            while (done < length) {
                final int n = Os.pread(pfd.getFileDescriptor(), part, done, (int) length - done, offset + done);
                if (n <= 0) {
                    throw new IOException("shorter than declared");
                }
                done += n;
            }
        } catch (ErrnoException e) {
            throw new IOException("not read", e);
        }
        return memoryFile(part, 0, part.length);
    }

    // The bytes in a memory file, read from its start.
    private static ParcelFileDescriptor memoryFile(byte[] data, int offset, int length) throws IOException {
        if (data == null || offset < 0 || length <= 0 || offset > data.length - length) {
            throw new IOException("bad byte range");
        }
        FileDescriptor memory = null;
        try {
            memory = Os.memfd_create("icon", OsConstants.MFD_CLOEXEC);
            int written = 0;
            while (written < length) {
                written += Os.write(memory, data, offset + written, length - written);
            }
            Os.lseek(memory, 0, OsConstants.SEEK_SET);
            return ParcelFileDescriptor.dup(memory);
        } catch (ErrnoException e) {
            throw new IOException("no memory file", e);
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

    // Waits for {@code pending}: a short wait (within the main thread's budget) or until the load's end.
    private static Result await(Pending pending, boolean shortWait) {
        if (!shortWait) {
            return pending.await(pending.end);
        }
        final boolean main = Looper.myLooper() == Looper.getMainLooper();
        final long now = SystemClock.uptimeMillis();
        long wait = now < sNoWaitUntil ? 0 : SHORT_WAIT_MS;
        if (main && wait > 0) {
            synchronized (sMainLock) {
                if (now - sMainWindowStart >= MAIN_WINDOW_MS) {
                    sMainWindowStart = now;
                    sMainSpent = 0;
                }
                wait = Math.max(0, Math.min(wait, MAIN_BUDGET_MS - sMainSpent));
            }
        }
        final Result result = wait > 0 ? pending.await(now + wait) : pending.result();
        final long waited = SystemClock.uptimeMillis() - now;
        if (main) {
            synchronized (sMainLock) {
                sMainSpent += waited;
            }
        }
        if (result == null && wait == SHORT_WAIT_MS) {
            sNoWaitUntil = SystemClock.uptimeMillis() + NO_WAIT_MS;
        }
        return result;
    }

    /**
     * A blank picture until the load it waits for ends with pixels, then those pixels, filled in on the main thread.
     * Tint, colour filter and alpha set meanwhile are kept for the picture.
     */
    static final class Filling extends DrawableWrapper {
        private final Pending mPending;
        private final Resources mRes;
        private boolean mFilled;
        private ColorStateList mTint;
        private boolean mHasTint;
        private BlendMode mBlendMode;
        private ColorFilter mColorFilter;
        private boolean mHasColorFilter;
        private int mAlpha = -1;

        Filling(Pending pending, Resources res) {
            super(blank(res));
            mPending = pending;
            mRes = res;
            if (!pending.listen(this)) {
                final Result result = pending.result();
                if (result != null && result.bitmap != null) {
                    fill(result.bitmap);
                }
            }
        }

        void fill(Bitmap bitmap) {
            if (mFilled) {
                return;
            }
            mFilled = true;
            final Drawable picture = new BitmapDrawable(mRes, bitmap);
            if (mHasTint) {
                picture.setTintList(mTint);
            }
            if (mBlendMode != null) {
                picture.setTintBlendMode(mBlendMode);
            }
            if (mHasColorFilter) {
                picture.setColorFilter(mColorFilter);
            }
            if (mAlpha >= 0) {
                picture.setAlpha(mAlpha);
            }
            setDrawable(picture);
            // A view sized to the blank picture takes the new size.
            if (getCallback() instanceof android.view.View) {
                ((android.view.View) getCallback()).requestLayout();
            }
        }

        @Override
        public void setTintList(ColorStateList tint) {
            mTint = tint;
            mHasTint = true;
            super.setTintList(tint);
        }

        @Override
        public void setTintBlendMode(BlendMode blendMode) {
            mBlendMode = blendMode;
            super.setTintBlendMode(blendMode);
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
            mColorFilter = colorFilter;
            mHasColorFilter = true;
            super.setColorFilter(colorFilter);
        }

        @Override
        public void setAlpha(int alpha) {
            mAlpha = alpha;
            super.setAlpha(alpha);
        }

        @Override
        public ConstantState getConstantState() {
            return new FillingState(mPending, mRes);
        }
    }

    // A copy of a filling drawable fills in from the same load.
    private static final class FillingState extends Drawable.ConstantState {
        private final Pending mPending;
        private final Resources mRes;

        FillingState(Pending pending, Resources res) {
            mPending = pending;
            mRes = res;
        }

        @Override
        public Drawable newDrawable() {
            return newDrawable(mRes);
        }

        @Override
        public Drawable newDrawable(Resources res) {
            return new Filling(mPending, res != null ? res : mRes);
        }

        @Override
        public int getChangingConfigurations() {
            return 0;
        }
    }

    private static synchronized ThreadPoolExecutor workers() {
        if (sWorkers == null) {
            sWorkers = new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<>(64), r -> {
                final Thread t = new Thread(r, "VmIconSend");
                t.setDaemon(true);
                return t;
            });
            sWorkers.allowCoreThreadTimeOut(true);
        }
        return sWorkers;
    }

    private static synchronized Handler timer() {
        if (sTimer == null) {
            final HandlerThread thread = new HandlerThread("VmIconTimer", Process.THREAD_PRIORITY_BACKGROUND);
            thread.start();
            sTimer = new Handler(thread.getLooper());
        }
        return sTimer;
    }

    private static synchronized Handler main() {
        if (sMain == null) {
            sMain = new Handler(Looper.getMainLooper());
        }
        return sMain;
    }

    private static void logLimited(String line) {
        final long minute = SystemClock.uptimeMillis() / 60_000;
        synchronized (sMainLock) {
            if (minute != sLogMinute) {
                sLogMinute = minute;
                sLogLines = 0;
            }
            if (++sLogLines > LOG_LINES_PER_MINUTE) {
                return;
            }
        }
        Log.w(TAG, line);
    }

    private static void closeQuietly(AssetFileDescriptor afd) {
        try {
            afd.close();
        } catch (IOException ignored) {
            // Closed.
        }
    }
}
