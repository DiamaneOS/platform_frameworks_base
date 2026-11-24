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
import android.os.ParcelFileDescriptor;
import android.os.SELinux;
import android.util.Slog;
import android.util.SparseArray;
import android.view.Display;
import android.view.DisplayInfo;

import com.android.server.ServiceThread;

import de.diamaneos.decode.client.FreshWallpaperInput;
import de.diamaneos.decode.client.WallpaperBox;
import de.diamaneos.decode.client.WallpaperSets;

import java.io.File;
import java.io.IOException;
import java.util.List;
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
 * path does not run twice for one event. A task works on the generation of the file its event saw: its clean copy is
 * put in place, its hints carried over and the update run under the service's lock only while the file is still that
 * generation, so an older image never replaces a newer one or comes back after a clear.
 */
final class WallpaperVmController {
    /** Keep close events from retired input descriptors out of the directory watch. */
    static int observerEvents(int events) {
        // Linux IN_EXCL_UNLINK keeps a retired inode from naming a later wallpaper set.
        return events | 0x04000000;
    }

    private static final String TAG = "WallpaperVm";
    private static final long[] RETRY_DELAYS_MS = {5_000, 15_000, 30_000, 60_000, 120_000, 300_000, 600_000};

    private final Object mLock;
    private final WallpaperDisplayHelper mDisplays;
    private final Consumer<WallpaperData> mShowCleanLocked;
    private final WallpaperVmSanitizer mSanitizer;
    private final Handler mHandler;
    // Stored wallpapers waiting for the VM, by source path, so a second check does not queue them twice.
    private final de.diamaneos.decode.client.WallpaperMigrations mMigrating =
            new de.diamaneos.decode.client.WallpaperMigrations();
    private final WallpaperSets mSets = new WallpaperSets();

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
     * A file event in a wallpaper directory. True when it was handled here: an image an app wrote is made clean
     * first and then shown. False when the service handles it as before
     * ({@code update}, its own update path, is not run here then).
     *
     * @param dataForEvent the wallpaper data of the event, for the lock wallpaper or the system one
     */
    boolean onEvent(int event, String path, File dir, File systemFile, File lockFile,
            Function<Boolean, WallpaperData> dataForEvent, Runnable update) {
        final File changed = new File(dir, path);
        final boolean lock = lockFile.equals(changed);
        final boolean imageWritten = (event == FileObserver.CLOSE_WRITE || event == FileObserver.MOVED_TO)
                && (lock || systemFile.equals(changed));
        if (!imageWritten) {
            return false;
        }
        final WallpaperData wallpaper = dataForEvent.apply(lock);
        if (event == FileObserver.MOVED_TO && lock && wallpaper != null) {
            // The service moved the home and lock image to the lock paths, its crop with it (a home-only image is
            // set over a shared one): no new image. Its clean crop stays, a missing one is made again from the
            // clean source, an unclean source is made clean first. The service's update leaves the move alone.
            synchronized (mLock) {
                migrateIfNeededLocked(wallpaper);
            }
            return false;
        }
        // The crop of the image this one replaces goes at once: the app's set completes before the VM has answered
        // (WallpaperObserver.completeSoon), and until then the wallpaper file is the default, not the old image.
        if (wallpaper != null) {
            synchronized (mLock) {
                wallpaper.getCropFile().delete();
            }
        }
        // The first try that works runs the service's update, as for any new image. A try that works after a first
        // one failed (the update ran then and showed the default) shows the clean copy itself.
        final AtomicBoolean failedFirst = new AtomicBoolean();
        final String generation = WallpaperVmSanitizer.generation(changed);
        if (generation == null) {
            return true; // Gone already (cleared): nothing to make clean.
        }
        cleanThen(changed, wallpaper, 0, generation, () -> {
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
     * wallpaper shows. A clean source whose crop is missing or not the system server's own gets its crop made again.
     * Called with the service's lock held.
     */
    void migrateIfNeededLocked(WallpaperData wallpaper) {
        if (wallpaper == null || !wallpaper.sourceExists()) {
            return;
        }
        final File source = wallpaper.getWallpaperFile();
        if (WallpaperVmSanitizer.isClean(source)) {
            // A crop not listed as the system server's own (made by an older build) is made again, so SystemUI
            // decodes it without the VM. So is a missing one (a lock image whose crop went), else a cropped read
            // finds no image.
            if (!wallpaper.cropExists() || !WallpaperVmSanitizer.isOwnCrop(source, wallpaper.getCropFile())) {
                Slog.i(TAG, "Wallpaper crop " + wallpaper.getCropFile().getName()
                        + " is made again from its clean copy");
                mHandler.post(() -> {
                    synchronized (mLock) {
                        mShowCleanLocked.accept(wallpaper);
                    }
                });
            }
            return;
        }
        // Hide the crop made from the old source: the default shows until the VM has re-made it. The source stays.
        wallpaper.getCropFile().delete();
        final String generation = WallpaperVmSanitizer.generation(source);
        if (!mMigrating.begin(source.getPath(), generation)) {
            return;
        }
        Slog.i(TAG, "Wallpaper " + source.getName() + " is not a clean copy from the VM yet, hidden until it is");
        cleanThen(source, wallpaper, 0, generation, () -> {
            mMigrating.retire(source.getPath(), generation);
            synchronized (mLock) {
                mShowCleanLocked.accept(wallpaper);
            }
        }, () -> {
            // The default shows already (no crop). The task keeps trying while the VM is unavailable. A refused
            // image stays listed here, so it is tried again only after the next boot.
        });
    }

    /**
     * The user whose wallpaper {@code source} is: the wallpaper's, else the user folder it is in
     * (/data/system/users/USER). -1 when neither says: the VM then refuses it.
     */
    static int userOf(File source, WallpaperData wallpaper) {
        if (wallpaper != null) {
            return wallpaper.userId;
        }
        final File dir = source.getParentFile();
        try {
            return dir == null ? -1 : Integer.parseInt(dir.getName());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** True when {@code wallpaper}'s source is there and not a clean copy from the VM yet. */
    static boolean needsVm(WallpaperData wallpaper) {
        return wallpaper.sourceExists() && !WallpaperVmSanitizer.isClean(wallpaper.getWallpaperFile());
    }

    /** The wallpaper was cleared: the original kept beside its clean copy goes too. */
    void onCleared(WallpaperData wallpaper) {
        final File source = wallpaper.getWallpaperFile();
        mSets.begin(source.getPath());
        mMigrating.clear(source.getPath());
        WallpaperVmSanitizer.originalOf(source).delete();
    }

    boolean consumeOwnMove(File dir, String path) {
        return mSanitizer.consumeOwnMove(dir, path);
    }

    /** A preparation failure leaves the previous source in place. */
    ParcelFileDescriptor openForAppLocked(WallpaperData wallpaper, Runnable beforeReplace) throws IOException {
        final File source = wallpaper.getWallpaperFile();
        final ParcelFileDescriptor input = FreshWallpaperInput.replace(source, staged -> {
            final String label = SELinux.fileSelabelLookup(source.getPath());
            if (label == null || (!label.equals(SELinux.getFileContext(staged.getPath()))
                    && !SELinux.setFileContext(staged.getPath(), label))) {
                throw new IOException("could not label the next wallpaper source");
            }
            return ParcelFileDescriptor.open(staged, ParcelFileDescriptor.MODE_READ_WRITE);
        }, staged -> {
            if (beforeReplace != null) beforeReplace.run();
            mSanitizer.moveToSource(staged, source);
        });
        mSets.begin(source.getPath());
        mMigrating.clear(source.getPath());
        WallpaperVmSanitizer.onOpenedForApp(source, input.getFileDescriptor());
        return input;
    }

    /**
     * Makes {@code source} a clean copy in the VM from its {@code generation} and, under the service's lock while the
     * source is still that generation, puts it in place, carries {@code wallpaper}'s crop hints over and runs
     * {@code onClean}. When the first try fails, runs {@code onFirstFailure} (the default wallpaper shows: nothing
     * decodes a source that is not clean). While the VM is unavailable it tries again, the last delay repeating,
     * until the file changes. An image the VM refuses is kept and not tried again until the next boot or user switch.
     * A task whose file changed meanwhile ends without a trace: the newer image's task handles it. The source is never
     * deleted here.
     */
    private void cleanThen(File source, WallpaperData wallpaper, int attempt, String generation, Runnable onClean,
            Runnable onFirstFailure) {
        cleanThen(source, wallpaper, attempt, generation, onClean, onFirstFailure, mSets.current(source.getPath()));
    }

    private void cleanThen(File source, WallpaperData wallpaper, int attempt, String generation, Runnable onClean,
            Runnable onFirstFailure, long set) {
        final long delay = attempt == 0 ? 0 : RETRY_DELAYS_MS[Math.min(attempt, RETRY_DELAYS_MS.length) - 1];
        mHandler.postDelayed(() -> {
            if (!mSets.current(source.getPath(), set)
                    || !generation.equals(WallpaperVmSanitizer.generation(source))) {
                mMigrating.retire(source.getPath(), generation);
                return;
            }
            final Point display = largestDisplaySize();
            final List<int[]> crops;
            synchronized (mLock) {
                crops = wallpaper == null ? null : WallpaperVmSanitizer.cropHints(wallpaper);
            }
            final WallpaperVmSanitizer.Prepared prepared = mSanitizer.prepare(source, generation,
                    userOf(source, wallpaper), display.x, display.y, crops);
            WallpaperVmSanitizer.Outcome outcome = prepared.outcome;
            if (outcome.result == WallpaperVmSanitizer.Result.CLEAN) {
                final boolean current = !outcome.remade || mSanitizer.stillCurrent(prepared);
                if (!current) {
                    WallpaperVmSanitizer.discard(prepared);
                    mMigrating.retire(source.getPath(), generation);
                    return;
                }
                synchronized (mLock) {
                    if (!mSets.current(source.getPath(), set)) {
                        WallpaperVmSanitizer.discard(prepared);
                        mMigrating.retire(source.getPath(), generation);
                        return;
                    }
                    if (outcome.remade) {
                        outcome = mSanitizer.publishLocked(prepared);
                    } else if (!generation.equals(WallpaperVmSanitizer.generation(source))) {
                        mMigrating.retire(source.getPath(), generation);
                        return;
                    }
                    if (outcome.result == WallpaperVmSanitizer.Result.CLEAN) {
                        if (outcome.remade && wallpaper != null) {
                            WallpaperVmSanitizer.mapCropHints(wallpaper, outcome);
                        }
                        onClean.run();
                        return;
                    }
                }
            }
            switch (outcome.result) {
                case STALE:
                    mMigrating.retire(source.getPath(), generation);
                    return;
                case UNAVAILABLE:
                    if (attempt == 0) {
                        firstFailureIfCurrent(source, generation, set, onFirstFailure);
                    }
                    cleanThen(source, wallpaper, attempt + 1, generation, onClean, onFirstFailure, set);
                    break;
                default:
                    Slog.w(TAG, "Wallpaper image " + source.getName()
                            + " was not decoded in the VM, kept, default shown");
                    if (attempt == 0) {
                        firstFailureIfCurrent(source, generation, set, onFirstFailure);
                    }
                    break;
            }
        }, delay);
    }

    /** A failed decode may update the pending crop only for its current set and input. */
    private void firstFailureIfCurrent(File source, String generation, long set, Runnable failure) {
        synchronized (mLock) {
            if (mSets.current(source.getPath(), set)
                    && generation.equals(WallpaperVmSanitizer.generation(source))) {
                failure.run();
            }
        }
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
