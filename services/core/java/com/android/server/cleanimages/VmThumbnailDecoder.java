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
import android.content.Context;
import android.content.pm.ProviderInfo;
import android.graphics.Bitmap;
import android.os.Binder;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.os.UserHandle;
import android.util.Log;

import com.android.internal.diamaneos.cleancopies.CleanCopyClient;
import com.android.internal.diamaneos.cleancopies.VmThumbnails;

import de.diamaneos.decode.client.VmDecode;

import java.io.IOException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * DiamaneOS: the thumbnails system processes load from content providers ({@code VmThumbnails} in the framework),
 * decoded in the protected VM. The caller opened the provider's reply with its own grants and hands over its bytes,
 * which the decode service reads: they are never read here. Each thumbnail is charged to the package of the provider
 * it came from, takes at most {@link #THUMBNAIL_MS} from the call (the wait in the queue included) and fits in the box
 * asked for, at most {@link #MAX_SIDE} a side. Three are decoded at a time on threads of their own, with up to six
 * waiting. The reply holds the bitmap, or the reason there is none.
 */
final class VmThumbnailDecoder {
    private static final String TAG = "VmThumbnails";

    static final long THUMBNAIL_MS = 5_000;
    private static final long DECODE_MARGIN_MS = 250;
    static final int MAX_SIDE = 2048;
    private static final int THREADS = 3;
    private static final int WAITING = 6;
    private static final int MAX_AUTHORITY = 1024;
    private static final int LOG_LINES_PER_MINUTE = 20;

    private static VmThumbnailDecoder sInstance;

    private final Context mContext;
    private final ThreadPoolExecutor mPool = new ThreadPoolExecutor(THREADS, THREADS, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(WAITING), r -> {
                final Thread t = new Thread(r, "CleanImagesThumbnail");
                t.setDaemon(true);
                return t;
            });
    private final Object mLogLock = new Object();
    private long mLogMinute; // guarded by mLogLock
    private int mLogLines; // guarded by mLogLock

    private VmThumbnailDecoder(Context context) {
        mContext = context;
        mPool.allowCoreThreadTimeOut(true);
    }

    static synchronized VmThumbnailDecoder get(Context context) {
        if (sInstance == null) {
            sInstance = new VmThumbnailDecoder(context.getApplicationContext() != null
                    ? context.getApplicationContext() : context);
        }
        return sInstance;
    }

    /** The reply to {@link VmThumbnails#METHOD_THUMBNAIL} from the system process {@code callingUid}. */
    Bundle thumbnail(Bundle extras, int callingUid) {
        final ParcelFileDescriptor fd = extras.getParcelable(CleanCopyClient.EXTRA_FD, ParcelFileDescriptor.class);
        final Bundle reply = new Bundle();
        try {
            final int width = extras.getInt(CleanCopyClient.EXTRA_WIDTH);
            final int height = extras.getInt(CleanCopyClient.EXTRA_HEIGHT);
            if (fd == null || width < 1 || height < 1) {
                reply.putString(VmThumbnails.EXTRA_ERROR, "bad request");
                return reply;
            }
            final String authority = extras.getString(VmThumbnails.EXTRA_AUTHORITY);
            final String owner = owner(authority, UserHandle.getUserId(callingUid));
            // The thumbnail is an image of the user of the provider it came from (VmDecode OWNER_USER): the user the
            // authority names, else the calling process's.
            final int ownerUser = authority == null ? UserHandle.getUserId(callingUid)
                    : ContentProvider.getUserIdFromAuthority(authority, UserHandle.getUserId(callingUid));
            final Bitmap bitmap = decode(fd, owner, ownerUser, callingUid, Math.min(width, MAX_SIDE),
                    Math.min(height, MAX_SIDE), reply);
            if (bitmap != null) {
                reply.putParcelable(CleanCopyClient.EXTRA_BITMAP, bitmap);
                if (Log.isLoggable(TAG, Log.DEBUG)) {
                    Log.d(TAG, "made " + bitmap.getWidth() + "x" + bitmap.getHeight() + " in the VM for uid "
                            + callingUid + ", owner " + owner);
                }
            } else {
                logLimited("no thumbnail for uid " + callingUid + ", owner " + owner + ": "
                        + reply.getString(VmThumbnails.EXTRA_ERROR));
            }
            return reply;
        } finally {
            closeQuietly(fd);
        }
    }

    // The package of the provider behind {@code authority} for {@code user} (or the user the authority names), else
    // the thumbnails' own lane.
    private String owner(String authority, int user) {
        if (authority == null || authority.isEmpty() || authority.length() > MAX_AUTHORITY) {
            return VmDecode.unknownOwner("thumbnail");
        }
        final long identity = Binder.clearCallingIdentity();
        try {
            final ProviderInfo provider = mContext.getPackageManager().resolveContentProviderAsUser(
                    ContentProvider.getAuthorityWithoutUserId(authority), 0,
                    ContentProvider.getUserIdFromAuthority(authority, user));
            if (provider != null && provider.packageName != null && !provider.packageName.isEmpty()) {
                return provider.packageName;
            }
        } catch (RuntimeException e) {
            // Unknown provider.
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
        return VmDecode.unknownOwner("thumbnail");
    }

    // Decodes on a thread of the pool within THUMBNAIL_MS. Null with the reason in the reply when it cannot.
    // forUid: the process the thumbnail is for (VmDecode.forApp): the decode service decides from it whether it is
    // per-user content or shared.
    private Bitmap decode(ParcelFileDescriptor fd, String owner, int ownerUser, int forUid, int width, int height,
            Bundle reply) {
        final ParcelFileDescriptor own;
        try {
            own = fd.dup();
        } catch (IOException e) {
            reply.putString(VmThumbnails.EXTRA_ERROR, "no descriptor");
            return null;
        }
        final long end = SystemClock.uptimeMillis() + THUMBNAIL_MS;
        // Whoever sets it first closes the descriptor: the task when it runs, this thread when the task never ran.
        final AtomicBoolean taken = new AtomicBoolean();
        final Future<Bitmap> task;
        try {
            task = mPool.submit(() -> {
                if (!taken.compareAndSet(false, true)) {
                    return null;
                }
                try (ParcelFileDescriptor bytes = own; VmDecode.ForApp app = VmDecode.forApp(forUid)) {
                    // The decode ends a little before the caller's wait does, so the decode client sees its own
                    // missed deadline (and checks the service) rather than an interrupt.
                    final long left = end - DECODE_MARGIN_MS - SystemClock.uptimeMillis();
                    if (left <= 0) {
                        return null;
                    }
                    final Bitmap made = VmDecode.decode(mContext, bytes, owner, ownerUser,
                            VmDecode.PRIORITY_USER_WAITING, width, height, VmDecode.deadlineIn(left)).bitmap;
                    // Immutable in shared memory: the reply hands it over without another copy.
                    final Bitmap shared = made.asShared();
                    if (shared != made) {
                        made.recycle();
                    }
                    return shared;
                }
            });
        } catch (RejectedExecutionException e) {
            closeQuietly(own);
            reply.putString(VmThumbnails.EXTRA_ERROR, "busy");
            return null;
        }
        try {
            final Bitmap bitmap = task.get(Math.max(1, end - SystemClock.uptimeMillis()), TimeUnit.MILLISECONDS);
            if (bitmap == null) {
                reply.putString(VmThumbnails.EXTRA_ERROR, "out of time");
                reply.putBoolean(VmThumbnails.EXTRA_LATER, true);
            }
            return bitmap;
        } catch (TimeoutException e) {
            // The service did not answer in time: the decode client checks it, and the caller may wait for its
            // answer (CleanImageMaker tells the watching processes) and ask once more.
            reply.putString(VmThumbnails.EXTRA_ERROR, "out of time");
            reply.putBoolean(VmThumbnails.EXTRA_LATER, true);
        } catch (ExecutionException e) {
            final Throwable cause = e.getCause();
            if (cause instanceof VmDecode.UnavailableException) {
                reply.putString(VmThumbnails.EXTRA_ERROR, "VM unavailable: " + cause.getMessage());
                // Only an outage the client is checking, or no room now, ends with an answer the caller can wait for.
                reply.putBoolean(VmThumbnails.EXTRA_LATER, VmDecode.isSuspect()
                        || ((VmDecode.UnavailableException) cause).isNoRoom());
            } else if (cause instanceof VmDecode.RejectedException) {
                reply.putString(VmThumbnails.EXTRA_ERROR, "refused by the VM: " + cause.getMessage());
            } else {
                reply.putString(VmThumbnails.EXTRA_ERROR, "failed: " + cause);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            reply.putString(VmThumbnails.EXTRA_ERROR, "interrupted");
        }
        task.cancel(true);
        if (taken.compareAndSet(false, true)) {
            closeQuietly(own);
        }
        return null;
    }

    private void logLimited(String line) {
        synchronized (mLogLock) {
            final long minute = SystemClock.elapsedRealtime() / 60_000;
            if (minute != mLogMinute) {
                mLogMinute = minute;
                mLogLines = 0;
            }
            if (++mLogLines > LOG_LINES_PER_MINUTE) {
                return;
            }
        }
        final StringBuilder clean = new StringBuilder(Math.min(line.length(), 300));
        for (int i = 0; i < line.length() && clean.length() < 300; i++) {
            final char c = line.charAt(i);
            clean.append(Character.isISOControl(c) ? '?' : c);
        }
        Log.w(TAG, clean.toString());
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
