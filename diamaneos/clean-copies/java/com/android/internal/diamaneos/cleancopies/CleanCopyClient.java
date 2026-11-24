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

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.ColorSpace;
import android.hardware.display.DisplayManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.LruCache;
import android.view.Display;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * The clean copies the system server keeps, seen from any system process: a copy is read through the system
 * server's provider as a {@link CleanImageFile} (checked here, no decoder runs), and a file without one
 * is asked for in the background, so the system server makes it in the VM. Only system processes may use the
 * provider. Never on the main thread.
 *
 * @hide
 */
public final class CleanCopyClient {
    private static final String TAG = "CleanCopies";

    /** The provider in the system server. */
    public static final String AUTHORITY = "de.diamaneos.cleanimages";
    public static final Uri URI = new Uri.Builder().scheme(ContentResolver.SCHEME_CONTENT).authority(AUTHORITY)
            .build();
    public static final String METHOD_REQUEST = "request";
    public static final String METHOD_FORGET = "forget";
    public static final String METHOD_STATUS = "status";
    public static final String METHOD_FALLBACK = "fallback";
    /** Decodes a shortcut's icon picture in the VM, from a descriptor the caller opened with its own grant. */
    public static final String METHOD_SHORTCUT = "shortcut";
    public static final String EXTRA_FD = "fd";
    public static final String EXTRA_PACKAGE = "package";
    public static final String EXTRA_BITMAP = "bitmap";
    /**
     * Registers a callback (EXTRA_CALLBACK, an IRemoteCallback) that the system server calls with EXTRA_PACKAGE and
     * EXTRA_USER when copies of that package's images were made: a process redraws what showed without them.
     */
    public static final String METHOD_WATCH = "watch";
    public static final String EXTRA_CALLBACK = "callback";
    /**
     * In what a watcher is sent: the system server's decode service answers again after calls failed for it (a
     * thumbnail that waits for it tries again).
     */
    public static final String EXTRA_DECODE_ANSWERS = "decodeAnswers";
    /** The resource a file is loaded as ({@code Resources.getResourceName}), for the stand-in of its copy. */
    public static final String EXTRA_RESOURCE = "resource";
    /** In a fallback reply: the key of the newest copy of the same resource, the closest in density. */
    public static final String EXTRA_KEY = "key";
    public static final String EXTRA_APK = "apk";
    public static final String EXTRA_FILE = "file";
    public static final String EXTRA_DENSITY = "density";
    public static final String EXTRA_WIDTH = "width";
    public static final String EXTRA_HEIGHT = "height";
    public static final String EXTRA_USER = "user";
    public static final String EXTRA_KEYS = "keys";
    /** In a status reply: how many of the keys have no copy and were not refused by the VM. */
    public static final String EXTRA_OPEN = "open";

    // A file asked for is not asked for again sooner than this.
    private static final long ASK_AGAIN_MS = 60_000;

    private static final LruCache<String, Long> sAsked = new LruCache<>(1024);
    private static Handler sHandler; // guarded by CleanCopyClient.class
    private static int[] sBox; // guarded by CleanCopyClient.class

    private CleanCopyClient() {
    }

    /** The URI of the copy kept under {@code key}. */
    public static Uri uriFor(String key) {
        return URI.buildUpon().appendPath(CleanImageFile.fileName(key)).build();
    }

    /** The box once {@link #box} has found the display, else null (no display call here). */
    public static synchronized int[] knownBox() {
        return sBox;
    }

    /**
     * The box every process makes and looks up copies in: the default display's size, either way round, so one copy
     * serves both orientations and every process (the copy itself is at most {@link CleanImageFile#MAX_PIXELS}).
     */
    public static synchronized int[] box(Context context) {
        if (sBox != null) {
            return sBox;
        }
        try {
            final Display display = context.getSystemService(DisplayManager.class)
                    .getDisplay(Display.DEFAULT_DISPLAY);
            final DisplayMetrics metrics = new DisplayMetrics();
            display.getRealMetrics(metrics);
            final int w = Math.min(metrics.widthPixels, metrics.heightPixels);
            final int h = Math.max(metrics.widthPixels, metrics.heightPixels);
            if (w > 0 && h > 0) {
                sBox = new int[] {w, h};
                return sBox;
            }
        } catch (RuntimeException e) {
            // No display yet (early in the system server): asked again next time.
        }
        // Until the display is known: a box no copy is kept in, so nothing is made for a wrong size.
        return new int[] {1, 1};
    }

