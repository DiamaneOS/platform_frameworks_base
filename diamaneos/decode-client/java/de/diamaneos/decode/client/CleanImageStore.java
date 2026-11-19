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

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.ColorSpace;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Comparator;

/**
 * Clean copies of apps' image files, kept on disk across restarts: the pixels the VM made for a file, under the same
 * key {@link AppResourceImages} caches it by in memory (APK path, time and size, file in the APK, density and box).
 * An app update, another theme, density or night mode, or an overlay makes Android pick another file or another APK,
 * so it is another key: a stale copy can never show. The files are this process's own (SystemUI's device-protected
 * storage, which no app can write) in a format of ours ({@link CleanImageFile}): reading one back runs no image
 * decoder, only zlib over data this process wrote, after its SHA-256 and key are checked.
 *
 * <p>Copies of APKs that are gone are removed once after a start, in the background, and the least recently used
 * copies go when the store is over its size. Never used on the main thread (disk).
 */
public final class CleanImageStore {
    private static final String TAG = "VmDecode";
    private static final String DIR = "clean-images";
    /** The store's size on disk. A splash's images are a few hundred KB compressed. */
    static final long MAX_BYTES = 64L * 1024 * 1024;

    private static CleanImageStore sInstance;

    private final File mDir;
    private final Object mLock = new Object();
    private long mBytes = -1; // guarded by mLock, -1 until counted
    private boolean mPruned; // guarded by mLock

    CleanImageStore(File dir) {
        mDir = dir;
    }

    /** The store of this process (SystemUI's), in its device-protected storage. */
    public static synchronized CleanImageStore get(Context context) {
        if (sInstance == null) {
            final Context storage = context.getApplicationContext() != null
                    ? context.getApplicationContext().createDeviceProtectedStorageContext()
                    : context.createDeviceProtectedStorageContext();
            sInstance = new CleanImageStore(new File(storage.getFilesDir(), DIR));
        }
        return sInstance;
    }

    /** One copy: its pixels and its nine-patch data. */
    public static final class Entry {
        public final Bitmap bitmap;
        public final NinePatchChunk chunk;

        Entry(Bitmap bitmap, NinePatchChunk chunk) {
            this.bitmap = bitmap;
            this.chunk = chunk;
        }
    }

