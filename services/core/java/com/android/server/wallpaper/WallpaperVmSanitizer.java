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
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.os.SELinux;
import android.system.ErrnoException;
import android.system.Os;
import android.system.StructStat;
import android.util.LruCache;
import android.util.Size;
import android.util.Slog;
import android.util.SparseArray;

import com.android.internal.diamaneos.cleancopies.CleanFileStream;

import de.diamaneos.decode.client.VmDecode;
import de.diamaneos.decode.client.BoundedImageInput;
import de.diamaneos.sanitizer.decode.IDecodeService;
import de.diamaneos.decode.client.WallpaperBox;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
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

/**
 * A wallpaper image set by an app is decoded once, in the protected VM of the decode service, and replaced by a PNG
 * that Android's own encoder writes from the checked pixels. From then on the system server and SystemUI only ever
 * decode that clean file and the crop the system server makes from it, whose structure comes from Android's encoder.
 * The pixel values are the app's choice, as they are for any picture, and cannot misdirect a decoder. Both are decoded
 * as they are, without the VM: each is read whole, and only bytes whose SHA-256 is listed are decoded
 * ({@link CleanFileStream}). The system server reads them with {@link #openClean}, SystemUI gets the listed hash of
 * the file it is handed ({@link #openCheckedWallpaper}) and checks the bytes it reads against it.
 *
 * <p>The clean file replaces the original by rename, so a file descriptor the app kept open reaches only the old,
 * unlinked file. The SHA-256 of every clean file and of every crop made from one is listed in {@link #CLEAN_LIST} in
 * the wallpaper directory: a source whose hash is not listed (an image stored before this change, a restore, a
 * migration) is not decoded until the VM has made it clean.
 *
 * <p>The VM decodes a private snapshot of one generation of the source (its inode, size and modification time, and
 * the hash of its bytes). The clean file, the kept original and the crop hints are put in place only while the source
 * is still that generation, under the service's lock ({@link #publishLocked}): a job that read an older image never
 * replaces a newer one, nor brings back a cleared one.
 *
 * <p>A file an app was handed a writable descriptor for is never clean by its bytes, whatever its hash: the app may
 * keep the descriptor and change the bytes between the check and the decode. Only the file the VM step puts in place
 * by rename (an inode no app ever held) can be clean.
 */
final class WallpaperVmSanitizer {
    private static final String TAG = "WallpaperVm";

    /** The hashes of the clean wallpaper sources in one wallpaper directory. */
    static final String CLEAN_LIST = "wallpaper_vm_clean";
    private static final String TMP_SUFFIX = ".vmclean.tmp";
    private static final String SNAPSHOT_SUFFIX = ".vmsnap.tmp";
    private static final String ORIGINAL_SUFFIX = ".vmorig";

    // A clean file is a PNG of at most 8 MP (about 32 MB). Anything much larger is not hashed: not clean.
    private static final long MAX_CLEAN_BYTES = CleanFileStream.MAX_BYTES;

    /**
     * The app's original image, kept beside the clean file so that a later, sharper re-make in the VM stays
     * possible. It is never decoded on Android: no code reads it, and it goes when the wallpaper is cleared.
     */
    static File originalOf(File source) {
        return new File(source.getParentFile(), source.getName() + ORIGINAL_SUFFIX);
    }

    enum Result { CLEAN, UNAVAILABLE, REJECTED, STALE }

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

    /**
     * A clean copy made in the VM from one generation of a source, waiting to be put in place ({@link #publishLocked})
     * or dropped ({@link #discard}). Its {@code outcome} is CLEAN and remade.
     */
    static final class Prepared {
        final Outcome outcome;
        final File source;
        final File snapshot; // the source's bytes the VM decoded, the kept original once in place
        final File clean; // the PNG written from the VM's pixels
        final String generation; // the source's inode, size and times when it was read
        final String sourceHash; // the hash of the bytes read
        final String cleanHash;