    /**
     * The copy kept under {@code key}, checked, or null. Allowed on the main thread, as loading the file from the APK
     * was before: one small read through the system server, never a wait for the VM.
     */
    public static CleanImageFile read(Context context, String key) {
        if (key == null) {
            return null;
        }
        final byte[] bytes;
        try {
            final ParcelFileDescriptor fd = context.getContentResolver().openFileDescriptor(uriFor(key), "r");
            if (fd == null) {
                return null;
            }
            bytes = readAll(fd);
        } catch (IOException | RuntimeException e) {
            // None kept (FileNotFoundException), or the provider is not there yet.
            return null;
        }
        return bytes == null ? null : CleanImageFile.read(bytes, bytes.length, key);
    }

    /**
     * The newest copy of the same resource as the file {@code file} of the APK at {@code apkPath} (the resource
     * {@code resourceName}, as {@code userId}), the closest to {@code density}, or null: what shows while the exact
     * copy is made (after an app update, or when Android picks another file for the resource). The system server
     * finds the app the APK belongs to itself.
     */
    public static CleanImageFile readFallback(Context context, String apkPath, String file, int density,
            int maxWidth, int maxHeight, String resourceName, int userId) {
        if (apkPath == null || file == null || resourceName == null) {
            return null;
        }
        final Bundle extras = request(apkPath, file, density, maxWidth, maxHeight, resourceName, userId);
        final Bundle reply = call(context, METHOD_FALLBACK, extras);
        final String key = reply != null ? reply.getString(EXTRA_KEY) : null;
        return key != null ? read(context, key) : null;
    }

