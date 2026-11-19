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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * One clean copy of an app's image file as {@link CleanImageStore} keeps it on disk: the pixels the VM made, never a
 * file format an image decoder reads. The layout is ours: a header, the raw pixels (ARGB_8888 as
 * {@code Bitmap.copyPixelsToBuffer} writes them) compressed with zlib, and a SHA-256 over all of it. A file is taken
 * only when its hash, its key and every field check out, so a damaged or foreign file is never shown. Plain Java,
 * tested on the host.
 */
public final class CleanImageFile {
    /** "DCI1": DiamaneOS clean image, version 1. */
    static final int MAGIC = 0x44434931;
    /** The most pixels one copy may hold: a cache entry's (see {@code VmIcons.cacheableBox}). */
    public static final int MAX_PIXELS = 1024 * 1024;
    /** The longest side, so a narrow copy cannot be absurdly long either. */
    public static final int MAX_SIDE = 8192;
    /** The largest file taken: the pixels uncompressed plus room for the header, enough for any honest copy. */
    public static final int MAX_FILE_BYTES = 4 * MAX_PIXELS + 256 * 1024;
    private static final int MAX_KEY_CHARS = 4096;
    private static final int MAX_CHUNK_BYTES = 64 * 1024;
    private static final int HASH_BYTES = 32;

    /** sRGB. */
    public static final int COLOR_SRGB = 0;
    /** Display P3. */
    public static final int COLOR_DISPLAY_P3 = 1;

    public final String key;
    public final int width;
    public final int height;
    /** The bitmap's density, {@code 0} for none. */
    public final int density;
    /** {@link #COLOR_SRGB} or {@link #COLOR_DISPLAY_P3}. */
    public final int colorSpace;
    /** The nine-patch chunk as a PNG file stores it ("npTc"), or null. */
    public final byte[] npTc;
    /** The optical insets as a PNG file stores them ("npLb"), or null. */
    public final byte[] npLb;
    /** The pixels, {@code 4 * width * height} bytes. */
    public final byte[] pixels;

    public CleanImageFile(String key, int width, int height, int density, int colorSpace, byte[] npTc, byte[] npLb,
            byte[] pixels) {
        this.key = key;
        this.width = width;
        this.height = height;
        this.density = density;
        this.colorSpace = colorSpace;
        this.npTc = npTc;
        this.npLb = npLb;
        this.pixels = pixels;
    }

    /** True when the fields describe a copy this format may hold. */
    public boolean valid() {
        return key != null && !key.isEmpty() && key.length() <= MAX_KEY_CHARS
                && width >= 1 && height >= 1 && width <= MAX_SIDE && height <= MAX_SIDE
                && (long) width * height <= MAX_PIXELS
                && density >= 0 && density <= 0xffff
                && (colorSpace == COLOR_SRGB || colorSpace == COLOR_DISPLAY_P3)
                && (npTc == null || (npTc.length > 0 && npTc.length <= MAX_CHUNK_BYTES))
                && (npLb == null || (npLb.length > 0 && npLb.length <= MAX_CHUNK_BYTES))
                && pixels != null && pixels.length == 4 * width * height;
    }

    /** The file's bytes. Throws when the fields are not {@link #valid}. */
    public byte[] write() throws IOException {
        if (!valid()) {
            throw new IOException("not a valid clean image");
        }
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream(pixels.length / 4 + 256);
        final DataOutputStream out = new DataOutputStream(bytes);
        out.writeInt(MAGIC);
        out.writeUTF(key);
        out.writeInt(width);
        out.writeInt(height);
        out.writeInt(density);
        out.writeByte(colorSpace);
        writeBlock(out, npTc);
        writeBlock(out, npLb);
        final byte[] deflated = deflate(pixels);
        out.writeInt(deflated.length);
        out.write(deflated);
        out.flush();
        final byte[] body = bytes.toByteArray();
        final byte[] hash = sha256(body, body.length);
        final byte[] file = Arrays.copyOf(body, body.length + HASH_BYTES);
        System.arraycopy(hash, 0, file, body.length, HASH_BYTES);
        return file;
    }