    /** The copy kept under {@code key}, or null. Never on the main thread: null there. */
    public Entry load(String key) {
        if (key == null || Looper.myLooper() == Looper.getMainLooper()) {
            return null;
        }
        // No pruning here: this runs on the app start path, and a copy of an APK that is gone has a key nobody asks
        // for.
        final File file = new File(mDir, CleanImageFile.fileName(key));
        final byte[] bytes = readFile(file);
        if (bytes == null) {
            return null;
        }
        final CleanImageFile image = CleanImageFile.read(bytes, bytes.length, key);
        if (image == null) {
            Log.w(TAG, "clean copy not taken, removed: " + file.getName());
            remove(file);
            return null;
        }
        final NinePatchChunk chunk = image.npTc == null ? null
                : NinePatchChunk.fromChunks(image.npTc, image.npLb, image.width, image.height);
        if (image.npTc != null && chunk == null) {
            remove(file);
            return null;
        }
        final Bitmap bitmap;
        try {
            bitmap = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888, true,
                    ColorSpace.get(image.colorSpace == CleanImageFile.COLOR_DISPLAY_P3
                            ? ColorSpace.Named.DISPLAY_P3 : ColorSpace.Named.SRGB));
            bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(image.pixels));
        } catch (RuntimeException | OutOfMemoryError e) {
            Log.w(TAG, "clean copy not made into a bitmap: " + e);
            return null;
        }
        bitmap.setDensity(image.density == 0 ? Bitmap.DENSITY_NONE : image.density);
        // Last used now: it stays when older copies are trimmed.
        file.setLastModified(System.currentTimeMillis());
        if (VmDecode.madeLogging()) {
            VmDecode.madeLine("kept " + image.width + "x" + image.height + " clean copy from disk");
        }
        return new Entry(VmIcons.immutable(bitmap), chunk);
    }

    /** Keeps {@code bitmap} (pixels the VM made) and its nine-patch data under {@code key}. */
    public void save(String key, Bitmap bitmap, NinePatchChunk chunk) {
        if (key == null || bitmap == null || Looper.myLooper() == Looper.getMainLooper()) {
            return;
        }
        final byte[] bytes;
        try {
            bytes = toFile(key, bitmap, chunk);
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            Log.w(TAG, "clean copy not kept: " + e);
            return;
        }
        if (bytes == null) {
            return;
        }
        prepare();
        final File file = new File(mDir, CleanImageFile.fileName(key));
        final File temp = new File(mDir, file.getName() + ".tmp");
        synchronized (mLock) {
            if (!mDir.isDirectory() && !mDir.mkdirs()) {
                Log.w(TAG, "no clean copy folder");
                return;
            }
            final long before = file.length();
            try (FileOutputStream out = new FileOutputStream(temp)) {
                out.write(bytes);
                out.getFD().sync();
            } catch (IOException e) {
                Log.w(TAG, "clean copy not written: " + e);
                temp.delete();
                return;
            }
            if (!temp.renameTo(file)) {
                temp.delete();
                return;
            }
            mBytes += bytes.length - before;
            if (mBytes > MAX_BYTES) {
                trimLocked();
            }
        }
    }

    /** {@link #save}, unless a copy is kept under {@code key} already. */
    public void saveIfMissing(String key, Bitmap bitmap, NinePatchChunk chunk) {
        if (key != null && !new File(mDir, CleanImageFile.fileName(key)).exists()) {
            save(key, bitmap, chunk);
        }
    }

    // The file of a copy: the bitmap as ARGB_8888 in sRGB or Display P3.
    static byte[] toFile(String key, Bitmap bitmap, NinePatchChunk chunk) throws IOException {
        Bitmap pixels = bitmap;
        int colorSpace = CleanImageFile.COLOR_SRGB;
        final ColorSpace cs = bitmap.getColorSpace();
        final boolean p3 = cs != null && cs.equals(ColorSpace.get(ColorSpace.Named.DISPLAY_P3));
        final boolean srgb = cs == null || cs.equals(ColorSpace.get(ColorSpace.Named.SRGB));
        if (bitmap.getConfig() != Bitmap.Config.ARGB_8888 || (!p3 && !srgb)) {
            pixels = bitmap.copy(Bitmap.Config.ARGB_8888, false);
            if (pixels == null) {
                return null;
            }
            if (!p3 && !srgb && pixels.getColorSpace() != null
                    && !pixels.getColorSpace().equals(ColorSpace.get(ColorSpace.Named.SRGB))) {
                // Another space: drawn into sRGB, as Android would show it on an sRGB surface.
                final Bitmap converted = Bitmap.createBitmap(pixels.getWidth(), pixels.getHeight(),
                        Bitmap.Config.ARGB_8888, true, ColorSpace.get(ColorSpace.Named.SRGB));
                new android.graphics.Canvas(converted).drawBitmap(pixels, 0, 0, null);
                pixels = converted;
            }
        } else if (p3) {
            colorSpace = CleanImageFile.COLOR_DISPLAY_P3;
        }
        final int w = pixels.getWidth();
        final int h = pixels.getHeight();
        if ((long) w * h > CleanImageFile.MAX_PIXELS) {
            return null;
        }
        final ByteBuffer buffer = ByteBuffer.allocate(4 * w * h);
        pixels.copyPixelsToBuffer(buffer);
        final int density = bitmap.getDensity() == Bitmap.DENSITY_NONE ? 0 : bitmap.getDensity();
        final boolean keepChunk = chunk != null && chunk.fits(w, h);
        final CleanImageFile image = new CleanImageFile(key, w, h, density, colorSpace,
                keepChunk ? chunk.toFileFormat() : null, keepChunk ? chunk.insetsFileFormat() : null,
                buffer.array());
        return image.write();
    }

    /** Counts the store, and once per start removes the copies of APKs that are gone. On a background thread. */
    void prepare() {
        synchronized (mLock) {
            if (mPruned) {
                return;
            }
            mPruned = true;
            long bytes = 0;
            final File[] files = mDir.listFiles();
            if (files != null) {
                for (File f : files) {
                    if (f.getName().endsWith(".tmp")) {
                        f.delete();
                        continue;
                    }
                    final String apk = apkOf(f);
                    if (apk == null || !new File(apk).exists()) {
                        f.delete();
                        continue;
                    }
                    bytes += f.length();
                }
            }
            mBytes = bytes;
            if (mBytes > MAX_BYTES) {
                trimLocked();
            }
        }
    }

    // The least recently used copies go until the store is at three quarters of its size.
    private void trimLocked() {
        final File[] files = mDir.listFiles();
        if (files == null) {
            return;
        }
        Arrays.sort(files, Comparator.comparingLong(File::lastModified));
        long bytes = 0;
        for (File f : files) {
            bytes += f.length();
        }
        for (File f : files) {
            if (bytes <= MAX_BYTES * 3 / 4) {
                break;
            }
            final long length = f.length();
            if (f.delete()) {
                bytes -= length;
            }
        }
        mBytes = bytes;
    }

    private void remove(File file) {
        synchronized (mLock) {
            final long length = file.length();
            if (file.delete() && mBytes >= 0) {
                mBytes -= length;
            }
        }
    }

    /** Removes every copy (tests, or when the store's format changes). */
    void clear() {
        synchronized (mLock) {
            final File[] files = mDir.listFiles();
            if (files != null) {
                for (File f : files) {
                    f.delete();
                }
            }
            mBytes = 0;
        }
    }

    // The APK path in the key of a copy ("rfile:" + path + ':' + time ...), or null.
    private static String apkOf(File f) {
        final byte[] head = new byte[8192];
        int n;
        try (FileInputStream in = new FileInputStream(f)) {
            n = in.read(head);
        } catch (IOException e) {
            return null;
        }
        if (n <= 0) {
            return null;
        }
        final String key = CleanImageFile.peekKey(head, n);
        if (key == null || !key.startsWith(AppResourceImages.KEY_PREFIX)) {
            return null;
        }
        final int end = key.indexOf(':', AppResourceImages.KEY_PREFIX.length());
        return end > AppResourceImages.KEY_PREFIX.length()
                ? key.substring(AppResourceImages.KEY_PREFIX.length(), end) : null;
    }

    private static byte[] readFile(File file) {
        final long length = file.length();
        if (length <= 0 || length > CleanImageFile.MAX_FILE_BYTES) {
            return null;
        }
        final byte[] bytes = new byte[(int) length];
        try (FileInputStream in = new FileInputStream(file)) {
            int off = 0;
            while (off < bytes.length) {
                final int n = in.read(bytes, off, bytes.length - off);
                if (n < 0) {
                    return null;
                }
                off += n;
            }
            return bytes;
        } catch (IOException e) {
            return null;
        }
    }
}
