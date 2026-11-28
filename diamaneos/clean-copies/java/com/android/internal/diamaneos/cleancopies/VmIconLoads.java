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
import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.graphics.BlendMode;
import android.graphics.Bitmap;
import android.graphics.ColorFilter;
import android.graphics.DecodeGate;
import android.graphics.GateRefill;
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
import android.os.UserHandle;
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
 * by then is a blank drawable that fills itself in on its view thread once its pixels arrive. Other threads wait
 * until the load's end ({@link #LOAD_MS} from its start). A list that draws many icons on its main thread uses
 * {@link #loadAsUserNoWait}: no wait at all, each icon fills in. An icon that cannot be made is blank (a data icon) or
 * null (a URI icon, as {@code Icon} gives for a URI it cannot open or decode), never a decode here. Made icons are
 * kept for reuse (data icons by their bytes, URI icons for up to a minute). URI opens over the limits wait in a queue
 * for a free one, within the load's time.
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
    // URI loads waiting for a free open, started in order as opens end. Past this many a load fails at once.
    private static final int MAX_QUEUED_OPENS = 128;
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
    // URI loads waiting for an open: {Pending, Request}. Guarded by sPending.
    private static final ArrayList<Object[]> sQueuedOpens = new ArrayList<>();
    private static final AtomicInteger sUnhashed = new AtomicInteger();
    private static volatile Runnable sBeforeSendForTest;

    private static final Object sMainLock = new Object();
    private static long sMainWindowStart; // guarded by sMainLock
    private static long sMainSpent; // guarded by sMainLock
    private static volatile long sNoWaitUntil;
    private static int sLogLines; // guarded by sMainLock
    private static long sLogMinute; // guarded by sMainLock

    private static ThreadPoolExecutor sWorkers; // guarded by VmIconLoads.class
    private static Handler sTimer; // guarded by VmIconLoads.class
    private static Handler sMain; // guarded by VmIconLoads.class

    private static final ImageAttempts<Pending> sAttempts = new ImageAttempts<>();

    private static Pending newest(Pending pending) {
        final Pending latest = sAttempts.latest(pending.stableKey);
        return latest != null ? latest : pending;
    }

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
        return CleanCopyGate.isActive() && ActivityThread.currentActivityThread() != null;
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

    /** Tests on debuggable builds: runs on the sending thread right before a load's bytes are handed over. */
    public static void setBeforeSendForTest(@Nullable Runnable beforeSend) {
        if (!android.os.Build.IS_DEBUGGABLE) {
            throw new SecurityException("debuggable builds only");
        }
        sBeforeSendForTest = beforeSend;
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
            sQueuedOpens.clear();
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
        try (DecodeGate.Wait wait = DecodeGate.waiting()) {
            return load(icon, context, wait);
        }
    }

    private static Drawable load(Icon icon, Context context, DecodeGate.Wait wait) {
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
        final Result result = await(pending, shortWait, wait);
        if (result != null) {
            if (result.bitmap != null) {
                return shaped(new BitmapDrawable(res, result.bitmap), adaptive);
            }
            if (result.forNow()) {
                return shaped(new Filling(pending, res), adaptive);
            }
            return failed(icon, res, result.opened);
        }
        return shaped(new Filling(pending, res), adaptive);
    }

    /**
     * {@code Icon.loadDrawableAsUser} for a list drawn on the main thread: a data or URI icon in a process where
     * {@link #applies} is never waited for. It is the picture made before, or a blank drawable that fills itself in
     * once the VM has made it. An app resource icon whose clean copy is not made yet is blank too and fills in once
     * the copies are made. Every other icon loads as {@code Icon} loads it.
     */
    public static @Nullable Drawable loadAsUserNoWait(@NonNull Icon icon, @NonNull Context context, int userId) {
        if (userId < 0) {
            return failed(icon, context.getResources(), false);
        }
        if (!handles(icon.getType())) {
            final int missed = CleanCopyGate.misses();
            final Drawable drawable = CleanCopyGate.iconLoad(userId, () -> icon.loadDrawableAsUser(context, userId));
            if (icon.getType() != Icon.TYPE_RESOURCE || CleanCopyGate.misses() == missed || !applies()) {
                return drawable;
            }
            final String resPackage = icon.getResPackage() == null || icon.getResPackage().isEmpty()
                    ? context.getPackageName() : icon.getResPackage();
            final Filling filling = new Filling(null, context.getResources(), () -> {
                final int before = CleanCopyGate.misses();
                final Drawable again = CleanCopyGate.iconLoad(userId, () -> icon.loadDrawableAsUser(context, userId));
                return CleanCopyGate.misses() == before ? again : null;
            });
            CleanCopyGate.whenMade(filling, resPackage, userId);
            filling.onMade(resPackage, userId); // Recheck after subscribing to a completion that raced the miss.
            return filling;
        }
        final Resources res = context.getResources();
        final boolean adaptive = icon.getType() == Icon.TYPE_URI_ADAPTIVE_BITMAP;
        final Context userContext;
        try {
            userContext = context.getUserId() == userId ? context
                    : context.createContextAsUser(UserHandle.of(userId), 0);
        } catch (RuntimeException e) {
            return failed(icon, res, false);
        }
        final Request request = Request.of(icon, userContext.getContentResolver(), context.getPackageName());
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
        final Pending pending = start(request);
        final Result result = pending.result();
        if (result != null && result.bitmap == null && !result.forNow()) {
            return failed(icon, res, result.opened);
        }
        return shaped(new Filling(pending, res), adaptive);
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
        try (DecodeGate.Wait wait = DecodeGate.waiting()) {
            return bitmap(icon, resolver, wait);
        }
    }

    private static Bitmap bitmap(Icon icon, ContentResolver resolver, DecodeGate.Wait wait) {
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
        final Result result = await(start(request), mustNotWaitLong(), wait);
        return result != null ? result.bitmap : null;
    }

    // True while the VM's refusal of what {@code request} names holds.
    private static boolean refused(Request request) {
        final Long until = sRefused.get(request.key);
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

    /**
     * What to load: the bytes or the URI, whose image it is, and the keys it is kept under. The owner is the user the
     * icon is loaded for (the resolver's), or the user a URI names: the system server takes it only from a process
     * that may act for that user. It is part of every key, so one user's icon is never another's. A data icon known
     * by a hash of its bytes carries the copy that was hashed, and that copy is what is sent: the icon's own array can
     * change meanwhile.
     */
    private static long userSerial(int user) {
        final Context app = android.app.AppGlobals.getInitialApplication();
        return app == null ? -1 : CleanCopyClient.userSerial(app, user);
    }

    private static final class Request {
        final Icon icon;
        final ContentResolver resolver;
        final String caller;
        final int ownerUser;
        final String key;
        final String stableKey;
        final IconUsers users;
        GateOwner gateUser;
        volatile byte[] data; // the hashed copy of a data icon's bytes, else null

        private Request(Icon icon, ContentResolver resolver, String caller, int ownerUser, String key,
                String stableKey, byte[] data, IconUsers users) {
            this.icon = icon;
            this.resolver = resolver;
            this.caller = caller;
            this.ownerUser = ownerUser;
            this.key = key;
            this.stableKey = stableKey;
            this.data = data;
            this.users = users;
            gateUser = DecodeGate.iconUser(ownerUser);
        }

        boolean current() {
            return users.current(VmIconLoads::userSerial);
        }

        // The same icon asked for again: a URI's key is that of the current window (its content can change).
        static Request again(Request r) {
            if (r.icon.getType() == Icon.TYPE_DATA) {
                return r;
            }
            final long window = SystemClock.uptimeMillis() / URI_WINDOW_MS;
            final Request next = new Request(r.icon, r.resolver, r.caller, r.ownerUser,
                    r.stableKey + ":t" + window, r.stableKey, null, r.users);
            next.gateUser = r.gateUser;
            return next;
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
                final GateOwner captured = DecodeGate.iconDataUser(resolver.getUserId());
                final int user = captured.user;
                // Copied once, and the copy hashed and sent: a key always names the bytes sent under it.
                byte[] data = null;
                if (length <= Math.min(MAX_HASHED_BYTES, 1 << 20)) {
                    try (android.graphics.DecodeMemory.Reservation memory = android.graphics.DecodeMemory.reserve(length)) {
                        data = java.util.Arrays.copyOfRange(icon.getDataBytes(), icon.getDataOffset(), icon.getDataOffset() + length);
                        android.graphics.DecodeMemory.keepBytes(data, memory);
                    } catch (android.graphics.DecodeMemory.NoRoomException | OutOfMemoryError ignored) {
                        data = null;
                        // The worker retries a unique request when the caller cannot stage it now.
                    }
                }
                final IconUsers users = new IconUsers(user, resolver.getUserId(), VmIconLoads::userSerial);
                if (!users.current(VmIconLoads::userSerial)) return null;
                final String key = "data:" + captured.key() + ':' + user + ':' + users.key() + ':' + dataKey(data);
                return new Request(icon, resolver, caller, user, key, key, data, users);
            }
            final String uri = icon.getUriString();
            if (uri == null || uri.length() > MAX_URI_CHARS
                    || ContentResolver.SCHEME_ANDROID_RESOURCE.equals(icon.getUri().getScheme())) {
                return null;
            }
            final int user = ContentProvider.getUserIdFromUri(icon.getUri(), resolver.getUserId());
            final IconUsers users = new IconUsers(user, resolver.getUserId(), VmIconLoads::userSerial);
            if (!users.current(VmIconLoads::userSerial)) return null;
            final String stable = "uri:" + DecodeGate.iconUser(user).key() + ':' + resolver.getUserId() + ':' + user + ':' + users.key() + ':'
                    + icon.getType() + ':' + uri;
            final long window = SystemClock.uptimeMillis() / URI_WINDOW_MS;
            return new Request(icon, resolver, caller, user, stable + ":t" + window, stable, null, users);
        }

        synchronized byte[] snapshot() throws android.graphics.DecodeMemory.NoRoomException {
            if (data == null) {
                try (android.graphics.DecodeMemory.Reservation memory = android.graphics.DecodeMemory.reserve(icon.getDataLength())) {
                    final byte[] copy = java.util.Arrays.copyOfRange(icon.getDataBytes(), icon.getDataOffset(),
                            icon.getDataOffset() + icon.getDataLength());
                    android.graphics.DecodeMemory.keepBytes(copy, memory);
                    data = copy;
                }
            }
            return data;
        }

        // The hash of a data icon's copied bytes, or a key of its own for one not copied (too large to hash here).
        private static String dataKey(byte[] data) {
            if (data != null) {
                try {
                    final MessageDigest digest = MessageDigest.getInstance("SHA-256");
                    digest.update(data);
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
        final boolean refused; // the VM refused the bytes themselves

        Result(Bitmap bitmap, boolean opened, boolean refused) {
            this.bitmap = bitmap;
            this.opened = opened;
            this.refused = refused;
        }

        // No pixels for a reason of the moment (out of time, the VM busy or down): the same icon may be had later.
        boolean forNow() {
            return bitmap == null && !refused;
        }
    }

    /**
     * A load under way, shared by every caller of the same key, and what fills in once it ends. A load that ended
     * without pixels for a reason of the moment keeps its filling drawables: pixels that still come for it (the system
     * server's late answer) fill them in, and each of them asks again later ({@link Filling#leftOut}).
     */
    static final class Pending {
        final Request request;
        final String key;
        final String stableKey;
        final long end;
        final CancellationSignal cancel = new CancellationSignal();
        ImageAttempts.Ticket<Pending> ticket;
        private Result mResult; // guarded by this
        private boolean mResolved; // no reply remains outstanding, guarded by this
        private final List<WeakReference<Filling>> mFillings = new ArrayList<>(); // guarded by this

        Pending(Request request) {
            this.request = request;
            key = request.key;
            stableKey = request.stableKey;
            end = SystemClock.uptimeMillis() + LOAD_MS;
        }

        synchronized Result result() {
            return request.current() ? mResult : new Result(null, false, true);
        }

        // True while pixels may still come for it: under way, or ended for the moment (out of time, the VM busy),
        // so a late open is still sent and its pixels fill in what waits for them.
        boolean wantsPixels() {
            if (!request.current() || !sAttempts.current(ticket)) return false;
            synchronized (this) { return !mResolved; }
        }

        synchronized Result await(long until) {
            while (mResult == null && request.current()) {
                final long left = until - SystemClock.uptimeMillis();
                if (left <= 0) {
                    return null;
                }
                try {
                    wait(left);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return result();
                }
            }
            return result();
        }

        // A filling drawable to fill in once the load ends. False when it ended already (with pixels or for good).
        boolean listen(Filling filling) {
            if (!request.current()) return false;
            synchronized (sAttempts) {
                if (!sAttempts.current(ticket)) {
                    final Pending next = newest(this);
                    if (next == this) return false;
                    main().post(() -> filling.follow(this, next));
                    return true;
                }
                synchronized (this) {
                    if (mResult != null && !mResult.forNow()) return false;
                    mFillings.add(new WeakReference<>(filling));
                    return mResult == null;
                }
            }
        }

        void followedBy(Pending next) {
            sPending.remove(key, this);
            timer().removeCallbacksAndMessages(this);
            final List<Filling> followers = new ArrayList<>();
            synchronized (this) {
                for (WeakReference<Filling> ref : mFillings) {
                    final Filling filling = ref.get();
                    if (filling != null) followers.add(filling);
                }
                mFillings.clear();
                notifyAll();
            }
            main().post(() -> { for (Filling filling : followers) filling.follow(this, next); });
        }

        void finish(Bitmap bitmap, boolean opened, boolean refused, String why) {
            finish(bitmap, opened, refused, why, false);
        }

        void finish(Bitmap bitmap, boolean opened, boolean refused, String why, boolean awaitingReply) {
            final Bitmap admitted;
            final boolean retired;
            final List<Filling> fillings = new ArrayList<>();
            final Result result;
            synchronized (sAttempts) {
            if (!sAttempts.current(ticket)) return;
            synchronized (this) {
                retired = !request.current();
                admitted = retired ? null : bitmap;
                if (mResolved || (mResult != null && awaitingReply)) return;
                mResolved = !awaitingReply || retired;
                // Counted, kept and refused before a waiting caller wakes up, so what it reads next (the count, the
                // cache, the refusal) already holds this load.
                if (admitted != null) {
                    sMade.incrementAndGet();
                    sCache.put(key, admitted);
                } else if (refused && !retired) {
                    sRefused.put(key, key.startsWith("uri:")
                            ? SystemClock.uptimeMillis() + URI_WINDOW_MS : Long.MAX_VALUE);
                }
                result = new Result(admitted, opened && !retired, refused || retired);
                mResult = result;
                notifyAll();
                for (WeakReference<Filling> ref : mFillings) {
                    final Filling f = ref.get();
                    if (f != null) {
                        fillings.add(f);
                    }
                }
                ticket.delivered = false;
            }
            }
            synchronized (sPending) {
                if (sPending.get(key) == this) {
                    sPending.remove(key);
                }
            }
            if (!result.forNow()) timer().removeCallbacksAndMessages(this);
            if (admitted == null) {
                logLimited("icon not made in the VM, blank shown: " + why);
            }
            main().post(() -> {
                for (Filling f : fillings) {
                    if (admitted != null && request.current()) f.fill(this, admitted);
                    else if (result.refused) f.refused(this);
                    else if (result.forNow()) f.leftOut(this);
                }
                synchronized (sAttempts) {
                    synchronized (this) {
                        if (mResult == result) {
                            if (!result.forNow()) mFillings.clear();
                            if (mResolved && sAttempts.current(ticket)) ticket.delivered = true;
                        }
                    }
                }
            });
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
            synchronized (sAttempts) {
                final Pending old = newest(pending);
                pending.ticket = sAttempts.begin(request.stableKey, pending);
                if (pending.ticket == null) {
                    pending.mResult = new Result(null, false, false);
                    return pending;
                }
                if (old != pending) old.followedBy(pending);
            }
            sPending.put(request.key, pending);
        }
        // Whatever happens, the load ends at its end.
        timer().postAtTime(() -> {
            synchronized (sPending) {
                final boolean wasQueued = sQueuedOpens.removeIf(entry -> entry[0] == pending);
                pending.finish(null, true, false, "out of time", !wasQueued);
            }
            pending.cancel.cancel();
        }, pending, pending.end);
        if (request.icon.getType() == Icon.TYPE_DATA) {
            try {
                workers().execute(() -> send(pending, request, null));
            } catch (RejectedExecutionException e) {
                pending.finish(null, true, false, "too many icons waiting");
            }
        } else {
            synchronized (sPending) {
                if (pending.result() != null) {
                    pending.finish(null, false, false, "no longer needed");
                    return pending;
                }
                if (!mayOpen(request.authority())) {
                    if (sQueuedOpens.size() >= MAX_QUEUED_OPENS) {
                        pending.finish(null, true, false, "too many opens waiting");
                    } else {
                        sQueuedOpens.add(new Object[] {pending, request});
                    }
                    return pending;
                }
                countOpen(request.authority(), 1);
            }
            startOpener(pending, request);
        }
        return pending;
    }

    // True when an open of authority may start now. Guarded by sPending.
    private static boolean mayOpen(String authority) {
        return sOpens < MAX_OPENS && sOpensOf.getOrDefault(authority, 0) < MAX_OPENS_PER_AUTHORITY;
    }

    // Guarded by sPending.
    private static void countOpen(String authority, int delta) {
        sOpens += delta;
        final int now = sOpensOf.getOrDefault(authority, 0) + delta;
        if (now <= 0) {
            sOpensOf.remove(authority);
        } else {
            sOpensOf.put(authority, now);
        }
    }

    private static void startOpener(Pending pending, Request request) {
        final Thread opener = new Thread(() -> {
            try {
                open(pending, request);
            } finally {
                final List<Object[]> next = new ArrayList<>();
                synchronized (sPending) {
                    countOpen(request.authority(), -1);
                    // Queued loads that may open now, in order. Those already ended (out of time) are dropped.
                    for (int i = 0; i < sQueuedOpens.size(); ) {
                        final Object[] queued = sQueuedOpens.get(i);
                        final String authority = ((Request) queued[1]).authority();
                        if (((Pending) queued[0]).result() != null) {
                            sQueuedOpens.remove(i);
                            ((Pending) queued[0]).finish(null, false, false, "no longer needed");
                        } else if (mayOpen(authority)) {
                            sQueuedOpens.remove(i);
                            countOpen(authority, 1);
                            next.add(queued);
                        } else {
                            i++;
                        }
                    }
                }
                for (Object[] queued : next) {
                    startOpener((Pending) queued[0], (Request) queued[1]);
                }
            }
        }, "VmIconOpen");
        opener.setDaemon(true);
        opener.start();
    }

    // Opens the URI with this process's own access, as Icon opens it, and sends what it gives.
    private static void open(Pending pending, Request request) {
        try (DecodeGate.ProviderInput input = DecodeGate.provider(request.resolver, request.icon.getUri(), request.gateUser)) {
            openAdmitted(pending, request);
        } catch (IOException | OutOfMemoryError e) {
            pending.finish(null, false, false, "input admission unavailable");
        }
    }

    private static void openAdmitted(Pending pending, Request request) {
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
        } catch (SecurityException e) {
            pending.finish(null, false, false, "the URI was denied");
            return;
        } catch (FileNotFoundException | RuntimeException e) {
            pending.finish(null, false, false, "the URI could not be opened: " + e.getClass().getSimpleName());
            return;
        }
        if (afd == null) {
            pending.finish(null, false, false, "the URI gave nothing");
            return;
        }
        if (!pending.wantsPixels()) {
            closeQuietly(afd);
            pending.finish(null, false, false, "no longer needed");
            return;
        }
        try {
            send(pending, request, afd);
        } finally {
            closeQuietly(afd);
        }
    }

    // Hands the bytes (data, or what the URI opened) to the system server with a callback for the pixels.
    private static void send(Pending pending, Request request, AssetFileDescriptor afd) {
        if (!pending.wantsPixels()) {
            pending.finish(null, false, false, "no longer needed");
            return;
        }
        final Runnable beforeSend = sBeforeSendForTest;
        if (beforeSend != null) {
            beforeSend.run();
        }
        if (!pending.wantsPixels()) {
            pending.finish(null, false, false, "no longer needed");
            return;
        }
        final DecodeGate.Input input;
        try {
            // A descriptor is copied by the load's end: a provider pipe that never ends frees this thread then.
            input = afd != null ? DecodeGate.snapshot(afd, pending.end) : DecodeGate.snapshot(request.snapshot());
        } catch (IOException | android.graphics.DecodeMemory.NoRoomException | OutOfMemoryError e) {
            pending.finish(null, true, false, "bytes not handed over: " + e.getClass().getSimpleName());
            return;
        }
        try (DecodeGate.Input owned = input) {
            final ParcelFileDescriptor bytes = input.fd();
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
            extras.putInt(DecodeGate.EXTRA_OWNER_USER, request.ownerUser);
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
                    if (bitmap != null) {
                        try { android.graphics.DecodeMemory.keep(bitmap); }
                        catch (android.graphics.DecodeMemory.NoRoomException | OutOfMemoryError e) {
                            android.graphics.DecodeMemory.recycle(bitmap, null);
                            pending.finish(null, true, false, "no room for the returned icon");
                            return;
                        }
                    }
                    pending.finish(bitmap, true, reply != null && reply.getBoolean(DecodeGate.EXTRA_REFUSED),
                            reply == null ? "no reply" : reply.getString(DecodeGate.EXTRA_ERROR));
                }
            }.asBinder());
            final Bundle reply;
            try {
                reply = DecodeGate.submitIcon(request.resolver, request.gateUser, extras);
            } catch (RuntimeException e) {
                pending.finish(null, true, false, "the system server did not take it: " + e);
                return;
            }
            if (reply == null || !reply.getBoolean(DecodeGate.EXTRA_TAKEN)) {
                pending.finish(null, true, reply != null && reply.getBoolean(DecodeGate.EXTRA_REFUSED),
                        reply == null ? "the system server did not take it" : reply.getString(DecodeGate.EXTRA_ERROR));
            }
        }
    }

    // Waits for {@code pending}: a short wait (within the main thread's budget) or until the load's end.
    private static Result await(Pending pending, boolean shortWait, DecodeGate.Wait scope) {
        if (!shortWait) {
            return pending.await(Math.min(pending.end, SystemClock.uptimeMillis() + scope.left()));
        }
        final boolean main = Looper.myLooper() == Looper.getMainLooper();
        final long now = SystemClock.uptimeMillis();
        long wait = now < sNoWaitUntil ? 0 : Math.min(SHORT_WAIT_MS, scope.left());
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
     * A blank picture until the load it waits for ends with pixels, then those pixels on the attached view's thread.
     * Tint, colour filter and alpha set meanwhile are kept for the picture. A load that ended without pixels for a
     * reason of the moment is asked for again later through a refill registration ({@link GateRefill}: a few times,
     * waiting longer each time, while this drawable is still used), and pixels that still come for it fill it in. A
     * refused icon stays blank.
     */
    static final class Filling extends DrawableWrapper implements CleanCopyGate.MadeListener {
        private volatile Pending mPending; // null: an app resource icon waiting for its clean copy.
        private final Resources mRes;
        private final java.util.function.Supplier<Drawable> mReload; // that icon's load, null while still missing
        private GateRefill mRefill; // guarded by this
        private volatile boolean mFilled;
        private Drawable mReadyPicture; // guarded by this, retained until the first valid binding
        private Pending mReadySource; // guarded by this
        private boolean mReadyNotified; // guarded by this
        private int mBinding; // guarded by this, 0 unseen, 1 first attachment queued, 2 attachment observed
        private ColorStateList mTint;
        private boolean mHasTint;
        private BlendMode mBlendMode;
        private ColorFilter mColorFilter;
        private boolean mHasColorFilter;
        private int mAlpha = -1;

        Filling(Pending pending, Resources res) {
            this(pending, res, null);
            pending = mPending;
            if (!pending.listen(this)) {
                final Result result = pending.result();
                if (result != null && result.bitmap != null) {
                    fill(pending, result.bitmap);
                } else if (result != null && result.forNow()) {
                    leftOut();
                }
            }
        }

        void follow(Pending old, Pending next) {
            final GateRefill retired;
            final Pending latest = newest(next);
            synchronized (this) {
                if (mFilled || mPending != old) return;
                mPending = latest;
                retired = mRefill;
                mRefill = null;
            }
            if (retired != null) retired.cancel();
            if (!latest.listen(this)) {
                final Result result = latest.result();
                if (result != null && result.bitmap != null) fill(latest, result.bitmap);
                else if (result != null && result.forNow()) leftOut(latest);
                else if (result != null && result.refused) refused(latest);
            }
        }

        void leftOut(Pending source) {
            if (mPending == source && sAttempts.current(source.ticket)) leftOut();
        }

        void refused(Pending source) {
            final GateRefill retired;
            synchronized (sAttempts) {
                if (!sAttempts.current(source.ticket)) return;
                synchronized (this) {
                    if (mPending != source) return;
                    retired = mRefill;
                    mRefill = null;
                }
            }
            if (retired != null) retired.cancel();
        }

        void fill(Pending source, Bitmap bitmap) {
            ready(source, new BitmapDrawable(mRes, bitmap));
        }

        private void fillOnViewThread(Pending source, Drawable picture) {
            synchronized (sAttempts) {
                if (source != null && !sAttempts.current(source.ticket)) return;
                synchronized (this) {
                    if (mFilled || (source != null && mPending != source)) return;
                    mFilled = true;
                    mPending = null;
                }
            }
            finishFill(picture);
        }

        // Its load ended without pixels for a reason of the moment: asked for again later (any thread).
        void leftOut() {
            final GateRefill refill;
            synchronized (this) {
                if (mFilled || mPending == null) {
                    return;
                }
                if (mRefill == null) {
                    final WeakReference<Filling> self = new WeakReference<>(this);
                    mRefill = new GateRefill(r -> main().post(r), () -> {
                        final Filling f = self.get();
                        if (f != null) {
                            f.loadAgain();
                        }
                    });
                }
                refill = mRefill;
            }
            refill.retryLater();
        }

        // On the main thread: the icon is loaded again, unless it was filled or is refused by now.
        private void loadAgain() {
            final Pending old = mPending;
            if (mFilled || old == null || !old.request.current()) {
                return;
            }
            final Pending next = newest(old);
            if (next != old) {
                follow(old, next);
                return;
            }
            final Request request = old.request;
            final Bitmap made = sCache.get(request.key);
            if (made != null && sAttempts.current(old.ticket)) {
                fill(old, made);
                return;
            }
            if (VmIconLoads.refused(request)) {
                final GateRefill refill;
                synchronized (this) {
                    refill = mRefill;
                }
                if (refill != null) {
                    refill.cancel();
                }
                return;
            }
            final Pending pending = start(Request.again(request));
            mPending = pending;
            if (!pending.listen(this)) {
                final Result result = pending.result();
                if (result != null && result.bitmap != null) {
                    fill(pending, result.bitmap);
                } else if (result != null && result.forNow()) {
                    leftOut();
                }
            }
        }

        Filling(Pending pending, Resources res, java.util.function.Supplier<Drawable> reload) {
            super(blank(res));
            mPending = pending != null ? newest(pending) : null;
            mRes = res;
            mReload = reload;
        }

        // Copies of the app's images were made (on the clean copy gate's thread): the icon loads again.
        @Override
        public void onMade(String packageName, int userId) {
            if (mReload == null || mFilled) {
                return;
            }
            final Drawable loaded = mReload.get();
            if (loaded != null) {
                main().post(() -> fill(loaded));
            }
        }

        void fill(Bitmap bitmap) {
            fill(new BitmapDrawable(mRes, bitmap));
        }

        void fill(Drawable picture) {
            ready(null, picture);
        }

        private void ready(Pending source, Drawable picture) {
            synchronized (this) {
                if (mFilled || (source != null && mPending != source)) return;
                mReadySource = source;
                mReadyPicture = picture;
                mReadyNotified = false;
            }
            final Drawable.Callback callback = getCallback();
            if (callback instanceof AdaptiveIconDrawable) {
                ((AdaptiveIconDrawable) callback).onVmIconReady(this);
            }
            bindReadyPicture();
        }

        private Drawable.Callback callbackHost() {
            Drawable.Callback callback = getCallback();
            while (callback instanceof Drawable) {
                callback = ((Drawable) callback).getCallback();
            }
            return callback;
        }

        private android.view.View callbackView() {
            final Drawable.Callback callback = callbackHost();
            return callback instanceof android.view.View ? (android.view.View) callback : null;
        }

        private void notifyReadyHost() {
            Drawable target = this;
            Drawable.Callback callback = target.getCallback();
            while (callback instanceof Drawable) {
                target = (Drawable) callback;
                callback = target.getCallback();
            }
            if (callback == null || callback instanceof android.view.View) return;
            synchronized (this) {
                if (mReadyPicture == null || mReadyNotified) return;
                mReadyNotified = true;
            }
            final Drawable picture = target;
            final Drawable.Callback host = callback;
            main().post(() -> {
                if (callbackHost() == host) host.invalidateDrawable(picture);
            });
        }

        private void bindReadyPicture() {
            final Drawable.Callback host = callbackHost();
            if (host == null) return;
            final android.view.View view = host instanceof android.view.View ? (android.view.View) host : null;
            if (view == null) {
                notifyReadyHost();
                return;
            }
            final Handler handler = view.getHandler();
            final Drawable picture;
            final Pending source;
            synchronized (this) {
                if (handler == null) {
                    if (mBinding == 0) {
                        mBinding = 1;
                        final WeakReference<Filling> self = new WeakReference<>(this);
                        view.post(() -> {
                            final Filling filling = self.get();
                            if (filling == null) return;
                            synchronized (filling) { filling.mBinding = 2; }
                            if (filling.callbackView() == view) filling.bindReadyPicture();
                        });
                    } else if (mBinding == 2) {
                        mReadyPicture = null;
                        mReadySource = null;
                    }
                    return;
                }
                mBinding = 2;
                picture = mReadyPicture;
                source = mReadySource;
                mReadyPicture = null;
                mReadySource = null;
            }
            if (picture == null) return;
            handler.post(() -> {
                if (callbackHost() == host && view.isAttachedToWindow() && view.getHandler() == handler) {
                    fillOnViewThread(source, picture);
                }
            });
        }

        @Override public int getIntrinsicWidth() {
            bindReadyPicture();
            synchronized (this) {
                return mReadyPicture != null ? mReadyPicture.getIntrinsicWidth() : super.getIntrinsicWidth();
            }
        }

        @Override public int getIntrinsicHeight() {
            bindReadyPicture();
            synchronized (this) {
                return mReadyPicture != null ? mReadyPicture.getIntrinsicHeight() : super.getIntrinsicHeight();
            }
        }

        @Override public void draw(android.graphics.Canvas canvas) {
            bindReadyPicture();
            // An unmounted drawable can also be consumed directly by an offscreen caller.
            if (callbackView() == null) {
                final Drawable picture;
                final Pending source;
                synchronized (this) {
                    picture = mReadyPicture;
                    source = mReadySource;
                    mReadyPicture = null;
                    mReadySource = null;
                }
                if (picture != null) {
                    synchronized (sAttempts) {
                        if (source != null && (!sAttempts.current(source.ticket) || mPending != source)) return;
                    }
                    try {
                        final Drawable shown = styled(picture);
                        shown.setBounds(getBounds());
                        shown.draw(canvas);
                    } finally {
                        synchronized (this) {
                            if (!mFilled && mReadyPicture == null && (source == null || mPending == source)) {
                                mReadyPicture = picture;
                                mReadySource = source;
                            }
                        }
                        bindReadyPicture();
                    }
                    return;
                }
            }
            super.draw(canvas);
        }

        private void finishFill(Drawable picture) {
            synchronized (this) {
                if (mRefill != null) {
                    mRefill.cancel();
                }
            }
            setDrawable(styled(picture));
            // A view sized to the blank picture takes the new size.
            final android.view.View view = callbackView();
            if (view != null) {
                view.requestLayout();
            }
        }

        private Drawable styled(Drawable picture) {
            picture = picture.mutate();
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
            return picture;
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
            synchronized (this) {
                if (mPending != null) return new FillingState(mPending, mRes);
                return mReadyPicture != null ? mReadyPicture.getConstantState() : super.getConstantState();
            }
        }
    }

    // A copy of a filling drawable fills in from the same load.
    private static final class FillingState extends Drawable.ConstantState {
        private final Pending mPending;
        private final Resources mRes;

        FillingState(Pending pending, Resources res) {
            mPending = pending != null ? newest(pending) : null;
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
