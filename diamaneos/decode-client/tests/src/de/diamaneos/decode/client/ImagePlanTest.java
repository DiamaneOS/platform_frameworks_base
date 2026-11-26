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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.zip.CRC32;

/** How an app's image file is sized before the VM is asked. */
@RunWith(JUnit4.class)
public class ImagePlanTest {

    private static void chunk(ByteArrayOutputStream out, String type, byte[] data) {
        out.write(ByteBuffer.allocate(4).putInt(data.length).array(), 0, 4);
        final byte[] t = type.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        out.write(t, 0, 4);
        out.write(data, 0, data.length);
        final CRC32 crc = new CRC32();
        crc.update(t);
        crc.update(data);
        out.write(ByteBuffer.allocate(4).putInt((int) crc.getValue()).array(), 0, 4);
    }

    // A PNG header of w x h, with a valid nine-patch chunk when asked.
    private static byte[] png(int w, int h, boolean ninePatch) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(new byte[] {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10}, 0, 8);
        chunk(out, "IHDR", ByteBuffer.allocate(13).putInt(w).putInt(h).put((byte) 8).put((byte) 6).array());
        if (ninePatch) {
            final ByteBuffer np = ByteBuffer.allocate(32 + 16);
            np.put((byte) 0).put((byte) 2).put((byte) 2).put((byte) 0).putInt(0).putInt(0);
            np.putInt(0).putInt(0).putInt(0).putInt(0).putInt(0);
            np.putInt(1).putInt(2).putInt(1).putInt(2);
            chunk(out, "npTc", np.array());
        }
        chunk(out, "IDAT", new byte[] {1});
        chunk(out, "IEND", new byte[0]);
        return out.toByteArray();
    }

    @Test
    public void ninePatchWithinTheBox_atItsOwnSize() {
        final byte[] f = png(40, 20, true);
        final ImagePlan plan = ImagePlan.of(f, f.length, 1080, 960);
        assertNotNull(plan.chunk);
        assertEquals(40, plan.boxWidth);
        assertEquals(20, plan.boxHeight);
        assertEquals(800, plan.pixels);
    }

    @Test
    public void hugeNinePatch_shownPlainWithinTheBox() {
        // Any PNG becomes a "nine-patch" with one chunk: it must not get a full-size decode.
        final byte[] f = png(2896, 2896, true);
        final ImagePlan plan = ImagePlan.of(f, f.length, 1080, 960);
        assertNull(plan.chunk);
        assertEquals(960, plan.boxWidth);
        assertEquals(960, plan.boxHeight);
        assertTrue(plan.pixels <= 1080L * 960);
        assertEquals(960L * 960, plan.pixels);
        assertEquals(2896, plan.storedWidth);
    }

    @Test
    public void pngWithinTheBox_chargesItsOwnSize() {
        final byte[] f = png(100, 50, false);
        final ImagePlan plan = ImagePlan.of(f, f.length, 1080, 960);
        assertNull(plan.chunk);
        assertEquals(5_000, plan.pixels);
    }

    @Test
    public void notAPng_chargesTheBox() {
        final byte[] jpeg = {(byte) 0xff, (byte) 0xd8, (byte) 0xff, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0};
        final ImagePlan plan = ImagePlan.of(jpeg, jpeg.length, 300, 200);
        assertEquals(60_000, plan.pixels);
        assertEquals(300, plan.boxWidth);
        assertNull(plan.chunk);
        assertEquals(0, plan.storedWidth);
    }

    @Test
    public void theAskedBox_boundsEveryReply_byWhatIsCharged() {
        // The VM's reply is only checked against the box asked for: what is charged before the decode must cover
        // all of it, so the box is narrowed to what the header says the image needs.
        final int[][] sizes = {{100, 50}, {1080, 960}, {4000, 2000}, {2000, 4000}, {1, 65535}, {2896, 2896},
                {999, 7}, {1081, 961}};
        for (int[] size : sizes) {
            final byte[] f = png(size[0], size[1], false);
            final ImagePlan plan = ImagePlan.of(f, f.length, 1080, 960);
            assertEquals(size[0] + "x" + size[1], (long) plan.boxWidth * plan.boxHeight, plan.pixels);
            assertTrue(plan.boxWidth <= 1080 && plan.boxHeight <= 960);
            assertEquals(ImagePlan.fittedPixels(size[0], size[1], 1080, 960), plan.pixels);
        }
    }

    @Test
    public void fittedPixels() {
        assertEquals(1080L * 540, ImagePlan.fittedPixels(4000, 2000, 1080, 960));
        assertEquals(50L * 50, ImagePlan.fittedPixels(50, 50, 1080, 960));
        assertEquals(1L * 960, ImagePlan.fittedPixels(1, 65535, 1080, 960));
    }
}
