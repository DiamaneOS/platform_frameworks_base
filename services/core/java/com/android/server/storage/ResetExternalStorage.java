/* Copyright (C) 2026 DiamaneOS
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.server.storage;

import android.content.Context;
import android.os.Binder;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.storage.DiskInfo;
import android.os.storage.StorageManager;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.LocalServices;
import com.android.server.StorageManagerInternal;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Best-effort card erase shared by the authorized policy and Settings reset paths. */
public final class ResetExternalStorage {
    private static final String TAG = "ResetExternalStorage";
    private static final long TIMEOUT_MILLIS = 30_000;

    private ResetExternalStorage() {}

    public static void wipe(Context context) {
        wipe(context, TIMEOUT_MILLIS);
    }

    @VisibleForTesting
    public static void wipe(Context context, long timeoutMillis) {
        Binder.withCleanCallingIdentity(() -> wipeWithDeadline(context, timeoutMillis));
    }

    private static void wipeWithDeadline(Context context, long timeoutMillis) {
        long deadline = SystemClock.elapsedRealtime() + timeoutMillis;
        FutureTask<Void> erase = new FutureTask<>(() -> {
            StorageManager storage = context.getSystemService(StorageManager.class);
            StorageManagerInternal internal = LocalServices.getService(StorageManagerInternal.class);
            for (DiskInfo disk : storage.getDisks()) {
                if (Thread.currentThread().isInterrupted()
                        || SystemClock.elapsedRealtime() >= deadline) break;
                if (!disk.isAdoptable()) continue;
                try {
                    internal.eraseAdoptableDiskForReset(disk, deadline);
                } catch (RuntimeException e) {
                    Slog.w(TAG, "Could not confirm erase of " + disk.getId(), e);
                }
            }
            return null;
        });
        PowerManager.WakeLock awake = null;
        try {
            awake = context.getSystemService(PowerManager.class)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG);
            awake.setReferenceCounted(false);
            // Keep the caller's timed wait running against its elapsed deadline through suspend.
            // A small grace period leaves the waiting caller responsible for releasing the lock.
            awake.acquire(timeoutMillis + TimeUnit.SECONDS.toMillis(1));
            new Thread(erase, "ResetExternalStorage").start();
            erase.get(Math.max(0, deadline - SystemClock.elapsedRealtime()), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            erase.cancel(true);
            Thread.currentThread().interrupt();
            Slog.w(TAG, "Card erase wait interrupted. Any outstanding erase is unconfirmed.");
        } catch (TimeoutException e) {
            erase.cancel(false);
            Slog.w(TAG, "Card erase deadline expired. Any outstanding erase is unconfirmed.");
        } catch (ExecutionException | RuntimeException e) {
            Slog.w(TAG, "Could not complete external storage erase", e);
        } finally {
            if (awake != null && awake.isHeld()) {
                try {
                    awake.release();
                } catch (RuntimeException e) {
                    Slog.w(TAG, "Could not release the card erase wake lock", e);
                }
            }
        }
    }
}
