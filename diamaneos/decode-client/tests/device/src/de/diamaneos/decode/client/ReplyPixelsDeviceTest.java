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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.graphics.Bitmap;
import android.graphics.DecodeMemory;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import de.diamaneos.sanitizer.decode.IDecodeService;
import de.diamaneos.sanitizer.decode.Thumbnail;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * The pixels of a decode service reply (ReplyPixels), from a service played here: stripes put in a block at a time
 * give exactly the image (and its gain map), a caller that left stops the reading between stripes, the memory is
 * reserved first (no room now is asked again, too large ever is refused), and a provider's turn is a quarter turn or
 * refused.
 */
@RunWith(AndroidJUnit4.class)
public class ReplyPixelsDeviceTest {
    private static final long HANDLE = 7;

    @After
    public void asBefore() {
        DecodeMemory.setMaxForTest(0);
        Thread.interrupted();
    }

    /** A reply of width x height in stripes of stripeRows, pixel (x, y) = (x, y, x + y, 255) premultiplied. */
    private static final class Service extends IDecodeService.Default {
        final Thumbnail thumbnail = new Thumbnail();
        final AtomicInteger stripesRead = new AtomicInteger();
        final AtomicInteger gainStripesRead = new AtomicInteger();
        volatile int interruptAt = -1;

        Service(int width, int height, int stripeRows) {
            thumbnail.status = IDecodeService.STATUS_OK;
            thumbnail.width = width;
            thumbnail.height = height;
            thumbnail.stripeRows = stripeRows;
            thumbnail.handle = HANDLE;
            thumbnail.gainmapParams = new float[0];
        }

        void withGainmap(int width, int height, int stripeRows) {
            thumbnail.gainmapWidth = width;
            thumbnail.gainmapHeight = height;
            thumbnail.gainmapStripeRows = stripeRows;
            final float[] p = new float[IDecodeService.GAINMAP_PARAMS];
            for (int c = 0; c < 3; c++) {
                p[c] = 1;
                p[3 + c] = 4;
                p[6 + c] = 1;
                p[9 + c] = 0.015625f;
                p[12 + c] = 0.015625f;
            }
            p[15] = 1;
            p[16] = 4;
            thumbnail.gainmapParams = p;
        }

        @Override
        public byte[] readStripe(long handle, int index) {
            if (index == interruptAt) {
                Thread.currentThread().interrupt(); // as the caller's give-up does (Future.cancel)
            }
            stripesRead.incrementAndGet();
            return stripe(thumbnail.width, thumbnail.height, thumbnail.stripeRows, index, false);
        }

        @Override
        public byte[] readGainmapStripe(long handle, int index) {
            gainStripesRead.incrementAndGet();
            return stripe(thumbnail.gainmapWidth, thumbnail.gainmapHeight, thumbnail.gainmapStripeRows, index, true);
        }

        private static byte[] stripe(int width, int height, int stripeRows, int index, boolean gain) {
            final int first = index * stripeRows;
            final int rows = Math.min(stripeRows, height - first);
            final byte[] s = new byte[rows * width * 4];
            for (int r = 0; r < rows; r++) {
                for (int x = 0; x < width; x++) {
                    final int i = (r * width + x) * 4;
                    final int y = first + r;
                    s[i] = (byte) (gain ? 200 : x % 256);
                    s[i + 1] = (byte) (y % 256);
                    s[i + 2] = (byte) ((x + y) % 256);
                    s[i + 3] = (byte) 255;
                }
            }
            return s;
        }
    }

