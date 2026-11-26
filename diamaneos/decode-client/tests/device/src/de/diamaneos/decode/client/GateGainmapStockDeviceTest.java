/* Copyright (C) 2026 DiamaneOS */
package de.diamaneos.decode.client;

import static org.junit.Assert.*;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.DecodeGate;
import android.graphics.Gainmap;
import android.graphics.ImageDecoder;
import android.graphics.PixelFormat;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Ultra HDR gain maps through the VM have stock's size and config: the gain image at the target's scale (also above
 * the VM's 8 MiPix reply limit, assembled from regions), a greyscale one as ALPHA_8, none with LOW_RAM or a
 * post-processor.
 */
public class GateGainmapStockDeviceTest {
    @Before public void prepare() {
        DecodeGate.setLocal(null);
        DecodeGate.setWaitForTest(60_000L);
        DecodeGate.resetForTest();
    }

    @After public void restore() {
        DecodeGate.setForcedForTest(null);
        DecodeGate.setWaitForTest(null);
        DecodeGate.resetForTest();
    }

    @Test public void smallColourGainmapMatchesStock() throws Exception {
        compareAll(hdr(640, 480, 640, 480, false), 640, 480);
    }

    @Test public void greyGainmapIsAlpha8AsStock() throws Exception {
        final byte[] bytes = hdr(640, 480, 320, 240, true);
        compareAll(bytes, 640, 480);
        final Bitmap stock = factory(bytes, false);
        try {
            // Only meaningful when the encoder wrote a greyscale gain image.
            if (stock.getGainmap().getGainmapContents().getConfig() == Bitmap.Config.ALPHA_8) {
                final Bitmap gated = factory(bytes, true);
                assertEquals(Bitmap.Config.ALPHA_8, gated.getGainmap().getGainmapContents().getConfig());
                gated.recycle();
            }
        } finally {
            stock.recycle();
        }
    }

    // 12.6 MP with a full-size gain image: above one VM reply, so both base and gain come from regions.
    @Test public void gainmapAboveOneReplyMatchesStock() throws Exception {
        compareAll(hdr(4096, 3072, 4096, 3072, false), 4096, 3072);
    }

    @Test public void greyGainmapAboveOneReplyMatchesStock() throws Exception {
        compareAll(hdr(4096, 3072, 4096, 3072, true), 4096, 3072);
    }

    @Test public void fractionalImageDecoderGainSizeMatchesStock() throws Exception {
        for (int[] size : new int[][] {{640, 480, 319, 239}, {4096, 3072, 4095, 3071}, {640, 480, 1, 1}}) {
            final byte[] bytes = hdr(size[0], size[1], size[0] / 2, size[1] / 2, false);
            for (int allocator : new int[] {ImageDecoder.ALLOCATOR_SOFTWARE, ImageDecoder.ALLOCATOR_DEFAULT,
                    ImageDecoder.ALLOCATOR_HARDWARE}) {
                assertEquals("fractional gain " + size[0] + " allocator=" + allocator,
                        describe(decoder(bytes, false, allocator, size[2], size[3], false, false)),
                        describe(decoder(bytes, true, allocator, size[2], size[3], false, false)));
            }
        }
    }

    @Test public void fractionalBitmapFactoryGainSizeStillMatchesStock() throws Exception {
        final byte[] bytes = hdr(640, 480, 320, 240, false);
        final String[] result = new String[2];
        for (int i = 0; i < result.length; i++) {
            DecodeGate.setForcedForTest(i == 1);
            final BitmapFactory.Options options = new BitmapFactory.Options();
            options.inDensity = 640;
            options.inTargetDensity = 319;
            result[i] = describe(BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options));
        }
        assertEquals(result[0], result[1]);
    }

    private static void compareAll(byte[] bytes, int width, int height) throws Exception {
        assertEquals("BitmapFactory", describe(factory(bytes, false)), describe(factory(bytes, true)));
        final int[] allocators = {ImageDecoder.ALLOCATOR_SOFTWARE, ImageDecoder.ALLOCATOR_DEFAULT,
                ImageDecoder.ALLOCATOR_HARDWARE};
        for (int allocator : allocators) {
            for (int scale : new int[] {1, 2}) {
                for (boolean post : new boolean[] {false, true}) {
                    for (boolean lowRam : new boolean[] {false, true}) {
                        final String label = "allocator=" + allocator + " scale=1/" + scale + " post=" + post
                                + " lowRam=" + lowRam;
                        assertEquals(label,
                                describe(decoder(bytes, false, allocator, width / scale, height / scale, post, lowRam)),
                                describe(decoder(bytes, true, allocator, width / scale, height / scale, post, lowRam)));
                    }
                }
            }
        }
    }

    // A width x height Ultra HDR JPEG with a gainWidth x gainHeight gain map, ALPHA_8 when grey.
    private static byte[] hdr(int width, int height, int gainWidth, int gainHeight, boolean grey) {
        final Bitmap base = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        final Bitmap gain = Bitmap.createBitmap(gainWidth, gainHeight,
                grey ? Bitmap.Config.ALPHA_8 : Bitmap.Config.ARGB_8888);
        try {
            base.eraseColor(Color.rgb(90, 140, 200));
            gain.eraseColor(grey ? Color.argb(160, 0, 0, 0) : Color.rgb(200, 120, 60));
            final Gainmap map = new Gainmap(gain);
            map.setRatioMax(4f, 4f, 4f);
            map.setDisplayRatioForFullHdr(4f);
            base.setGainmap(map);
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            assertTrue(base.compress(Bitmap.CompressFormat.JPEG, 90, out));
            return out.toByteArray();
        } finally {
            base.recycle();
            gain.recycle();
        }
    }

    private static Bitmap factory(byte[] bytes, boolean gated) {
        DecodeGate.setForcedForTest(gated);
        final Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        assertNotNull(gated ? "gated decode refused" : "stock decode refused", bitmap);
        return bitmap;
    }

    private static Bitmap decoder(byte[] bytes, boolean gated, int allocator, int width, int height, boolean post,
            boolean lowRam) throws Exception {
        DecodeGate.setForcedForTest(gated);
        final int before = DecodeGate.countsForTest()[0] + DecodeGate.countsForTest()[4];
        final Bitmap bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes)),
                (decoder, info, source) -> {
                    decoder.setAllocator(allocator);
                    decoder.setTargetSize(width, height);
                    if (lowRam) decoder.setMemorySizePolicy(ImageDecoder.MEMORY_POLICY_LOW_RAM);
                    if (post) decoder.setPostProcessor(canvas -> PixelFormat.UNKNOWN);
                });
        if (gated) {
            assertTrue("the VM did not decode", DecodeGate.countsForTest()[0] + DecodeGate.countsForTest()[4] > before);
        }
        return bitmap;
    }

    // The base size and the gain map's size and config, then the bitmap is given back.
    private static String describe(Bitmap bitmap) {
        try {
            final StringBuilder s = new StringBuilder(bitmap.getWidth() + "x" + bitmap.getHeight());
            if (!bitmap.hasGainmap()) return s.append(" no gain map").toString();
            final Gainmap map = bitmap.getGainmap();
            final Bitmap contents = map.getGainmapContents();
            s.append(" gain ").append(contents.getWidth()).append('x').append(contents.getHeight());
            // A hardware result's gain map is hardware too, compare the stored kind behind it.
            if (contents.getConfig() != Bitmap.Config.HARDWARE) s.append(' ').append(contents.getConfig());
            s.append(" ratio ").append(map.getRatioMax()[0]);
            return s.toString();
        } finally {
            bitmap.recycle();
        }
    }
}