    /**
     * A shortcut's icon picture, decoded in the VM by the system server from {@code fd} (which the caller opened
     * with its own grant, as Android does), charged to the shortcut's app, or null (the VM down, out of time). The
     * system server gives it at most 1.5 s. Never on the main thread. Takes ownership of {@code fd}.
     */
    public static Bitmap shortcutIcon(Context context, ParcelFileDescriptor fd, String packageName,
            int userId) {
        try (ParcelFileDescriptor owned = fd) {
            if (fd == null || packageName == null || Looper.myLooper() == Looper.getMainLooper()) {
                return null;
            }
            final Bundle extras = new Bundle();
            extras.putParcelable(EXTRA_FD, fd);
            extras.putString(EXTRA_PACKAGE, packageName);
            extras.putInt(EXTRA_USER, userId);
            final Bundle reply = call(context, METHOD_SHORTCUT, extras);
            return reply == null ? null : reply.getParcelable(EXTRA_BITMAP, Bitmap.class);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static final java.util.concurrent.ThreadPoolExecutor sShortcutInputs =
            new java.util.concurrent.ThreadPoolExecutor(2, 2, 30, java.util.concurrent.TimeUnit.SECONDS,
                    new java.util.concurrent.ArrayBlockingQueue<>(6), runnable -> {
                        final Thread thread = new Thread(runnable, "CleanShortcutInput");
                        thread.setDaemon(true);
                        return thread;
                    }, new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());

    private static final java.util.Set<String> sShortcutAuthorities = new java.util.HashSet<>();
    private static int sShortcutWaiters; // guarded by sShortcutAuthorities

    /** Loads the trusted file directly or opens the shortcut URI on an admitted worker. */
    public static Bitmap shortcutIcon(Context context, android.content.pm.ShortcutInfo shortcut) {
        if (shortcut == null || Looper.myLooper() == Looper.getMainLooper()
                || android.os.Binder.getCallingPid() != Process.myPid()) {
            return null;
        }
        final long deadline = android.os.SystemClock.uptimeMillis() + 1500;
        try {
            final android.content.pm.ILauncherApps apps = android.content.pm.ILauncherApps.Stub.asInterface(
                    android.os.ServiceManager.getService(Context.LAUNCHER_APPS_SERVICE));
            if (apps == null) return null;
            final String caller = context.getOpPackageName();
            final ParcelFileDescriptor file = apps.getShortcutIconFd(
                    caller, shortcut.getPackage(), shortcut.getId(), shortcut.getUserId());
            if (file != null) return shortcutInput(context, shortcut, caller, file, null, deadline);
            final String named = apps.getShortcutIconUri(caller, shortcut.getPackage(), shortcut.getId(),
                    shortcut.getUserId());
            if (named == null) return null;
            final Uri uri = Uri.parse(named);
            if (!ContentResolver.SCHEME_CONTENT.equals(uri.getScheme())) return null;
            return openShortcutUri(context, shortcut, caller, uri, deadline);
        } catch (android.os.RemoteException | RuntimeException e) {
            return null;
        }
    }

    private static Bitmap openShortcutUri(Context context, android.content.pm.ShortcutInfo shortcut,
            String caller, Uri uri, long deadline) {
        final String authority = android.content.ContentProvider.getAuthorityWithoutUserId(uri.getAuthority());
        if (authority == null || authority.isEmpty()) return null;
        final int user = android.content.ContentProvider.getUserIdFromUri(uri, shortcut.getUserId());
        final String provider = user + "@" + authority;
        final java.util.concurrent.FutureTask<Bitmap> task = new java.util.concurrent.FutureTask<>(
                () -> android.os.SystemClock.uptimeMillis() < deadline
                        ? shortcutInput(context, shortcut, caller, null, uri, deadline) : null);
        synchronized (sShortcutAuthorities) {
            if (!reserveShortcutProvider(provider, deadline)) return null;
            try {
                sShortcutInputs.execute(() -> {
                    try {
                        task.run();
                    } finally {
                        synchronized (sShortcutAuthorities) {
                            sShortcutAuthorities.remove(provider);
                            sShortcutAuthorities.notifyAll();
                        }
                    }
                });
            } catch (java.util.concurrent.RejectedExecutionException e) {
                sShortcutAuthorities.remove(provider);
                sShortcutAuthorities.notifyAll();
                return null;
            }
        }
        try {
            return task.get(Math.max(0, deadline - android.os.SystemClock.uptimeMillis()),
                    java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
            // The caller has no image. The physical open keeps its authority until it exits.
        }
        task.cancel(true);
        return null;
    }

    // Called under sShortcutAuthorities. Only callers for this provider wait here.
    private static boolean reserveShortcutProvider(String provider, long deadline) {
        if (sShortcutAuthorities.add(provider)) return true;
        if (sShortcutWaiters >= 6) return false;
        sShortcutWaiters++;
        try {
            do {
                final long left = deadline - android.os.SystemClock.uptimeMillis();
                if (left <= 0) return false;
                sShortcutAuthorities.wait(left);
            } while (!sShortcutAuthorities.add(provider));
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            sShortcutWaiters--;
        }
    }

    private static Bitmap shortcutInput(Context context, android.content.pm.ShortcutInfo shortcut,
            String caller, ParcelFileDescriptor file, Uri uri, long deadline) {
        try (ParcelFileDescriptor input = file != null ? file : context.getContentResolver().openFileDescriptor(
                android.content.ContentProvider.maybeAddUserId(uri, shortcut.getUserId()), "r")) {
            if (input == null || android.os.SystemClock.uptimeMillis() >= deadline
                    || Thread.currentThread().isInterrupted()) return null;
            final Bundle extras = new Bundle();
            extras.putParcelable(EXTRA_FD, input);
            extras.putString(EXTRA_PACKAGE, shortcut.getPackage());
            extras.putInt(EXTRA_USER, shortcut.getUserId());
            extras.putString("shortcutId", shortcut.getId());
            extras.putString("callingPackage", caller);
            final Bundle reply = call(context, METHOD_SHORTCUT, extras);
            final Bitmap made = reply == null ? null : reply.getParcelable(EXTRA_BITMAP, Bitmap.class);
            if (Thread.currentThread().isInterrupted()) {
                if (made != null) made.recycle();
                return null;
            }
            return made;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** The non-reusable serial of a live Android user, or -1 when it cannot be resolved. */
    public static long userSerial(Context context, int user) {
        try {
            return user < 0 ? -1 : context.getSystemService(android.os.UserManager.class).getUserSerialNumber(user);
        } catch (RuntimeException e) {
            return -1;
        }
    }

    /** An exact key for the current incarnation of the requested user. */
    public static String key(Context context, String apk, String file, int density, int width, int height, int user) {
        return CleanCopyKey.forSerial(CleanCopyKey.of(apk, file, density, width, height, user),
                userSerial(context, user));
    }

    /** The pixels of a checked copy as an immutable bitmap with its density, or null. No decoder runs. */
    public static Bitmap toBitmap(CleanImageFile image) {
        if (image == null) {
            return null;
        }
        try {
            final Bitmap bitmap = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888, true,
                    ColorSpace.get(image.colorSpace == CleanImageFile.COLOR_DISPLAY_P3
                            ? ColorSpace.Named.DISPLAY_P3 : ColorSpace.Named.SRGB));
            bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(image.pixels));
            // Immutable, so caches can share it and nobody draws into it.
            final Bitmap kept = bitmap.copy(Bitmap.Config.ARGB_8888, false);
            bitmap.recycle();
            if (kept == null) {
                return null;
            }
            kept.setDensity(image.density == 0 ? Bitmap.DENSITY_NONE : image.density);
            return kept;
        } catch (RuntimeException | OutOfMemoryError e) {
            Log.w(TAG, "clean copy not made into a bitmap: " + e);
            return null;
        }
    }

    /**
     * Asks the system server, in the background, to make the copy of the file {@code file} of the APK at
     * {@code apkPath} (density, box), loaded as the resource {@code resourceName} (or null) as {@code userId}. The
     * system server finds the app the APK belongs to itself. A file asked for within the last minute is not asked for
     * again.
     */
    public static void request(Context context, String key, String apkPath, String file, int density,
            int maxWidth, int maxHeight, String resourceName, int userId) {
        if (key == null || apkPath == null || file == null) {
            return;
        }
        final long now = SystemClock.elapsedRealtime();
        synchronized (sAsked) {
            final Long asked = sAsked.get(key);
            if (asked != null && now - asked < ASK_AGAIN_MS) {
                return;
            }
            sAsked.put(key, now);
        }
        final Bundle extras = request(apkPath, file, density, maxWidth, maxHeight, resourceName, userId);
        final Context app = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        handler().post(() -> call(app, METHOD_REQUEST, extras));
    }

    private static Bundle request(String apkPath, String file, int density, int maxWidth, int maxHeight,
            String resourceName, int userId) {
        final Bundle extras = new Bundle();
        extras.putString(EXTRA_APK, apkPath);
        extras.putString(EXTRA_FILE, file);
        extras.putInt(EXTRA_DENSITY, density);
        extras.putInt(EXTRA_WIDTH, maxWidth);
        extras.putInt(EXTRA_HEIGHT, maxHeight);
        extras.putInt(EXTRA_USER, userId);
        if (resourceName != null) {
            extras.putString(EXTRA_RESOURCE, resourceName);
        }
        return extras;
    }

    /**
     * Has {@code callback} called (with {@link #EXTRA_PACKAGE} and {@link #EXTRA_USER}) whenever copies of a
     * package's images were made, for as long as this process lives. False when the system server cannot be asked
     * yet. Never on the main thread.
     */
    public static boolean watch(Context context, android.os.IRemoteCallback callback) {
        final Bundle extras = new Bundle();
        extras.putBinder(EXTRA_CALLBACK, callback.asBinder());
        final Bundle reply = call(context, METHOD_WATCH, extras);
        return reply != null && reply.getBoolean(METHOD_WATCH);
    }

    /** How many of {@code keys} have no copy yet and were not refused: all of them when the server cannot be asked. */
    public static int open(Context context, List<String> keys) {
        final Bundle extras = new Bundle();
        extras.putStringArrayList(EXTRA_KEYS, new ArrayList<>(keys));
        final Bundle reply = call(context, METHOD_STATUS, extras);
        return reply != null ? reply.getInt(EXTRA_OPEN, keys.size()) : keys.size();
    }

    /**
     * The system server's decode service answers again: files asked for during the outage may be asked for again at
     * once (their requests may have run out of time while it did not answer).
     */
    static void decodeAnswers() {
        synchronized (sAsked) {
            sAsked.evictAll();
        }
    }

    /** Removes the copies of the APK at {@code apkPath}, for tests that want them made again. */
    public static void forget(Context context, String apkPath) {
        synchronized (sAsked) {
            sAsked.evictAll();
        }
        final Bundle extras = new Bundle();
        extras.putString(EXTRA_APK, apkPath);
        call(context, METHOD_FORGET, extras);
    }

    private static Bundle call(Context context, String method, Bundle extras) {
        try {
            return context.getContentResolver().call(URI, method, null, extras);
        } catch (RuntimeException e) {
            Log.w(TAG, method + " not taken: " + e);
            return null;
        }
    }

    private static synchronized Handler handler() {
        if (sHandler == null) {
            final HandlerThread thread = new HandlerThread("CleanCopiesAsk", Process.THREAD_PRIORITY_BACKGROUND);
            thread.start();
            sHandler = new Handler(thread.getLooper());
        }
        return sHandler;
    }

    // Reads the whole stream (the system server writes the copy into a pipe) and closes it. Null when it is longer
    // than any copy can be.
    private static byte[] readAll(ParcelFileDescriptor fd) throws IOException {
        try (InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(fd)) {
            final java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(64 * 1024);
            final byte[] buffer = new byte[64 * 1024];
            int n;
            while ((n = in.read(buffer)) > 0) {
                if (out.size() + n > CleanImageFile.MAX_FILE_BYTES) {
                    return null;
                }
                out.write(buffer, 0, n);
            }
            return out.size() > 0 ? out.toByteArray() : null;
        }
    }
}
