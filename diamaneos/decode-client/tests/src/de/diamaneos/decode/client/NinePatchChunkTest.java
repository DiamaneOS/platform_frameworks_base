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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.zip.CRC32;

/** Reading and writing the stretch data of nine-patch PNGs. */
@RunWith(JUnit4.class)
public class NinePatchChunkTest {
    private static final byte[] SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10};

    private static void chunk(ByteArrayOutputStream out, String type, byte[] data) {
        final ByteBuffer length = ByteBuffer.allocate(4).putInt(data.length);
        out.write(length.array(), 0, 4);
        final byte[] t = type.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        out.write(t, 0, 4);
        out.write(data, 0, data.length);
        final CRC32 crc = new CRC32();
        crc.update(t);
        crc.update(data);
        out.write(ByteBuffer.allocate(4).putInt((int) crc.getValue()).array(), 0, 4);
    }

    private static byte[] ihdr(int w, int h) {
        return ByteBuffer.allocate(13).putInt(w).putInt(h).put((byte) 8).put((byte) 6).array();
    }

    // The chunk as aapt2 writes it: header, then divisions and colours in network byte order.
    private static byte[] npTc(int[] xDivs, int[] yDivs, int[] padding, int colors) {
        final ByteBuffer b = ByteBuffer.allocate(32 + 4 * (xDivs.length + yDivs.length + colors))
                .order(ByteOrder.BIG_ENDIAN);
        b.put((byte) 0).put((byte) xDivs.length).put((byte) yDivs.length).put((byte) colors);
        b.putInt(0).putInt(0);
        for (int p : padding) {
            b.putInt(p);
        }
        b.putInt(0);
        for (int d : xDivs) {
            b.putInt(d);
        }
        for (int d : yDivs) {
            b.putInt(d);
        }
        for (int i = 0; i < colors; i++) {
            b.putInt(1);
        }
        return b.array();
    }

    private static byte[] png(int w, int h, byte[] chunkData, boolean afterData) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(SIGNATURE, 0, SIGNATURE.length);
        chunk(out, "IHDR", ihdr(w, h));
        if (chunkData != null && !afterData) {
            chunk(out, "npTc", chunkData);
        }
        chunk(out, "IDAT", new byte[] {1, 2, 3});
        if (chunkData != null && afterData) {
            chunk(out, "npTc", chunkData);
        }
        chunk(out, "IEND", new byte[0]);
        return out.toByteArray();
    }

    @Test
    public void readsAndChecks() {
        final byte[] file = png(8, 6, npTc(new int[] {2, 5}, new int[] {1, 4}, new int[] {1, 2, 0, 3}, 9), false);
        final NinePatchChunk chunk = NinePatchChunk.fromPng(file, file.length);
        assertNotNull(chunk);
        assertEquals(8, chunk.width);
        assertEquals(6, chunk.height);
        assertArrayEquals(new int[] {2, 5}, chunk.xDivs());
        assertArrayEquals(new int[] {1, 4}, chunk.yDivs());
        assertEquals(1, chunk.paddingLeft);
        assertEquals(2, chunk.paddingRight);
        assertEquals(0, chunk.paddingTop);
        assertEquals(3, chunk.paddingBottom);
        assertTrue(chunk.fits(8, 6));
        assertTrue(!chunk.fits(4, 3));
    }

    @Test
    public void rejectsWhatDoesNotHoldTogether() {
        final int[] pad = {0, 0, 0, 0};
        // Not a PNG, no chunk, chunk after the image data, truncated.
        assertNull(NinePatchChunk.fromPng(new byte[40], 40));
        assertNull(NinePatchChunk.fromPng(png(8, 8, null, false), png(8, 8, null, false).length));
        byte[] f = png(8, 8, npTc(new int[] {2, 5}, new int[] {2, 5}, pad, 0), true);
        assertNull(NinePatchChunk.fromPng(f, f.length));
        f = png(8, 8, npTc(new int[] {2, 5}, new int[] {2, 5}, pad, 0), false);
        assertNull(NinePatchChunk.fromPng(f, 40));
        // Divisions out of order, past the image, odd in number.
        f = png(8, 8, npTc(new int[] {5, 2}, new int[] {2, 5}, pad, 0), false);
        assertNull(NinePatchChunk.fromPng(f, f.length));
        f = png(8, 8, npTc(new int[] {2, 9}, new int[] {2, 5}, pad, 0), false);
        assertNull(NinePatchChunk.fromPng(f, f.length));
        f = png(8, 8, npTc(new int[] {2, 5, 6}, new int[] {2, 5}, pad, 0), false);
        assertNull(NinePatchChunk.fromPng(f, f.length));
        // Negative or too large padding.
        f = png(8, 8, npTc(new int[] {2, 5}, new int[] {2, 5}, new int[] {-1, 0, 0, 0}, 0), false);
        assertNull(NinePatchChunk.fromPng(f, f.length));
        f = png(8, 8, npTc(new int[] {2, 5}, new int[] {2, 5}, new int[] {0, 0, 0, 9}, 0), false);
        assertNull(NinePatchChunk.fromPng(f, f.length));
        // A length that does not match the counts.
        final byte[] data = npTc(new int[] {2, 5}, new int[] {2, 5}, pad, 0);
        data[3] = 4;
        f = png(8, 8, data, false);
        assertNull(NinePatchChunk.fromPng(f, f.length));
        // A chunk length past the end of the file.
        f = png(8, 8, npTc(new int[] {2, 5}, new int[] {2, 5}, pad, 0), false);
        f[8 + 25 + 3] = 127;
        assertNull(NinePatchChunk.fromPng(f, f.length));
    }

    @Test
    public void writesFileAndDeviceFormat() {
        final byte[] file = png(8, 6, npTc(new int[] {2, 5}, new int[] {1, 4}, new int[] {1, 2, 0, 3}, 9), false);
        final NinePatchChunk chunk = NinePatchChunk.fromPng(file, file.length);

        // File format: no colour hints, read back the same.
        final byte[] fileFormat = chunk.toFileFormat();
        assertEquals(32 + 16, fileFormat.length);
        final byte[] again = png(8, 6, fileFormat, false);
        final NinePatchChunk read = NinePatchChunk.fromPng(again, again.length);
        assertArrayEquals(chunk.xDivs(), read.xDivs());
        assertArrayEquals(chunk.yDivs(), read.yDivs());
        assertEquals(3, read.paddingBottom);

        // Device format: offsets filled in, values in the given order.
        final ByteBuffer device = ByteBuffer.wrap(chunk.toDeviceFormat(ByteOrder.LITTLE_ENDIAN))
                .order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(2, device.get(1));
        assertEquals(2, device.get(2));
        assertEquals(0, device.get(3));
        assertEquals(32, device.getInt(4));
        assertEquals(40, device.getInt(8));
        assertEquals(1, device.getInt(12));
        assertEquals(2, device.getInt(16));
        assertEquals(0, device.getInt(20));
        assertEquals(3, device.getInt(24));
        assertEquals(48, device.getInt(28));
        assertEquals(2, device.getInt(32));
        assertEquals(5, device.getInt(36));
        assertEquals(1, device.getInt(40));
        assertEquals(4, device.getInt(44));
    }

    @Test
    public void insertsAChunkAfterTheHeader() {
        final byte[] plain = png(8, 6, null, false);
        final byte[] data = npTc(new int[] {2, 5}, new int[] {1, 4}, new int[] {0, 0, 0, 0}, 0);
        final byte[] withChunk = NinePatchChunk.insertChunk(plain, "npTc", data);
        assertNotNull(withChunk);
        assertEquals(plain.length + data.length + 12, withChunk.length);
        final NinePatchChunk read = NinePatchChunk.fromPng(withChunk, withChunk.length);
        assertNotNull(read);
        assertArrayEquals(new int[] {2, 5}, read.xDivs());
        // The CRC is right.
        final CRC32 crc = new CRC32();
        crc.update(withChunk, 33 + 4, 4 + data.length);
        assertEquals((int) crc.getValue(), ByteBuffer.wrap(withChunk, 33 + 8 + data.length, 4).getInt());
        assertNull(NinePatchChunk.insertChunk(new byte[40], "npTc", data));
    }
}
