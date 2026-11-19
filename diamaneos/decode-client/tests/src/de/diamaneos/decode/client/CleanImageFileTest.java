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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.zip.Deflater;

/** The on-disk format of the clean copies: what is written is read back, and nothing else is ever taken. */
@RunWith(JUnit4.class)
public class CleanImageFileTest {
    private static final String KEY = "rfile:/data/app/~~a/p-b/base.apk:1:2:res/mipmap-xxxhdpi-v4/i.webp:640:1000x1000";

    private static CleanImageFile image(int w, int h, byte[] npTc, byte[] npLb) {
        final byte[] pixels = new byte[4 * w * h];
        for (int i = 0; i < pixels.length; i++) {
            pixels[i] = (byte) (i * 31 + 7);
        }
        return new CleanImageFile(KEY, w, h, 640, CleanImageFile.COLOR_SRGB, npTc, npLb, pixels);
    }

    @Test
    public void roundTrip_keepsEveryField() throws IOException {
        final byte[] npTc = new byte[36];
        npTc[1] = 1;
        final byte[] npLb = new byte[16];
        final CleanImageFile in = new CleanImageFile(KEY, 7, 3, 480, CleanImageFile.COLOR_DISPLAY_P3, npTc, npLb,
                image(7, 3, null, null).pixels);
        final byte[] file = in.write();
        final CleanImageFile out = CleanImageFile.read(file, file.length, KEY);
        assertNotNull(out);
        assertEquals(KEY, out.key);
        assertEquals(7, out.width);
        assertEquals(3, out.height);
        assertEquals(480, out.density);
        assertEquals(CleanImageFile.COLOR_DISPLAY_P3, out.colorSpace);
        assertArrayEquals(npTc, out.npTc);
        assertArrayEquals(npLb, out.npLb);
        assertArrayEquals(in.pixels, out.pixels);
        assertEquals(KEY, CleanImageFile.peekKey(file, file.length));
    }

    @Test
    public void anyChangedByte_isRefused() throws IOException {
        final byte[] file = image(16, 16, null, null).write();
        for (int i = 0; i < file.length; i += Math.max(1, file.length / 97)) {
            final byte[] bad = file.clone();
            bad[i] ^= 0x40;
            assertNull("byte " + i, CleanImageFile.read(bad, bad.length, KEY));
        }
    }

    @Test
    public void anotherKey_truncatedOrLonger_isRefused() throws IOException {
        final byte[] file = image(16, 16, null, null).write();
        assertNull(CleanImageFile.read(file, file.length, KEY + "x"));
        assertNull(CleanImageFile.read(file, file.length - 1, KEY));
        assertNull(CleanImageFile.read(Arrays.copyOf(file, file.length + 1), file.length + 1, KEY));
        assertNull(CleanImageFile.read(file, 10, KEY));
        assertNull(CleanImageFile.read(null, 0, KEY));
    }

    @Test
    public void invalidFields_areNotWritten() {
        assertFalse(new CleanImageFile(KEY, 0, 1, 0, 0, null, null, new byte[0]).valid());
        assertFalse(new CleanImageFile(KEY, 2, 2, 0, 0, null, null, new byte[15]).valid());
        assertFalse(new CleanImageFile(KEY, 2, 2, 0, 7, null, null, new byte[16]).valid());
        assertFalse(new CleanImageFile("", 2, 2, 0, 0, null, null, new byte[16]).valid());
        assertFalse(new CleanImageFile(KEY, 2048, 1024, 0, 0, null, null, null).valid());
        assertTrue(new CleanImageFile(KEY, 2, 2, 0, 0, null, null, new byte[16]).valid());
    }

    /** A hash-correct file whose pixels expand to more (or fewer) bytes than its size says is refused. */
    @Test
    public void pixelsOfAnotherSize_areRefused() throws Exception {
        assertNull(forge(4, 4, new byte[4 * 4 * 4 + 1]));
        assertNull(forge(4, 4, new byte[4 * 4 * 4 - 1]));
        assertNotNull(forge(4, 4, new byte[4 * 4 * 4]));
        // A large size claimed with little data, and a size over the cap.
        assertNull(forge(1024, 1024, new byte[64]));
        assertNull(forge(2048, 1024, new byte[16]));
    }

    @Test
    public void fileNames_arePlainAndDiffer() {
        final String a = CleanImageFile.fileName(KEY);
        final String b = CleanImageFile.fileName(KEY + "1");
        assertTrue(a.matches("[0-9a-f]{64}\\.dci"));
        assertNotEquals(a, b);
    }

    // A file with a correct hash, written by hand with any pixel payload.
    private static CleanImageFile forge(int w, int h, byte[] payload) throws Exception {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        final DataOutputStream out = new DataOutputStream(bytes);
        out.writeInt(CleanImageFile.MAGIC);
        out.writeUTF(KEY);
        out.writeInt(w);
        out.writeInt(h);
        out.writeInt(0);
        out.writeByte(0);
        out.writeInt(0);
        out.writeInt(0);
        final Deflater deflater = new Deflater();
        deflater.setInput(payload);
        deflater.finish();
        final byte[] buffer = new byte[payload.length + 1024];
        final int n = deflater.deflate(buffer);
        deflater.end();
        out.writeInt(n);
        out.write(buffer, 0, n);
        out.flush();
        final byte[] body = bytes.toByteArray();
        final byte[] hash = MessageDigest.getInstance("SHA-256").digest(body);
        final byte[] file = Arrays.copyOf(body, body.length + hash.length);
        System.arraycopy(hash, 0, file, body.length, hash.length);
        return CleanImageFile.read(file, file.length, KEY);
    }
}
