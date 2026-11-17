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

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.zip.CRC32;

/**
 * The stretch and padding data of a nine-patch PNG ("npTc") and its optical insets ("npLb", layout bounds), read in
 * plain Java from an app's file and checked, so a nine-patch decoded in the VM keeps its stretching and its optical
 * bounds. Only checked numbers leave this class: the bytes Android's nine-patch code gets are written here, never the
 * app's. Plain Java, tested on the host.
 */
public final class NinePatchChunk {
    private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10};
    private static final int HEADER_BYTES = 32; // sizeof(Res_png_9patch)
    private static final int MAX_CHUNKS = 4096;
    private static final int MAX_SIDE = 65535;

    /** The size of the image the chunk belongs to (IHDR). */
    public final int width;
    public final int height;
    private final int[] mXDivs;
    private final int[] mYDivs;
    public final int paddingLeft;
    public final int paddingRight;
    public final int paddingTop;
    public final int paddingBottom;
    // Optical insets (left, top, right, bottom), or null when the file has none.
    private int[] mOpticalInsets;

    private NinePatchChunk(int width, int height, int[] xDivs, int[] yDivs, int left, int right, int top,
            int bottom) {
        this.width = width;
        this.height = height;
        mXDivs = xDivs;
        mYDivs = yDivs;
        paddingLeft = left;
        paddingRight = right;
        paddingTop = top;
        paddingBottom = bottom;
    }

    public int[] xDivs() {
        return mXDivs.clone();
    }

    public int[] yDivs() {
        return mYDivs.clone();
    }

    /** The optical insets (left, top, right, bottom), or null when the file has none. */
    public int[] opticalInsets() {
        return mOpticalInsets == null ? null : mOpticalInsets.clone();
    }

    /**
     * The width and height in the header of the PNG file in {@code png[0..length)}, or null when it is not a PNG.
     * Read without decoding anything, to size a decode before it is asked for.
     */
    public static int[] pngSize(byte[] png, int length) {
        if (!isPng(png, length)) {
            return null;
        }
        final int pos = PNG_SIGNATURE.length;
        if (readUInt(png, pos) != 13 || !isType(png, pos + 4, "IHDR")) {
            return null;
        }
        final long width = readUInt(png, pos + 8);
        final long height = readUInt(png, pos + 12);
        if (width < 1 || height < 1 || width > MAX_SIDE || height > MAX_SIDE) {
            return null;
        }
        return new int[] {(int) width, (int) height};
    }

    private static boolean isPng(byte[] png, int length) {
        if (png == null || length < 0 || length > png.length || length < PNG_SIGNATURE.length + 25) {
            return false;
        }
        for (int i = 0; i < PNG_SIGNATURE.length; i++) {
            if (png[i] != PNG_SIGNATURE[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * The checked nine-patch chunk of the PNG file in {@code png[0..length)}, or null when the file is not a PNG, has
     * no chunk, or has one that does not hold together (then it is shown as a plain image).
     */
    public static NinePatchChunk fromPng(byte[] png, int length) {
        if (!isPng(png, length)) {
            return null;
        }
        int pos = PNG_SIGNATURE.length;
        int width = -1;
        int height = -1;
        NinePatchChunk chunk = null;
        int[] insets = null;
        boolean badInsets = false;
        for (int n = 0; n < MAX_CHUNKS && pos + 12 <= length; n++) {
            final long chunkLength = readUInt(png, pos);
            if (chunkLength > length - pos - 12) {
                return null;
            }
            final int dataStart = pos + 8;
            final int dataLength = (int) chunkLength;
            if (n == 0) {
                if (!isType(png, pos + 4, "IHDR") || dataLength != 13) {
                    return null;
                }
                width = (int) Math.min(Integer.MAX_VALUE, readUInt(png, dataStart));
                height = (int) Math.min(Integer.MAX_VALUE, readUInt(png, dataStart + 4));
                if (width < 1 || height < 1 || width > MAX_SIDE || height > MAX_SIDE) {
                    return null;
                }
            } else if (isType(png, pos + 4, "npTc")) {
                if (chunk != null) {
                    return null;
                }
                chunk = parse(png, dataStart, dataLength, width, height);
                if (chunk == null) {
                    return null;
                }
            } else if (isType(png, pos + 4, "npLb")) {
                insets = parseInsets(png, dataStart, dataLength, width, height);
                badInsets |= insets == null;
            } else if (isType(png, pos + 4, "IDAT") || isType(png, pos + 4, "IEND")) {
                // Android reads the chunks only before the image data.
                break;
            }
            pos = dataStart + dataLength + 4;
        }
        if (chunk != null && !badInsets) {
            chunk.mOpticalInsets = insets;
        }
        // Optical insets that do not hold together: the nine-patch is shown without them.
        return chunk;
    }

    // Optical insets as a PNG stores them: left, top, right, bottom, in network byte order.
    private static int[] parseInsets(byte[] b, int start, int length, int width, int height) {
        if (length != 16) {
            return null;
        }
        final int left = readInt(b, start);
        final int top = readInt(b, start + 4);
        final int right = readInt(b, start + 8);
        final int bottom = readInt(b, start + 12);
        if (!inRange(left, width) || !inRange(right, width) || !inRange(top, height) || !inRange(bottom, height)
                || left + right > width || top + bottom > height) {
            return null;
        }
        return new int[] {left, top, right, bottom};
    }

    /** The optical insets chunk data for a PNG file ("npLb"), or null when there are none. */
    public byte[] insetsFileFormat() {
        if (mOpticalInsets == null) {
            return null;
        }
        final ByteBuffer out = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN);
        for (int inset : mOpticalInsets) {
            out.putInt(inset);
        }
        return out.array();
    }

    // The chunk as a PNG file stores it: a 32-byte header, then the divisions and colours in network byte order.
    private static NinePatchChunk parse(byte[] b, int start, int length, int width, int height) {
        if (length < HEADER_BYTES) {
            return null;
        }
        final int numX = b[start + 1] & 0xff;
        final int numY = b[start + 2] & 0xff;
        final int numColors = b[start + 3] & 0xff;
        if (length != HEADER_BYTES + 4 * (numX + numY + numColors)) {
            return null;
        }
        if (numX % 2 != 0 || numY % 2 != 0) {
            return null;
        }
        final int left = readInt(b, start + 12);
        final int right = readInt(b, start + 16);
        final int top = readInt(b, start + 20);
        final int bottom = readInt(b, start + 24);
        final int[] xDivs = readDivs(b, start + HEADER_BYTES, numX, width);
        final int[] yDivs = readDivs(b, start + HEADER_BYTES + 4 * numX, numY, height);
        if (xDivs == null || yDivs == null) {
            return null;
        }
        if (!inRange(left, width) || !inRange(right, width) || !inRange(top, height) || !inRange(bottom, height)) {
            return null;
        }
        // The colours are only drawing hints: they are not kept (Android draws the pixels instead).
        return new NinePatchChunk(width, height, xDivs, yDivs, left, right, top, bottom);
    }

    private static int[] readDivs(byte[] b, int start, int count, int limit) {
        final int[] divs = new int[count];
        int previous = 0;
        for (int i = 0; i < count; i++) {
            final int div = readInt(b, start + 4 * i);
            if (div < previous || div > limit) {
                return null;
            }
            divs[i] = div;
            previous = div;
        }
        return divs;
    }

    private static boolean inRange(int value, int limit) {
        return value >= 0 && value <= limit;
    }

    /** Whether a decoded image of {@code w} x {@code h} can carry this chunk: only at the file's own size. */
    public boolean fits(int w, int h) {
        return w == width && h == height;
    }

    /** The chunk data for a PNG file ("npTc"), as Android's PNG decoder reads it. */
    public byte[] toFileFormat() {
        return write(ByteOrder.BIG_ENDIAN);
    }

    /**
     * The chunk as Android keeps it in memory ({@code Bitmap.getNinePatchChunk}, {@code NinePatch}): the same layout
     * in {@code order} (the device's own), with the offsets filled in.
     */
    public byte[] toDeviceFormat(ByteOrder order) {
        return write(order);
    }

    private byte[] write(ByteOrder order) {
        final int numX = mXDivs.length;
        final int numY = mYDivs.length;
        final ByteBuffer out = ByteBuffer.allocate(HEADER_BYTES + 4 * (numX + numY)).order(order);
        out.put((byte) 0); // wasDeserialized
        out.put((byte) numX);
        out.put((byte) numY);
        out.put((byte) 0); // no colour hints
        final int xOffset = HEADER_BYTES;
        final int yOffset = xOffset + 4 * numX;
        final int colorsOffset = yOffset + 4 * numY;
        out.putInt(xOffset);
        out.putInt(yOffset);
        out.putInt(paddingLeft);
        out.putInt(paddingRight);
        out.putInt(paddingTop);
        out.putInt(paddingBottom);
        out.putInt(colorsOffset);
        for (int div : mXDivs) {
            out.putInt(div);
        }
        for (int div : mYDivs) {
            out.putInt(div);
        }
        return out.array();
    }

    /**
     * {@code png} (a file this process wrote) with a chunk of {@code type} and {@code data} added right after its
     * header, or null when {@code png} does not start with a PNG header.
     */
    public static byte[] insertChunk(byte[] png, String type, byte[] data) {
        final int ihdrEnd = PNG_SIGNATURE.length + 25;
        if (png == null || png.length < ihdrEnd || type.length() != 4) {
            return null;
        }
        for (int i = 0; i < PNG_SIGNATURE.length; i++) {
            if (png[i] != PNG_SIGNATURE[i]) {
                return null;
            }
        }
        if (!isType(png, PNG_SIGNATURE.length + 4, "IHDR") || readUInt(png, PNG_SIGNATURE.length) != 13) {
            return null;
        }
        final ByteArrayOutputStream out = new ByteArrayOutputStream(png.length + data.length + 12);
        out.write(png, 0, ihdrEnd);
        final byte[] typeBytes = new byte[4];
        for (int i = 0; i < 4; i++) {
            typeBytes[i] = (byte) type.charAt(i);
        }
        writeUInt(out, data.length);
        out.write(typeBytes, 0, 4);
        out.write(data, 0, data.length);
        final CRC32 crc = new CRC32();
        crc.update(typeBytes);
        crc.update(data);
        writeUInt(out, crc.getValue());
        out.write(png, ihdrEnd, png.length - ihdrEnd);
        return out.toByteArray();
    }

    private static boolean isType(byte[] b, int pos, String type) {
        for (int i = 0; i < 4; i++) {
            if (b[pos + i] != (byte) type.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    private static long readUInt(byte[] b, int pos) {
        return ((long) (b[pos] & 0xff) << 24) | ((b[pos + 1] & 0xff) << 16) | ((b[pos + 2] & 0xff) << 8)
                | (b[pos + 3] & 0xff);
    }

    private static int readInt(byte[] b, int pos) {
        return (int) readUInt(b, pos);
    }

    private static void writeUInt(ByteArrayOutputStream out, long value) {
        out.write((int) (value >>> 24) & 0xff);
        out.write((int) (value >>> 16) & 0xff);
        out.write((int) (value >>> 8) & 0xff);
        out.write((int) value & 0xff);
    }
}
