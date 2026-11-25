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

package com.android.server.cleanimages;

import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.AssetFileDescriptor;
import android.graphics.DecodeGate;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.IBinder;
import android.os.IRemoteCallback;
import android.os.IUserManager;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.UserHandle;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * DiamaneOS: images of another user than the remote caller's own, which the decode gate's provider acquires itself.
 * The caller's descriptor holds bytes nothing ties to that user, so it is closed unused. The request names its source
 * instead ({@link DecodeGate#EXTRA_SOURCE}, GateOwner#source), checked here with the caller's binder identity:
 *
 * <ul>
 *   <li>{@code usericon:<user>}: the user's photo, opened by UserManagerService.getUserIcon with the caller's identity,
 *       so its own check (MANAGE_USERS or GET_ACCOUNTS_PRIVILEGED, the profile group) decides.</li>
 *   <li>{@code uri:<uri>} (opened as Icon opens it) and {@code uri-image:<uri>} (as ImageDecoder opens it): a content
 *       URI of that user, which the caller may name ({@link OwnerUsers}) and read
 *       (checkContentUriPermissionFull), opened as that user with the system identity.</li>
 * </ul>
 *
 * The open and the copy into a sealed memory file run on {@link #THREADS} threads of their own within the request's
 * time, never on the binder thread. Then the request is the provider's like any other, of that user for the caller. A
 * source that is refused or not read answers the caller's callback with a refusal.
 */
final class ForeignSources {
    private static final String TAG = "VmDecodeGate";
    static final int THREADS = 2;
    static final int WAITING = 16;
    private static final String USER_ICON = "usericon:";
    private static final String URI = "uri:";
    private static final String URI_IMAGE = "uri-image:";

    /** The provider's admission of a request whose descriptor is now the system server's own. */
    interface Taker {
        Bundle take(String method, Bundle extras, int callingUid, int ownerUser, int forUid, String attributed);
    }

    /** One acquisition: opened on a source thread, closed after. */
    private interface Opener extends AutoCloseable {
        AssetFileDescriptor open(CancellationSignal cancel) throws IOException;

        @Override
        default void close() {
        }
    }

    private static final ThreadPoolExecutor sOpeners = new ThreadPoolExecutor(THREADS, THREADS, 30,
            TimeUnit.SECONDS, new LinkedBlockingQueue<>(WAITING), r -> {
                final Thread t = new Thread(() -> {
                    DecodeGate.markGateThread();
                    r.run();
                }, "VmDecodeSource");
                t.setDaemon(true);
                return t;
            });

    static {
        sOpeners.allowCoreThreadTimeOut(true);
    }

    private ForeignSources() {
    }

    /**
     * On the binder thread, with the caller's identity: a request of {@code uid} for an image of user {@code named},
     * not its own. Taken (the answer comes to its callback) or refused.
     */
    static Bundle take(Context context, Taker taker, String method, Bundle extras, int uid, int named,
            String attributed) {
        closeQuietly(extras.getParcelable(DecodeGate.EXTRA_FD, ParcelFileDescriptor.class));
        final String source = extras.getString(DecodeGate.EXTRA_SOURCE);
        final IBinder binder = extras.getBinder(DecodeGate.EXTRA_CALLBACK);
        final Opener opener = binder == null ? null : opener(context, uid, Binder.getCallingPid(), named, source);
        if (opener == null) {
            android.util.Log.w(TAG, "refused: uid " + uid + " named user " + named + " without a source it may read");
            return refusal("may not name user " + named);
        }
        final long end = SystemClock.uptimeMillis() + Math.max(1, Math.min(DecodeGate.SERVER_MS,
                extras.getLong(DecodeGate.EXTRA_TIME, DecodeGate.SERVER_MS)));
        final Bundle request = new Bundle(extras);
        request.remove(DecodeGate.EXTRA_FD);
        request.remove(DecodeGate.EXTRA_SOURCE);
        final IRemoteCallback callback = IRemoteCallback.Stub.asInterface(binder);
        try {
            sOpeners.execute(() -> acquire(taker, method, request, uid, named, attributed, opener, end, callback));
        } catch (RejectedExecutionException e) {
            opener.close();
            return refusal("too many images of other users waiting");
        }
        final Bundle reply = new Bundle();
        reply.putBoolean(DecodeGate.EXTRA_TAKEN, true);
        return reply;
    }

    // The source's check, with the caller's identity: what opens it, or null when the caller may not read it.
    private static Opener opener(Context context, int uid, int pid, int named, String source) {
        if (source == null || named < 0) {
            return null;
        }
        try {
            if (source.equals(USER_ICON + named)) {
                final ParcelFileDescriptor photo = IUserManager.Stub.asInterface(
                        ServiceManager.getService(Context.USER_SERVICE)).getUserIcon(named);
                return photo == null ? null : new Opener() {
                    @Override
                    public AssetFileDescriptor open(CancellationSignal cancel) {
                        return new AssetFileDescriptor(photo, 0, AssetFileDescriptor.UNKNOWN_LENGTH);
                    }

                    @Override
                    public void close() {
                        closeQuietly(photo);
                    }
                };
            }
            final boolean image = source.startsWith(URI_IMAGE);
            if (!image && !source.startsWith(URI)) {
                return null;
            }
            final Uri uri = Uri.parse(source.substring(image ? URI_IMAGE.length() : URI.length()));
            if (!ContentResolver.SCHEME_CONTENT.equals(uri.getScheme())
                    || ContentProvider.getUserIdFromUri(uri, -1) != named
                    || OwnerUsers.of(context, uid, named, true) != named) {
                return null;
            }
            final long identity = Binder.clearCallingIdentity();
            try {
                if (context.checkContentUriPermissionFull(uri, pid, uid, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        != PackageManager.PERMISSION_GRANTED) {
                    return null;
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
            final Uri local = ContentProvider.getUriWithoutUserId(uri);
            return cancel -> {
                final ContentResolver resolver = context.createContextAsUser(UserHandle.of(named), 0)
                        .getContentResolver();
                if (image) {
                    try {
                        return resolver.openTypedAssetFileDescriptor(local, "image/*", null, cancel);
                    } catch (FileNotFoundException e) {
                        // ImageDecoder reads the plain open when no typed descriptor is offered.
                    }
                }
                return resolver.openAssetFileDescriptor(local, "r", cancel);
            };
        } catch (RemoteException | RuntimeException e) {
            return null;
        }
    }

    // On a source thread: the source opened and copied, then handed to the provider as user {@code named}.
    private static void acquire(Taker taker, String method, Bundle request, int uid, int named, String attributed,
            Opener opener, long end, IRemoteCallback callback) {
        final CancellationSignal cancel = new CancellationSignal();
        final Object token = new Object();
        com.android.internal.os.BackgroundThread.getHandler().postAtTime(cancel::cancel, token, end);
        Bundle reply;
        try (Opener owned = opener) {
            if (SystemClock.uptimeMillis() >= end) {
                reply = refusal("out of time before the source was opened");
            } else {
                try (AssetFileDescriptor afd = owned.open(cancel)) {
                    if (afd == null) {
                        reply = refusal("no image behind the source", true);
                    } else {
                        try (DecodeGate.Input input = DecodeGate.snapshot(afd)) {
                            request.putParcelable(DecodeGate.EXTRA_FD, input.fd());
                            request.putLong(DecodeGate.EXTRA_TIME, Math.max(1, end - SystemClock.uptimeMillis()));
                            reply = taker.take(method, request, uid, named, uid, attributed);
                        }
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            reply = refusal("the image of user " + named + " was not read: " + e.getClass().getSimpleName());
        } finally {
            com.android.internal.os.BackgroundThread.getHandler().removeCallbacksAndMessages(token);
        }
        if (reply == null || !reply.getBoolean(DecodeGate.EXTRA_TAKEN)) {
            deliver(callback, reply != null ? reply : refusal("not taken"));
        }
    }

    private static void deliver(IRemoteCallback callback, Bundle reply) {
        try {
            callback.sendResult(reply);
        } catch (RemoteException e) {
            // The caller is gone.
        }
    }

    private static Bundle refusal(String why) {
        return refusal(why, false);
    }

    private static Bundle refusal(String why, boolean terminal) {
        final Bundle reply = new Bundle();
        reply.putBoolean(DecodeGate.EXTRA_TAKEN, false);
        reply.putBoolean(DecodeGate.EXTRA_REFUSED, terminal);
        reply.putString(DecodeGate.EXTRA_ERROR, why);
        return reply;
    }

    private static void closeQuietly(ParcelFileDescriptor fd) {
        try {
            if (fd != null) {
                fd.close();
            }
        } catch (IOException ignored) {
            // Closed.
        }
    }
}
