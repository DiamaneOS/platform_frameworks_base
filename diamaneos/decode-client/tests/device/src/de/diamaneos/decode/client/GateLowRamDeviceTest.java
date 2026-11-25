/* Copyright (C) 2026 DiamaneOS */
package de.diamaneos.decode.client;

import static org.junit.Assert.*;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.DecodeGate;
import android.graphics.ImageDecoder;
import android.graphics.PixelFormat;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** Low-RAM ImageDecoder results have the stock config for the same bytes and settings. */
public class GateLowRamDeviceTest {
    @Before public void prepare() {
        DecodeGate.setLocal(null);
        DecodeGate.setWaitForTest(30_000L);
        DecodeGate.resetForTest();
    }

    @After public void restore() {
        DecodeGate.setForcedForTest(null);
        DecodeGate.setWaitForTest(null);
        DecodeGate.resetForTest();
    }

    @Test public void jpegMatchesStock() throws Exception {
        compareAll(encoded(Bitmap.CompressFormat.JPEG));
    }

    @Test public void opaquePngMatchesStock() throws Exception {
        compareAll(encoded(Bitmap.CompressFormat.PNG));
    }

    // An alpha channel whose pixels are all opaque: stock keeps the encoded alpha, so no RGB_565.
    @Test public void alphaChannelPngWithOpaquePixelsMatchesStock() throws Exception {
        compareAll(encoded(Bitmap.CompressFormat.PNG, true));
    }

    @Test public void lossyWebpMatchesStock() throws Exception {
        compareAll(encoded(Bitmap.CompressFormat.WEBP_LOSSY, false));
    }

    @Test public void losslessWebpWithAlphaMatchesStock() throws Exception {
        compareAll(encoded(Bitmap.CompressFormat.WEBP_LOSSLESS, true));
    }

    private static byte[] encoded(Bitmap.CompressFormat format) {
        return encoded(format, false);
    }

    private static byte[] encoded(Bitmap.CompressFormat format, boolean alphaChannel) {
        final Bitmap pixels = Bitmap.createBitmap(48, 32, Bitmap.Config.ARGB_8888);
        try {
            pixels.eraseColor(Color.rgb(40, 120, 200));
            pixels.setHasAlpha(alphaChannel);
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            assertTrue(pixels.compress(format, 90, out));
            return out.toByteArray();
        } finally {
            pixels.recycle();
        }
    }

    private static void compareAll(byte[] bytes) throws Exception {
        final int[] allocators = {ImageDecoder.ALLOCATOR_SOFTWARE, ImageDecoder.ALLOCATOR_SHARED_MEMORY,
                ImageDecoder.ALLOCATOR_DEFAULT, ImageDecoder.ALLOCATOR_HARDWARE};
        for (int allocator : allocators) {
            for (boolean mutable : new boolean[] {false, true}) {
                if (mutable && allocator == ImageDecoder.ALLOCATOR_HARDWARE) continue;
                for (boolean post : new boolean[] {false, true}) {
                    for (boolean lowRam : new boolean[] {false, true}) {
                        final String label = "allocator=" + allocator + " mutable=" + mutable + " post=" + post
                                + " lowRam=" + lowRam;
                        assertEquals(label, decode(bytes, false, allocator, mutable, post, lowRam),
                                decode(bytes, true, allocator, mutable, post, lowRam));
                    }
                }
            }
        }
    }

    // The result config, or the exception class when the settings are refused.
    private static String decode(byte[] bytes, boolean gated, int allocator, boolean mutable, boolean post,
            boolean lowRam) throws Exception {
        DecodeGate.setForcedForTest(gated);
        final int before = DecodeGate.countsForTest()[0] + DecodeGate.countsForTest()[4];
        final Bitmap bitmap;
        try {
            bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes)),
                (decoder, info, source) -> {
                    decoder.setAllocator(allocator);
                    decoder.setMutableRequired(mutable);
                    if (lowRam) decoder.setMemorySizePolicy(ImageDecoder.MEMORY_POLICY_LOW_RAM);
                    if (post) decoder.setPostProcessor(canvas -> PixelFormat.UNKNOWN);
                });
        } catch (IllegalStateException | IllegalArgumentException e) {
            return e.getClass().getSimpleName();
        }
        try {
            if (gated) {
                final int after = DecodeGate.countsForTest()[0] + DecodeGate.countsForTest()[4];
                assertTrue("the VM did not decode", after > before);
            }
            return String.valueOf(bitmap.getConfig());
        } finally {
            bitmap.recycle();
        }
    }
}
