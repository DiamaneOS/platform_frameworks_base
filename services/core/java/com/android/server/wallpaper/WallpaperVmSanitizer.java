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
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.SELinux;
import android.system.ErrnoException;
import android.system.Os;
import android.util.Size;
import android.util.Slog;
import android.util.SparseArray;

import de.diamaneos.decode.client.VmDecode;
import de.diamaneos.decode.client.WallpaperBox;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A wallpaper image set by an app is decoded once, in the protected VM of the decode service, and replaced by a PNG
 * that Android's own encoder writes from the checked pixels. From then on the system server and SystemUI only ever
 * decode that clean file, whose structure comes from Android's encoder. The pixel values are the app's choice, as
 * they are for any picture, and cannot misdirect a decoder.
 *
 * <p>The clean file replaces the original by rename, so a file descriptor the app kept open reaches only the old,
 * unlinked file. The SHA-256 of every clean file is listed in {@link #CLEAN_LIST} in the wallpaper directory: a source
 * whose hash is not listed (an image stored before this change, a restore, a migration) is not decoded until the VM
 * has made it clean.
 */
final class WallpaperVmSanitizer {
    private static final String TAG = "WallpaperVm";

    /** The hashes of the clean wallpaper sources in one wallpaper directory. */
    static final String CLEAN_LIST = "wallpaper_vm_clean";
    private static final String TMP_SUFFIX = ".vmclean.tmp";
    private static final String ORIGINAL_SUFFIX = ".vmorig";

    // A clean file is a PNG of at most 8 MP (about 32 MB). Anything much larger is not hashed: not clean.
    private static final long MAX_CLEAN_BYTES = 64L * 1024 * 1024;

    /**
     * The app's original image, kept beside the clean file so that a later, sharper re-make in the VM stays
     * possible. It is never decoded on Android: no code reads it, and it goes when the wallpaper is cleared.
     */
    static File originalOf(File source) {
        return new File(source.getParentFile(), source.getName() + ORIGINAL_SUFFIX);
    }

    enum Result { CLEAN, UNAVAILABLE, REJECTED }

    /**
     * What {@link #sanitize} did. For a file it re-made: the stored size of the original (before its EXIF turn), the
     * clockwise turn applied, and the size of the clean file, to carry crop hints over.
     */
    static final class Outcome {
        final Result result;
        final boolean remade;
        final int originalWidth;
        final int originalHeight;
        final int rotation;
        final int width;
        final int height;

        Outcome(Result result) {
            this(result, false, 0, 0, 0, 0, 0);
        }

        Outcome(Result result, boolean remade, int originalWidth, int originalHeight, int rotation, int width,
                int height) {
            this.result = result;
            this.remade = remade;
            this.originalWidth = originalWidth;
            this.originalHeight = originalHeight;
            this.rotation = rotation;
            this.width = width;
            this.height = height;
        }
    }

    private final Context mContext;
    // Sources this class moved into place: their MOVED_TO events are its own, not a new image.
    private final Set<String> mOwnMoves = ConcurrentHashMap.newKeySet();

    // Hash of a source by path, valid while its length and modification time stay the same.
    private static final Map<String, String[]> sHashCache = new HashMap<>();

    WallpaperVmSanitizer(Context context) {
        mContext = context;
    }

    /** True when the MOVED_TO event for {@code path} in the wallpaper directory was this class renaming a file. */
    boolean consumeOwnMove(File dir, String path) {
        return mOwnMoves.remove(new File(dir, path).getPath());
    }

    /** True when {@code source} is a clean file made by this class (also after a move to another name). */
    static boolean isClean(File source) {
        if (source == null || !source.isFile() || source.length() > MAX_CLEAN_BYTES) {
            return false;
        }
        final String hash = hash(source);
        return hash != null && readList(source.getParentFile()).contains(hash);
    }

    /**
     * Makes {@code source} clean in the VM at display size: scaled so that {@code crop} (the crop hints, in the
     * original's stored pixels, the whole image when null or empty) just covers a display of {@code displayWidth} x
     * {@code displayHeight} in either orientation, never larger than the original (the service scales by any factor,
     * down to its pixel limit). The crop Android makes from it under its lock is then cheap. The original stays beside
     * it as the app gave it, never decoded on Android. Call on a worker thread: it waits for the VM. Never deletes
     * {@code source}: on any failure it stays as it is and is simply not decoded.
     */
    Outcome sanitize(File source, int displayWidth, int displayHeight, java.util.List<int[]> crops) {
        if (!source.isFile()) {
            return new Outcome(Result.REJECTED);
        }
        if (isClean(source)) {
            return new Outcome(Result.CLEAN);
        }
        final Size original;
        final VmDecode.Decoded decoded;
        try {
            original = VmDecode.probeFile(mContext, source, "", VmDecode.PRIORITY_USER_WAITING,
                    VmDecode.NO_DEADLINE);
            final int side = WallpaperBox.side(original.getWidth(), original.getHeight(), displayWidth,
                    displayHeight, crops, VmDecode.MAX_BOX);
            decoded = VmDecode.decodeFile(mContext, source, "", VmDecode.PRIORITY_USER_WAITING, side, side,
                    VmDecode.NO_DEADLINE);
        } catch (VmDecode.UnavailableException e) {
            Slog.w(TAG, "VM not available for " + source.getName() + ": " + e.getMessage());
            return new Outcome(Result.UNAVAILABLE);
        } catch (VmDecode.RejectedException e) {
            Slog.w(TAG, "Image not decoded for " + source.getName() + ": " + e.getMessage());
            return new Outcome(Result.REJECTED);
        }
        final Bitmap bitmap = decoded.bitmap;
        final File dir = source.getParentFile();
        final File tmp = new File(dir, source.getName() + TMP_SUFFIX);
        try {
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                    throw new IOException("PNG encoding failed");
                }
                out.getFD().sync();
            }
            final String hash = hash(tmp);
            if (hash == null) {
                throw new IOException("hash failed");
            }
            addToList(dir, hash, source);
            // Keep the original beside it (never decoded here), then move the clean file into place.
            Os.rename(source.getPath(), originalOf(source).getPath());
            mOwnMoves.add(source.getPath());
            Os.rename(tmp.getPath(), source.getPath());
            SELinux.restorecon(source);
            Slog.i(TAG, "Wallpaper " + source.getName() + " re-made from pixels decoded in the VM: "
                    + original.getWidth() + "x" + original.getHeight() + " turned " + decoded.rotation + " to "
                    + bitmap.getWidth() + "x" + bitmap.getHeight());
            return new Outcome(Result.CLEAN, true, original.getWidth(), original.getHeight(), decoded.rotation,
                    bitmap.getWidth(), bitmap.getHeight());
        } catch (IOException | ErrnoException e) {
            mOwnMoves.remove(source.getPath());
            // If the original was moved aside but the clean file did not follow, put the original back.
            final File kept = originalOf(source);
            if (!source.exists() && kept.exists()) {
                try {
                    mOwnMoves.add(source.getPath());
                    Os.rename(kept.getPath(), source.getPath());
                } catch (ErrnoException restore) {
                    mOwnMoves.remove(source.getPath());
                    Slog.e(TAG, "Could not put the original wallpaper back", restore);
                }
            }
            Slog.w(TAG, "Could not store the clean wallpaper " + source.getName(), e);
            return new Outcome(Result.UNAVAILABLE);
        } finally {
            tmp.delete();
            bitmap.recycle();
        }
    }

    /**
     * Every crop hint of {@code wallpaper} (one per screen orientation and size), in the original's stored pixels: the
     * clean copy is made big enough for the most demanding one. Empty when it has none (the whole image counts).
     */
    static java.util.List<int[]> cropHints(WallpaperData wallpaper) {
        final java.util.List<int[]> hints = new java.util.ArrayList<>();
        if (wallpaper.cropHint != null && !wallpaper.cropHint.isEmpty()) {
            hints.add(rect(wallpaper.cropHint));
        }
        for (int i = 0; wallpaper.mCropHints != null && i < wallpaper.mCropHints.size(); i++) {
            final Rect r = wallpaper.mCropHints.valueAt(i);
            if (r != null && !r.isEmpty()) {
                hints.add(rect(r));
            }
        }
        return hints;
    }

    private static int[] rect(Rect r) {
        return new int[] {r.left, r.top, r.right, r.bottom};
    }

    /**
     * Carries the crop hints of {@code wallpaper}, given in the original file's stored pixels (the cropper checks
     * them against the stored size, before any EXIF turn), over to the clean file: turned the same way, then scaled.
     */
    static void mapCropHints(WallpaperData wallpaper, Outcome outcome) {
        if (!outcome.remade || outcome.originalWidth <= 0 || outcome.originalHeight <= 0) {
            return;
        }
        if (!wallpaper.cropHint.isEmpty()) {
            wallpaper.cropHint.set(mapRect(wallpaper.cropHint, outcome));
        }
        final SparseArray<Rect> mapped = new SparseArray<>();
        for (int i = 0; i < wallpaper.mCropHints.size(); i++) {
            mapped.put(wallpaper.mCropHints.keyAt(i), mapRect(wallpaper.mCropHints.valueAt(i), outcome));
        }
        wallpaper.mCropHints = mapped;
    }

    /** {@code r} in the original's stored pixels, in the clean file's pixels. */
    static Rect mapRect(Rect r, Outcome o) {
        final int[] m = WallpaperBox.map(rect(r), o.originalWidth, o.originalHeight, o.rotation, o.width,
                o.height);
        return new Rect(m[0], m[1], m[2], m[3]);
    }

    private static String hash(File file) {
        final String key = file.getPath();
        final long length = file.length();
        final long modified = file.lastModified();
        synchronized (sHashCache) {
            final String[] cached = sHashCache.get(key);
            if (cached != null && cached[0].equals(length + ":" + modified)) {
                return cached[1];
            }
        }
        try (InputStream in = new FileInputStream(file)) {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            final byte[] buffer = new byte[64 * 1024];
            int n;
            while ((n = in.read(buffer)) > 0) {
                digest.update(buffer, 0, n);
            }
            final StringBuilder hex = new StringBuilder();
            for (byte b : digest.digest()) {
                hex.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
            }
            final String hash = hex.toString();
            synchronized (sHashCache) {
                sHashCache.put(key, new String[] {length + ":" + modified, hash});
            }
            return hash;
        } catch (IOException | NoSuchAlgorithmException e) {
            return null;
        }
    }

    private static Set<String> readList(File dir) {
        final Set<String> hashes = new HashSet<>();
        final File list = new File(dir, CLEAN_LIST);
        if (!list.isFile()) {
            return hashes;
        }
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(list), StandardCharsets.US_ASCII))) {
            String line;
            while ((line = reader.readLine()) != null && hashes.size() < 64) {
                line = line.trim();
                if (line.length() == 64) {
                    hashes.add(line);
                }
            }
        } catch (IOException e) {
            Slog.w(TAG, "Could not read " + list, e);
        }
        return hashes;
    }

    /** Adds {@code hash} and keeps only the hashes of sources still in the directory. */
    private static synchronized void addToList(File dir, String hash, File source) throws IOException {
        final Set<String> keep = new HashSet<>();
        keep.add(hash);
        final Set<String> listed = readList(dir);
        for (String name : new String[] {WallpaperUtils.WALLPAPER, WallpaperUtils.WALLPAPER_LOCK_ORIG}) {
            final File other = new File(dir, name);
            if (!other.equals(source) && other.isFile()) {
                final String otherHash = hash(other);
                if (otherHash != null && listed.contains(otherHash)) {
                    keep.add(otherHash);
                }
            }
        }
        final File list = new File(dir, CLEAN_LIST);
        final File tmp = new File(dir, CLEAN_LIST + TMP_SUFFIX);
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            for (String h : keep) {
                out.write((h + "\n").getBytes(StandardCharsets.US_ASCII));
            }
            out.getFD().sync();
        }
        try {
            Os.rename(tmp.getPath(), list.getPath());
        } catch (ErrnoException e) {
            tmp.delete();
            throw new IOException(e);
        }
    }
}