        Prepared(Outcome outcome) {
            this(outcome, null, null, null, null, null, null);
        }

        Prepared(Outcome outcome, File source, File snapshot, File clean, String generation, String sourceHash,
                String cleanHash) {
            this.outcome = outcome;
            this.source = source;
            this.snapshot = snapshot;
            this.clean = clean;
            this.generation = generation;
            this.sourceHash = sourceHash;
            this.cleanHash = cleanHash;
        }
    }

    private final Context mContext;
    // Sources this class moved into place, with the time of the move: their MOVED_TO events are its own, not a new
    // image. An entry older than OWN_MOVE_MS is not trusted any more (its event never came), so a later genuine move
    // (a restore) is never swallowed by it.
    private final Map<String, long[]> mOwnMoves = new HashMap<>(); // path to move time and pending count
    private static final long OWN_MOVE_MS = 30_000;
    private static final LruCache<String, String> sCropSources = new LruCache<>(64);
    // The clean list of a directory, kept while the list file stays the same: (path, length, time) to hashes.
    private static final Map<String, Object[]> sListCache = new HashMap<>();

    // Hash of a source by path, valid while its inode, length and change times stay the same (see stamp).
    private static final Map<String, String[]> sHashCache = new HashMap<>();

    // Per wallpaper directory, the inodes ("dev:ino") an app was handed a writable descriptor for: never clean. In
    // memory only, as no app process outlives the system server. Kept to the inodes still at a source path.
    private static final Map<String, Set<String>> sAppWritable = new HashMap<>();

    WallpaperVmSanitizer(Context context) {
        mContext = context;
    }

    /** True when a source rename was performed by this service. */
    boolean consumeOwnMove(File dir, String path) {
        synchronized (mOwnMoves) {
            final String key = new File(dir, path).getPath();
            final long[] moves = mOwnMoves.get(key);
            if (moves == null) return false;
            final boolean current = SystemClock.uptimeMillis() - moves[0] <= OWN_MOVE_MS;
            if (!current || --moves[1] == 0) mOwnMoves.remove(key);
            return current;
        }
    }

    void moveToSource(File staged, File source) throws IOException {
        synchronized (mOwnMoves) {
            try {
                Os.rename(staged.getPath(), source.getPath());
            } catch (ErrnoException e) {
                throw new IOException(e);
            }
            final long now = SystemClock.uptimeMillis();
            mOwnMoves.values().removeIf(move -> now - move[0] > OWN_MOVE_MS);
            final long[] moves = mOwnMoves.computeIfAbsent(source.getPath(), ignored -> new long[2]);
            moves[0] = now;
            moves[1]++;
        }
    }

    /** True when {@code source} is a clean file made by this class (also after a move to another name). */
    static boolean isClean(File source) {
        return cleanHash(source) != null;
    }

    // The listed hash of {@code file}, a clean file or a crop of one, or null.
    private static String cleanHash(File file) {
        if (file == null || !file.isFile() || file.length() > MAX_CLEAN_BYTES) {
            return null;
        }
        final String inode = inodeOf(file);
        if (inode == null || isAppWritable(file.getParentFile(), inode)) {
            return null;
        }
        final String hash = hash(file);
        return hash != null && readList(file.getParentFile()).contains(hash) ? hash : null;
    }

    /**
     * {@code file} (a clean source or its crop) read whole, to be decoded here without the VM: only when what was
     * read is listed and its inode was never handed to an app for writing. Null otherwise.
     */
    static CleanFileStream openClean(File file) {
        try (FileInputStream in = new FileInputStream(file)) {
            final StructStat st = Os.fstat(in.getFD());
            if (isAppWritable(file.getParentFile(), st.st_dev + ":" + st.st_ino)) {
                return null;
            }
            return CleanFileStream.read(in.getFD(), readList(file.getParentFile()));
        } catch (IOException | ErrnoException e) {
            return null;
        }
    }

