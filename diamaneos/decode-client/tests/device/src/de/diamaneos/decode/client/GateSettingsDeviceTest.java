/* Copyright (C) 2026 DiamaneOS */
package de.diamaneos.decode.client;

import static org.junit.Assert.*;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.ColorSpace;
import android.graphics.DecodeGate;
import android.graphics.ImageDecoder;
import android.graphics.PixelFormat;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import org.junit.After;
import org.junit.Test;

/** Compare settings on identical raw red pixels from stock and the local checked-reply fixture. */
public class GateSettingsDeviceTest {
    @After public void restore() {
        DecodeGate.setLocal(null);
        DecodeGate.setForcedForTest(null);
        DecodeGate.setWaitForTest(null);
        DecodeGate.resetForTest();
    }

    private static byte[] png() {
        final Bitmap bitmap = Bitmap.createBitmap(64, 32, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(Color.RED);
        bitmap.setHasAlpha(false);
        try {
            final ByteArrayOutputStream output = new ByteArrayOutputStream();
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output));
            return output.toByteArray();
        } finally {
            bitmap.recycle();
        }
    }

    private static void mode(boolean gated) {
        DecodeGate.resetForTest();
        DecodeGate.setLocal(gated ? new FakeGateProvider(0) : null);
        DecodeGate.setForcedForTest(gated);
    }

    private static void sameColour(Bitmap stock, Bitmap gated) {
        assertEquals(stock.getColorSpace(), gated.getColorSpace());
        final float[] wanted = stock.getColor(0, 0).getComponents();
        final float[] actual = gated.getColor(0, 0).getComponents();
        assertArrayEquals(wanted, actual, 0.008f);
    }

    @Test public void bitmapFactoryAppliesTheRequestedColourSpace() {
        final byte[] png = png();
        Bitmap stock = null;
        Bitmap gated = null;
        try {
            for (boolean enabled : new boolean[] {false, true}) {
                mode(enabled);
                final BitmapFactory.Options options = new BitmapFactory.Options();
                options.inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.DISPLAY_P3);
                options.inMutable = true;
                final Bitmap bitmap = BitmapFactory.decodeByteArray(png, 0, png.length, options);
                assertNotNull(bitmap);
                if (enabled) gated = bitmap;
                else stock = bitmap;
                assertTrue(bitmap.isMutable());
                assertEquals(options.inPreferredColorSpace, bitmap.getColorSpace());
                assertEquals(bitmap.getColorSpace(), options.outColorSpace);
            }
            sameColour(stock, gated);
        } finally {
            if (stock != null) stock.recycle();
            if (gated != null) gated.recycle();
        }
    }

    @Test public void imageDecoderAppliesTheRequestedColourSpace() throws Exception {
        compareImageDecoder(false, false, ColorSpace.Named.DISPLAY_P3);
    }

    @Test public void anExtendedTargetUsesF16WorkingPixels() throws Exception {
        compareImageDecoder(false, false, ColorSpace.Named.EXTENDED_SRGB);
    }

    private static void compareImageDecoder(boolean lowRam, boolean postProcess, ColorSpace.Named target)
            throws Exception {
        final byte[] png = png();
        Bitmap stock = null;
        Bitmap gated = null;
        try {
            for (boolean enabled : new boolean[] {false, true}) {
                mode(enabled);
                final Bitmap bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(png)),
                        (decoder, info, source) -> {
                            decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                            if (target != null) decoder.setTargetColorSpace(ColorSpace.get(target));
                            if (lowRam) decoder.setMemorySizePolicy(ImageDecoder.MEMORY_POLICY_LOW_RAM);
                            if (postProcess) decoder.setPostProcessor(canvas -> PixelFormat.TRANSLUCENT);
                        });
                assertNotNull(bitmap);
                if (enabled) gated = bitmap;
                else stock = bitmap;
            }
            assertEquals(stock.getConfig(), gated.getConfig());
            assertEquals(stock.getAllocationByteCount(), gated.getAllocationByteCount());
            sameColour(stock, gated);
        } finally {
            if (stock != null) stock.recycle();
            if (gated != null) gated.recycle();
        }
    }

    @Test public void opaqueSoftwareCanConserveMemory() throws Exception {
        compareImageDecoder(true, false, null);
    }

    @Test public void aPostprocessorRetainsItsAlphaCapableStorage() throws Exception {
        compareImageDecoder(true, true, null);
    }
}