    @Test
    public void stripesInBlocks_giveExactlyTheImage_andItsGainMap() throws Exception {
        final long base = DecodeMemory.held();
        final Service service = new Service(300, 217, 50);
        service.withGainmap(150, 109, 40);
        final VmDecode.Decoded decoded = ReplyPixels.thumbnail(service, service.thumbnail, 300, 300);
        final Bitmap b = decoded.bitmap;
        assertEquals(300, b.getWidth());
        assertEquals(217, b.getHeight());
        for (int y = 0; y < 217; y += 7) {
            for (int x = 0; x < 300; x += 11) {
                assertEquals("pixel " + x + "," + y, 0xff000000 | (x % 256) << 16 | (y % 256) << 8 | ((x + y) % 256),
                        b.getPixel(x, y));
            }
        }
        assertTrue(b.hasGainmap());
        final Bitmap gain = b.getGainmap().getGainmapContents();
        assertEquals(150, gain.getWidth());
        assertEquals(109, gain.getHeight());
        assertEquals(0xff000000 | 200 << 16 | (108 % 256) << 8 | ((149 + 108) % 256), gain.getPixel(149, 108));
        assertEquals(4f, b.getGainmap().getRatioMax()[0], 0f);
        assertTrue("live HDR result lost its charge", DecodeMemory.held() >= base + b.getAllocationByteCount()
                + gain.getAllocationByteCount());
        DecodeMemory.recycle(b, null);
        assertEquals(base, DecodeMemory.held());
    }

    @Test
    public void aCallerThatLeft_stopsTheReadingBetweenStripes() throws Exception {
        final long base = DecodeMemory.held();
        final Service service = new Service(200, 400, 40); // 10 stripes
        service.interruptAt = 2;
        try {
            ReplyPixels.thumbnail(service, service.thumbnail, 200, 400);
            fail("read on after its caller left");
        } catch (VmDecode.UnavailableException e) {
            assertFalse(e.isNoRoom());
        } finally {
            Thread.interrupted();
        }
        assertEquals("stripes read", 3, service.stripesRead.get());
        assertEquals(base, DecodeMemory.held());
    }

    @Test
    public void memoryIsReservedFirst_noRoomNowIsAskedAgain_tooLargeEverIsRefused() throws Exception {
        final Service service = new Service(512, 512, 64); // 1 MiB of pixels
        DecodeMemory.setMaxForTest(4L << 20);
        try (DecodeMemory.Reservation others = DecodeMemory.reserve(3L << 20)) {
            ReplyPixels.thumbnail(service, service.thumbnail, 512, 512);
            fail("allocated without room");
        } catch (VmDecode.UnavailableException e) {
            assertTrue("no room now: " + e.getMessage(), e.isNoRoom());
        }
        assertEquals("nothing read without room", 0, service.stripesRead.get());
        final Bitmap fitted = ReplyPixels.thumbnail(service, service.thumbnail, 512, 512).bitmap;
        assertNotNull(fitted);
        DecodeMemory.recycle(fitted, null);
        DecodeMemory.setMaxForTest(512 * 1024);
        try {
            ReplyPixels.thumbnail(service, service.thumbnail, 512, 512);
            fail("allocated what could never fit");
        } catch (VmDecode.RejectedException e) {
            // Refused for good.
        }
    }

    @Test
    public void aProvidersTurn_isAQuarterTurnOrRefused() throws Exception {
        assertEquals(0, ReplyPixels.quarterTurn(0));
        assertEquals(90, ReplyPixels.quarterTurn(90));
        assertEquals(90, ReplyPixels.quarterTurn(450));
        assertEquals(270, ReplyPixels.quarterTurn(-90));
        assertEquals(180, ReplyPixels.quarterTurn(180));
        for (int degrees : new int[] {45, 1, 359, -30, Integer.MAX_VALUE}) {
            try {
                ReplyPixels.quarterTurn(degrees);
                fail("took a turn of " + degrees);
            } catch (VmDecode.RejectedException e) {
                // Would make the image larger than its box.
            }
        }
    }
    @Test
    public void aTurnedHdrResultKeepsTheWholeLiveStorage() throws Exception {
        final Service service = new Service(300, 217, 50);
        service.withGainmap(150, 109, 40);
        service.thumbnail.rotation = 90;
        final long before = DecodeMemory.held();
        final Bitmap result = ReplyPixels.thumbnail(service, service.thumbnail, 300, 300).bitmap;
        final long live = (long) result.getAllocationByteCount()
                + result.getGainmap().getGainmapContents().getAllocationByteCount();
        assertTrue("turned HDR result uncharged", DecodeMemory.held() >= before + live);
        DecodeMemory.recycle(result, null);
        assertEquals(before, DecodeMemory.held());
    }
}