    /**
     * The copy in {@code file[0..length)} when it is a whole, unchanged file of this format for {@code expectedKey},
     * else null. Never throws on what the file holds.
     */
    public static CleanImageFile read(byte[] file, int length, String expectedKey) {
        if (file == null || expectedKey == null || length <= HASH_BYTES || length > file.length
                || length > MAX_FILE_BYTES) {
            return null;
        }
        final int bodyLength = length - HASH_BYTES;
        if (!MessageDigest.isEqual(sha256(file, bodyLength),
                Arrays.copyOfRange(file, bodyLength, length))) {
            return null;
        }
        try {
            final DataInputStream in = new DataInputStream(new ByteArrayInputStream(file, 0, bodyLength));
            if (in.readInt() != MAGIC) {
                return null;
            }
            final String key = in.readUTF();
            if (!expectedKey.equals(key)) {
                return null;
            }
            final int width = in.readInt();
            final int height = in.readInt();
            final int density = in.readInt();
            final int colorSpace = in.readUnsignedByte();
            if (width < 1 || height < 1 || width > MAX_SIDE || height > MAX_SIDE
                    || (long) width * height > MAX_PIXELS) {
                return null;
            }
            final byte[] npTc = readBlock(in);
            final byte[] npLb = readBlock(in);
            final int deflatedLength = in.readInt();
            if (deflatedLength < 1 || deflatedLength > in.available()) {
                return null;
            }
            final byte[] deflated = new byte[deflatedLength];
            in.readFully(deflated);
            if (in.available() != 0) {
                return null;
            }
            final byte[] pixels = inflate(deflated, 4 * width * height);
            if (pixels == null) {
                return null;
            }
            final CleanImageFile image =
                    new CleanImageFile(key, width, height, density, colorSpace, npTc, npLb, pixels);
            return image.valid() ? image : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** The key of the copy in {@code file[0..length)} without checking the rest (for pruning), or null. */
    public static String peekKey(byte[] file, int length) {
        try {
            final DataInputStream in = new DataInputStream(new ByteArrayInputStream(file, 0, length));
            if (in.readInt() != MAGIC) {
                return null;
            }
            return in.readUTF();
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** The file name of {@code key} in the store: hex of its SHA-256, so any key maps to a plain name. */
    public static String fileName(String key) {
        final byte[] utf = key.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        final byte[] hash = sha256(utf, utf.length);
        final StringBuilder name = new StringBuilder(2 * hash.length + 4);
        for (byte b : hash) {
            name.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
        }
        return name.append(".dci").toString();
    }

    private static void writeBlock(DataOutputStream out, byte[] block) throws IOException {
        if (block == null) {
            out.writeInt(0);
            return;
        }
        out.writeInt(block.length);
        out.write(block);
    }

    private static byte[] readBlock(DataInputStream in) throws IOException {
        final int length = in.readInt();
        if (length == 0) {
            return null;
        }
        if (length < 0 || length > MAX_CHUNK_BYTES || length > in.available()) {
            throw new IOException("bad block");
        }
        final byte[] block = new byte[length];
        in.readFully(block);
        return block;
    }

    private static byte[] deflate(byte[] data) {
        final Deflater deflater = new Deflater(Deflater.BEST_SPEED);
        try {
            deflater.setInput(data);
            deflater.finish();
            final ByteArrayOutputStream out = new ByteArrayOutputStream(data.length / 4 + 64);
            final byte[] buffer = new byte[64 * 1024];
            while (!deflater.finished()) {
                final int n = deflater.deflate(buffer);
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        } finally {
            deflater.end();
        }
    }

    // Exactly `expected` bytes or null: a stream that ends early, runs long or is not whole zlib data is refused.
    private static byte[] inflate(byte[] data, int expected) {
        final Inflater inflater = new Inflater();
        try {
            inflater.setInput(data);
            final byte[] out = new byte[expected];
            int filled = 0;
            while (filled < expected) {
                final int n = inflater.inflate(out, filled, expected - filled);
                if (n <= 0) {
                    // Ended early, wants more input or a dictionary: not a whole copy.
                    return null;
                }
                filled += n;
            }
            // Nothing may follow the pixels.
            final byte[] extra = new byte[1];
            if (!inflater.finished() && inflater.inflate(extra) > 0) {
                return null;
            }
            if (!inflater.finished() || inflater.getRemaining() != 0) {
                return null;
            }
            return out;
        } catch (DataFormatException e) {
            return null;
        } finally {
            inflater.end();
        }
    }

    private static byte[] sha256(byte[] data, int length) {
        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(data, 0, length);
            return digest.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