    /** Opens the checked inode occupying the requested slot, or returns unavailable. */
    static ParcelFileDescriptor openCheckedWallpaper(File source, File result, Bundle outParams)
            throws FileNotFoundException {
        final String expected = stamp(result);
        final String hash = cleanHash(result);
        if (!source.equals(result) && !isOwnCrop(source, result, hash)) return null;
        if (expected == null || hash == null) return null;
        final ParcelFileDescriptor opened = ParcelFileDescriptor.open(result, ParcelFileDescriptor.MODE_READ_ONLY);
        try {
            if (expected.equals(stamp(Os.fstat(opened.getFileDescriptor()))) && expected.equals(stamp(result))) {
                if (outParams != null) outParams.putString(CleanFileStream.EXTRA_SHA256, hash);
                return opened;
            }
        } catch (ErrnoException e) {
            Slog.w(TAG, "Could not verify the opened wallpaper", e);
        }
        libcore.io.IoUtils.closeQuietly(opened);
        return null;
    }

    // A stock home-to-lock move keeps both inodes and their modification times in the same user directory.
    private static String cropKey(File crop) {
        return crop.getParent() + '/' + generation(crop);
    }

    static void invalidateOwnCrop(File crop) {
        sCropSources.remove(cropKey(crop));
    }

    /** Records the digest produced by a successful crop of the checked source. */
    static boolean addOwnCrop(File source, String sourceGeneration, String sourceHash,
            File crop, String outputHash) {
        final String key = cropKey(crop);
        if (outputHash == null || sourceGeneration == null || !sourceGeneration.equals(generation(source))
                || !sourceHash.equals(cleanHash(source)) || !outputHash.equals(hash(crop))) {
            return false;
        }
        try {
            addToList(crop.getParentFile(), outputHash, crop);
            sCropSources.put(key, sourceGeneration + '/' + sourceHash + '/' + outputHash);
            return true;
        } catch (IOException e) {
            Slog.w(TAG, "Could not list the crop " + crop.getName(), e);
            return false;
        }
    }

    static boolean isOwnCrop(File source, File crop) {
        return isOwnCrop(source, crop, cleanHash(crop));
    }

    private static boolean isOwnCrop(File source, File crop, String cropHash) {
        final String sourceGeneration = generation(source);
        final String sourceHash = cleanHash(source);
        return cropHash != null && sourceGeneration != null && sourceHash != null
                && (sourceGeneration + '/' + sourceHash + '/' + cropHash).equals(sCropSources.get(cropKey(crop)));
    }

    /**
     * The generation of the file at {@code file}: its inode, size and modification time to the nanosecond, which any
     * write, truncation, rename or clear changes (an app can set that time only to now). Not the change time, which a
     * label or mode set again moves without new bytes. Null when there is no file.
     */
    static String generation(File file) {
        try {
            return generation(Os.stat(file.getPath()));
        } catch (ErrnoException e) {
            return null;
        }
    }

    private static String generation(StructStat st) {
        return st.st_dev + ":" + st.st_ino + ":" + st.st_size + ":" + st.st_mtim.tv_sec + "." + st.st_mtim.tv_nsec;
    }

