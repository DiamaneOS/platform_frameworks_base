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

import android.graphics.Bitmap;
import android.graphics.ColorSpace;
import android.util.Log;

import com.android.internal.diamaneos.cleancopies.CleanCopyClient;
import com.android.internal.diamaneos.cleancopies.CleanCopyKey;
import com.android.internal.diamaneos.cleancopies.CleanImageFile;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Clean copies of apps' image files, kept on disk across restarts: the pixels the VM made for a file, under the same
 * key {@link AppResourceImages} caches it by in memory (APK path, time and size, file in the APK, density and box).
 * An app update, another theme, density or night mode, or an overlay makes Android pick another file or another APK,
 * so it is another key: a stale copy is never taken for the exact file. The system server keeps the store (in
 * /data/system, which no app can reach) and hands its files to other system processes ({@link CleanImages}). The format
 * is ours ({@link CleanImageFile}): reading one back runs no image decoder, only zlib over data the system server
 * wrote, after its SHA-256 and key are checked.
 *
 * <p>Each copy also names the resource it is of ({@code CleanCopyKey.identity}). While the exact copy of a file is
 * made, the newest copy of the same resource stands in ({@link #fallback}), and a new copy replaces the older copies
 * of the same resource at the same density. An app's copies go when it is removed, copies of APKs that are gone go
 * once after a start (unless they still stand in for their app's resource), one APK's copies are capped, and the
 * least recently used copies go when the store is over its size.
 */
public final class CleanImageStore implements CleanCopies {
    private static final String TAG = "VmDecode";
    /** The store's size on disk. */
    static final long MAX_BYTES = 64L * 1024 * 1024;
    /** One APK's copies at most, so one app's images cannot push out every other app's. */
    static final long MAX_APK_BYTES = 4L * 1024 * 1024;
    // A copy read again is marked used on disk (its file time) at most this often. In memory every read counts.
    private static final long TOUCH_MS = 60 * 60 * 1000;

    /** {@link #saveCopy}: kept. */
    public static final int SAVED = 0;
    /** {@link #saveCopy}: not kept, the APK's copies are at their cap and the copy may not make room. */
    public static final int OVER_CAP = 1;
    /** {@link #saveCopy}: not kept, too large or not written (tried again later). */
    public static final int NOT_WRITTEN = 2;

    private final File mDir;
    private final Object mLock = new Object();
    // By file name: changed under mLock, read without it (touch).
    private final Map<String, Info> mFiles = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, Long> mApkBytes = new HashMap<>(); // by APK path, guarded by mLock
    private long mBytes; // guarded by mLock
    private boolean mIndexed; // guarded by mLock

    // What the store knows of one file, from its header.
    private static final class Info {
        final String name;
        final String key;
        final String identity;
        final String apk;
        final int density;
        final long length;
        volatile long used; // the last read, in memory
        volatile long usedOnDisk; // the file time

        Info(String name, String key, String identity, int density, long length, long used) {
            this.name = name;
            this.key = key;
            this.identity = identity;
            this.apk = CleanCopyKey.apkOf(key);
            this.density = density;
            this.length = length;
            this.used = used;
            this.usedOnDisk = used;
        }
    }

    /** The store in {@code dir}: the system server's, or a test's. */
    public CleanImageStore(File dir) {
        mDir = dir;
    }

    /** The file a copy under {@code key} is kept in (it may not exist). */
    public File fileFor(String key) {
        return new File(mDir, CleanImageFile.fileName(key));
    }

    /** The file named {@code name} (as {@link CleanImageFile#fileName} makes them), or null for any other name. */
    public File fileNamed(String name) {
        return name != null && name.matches("[0-9a-f]{64}\\.dci") ? new File(mDir, name) : null;
    }

    /** The copy kept under {@code key}, or null. */
    @Override
    public Entry load(String key) {
        if (key == null) {
            return null;
        }
        final File file = fileFor(key);
        final byte[] bytes = readFile(file);
        if (bytes == null) {
            return null;
        }
        final Entry entry = entryOf(bytes, key);
        if (entry == null) {
            Log.w(TAG, "clean copy not taken, removed: " + file.getName());
            remove(file.getName());
            return null;
        }
        touch(file.getName());
        return entry;
    }

    /** The key of the newest copy of {@code identity}, the closest to {@code density}, or null. */
    public String fallbackKey(String identity, int density) {
        if (identity == null || identity.isEmpty()) {
            return null;
        }
        synchronized (mLock) {
            indexLocked();
            Info best = null;
            for (Info info : mFiles.values()) {
                if (!identity.equals(info.identity)) {
                    continue;
                }
                if (best == null || Math.abs(info.density - density) < Math.abs(best.density - density)
                        || (info.density == best.density && info.used > best.used)) {
                    best = info;
                }
            }
            return best == null ? null : best.key;
        }
    }

    /** Marks the copy in the file {@code name} as used now (at most once an hour), for the trim order. */
    public void touch(String name) {
        // Without the lock: a read on a thread that shows something never waits for a save's write.
        final long now = System.currentTimeMillis();
        final Info info = mFiles.get(name);
        if (info == null) {
            return;
        }
        info.used = now;
        if (now - info.usedOnDisk >= TOUCH_MS) {
            info.usedOnDisk = now;
            new File(mDir, name).setLastModified(now);
        }
    }

    /** The copy in a file's {@code bytes} when it is whole and kept under {@code key}, else null. No decoder runs. */
    static Entry entryOf(byte[] bytes, String key) {
        return entryOf(CleanImageFile.read(bytes, bytes.length, key));
    }

    /** A checked copy as pixels and nine-patch data, or null. */
    static Entry entryOf(CleanImageFile image) {
        if (image == null) {
            return null;
        }
        final NinePatchChunk chunk = image.npTc == null ? null
                : NinePatchChunk.fromChunks(image.npTc, image.npLb, image.width, image.height);
        if (image.npTc != null && chunk == null) {
            return null;
        }
        final Bitmap bitmap = CleanCopyClient.toBitmap(image);
        if (bitmap == null) {
            return null;
        }
        if (VmDecode.madeLogging()) {
            VmDecode.madeLine("kept " + image.width + "x" + image.height + " clean copy");
        }
        return new Entry(bitmap, chunk);
    }

    /** True when a copy is kept under {@code key}. */
    public boolean has(String key) {
        return key != null && fileFor(key).exists();
    }

    /** Removes every copy of the APK at {@code apkPath} (a test that wants its images made again). */
    public void forgetApk(String apkPath) {
        synchronized (mLock) {
            indexLocked();
            for (Info info : new ArrayList<>(mFiles.values())) {
                if (apkPath.equals(info.apk)) {
                    removeLocked(info.name);
                }
            }
        }
    }

    /** Removes every copy of an app: it is gone for every user. */
    public void forgetOwner(String ownerPackage) {
        synchronized (mLock) {
            indexLocked();
            for (Info info : new ArrayList<>(mFiles.values())) {
                if (ownerPackage.equals(CleanCopyKey.ownerOf(info.identity))) {
                    removeLocked(info.name);
                }
            }
        }
    }

    /** Keeps {@code bitmap} (pixels the VM made) and its nine-patch data under {@code key}. */
    @Override
    public void save(String key, Bitmap bitmap, NinePatchChunk chunk) {
        saveCopy(key, "", bitmap, chunk, System.currentTimeMillis());
    }

    /** Reads every copy's header now (the system server does it once, early, on its own thread). */
    public void index() {
        synchronized (mLock) {
            indexLocked();
        }
    }

    /** True when the copy under {@code key} is whole (its hash and key check out), else it is removed. */
    public boolean verify(String key) {
        final File file = fileFor(key);
        final byte[] bytes = readFile(file);
        if (bytes != null && CleanImageFile.read(bytes, bytes.length, key) != null) {
            return true;
        }
        Log.w(TAG, "clean copy does not check out, removed: " + file.getName());
        remove(file.getName());
        return false;
    }

    /** {@link #save}, unless a copy is kept under {@code key} already. */
    @Override
    public void saveIfMissing(String key, Bitmap bitmap, NinePatchChunk chunk) {
        if (key != null && !has(key)) {
            save(key, bitmap, chunk);
        }
    }

    /**
     * Keeps a copy of the resource {@code identity} (or none), marked used at {@code used} (the trim order: the
     * least recently used go first): older copies of the same resource at the same density (an older version of the
     * app's file) go, and the APK's least used copies go when its copies would pass their cap. False when it was not
     * kept (larger than the cap alone, a write that failed).
     */
    public boolean saveCopy(String key, String identity, Bitmap bitmap, NinePatchChunk chunk, long used) {
        return saveCopy(key, identity, bitmap, chunk, used, true) == SAVED;
    }

    /**
     * {@link #saveCopy}, and {@code mayEvict}: whether the copy may make room among its APK's least recently read
     * copies when they are at their cap. A copy that may not (the warm-up's) is refused instead ({@link #OVER_CAP}),
     * so the warm-up never pushes out a copy it made a moment ago. The file is written and synced before the lock is
     * taken: readers never wait for a write.
     */
    public int saveCopy(String key, String identity, Bitmap bitmap, NinePatchChunk chunk, long used,
            boolean mayEvict) {
        if (key == null || bitmap == null) {
            return NOT_WRITTEN;
        }
        final byte[] bytes;
        try {
            bytes = toFile(key, identity == null ? "" : identity, bitmap, chunk);
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            Log.w(TAG, "clean copy not kept: " + e);
            return NOT_WRITTEN;
        }
        if (bytes == null) {
            return NOT_WRITTEN;
        }
        if (bytes.length > MAX_APK_BYTES) {
            Log.w(TAG, "clean copy not kept: larger than an APK's copies may be");
            return OVER_CAP;
        }
        // The store is indexed before the temporary file is written: the first index removes every temporary file
        // it finds (left by a write that never ended).
        synchronized (mLock) {
            indexLocked();
        }
        final File file = fileFor(key);
        final File temp = new File(mDir, file.getName() + "." + Thread.currentThread().getId() + ".tmp");
        if (!mDir.isDirectory() && !mDir.mkdirs()) {
            Log.w(TAG, "no clean copy folder");
            return NOT_WRITTEN;
        }
        try (FileOutputStream out = new FileOutputStream(temp)) {
            out.write(bytes);
            out.getFD().sync();
        } catch (IOException e) {
            Log.w(TAG, "clean copy not written: " + e);
            temp.delete();
            return NOT_WRITTEN;
        }
        synchronized (mLock) {
            indexLocked();
            final String apk = CleanCopyKey.apkOf(key);
            if (apk != null) {
                // Over the APK's cap: its least recently read copies make room (never another APK's), when this copy
                // may evict.
                final Info same = mFiles.get(file.getName());
                long apkBytes = mApkBytes.getOrDefault(apk, 0L) - (same != null ? same.length : 0);
                if (apkBytes + bytes.length > MAX_APK_BYTES && !mayEvict) {
                    temp.delete();
                    return OVER_CAP;
                }
                if (apkBytes + bytes.length > MAX_APK_BYTES) {
                    final List<Info> own = new ArrayList<>();
                    for (Info info : mFiles.values()) {
                        if (apk.equals(info.apk) && !info.name.equals(file.getName())) {
                            own.add(info);
                        }
                    }
                    own.sort(Comparator.comparingLong(i -> i.used));
                    for (Info info : own) {
                        if (apkBytes + bytes.length <= MAX_APK_BYTES) {
                            break;
                        }
                        apkBytes -= info.length;
                        removeLocked(info.name);
                    }
                }
            }
            if (!temp.renameTo(file)) {
                Log.w(TAG, "clean copy not kept: its file could not be put in place");
                temp.delete();
                return NOT_WRITTEN;
            }
            file.setLastModified(used);
            forgetLocked(file.getName());
            final Info info = new Info(file.getName(), key, identity == null ? "" : identity,
                    bitmap.getDensity() == Bitmap.DENSITY_NONE ? 0 : bitmap.getDensity(), bytes.length, used);
            addLocked(info);
            // The copy replaces older copies of the same resource at the same density.
            if (!info.identity.isEmpty()) {
                for (Info other : new ArrayList<>(mFiles.values())) {
                    if (other != info && info.identity.equals(other.identity) && other.density == info.density
                            && other.apk != null && !other.apk.equals(info.apk)) {
                        removeLocked(other.name);
                    }
                }
            }
            if (mBytes > MAX_BYTES) {
                trimLocked();
            }
            return SAVED;
        }
    }

    // The file of a copy: the bitmap as ARGB_8888 in sRGB or Display P3.
    static byte[] toFile(String key, String identity, Bitmap bitmap, NinePatchChunk chunk) throws IOException {
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
        final CleanImageFile image = new CleanImageFile(key, identity, w, h, density, colorSpace,
                keepChunk ? chunk.toFileFormat() : null, keepChunk ? chunk.insetsFileFormat() : null,
                buffer.array());
        return image.write();
    }

    /**
     * Indexes the store and removes copies of APKs that are gone: kept only while their app is installed
     * ({@code installed}, by package) and they are the newest copy of their resource at their density, to stand in
     * until the new one is made. Once per start, on a background thread.
     */
    public void prepare(Predicate<String> installed) {
        synchronized (mLock) {
            indexLocked();
            for (Info info : new ArrayList<>(mFiles.values())) {
                if (info.apk != null && new File(info.apk).exists()) {
                    continue;
                }
                final String owner = CleanCopyKey.ownerOf(info.identity);
                boolean keep = owner != null && installed != null && installed.test(owner);
                if (keep) {
                    for (Info other : mFiles.values()) {
                        if (other != info && info.identity.equals(other.identity) && other.density == info.density
                                && other.used > info.used) {
                            keep = false;
                            break;
                        }
                    }
                }
                if (!keep) {
                    removeLocked(info.name);
                }
            }
            if (mBytes > MAX_BYTES) {
                trimLocked();
            }
        }
    }

    // Reads every copy's header once (a background thread asks first: prepare, a save or a fallback).
    private void indexLocked() {
        if (mIndexed) {
            return;
        }
        mIndexed = true;
        mFiles.clear();
        mApkBytes.clear();
        mBytes = 0;
        final File[] files = mDir.listFiles();
        if (files == null) {
            return;
        }
        for (File f : files) {
            if (f.getName().endsWith(".tmp")) {
                f.delete();
                continue;
            }
            final String[] head = peek(f);
            if (head == null || fileNamed(f.getName()) == null
                    || !f.getName().equals(CleanImageFile.fileName(head[0]))) {
                f.delete();
                continue;
            }
            int density;
            try {
                density = Integer.parseInt(head[2]);
            } catch (NumberFormatException e) {
                density = 0;
            }
            addLocked(new Info(f.getName(), head[0], head[1], density, f.length(), f.lastModified()));
        }
    }

    private void addLocked(Info info) {
        mFiles.put(info.name, info);
        mBytes += info.length;
        if (info.apk != null) {
            mApkBytes.merge(info.apk, info.length, Long::sum);
        }
    }

    // Drops a file from the index (not from disk).
    private void forgetLocked(String name) {
        final Info info = mFiles.remove(name);
        if (info == null) {
            return;
        }
        mBytes -= info.length;
        if (info.apk != null) {
            final long left = mApkBytes.getOrDefault(info.apk, 0L) - info.length;
            if (left > 0) {
                mApkBytes.put(info.apk, left);
            } else {
                mApkBytes.remove(info.apk);
            }
        }
    }

    // The least recently used copies go until the store is at three quarters of its size.
    private void trimLocked() {
        final List<Info> all = new ArrayList<>(mFiles.values());
        all.sort(Comparator.comparingLong(i -> i.used));
        for (Info info : all) {
            if (mBytes <= MAX_BYTES * 3 / 4) {
                break;
            }
            removeLocked(info.name);
        }
    }

    private void remove(String name) {
        synchronized (mLock) {
            removeLocked(name);
        }
    }

    private void removeLocked(String name) {
        forgetLocked(name);
        new File(mDir, name).delete();
    }

    /** Removes every copy (tests, or when the store's format changes). */
    public void clear() {
        synchronized (mLock) {
            final File[] files = mDir.listFiles();
            if (files != null) {
                for (File f : files) {
                    f.delete();
                }
            }
            mFiles.clear();
            mApkBytes.clear();
            mBytes = 0;
            mIndexed = true;
        }
    }

    // The key, identity and density of a copy, from its header, or null.
    private static String[] peek(File f) {
        final byte[] head = new byte[8192];
        int n;
        try (FileInputStream in = new FileInputStream(f)) {
            n = in.read(head);
        } catch (IOException e) {
            return null;
        }
        return n <= 0 ? null : CleanImageFile.peek(head, n);
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
