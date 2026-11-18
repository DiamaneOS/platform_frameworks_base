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

package com.android.server.wallpaper;

import android.content.Context;
import android.graphics.Point;
import android.os.FileObserver;
import android.os.Handler;
import android.util.Slog;
import android.util.SparseArray;
import android.view.Display;
import android.view.DisplayInfo;

import com.android.server.ServiceThread;

import de.diamaneos.decode.client.WallpaperBox;

import java.io.File;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * DiamaneOS: images that apps set as wallpaper are decoded in the protected VM of the decode service and stored as
 * clean PNGs (WallpaperVmSanitizer) before anything in the system server or SystemUI decodes them. This class runs
 * that step for WallpaperManagerService, on a thread of its own (a call waits for the VM) with no lock of the service
 * held, and retries it while the VM is unavailable. Until a source is clean no crop is made from it and the default
 * wallpaper shows. When the clean copy comes after a failed first try, {@code showClean} (given by the service, run
 * under its lock) makes the crop, stores the crop hints and binds the image wallpaper again: the service's own update
 * path does not run twice for one event.
 */
final class WallpaperVmController {
    private static final String TAG = "WallpaperVm";
    private static final long[] RETRY_DELAYS_MS = {5_000, 15_000, 30_000, 60_000, 120_000, 300_000, 600_000};

    private final Object mLock;
    private final WallpaperDisplayHelper mDisplays;
    private final Consumer<WallpaperData> mShowCleanLocked;
    private final WallpaperVmSanitizer mSanitizer;
    private final Handler mHandler;
    // Stored wallpapers waiting for the VM, by source path, so a second check does not queue them twice.
    private final Set<String> mMigrating = ConcurrentHashMap.newKeySet();

    /**
     * {@code lock} is the service's lock, {@code showCleanLocked} (run under it) makes the crop of a wallpaper whose
     * source became clean, stores its settings, binds the image wallpaper and tells the callbacks.
     */
    WallpaperVmController(Context context, Object lock, WallpaperDisplayHelper displays,
            Consumer<WallpaperData> showCleanLocked) {
        mLock = lock;
        mDisplays = displays;
        mShowCleanLocked = showCleanLocked;
        mSanitizer = new WallpaperVmSanitizer(context);
        final ServiceThread thread = new ServiceThread("WallpaperVm",
                android.os.Process.THREAD_PRIORITY_BACKGROUND, true /* allowIo */);
        thread.start();
        mHandler = thread.getThreadHandler();
    }

    /**
     * A file event in a wallpaper directory. True when it was handled here: a rename this class made, or an image
     * an app wrote, which is made clean first and then shown. False when the service handles it as before
     * ({@code update}, its own update path, is not run here then).
     *
     * @param dataForEvent the wallpaper data of the event, for the lock wallpaper or the system one
     */
    boolean onEvent(int event, String path, File dir, File systemFile, File lockFile,
            Function<Boolean, WallpaperData> dataForEvent, Runnable update) {
        if (event == FileObserver.MOVED_TO && mSanitizer.consumeOwnMove(dir, path)) {
            // The clean copy from the VM moved into place: the task that made it goes on.
            return true;
        }
        final File changed = new File(dir, path);
        final boolean lock = lockFile.equals(changed);
        final boolean imageWritten = (event == FileObserver.CLOSE_WRITE || event == FileObserver.MOVED_TO)
                && (lock || systemFile.equals(changed));
        if (!imageWritten) {
            return false;
        }
        final WallpaperData wallpaper = dataForEvent.apply(lock);
        // The first try that works runs the service's update, as for any new image. A try that works after a first
        // one failed (the update ran then and showed the default) shows the clean copy itself.
        final AtomicBoolean failedFirst = new AtomicBoolean();
        cleanThen(changed, wallpaper, 0, stampOf(changed), () -> {
            if (!failedFirst.get()) {
                update.run();
            } else if (wallpaper != null) {
                synchronized (mLock) {
                    mShowCleanLocked.accept(wallpaper);
                }
            }
        }, () -> {
            failedFirst.set(true);
            update.run();
        });
        return true;
    }