    /**
     * Makes a clean copy of {@code source} in the VM at display size, from a private snapshot of its bytes taken while
     * it is the generation {@code generation} (null: whatever it is now): scaled so that {@code crop} (the crop hints,
     * in the original's stored pixels, the whole image when null or empty) just covers a display of
     * {@code displayWidth} x {@code displayHeight} in either orientation, never larger than the original (the service
     * scales by any factor, down to its pixel limit). The crop Android makes from it under its lock is then cheap.
     * Nothing is put in place here: a remade copy waits for {@link #publishLocked}. STALE when the source is another
     * generation already. Call on a worker thread: it waits for the VM. Never touches {@code source}: on any failure
     * it stays as it is and is simply not decoded. {@code userId}: the user whose wallpaper it is, the owner user of
     * the image in the VM (VmDecode OWNER_USER).
     */
    Prepared prepare(File source, String generation, int userId, int displayWidth, int displayHeight,
            java.util.List<int[]> crops) {
        if (!source.isFile()) {
            return new Prepared(new Outcome(generation != null ? Result.STALE : Result.REJECTED));
        }
        if (isClean(source)) {
            return new Prepared(new Outcome(Result.CLEAN));
        }
        final File dir = source.getParentFile();
        final File snapshot = new File(dir, source.getName() + SNAPSHOT_SUFFIX);
        final File tmp = new File(dir, source.getName() + TMP_SUFFIX);
        boolean kept = false;
        Bitmap bitmap = null;
        try {
            // One read of one generation: the VM decodes these bytes, and they become the kept original.
            final String[] read = snapshot(source, snapshot);
            if (read == null || (generation != null && !generation.equals(read[0]))) {
                return new Prepared(new Outcome(Result.STALE));
            }
            final Size original;
            final VmDecode.Decoded decoded;
            try {
                original = VmDecode.probeFile(mContext, snapshot, "", userId, VmDecode.PRIORITY_USER_WAITING,
                        VmDecode.NO_DEADLINE);
                final int side = WallpaperBox.side(original.getWidth(), original.getHeight(), displayWidth,
                        displayHeight, crops, VmDecode.MAX_BOX);
                decoded = VmDecode.decodeFile(mContext, snapshot, "", userId, VmDecode.PRIORITY_USER_WAITING,
                        side, side, VmDecode.NO_DEADLINE);
            } catch (VmDecode.UnavailableException e) {
                Slog.w(TAG, "VM not available for " + source.getName() + ": " + e.getMessage());
                return new Prepared(new Outcome(Result.UNAVAILABLE));
            } catch (VmDecode.RejectedException e) {
                Slog.w(TAG, "Image not decoded for " + source.getName() + ": " + e.getMessage());
                return new Prepared(new Outcome(Result.REJECTED));
            }
            bitmap = decoded.bitmap;
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
            // A new inode this class wrote itself. Its number may be that of a freed one an app once held.
            final String tmpInode = inodeOf(tmp);
            if (tmpInode == null) {
                throw new IOException("stat failed");
            }
            synchronized (sAppWritable) {
                final Set<String> held = sAppWritable.get(dir.getPath());
                if (held != null) {
                    held.remove(tmpInode);
                }
            }
            Slog.i(TAG, "Wallpaper " + source.getName() + " re-made from pixels decoded in the VM: "
                    + original.getWidth() + "x" + original.getHeight() + " turned " + decoded.rotation + " to "
                    + bitmap.getWidth() + "x" + bitmap.getHeight());
            kept = true;
            return new Prepared(new Outcome(Result.CLEAN, true, original.getWidth(), original.getHeight(),
                    decoded.rotation, bitmap.getWidth(), bitmap.getHeight()), source, snapshot, tmp, read[0], read[1],
                    hash);
        } catch (BoundedImageInput.TooLarge e) {
            Slog.w(TAG, "Wallpaper input exceeds the VM input limit: " + source.getName());
            return new Prepared(new Outcome(Result.REJECTED));
        } catch (IOException | ErrnoException e) {
            Slog.w(TAG, "Could not store the clean wallpaper " + source.getName(), e);
            return new Prepared(new Outcome(Result.UNAVAILABLE));
        } finally {
            if (!kept) {
                tmp.delete();
                snapshot.delete();
            }
            if (bitmap != null) {
                bitmap.recycle();
            }
        }
    }

