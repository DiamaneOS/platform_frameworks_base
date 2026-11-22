/* Copyright (C) 2026 DiamaneOS */
package de.diamaneos.decode.client;

import static org.junit.Assert.*;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapRegionDecoder;
import android.graphics.DecodeGate;
import android.graphics.ImageDecoder;
import android.graphics.Rect;
import android.util.Log;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.FileInputStream;
import java.security.MessageDigest;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

/** Full-size and zoom checks for a separately supplied, hash-bound JPEG. */
public class GateLargeImageDeviceTest {
    private File file;
    private int width;
    private int height;

    @Before public void prepare() throws Exception {
        final String path = InstrumentationRegistry.getArguments().getString("largeJpeg");
        Assume.assumeTrue("the large JPEG fixture is required", path != null);
        final String wanted = InstrumentationRegistry.getArguments().getString("largeSha256");
        assertNotNull(wanted);
        assertTrue(wanted.matches("[0-9a-f]{64}"));
        file = new File(path);
        final MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (FileInputStream input = new FileInputStream(file)) {
            final byte[] block = new byte[65536];
            for (int count; (count = input.read(block)) != -1;) digest.update(block, 0, count);
        }
        final StringBuilder actual = new StringBuilder();
        for (byte value : digest.digest()) actual.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        assertEquals(wanted, actual.toString());
        DecodeGate.setForcedForTest(false);
        final BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        assertNull(BitmapFactory.decodeFile(file.getPath(), bounds));
        width = bounds.outWidth;
        height = bounds.outHeight;
        assertTrue("the fixture must contain at least 45 MP", (long) width * height >= 45000000L);
        assertEquals("image/jpeg", bounds.outMimeType);
        DecodeGate.setForcedForTest(true);
        DecodeGate.setWaitForTest(30000L);
        DecodeGate.resetForTest();
    }

    @After public void restore() {
        DecodeGate.setForcedForTest(null);
        DecodeGate.setWaitForTest(null);
        DecodeGate.resetForTest();
    }

    @Test public void bitmapFactoryKeepsTheFullSourceResolution() {
        final Bitmap bitmap = BitmapFactory.decodeFile(file.getPath());
        assertNotNull("full-resolution JPEG was refused", bitmap);
        try {
            assertEquals(width, bitmap.getWidth());
            assertEquals(height, bitmap.getHeight());
            assertFalse(bitmap.isMutable());
            assertEquals(Bitmap.Config.ARGB_8888, bitmap.getConfig());
            Log.i("LargeImageProof", "factory pixels=" + (long) width * height
                    + " allocation=" + bitmap.getAllocationByteCount());
        } finally {
            bitmap.recycle();
        }
    }

    @Test public void softwareImageDecoderKeepsTheFullUprightResolution() throws Exception {
        final int[] upright = new int[2];
        DecodeGate.setForcedForTest(false);
        final Bitmap reference = ImageDecoder.decodeBitmap(ImageDecoder.createSource(file),
                (decoder, info, source) -> {
                    upright[0] = info.getSize().getWidth();
                    upright[1] = info.getSize().getHeight();
                    decoder.setTargetSize(1, 1);
                    decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                });
        reference.recycle();
        DecodeGate.setForcedForTest(true);
        final Bitmap bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(file),
                (decoder, info, source) -> decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE));
        assertNotNull(bitmap);
        try {
            assertEquals(upright[0], bitmap.getWidth());
            assertEquals(upright[1], bitmap.getHeight());
            assertEquals(Bitmap.Config.ARGB_8888, bitmap.getConfig());
            assertFalse(bitmap.isMutable());
            Log.i("LargeImageProof", "decoder pixels=" + (long) bitmap.getWidth() * bitmap.getHeight()
                    + " allocation=" + bitmap.getAllocationByteCount());
        } finally {
            bitmap.recycle();
        }
    }

    @Test public void regionZoomMatchesTheStockPixels() throws Exception {
        final int w = Math.min(2048, width);
        final int h = Math.min(2048, height);
        final Rect rect = new Rect((width - w) / 2, (height - h) / 2,
                (width - w) / 2 + w, (height - h) / 2 + h);
        Bitmap stock = null;
        Bitmap gated = null;
        try {
            for (boolean enabled : new boolean[] {false, true}) {
                DecodeGate.setForcedForTest(enabled);
                final BitmapRegionDecoder decoder = BitmapRegionDecoder.newInstance(file.getPath(), false);
                try {
                    assertEquals(width, decoder.getWidth());
                    assertEquals(height, decoder.getHeight());
                    final Bitmap bitmap = decoder.decodeRegion(rect, new BitmapFactory.Options());
                    assertNotNull(bitmap);
                    if (enabled) gated = bitmap;
                    else stock = bitmap;
                    assertEquals(w, bitmap.getWidth());
                    assertEquals(h, bitmap.getHeight());
                } finally {
                    decoder.recycle();
                }
            }
            final int[] wanted = new int[w * 32];
            final int[] actual = new int[w * 32];
            long difference = 0;
            for (int y = 0; y < h; y += 32) {
                final int rows = Math.min(32, h - y);
                stock.getPixels(wanted, 0, w, 0, y, w, rows);
                gated.getPixels(actual, 0, w, 0, y, w, rows);
                for (int p = 0; p < w * rows; p++) {
                    for (int shift : new int[] {0, 8, 16}) {
                        final int delta = ((wanted[p] >>> shift) & 255) - ((actual[p] >>> shift) & 255);
                        difference += (long) delta * delta;
                    }
                }
            }
            assertTrue("region pixels differ from stock", difference <= 144L * w * h * 3);
            Log.i("LargeImageProof", "large-source zoom=" + w + "x" + h + " squared-error=" + difference);
        } finally {
            if (stock != null) stock.recycle();
            if (gated != null) gated.recycle();
        }
    }
}