    /**
     * A static wallpaper stored before images were decoded in the VM (or restored or moved without it): its crop is
     * removed right away, so nothing decodes it, and the VM re-makes it in the background. Until then the default
     * wallpaper shows. Called with the service's lock held.
     */
    void migrateIfNeededLocked(WallpaperData wallpaper) {
        if (wallpaper == null || !wallpaper.sourceExists()) {
            return;
        }
        final File source = wallpaper.getWallpaperFile();
        if (WallpaperVmSanitizer.isClean(source)) {
            return;
        }
        // Hide the crop made from the old source: the default shows until the VM has re-made it. The source stays.
        wallpaper.getCropFile().delete();
        if (!mMigrating.add(source.getPath())) {
            return;
        }
        Slog.i(TAG, "Wallpaper " + source.getName() + " is not a clean copy from the VM yet, hidden until it is");
        cleanThen(source, wallpaper, 0, stampOf(source), () -> {
            mMigrating.remove(source.getPath());
            synchronized (mLock) {
                mShowCleanLocked.accept(wallpaper);
            }
        }, () -> {
            // The default shows already (no crop). The task keeps trying while the VM is unavailable. A refused
            // image stays listed here, so it is tried again only after the next boot.
        });
    }

    /** True when {@code wallpaper}'s source is there and not a clean copy from the VM yet. */
    static boolean needsVm(WallpaperData wallpaper) {
        return wallpaper.sourceExists() && !WallpaperVmSanitizer.isClean(wallpaper.getWallpaperFile());
    }

    /** The wallpaper was cleared: the original kept beside its clean copy goes too. */
    static void onCleared(WallpaperData wallpaper) {
        WallpaperVmSanitizer.originalOf(wallpaper.getWallpaperFile()).delete();
    }

    /**
     * Makes {@code source} a clean copy in the VM, carries {@code wallpaper}'s crop hints over, then runs
     * {@code onClean}. When the first try fails, runs {@code onFirstFailure} (the default wallpaper shows: nothing
     * decodes a source that is not clean). While the VM is unavailable it tries again, the last delay repeating,
     * until the file changes. An image the VM refuses is kept and not tried again until the next boot or user switch.
     * The source is never deleted here.
     */
    private void cleanThen(File source, WallpaperData wallpaper, int attempt, long[] stamp, Runnable onClean,
            Runnable onFirstFailure) {
        final long delay = attempt == 0 ? 0 : RETRY_DELAYS_MS[Math.min(attempt, RETRY_DELAYS_MS.length) - 1];
        mHandler.postDelayed(() -> {
            if (!java.util.Arrays.equals(stamp, stampOf(source))) {
                // A newer image replaced this one: its own task handles it.
                return;
            }
            final Point display = largestDisplaySize();
            final List<int[]> crops;
            synchronized (mLock) {
                crops = wallpaper == null ? null : WallpaperVmSanitizer.cropHints(wallpaper);
            }
            final WallpaperVmSanitizer.Outcome outcome = mSanitizer.sanitize(source, display.x, display.y, crops);
            switch (outcome.result) {
                case CLEAN:
                    if (outcome.remade && wallpaper != null) {
                        synchronized (mLock) {
                            WallpaperVmSanitizer.mapCropHints(wallpaper, outcome);
                        }
                    }
                    onClean.run();
                    break;
                case UNAVAILABLE:
                    if (attempt == 0) {
                        onFirstFailure.run();
                    }
                    cleanThen(source, wallpaper, attempt + 1, stamp, onClean, onFirstFailure);
                    break;
                default:
                    Slog.w(TAG, "Wallpaper image " + source.getName()
                            + " was not decoded in the VM, kept, default shown");
                    if (attempt == 0) {
                        onFirstFailure.run();
                    }
                    break;
            }
        }, delay);
    }

    private static long[] stampOf(File file) {
        return new long[] {file.length(), file.lastModified()};
    }

    /**
     * The size to cover on the default display: the largest short side and the largest long side over all its sizes
     * (a fold's inner and cover screens included). The VM makes the clean copy just big enough for it, so the crop
     * that follows under the lock is cheap. Other displays are cropped from that copy too.
     */
    private Point largestDisplaySize() {
        final SparseArray<Point> sizes = mDisplays.getDefaultDisplaySizes();
        final List<int[]> all = new java.util.ArrayList<>();
        for (int i = 0; sizes != null && i < sizes.size(); i++) {
            final Point size = sizes.valueAt(i);
            all.add(new int[] {size.x, size.y});
        }
        if (all.isEmpty()) {
            final DisplayInfo info = mDisplays.getDisplayInfo(Display.DEFAULT_DISPLAY);
            all.add(info == null ? new int[] {2048, 2048} : new int[] {info.logicalWidth, info.logicalHeight});
        }
        final int[] largest = WallpaperBox.largest(all);
        return new Point(Math.max(1, largest[0]), Math.max(1, largest[1]));
    }
}