    // Copies {@code source} into {@code snapshot} (a new file of the system server) through one descriptor, hashing
    // it: its generation and hash, or null when it changed while it was read.
    private static String[] snapshot(File source, File snapshot) throws IOException, ErrnoException {
        try (FileInputStream in = new FileInputStream(source)) {
            final StructStat stat = Os.fstat(in.getFD());
            if (!android.system.OsConstants.S_ISREG(stat.st_mode) || stat.st_size < 0
                    || stat.st_size > IDecodeService.MAX_INPUT_BYTES) {
                throw new BoundedImageInput.TooLarge();
            }
            final String before = generation(stat);
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (FileOutputStream out = new FileOutputStream(snapshot)) {
                BoundedImageInput.copy(new java.security.DigestInputStream(in, digest), out, stat.st_size,
                        IDecodeService.MAX_INPUT_BYTES);
                out.getFD().sync();
            }
            return before.equals(generation(Os.fstat(in.getFD())))
                    ? new String[] {before, hex(digest.digest())} : null;
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    /**
     * True when {@code prepared}'s source still holds the bytes its copy was made from: the same generation, and the
     * same hash read again. Off the service's lock (it reads the file): {@link #publishLocked} checks the generation
     * once more under it.
     */
    boolean stillCurrent(Prepared prepared) {
        return prepared.generation.equals(generation(prepared.source))
                && prepared.sourceHash.equals(hash(prepared.source))
                && prepared.generation.equals(generation(prepared.source));
    }

    /**
     * Puts {@code prepared}'s clean file in place of its source, with the snapshot as the kept original, and lists
     * its hash, only when the source is still the generation it was made from: else (a newer image, a clear) nothing
     * changes and the outcome is STALE. Under the service's lock, so no set or clear comes in between. The files of
     * {@code prepared} are taken or dropped either way.
     */
    Outcome publishLocked(Prepared prepared) {
        final File source = prepared.source;
        if (!prepared.generation.equals(generation(source))) {
            discard(prepared);
            Slog.i(TAG, "Wallpaper " + source.getName()
                    + " changed while the VM made it clean: left to the newer task");
            return new Outcome(Result.STALE);
        }
        final File dir = source.getParentFile();
        try {
            addToList(dir, prepared.cleanHash, source);
            // The bytes the VM decoded become the kept original, then the clean file takes the source's place with
            // one rename: the source is never missing, whatever happens in between.
            Os.rename(prepared.snapshot.getPath(), originalOf(source).getPath());
            moveToSource(prepared.clean, source);
            SELinux.restorecon(source);
            // The hash just made is the source's now: no second pass over the file under the service's lock.
            final String stamp = stamp(source);
            if (stamp != null) {
                synchronized (sHashCache) {
                    sHashCache.put(source.getPath(), new String[] {stamp, prepared.cleanHash});
                }
            }
            return prepared.outcome;
        } catch (IOException | ErrnoException e) {
            // The source is still the original: the rename is the last step and either happened or did not.
            discard(prepared);
            Slog.w(TAG, "Could not store the clean wallpaper " + source.getName(), e);
            return new Outcome(Result.UNAVAILABLE);
        }
    }

    /** Drops what {@code prepared} made and did not put in place. */
    static void discard(Prepared prepared) {
        if (prepared.clean != null) {
            prepared.clean.delete();
        }
        if (prepared.snapshot != null) {
            prepared.snapshot.delete();
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

    /**
     * {@code file} was just opened for an app with {@code fd} writable (WallpaperManagerService gives the app the
     * source to write into): its inode is never clean from now on, and the hash kept for its path is dropped.
     */
    static void onOpenedForApp(File file, FileDescriptor fd) {
        synchronized (sHashCache) {
            sHashCache.remove(file.getPath());
        }
        final File dir = file.getParentFile();
        final Set<String> atSources = new HashSet<>();
        for (String name : new String[] {WallpaperUtils.WALLPAPER, WallpaperUtils.WALLPAPER_LOCK_ORIG}) {
            final String at = inodeOf(new File(dir, name));
            if (at != null) {
                atSources.add(at);
            }
        }
        String inode;
        try {
            final StructStat st = Os.fstat(fd);
            inode = st.st_dev + ":" + st.st_ino;
        } catch (ErrnoException e) {
            Slog.w(TAG, "Could not stat the wallpaper file handed to an app " + file.getName(), e);
            inode = null;
        }
        synchronized (sAppWritable) {
            final Set<String> held = sAppWritable.computeIfAbsent(dir.getPath(), d -> new HashSet<>());
            // Only inodes still at a source path can be decoded: the others are gone for good.
            held.retainAll(atSources);
            if (inode != null) {
                held.add(inode);
            } else {
                // Not known which inode the app holds: no source here is clean until the VM re-makes it.
                held.addAll(atSources);
            }
        }
    }

    private static boolean isAppWritable(File dir, String inode) {
        synchronized (sAppWritable) {
            final Set<String> held = sAppWritable.get(dir.getPath());
            return held != null && held.contains(inode);
        }
    }

    // "dev:ino" of the file at {@code file}, or null.
    private static String inodeOf(File file) {
        try {
            final StructStat st = Os.stat(file.getPath());
            return st.st_dev + ":" + st.st_ino;
        } catch (ErrnoException e) {
            return null;
        }
    }

    // What must stay the same for a kept hash to hold: the inode, the length and both times to the nanosecond. The
    // change time cannot be set by any app, and any write or rename moves it. Null when the file cannot be read.
    private static String stamp(File file) {
        try {
            return stamp(Os.stat(file.getPath()));
        } catch (ErrnoException e) {
            return null;
        }
    }

    private static String stamp(StructStat st) {
        return st.st_dev + ":" + st.st_ino + ":" + st.st_size + ":" + st.st_mtim.tv_sec + "."
                + st.st_mtim.tv_nsec + ":" + st.st_ctim.tv_sec + "." + st.st_ctim.tv_nsec;
    }

    static String hex(byte[] bytes) {
        final StringBuilder hex = new StringBuilder();
        for (byte b : bytes) {
            hex.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
        }
        return hex.toString();
    }

    private static String hash(File file) {
        final String key = file.getPath();
        final String before = stamp(file);
        if (before == null) {
            return null;
        }
        synchronized (sHashCache) {
            final String[] cached = sHashCache.get(key);
            if (cached != null && cached[0].equals(before)) {
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
            final String hash = hex(digest.digest());
            // Kept only when the file did not change while it was read.
            if (before.equals(stamp(file))) {
                synchronized (sHashCache) {
                    sHashCache.put(key, new String[] {before, hash});
                }
            }
            return hash;
        } catch (IOException | NoSuchAlgorithmException e) {
            return null;
        }
    }

    // The hashes of the list file, read again only when the file changed (it is read under the service's lock).
    private static Set<String> readList(File dir) {
        final File list = new File(dir, CLEAN_LIST);
        final String stamp = list.length() + ":" + list.lastModified();
        synchronized (sListCache) {
            final Object[] cached = sListCache.get(list.getPath());
            if (cached != null && cached[0].equals(stamp)) {
                @SuppressWarnings("unchecked")
                final Set<String> hashes = (Set<String>) cached[1];
                return new HashSet<>(hashes);
            }
        }
        final Set<String> hashes = readListFile(list);
        synchronized (sListCache) {
            sListCache.put(list.getPath(), new Object[] {stamp, new HashSet<>(hashes)});
        }
        return hashes;
    }

    private static Set<String> readListFile(File list) {
        final Set<String> hashes = new HashSet<>();
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

    /** Adds {@code hash} and keeps only the hashes of sources and crops still in the directory. */
    private static synchronized void addToList(File dir, String hash, File source) throws IOException {
        final Set<String> keep = new HashSet<>();
        keep.add(hash);
        final Set<String> listed = readList(dir);
        for (String name : new String[] {WallpaperUtils.WALLPAPER, WallpaperUtils.WALLPAPER_LOCK_ORIG,
                WallpaperUtils.WALLPAPER_CROP, WallpaperUtils.WALLPAPER_LOCK_CROP}) {
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
