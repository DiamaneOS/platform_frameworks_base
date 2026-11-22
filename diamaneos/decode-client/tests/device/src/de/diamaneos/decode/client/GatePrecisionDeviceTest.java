/* Copyright (C) 2026 DiamaneOS */
package de.diamaneos.decode.client;

import static org.junit.Assert.*;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.DecodeGate;
import android.graphics.ImageDecoder;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashSet;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.zip.CRC32;
import java.util.zip.DeflaterOutputStream;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** Public API comparisons reject eight-bit pixels promoted into F16 storage. */
public class GatePrecisionDeviceTest {
    @Before public void prepare() {
        DecodeGate.setLocal(null);
        DecodeGate.setWaitForTest(20000L);
        DecodeGate.resetForTest();
    }

    @After public void restore() {
        DecodeGate.setLocal(null);
        DecodeGate.setForcedForTest(null);
        DecodeGate.setWaitForTest(null);
        DecodeGate.resetForTest();
    }

    @Test public void factoryDefaultKeepsSixteenBitStorageAndValues() throws Exception {
        compare(png -> BitmapFactory.decodeByteArray(png, 0, png.length), false);
    }

    @Test public void factoryF16KeepsSixteenBitValues() throws Exception {
        compare(png -> {
            final BitmapFactory.Options options = new BitmapFactory.Options();
            options.inPreferredConfig = Bitmap.Config.RGBA_F16;
            return BitmapFactory.decodeByteArray(png, 0, png.length, options);
        }, false);
    }

    @Test public void softwareImageDecoderKeepsSixteenBitValues() throws Exception {
        compare(png -> ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(png)),
                (decoder, info, source) -> decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE)), false);
    }

    @Test public void hardwareImageDecoderKeepsSixteenBitValues() throws Exception {
        compare(png -> ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(png)),
                (decoder, info, source) -> decoder.setAllocator(ImageDecoder.ALLOCATOR_HARDWARE)), true);
    }

    @Test public void boundsKeepTheNativeConfigAndColourSpace() throws Exception {
        worker(() -> {
            final byte[] png = png16();
            final BitmapFactory.Options[] options = {
                    new BitmapFactory.Options(), new BitmapFactory.Options()};
            for (int i = 0; i < options.length; i++) {
                DecodeGate.setForcedForTest(i == 1);
                DecodeGate.resetForTest();
                options[i].inJustDecodeBounds = true;
                assertNull(BitmapFactory.decodeByteArray(png, 0, png.length, options[i]));
            }
            assertEquals(Bitmap.Config.RGBA_F16, options[0].outConfig);
            assertNotNull(options[0].outColorSpace);
            assertEquals(options[0].outWidth, options[1].outWidth);
            assertEquals(options[0].outHeight, options[1].outHeight);
            assertEquals("bounds config differs from native", options[0].outConfig, options[1].outConfig);
            assertEquals("bounds colour differs from native", options[0].outColorSpace, options[1].outColorSpace);
            return null;
        });
    }

    private interface Decoder {
        Bitmap decode(byte[] png) throws Exception;
    }

    private static void compare(Decoder decoder, boolean hardware) throws Exception {
        worker(() -> {
            final byte[] png = png16();
            Bitmap stock = null;
            Bitmap gated = null;
            Bitmap stockRead = null;
            Bitmap gatedRead = null;
            try {
                DecodeGate.setForcedForTest(false);
                DecodeGate.resetForTest();
                stock = decoder.decode(png);
                assertNotNull("native reference refused the generated PNG", stock);
                DecodeGate.setForcedForTest(true);
                DecodeGate.resetForTest();
                gated = decoder.decode(png);
                assertNotNull("the gate refused the generated PNG", gated);
                assertEquals(hardware ? Bitmap.Config.HARDWARE : Bitmap.Config.RGBA_F16, stock.getConfig());
                assertEquals("result config differs from native", stock.getConfig(), gated.getConfig());
                assertEquals(stock.getWidth(), gated.getWidth());
                assertEquals(stock.getHeight(), gated.getHeight());
                assertEquals(stock.getColorSpace(), gated.getColorSpace());
                stockRead = hardware ? stock.copy(Bitmap.Config.RGBA_F16, false) : stock;
                gatedRead = hardware ? gated.copy(Bitmap.Config.RGBA_F16, false) : gated;
                assertNotNull(stockRead);
                assertNotNull(gatedRead);
                final ByteBuffer reference = pixels(stockRead);
                final ByteBuffer actual = pixels(gatedRead);
                final HashSet<Integer> referenceLevels = new HashSet<>();
                final HashSet<Integer> actualLevels = new HashSet<>();
                for (int y = 0; y < stockRead.getHeight(); y++) {
                    for (int x = 0; x < stockRead.getWidth(); x++) {
                        final int expectedAt = y * stockRead.getRowBytes() + x * 8;
                        final int actualAt = y * gatedRead.getRowBytes() + x * 8;
                        referenceLevels.add(reference.getShort(expectedAt) & 65535);
                        actualLevels.add(actual.getShort(actualAt) & 65535);
                        for (int channel = 0; channel < 4; channel++) {
                            final int expected = reference.getShort(expectedAt + channel * 2) & 65535;
                            final int value = actual.getShort(actualAt + channel * 2) & 65535;
                            assertTrue("nonfinite or negative generated native value", expected <= 0x3c00);
                            assertTrue("deep value differs at " + x + "," + y + " channel " + channel
                                    + " expected=" + expected + " actual=" + value,
                                    Math.abs(expected - value) <= 1);
                        }
                    }
                }
                assertTrue("the reference lacks deep source values", referenceLevels.size() > 1024);
                assertTrue("the gate returned eight-bit promotion", actualLevels.size() > 1024);
                return null;
            } finally {
                if (stockRead != null && stockRead != stock) stockRead.recycle();
                if (gatedRead != null && gatedRead != gated) gatedRead.recycle();
                if (stock != null) stock.recycle();
                if (gated != null) gated.recycle();
            }
        });
    }

    private static ByteBuffer pixels(Bitmap bitmap) {
        assertEquals(Bitmap.Config.RGBA_F16, bitmap.getConfig());
        final ByteBuffer buffer = ByteBuffer.allocate(bitmap.getByteCount()).order(ByteOrder.LITTLE_ENDIAN);
        bitmap.copyPixelsToBuffer(buffer);
        return buffer;
    }

    private static byte[] png16() throws Exception {
        final ByteArrayOutputStream raw = new ByteArrayOutputStream();
        final DataOutputStream rows = new DataOutputStream(raw);
        for (int y = 0; y < 256; y++) {
            rows.writeByte(0);
            for (int x = 0; x < 256; x++) {
                final int value = x + 256 * y;
                rows.writeShort(value);
                rows.writeShort((value * 73) & 65535);
                rows.writeShort(65535 - value);
                rows.writeShort(65535);
            }
        }
        final ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (DeflaterOutputStream output = new DeflaterOutputStream(compressed)) {
            raw.writeTo(output);
        }
        final ByteArrayOutputStream png = new ByteArrayOutputStream();
        final DataOutputStream out = new DataOutputStream(png);
        out.write(new byte[] {(byte) 137, 80, 78, 71, 13, 10, 26, 10});
        final ByteArrayOutputStream header = new ByteArrayOutputStream();
        final DataOutputStream ihdr = new DataOutputStream(header);
        ihdr.writeInt(256);
        ihdr.writeInt(256);
        ihdr.write(new byte[] {16, 6, 0, 0, 0});
        chunk(out, "IHDR", header.toByteArray());
        chunk(out, "IDAT", compressed.toByteArray());
        chunk(out, "IEND", new byte[0]);
        return png.toByteArray();
    }

    private static void chunk(DataOutputStream output, String type, byte[] bytes) throws Exception {
        final byte[] tag = type.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        final CRC32 crc = new CRC32();
        crc.update(tag);
        crc.update(bytes);
        output.writeInt(bytes.length);
        output.write(tag);
        output.write(bytes);
        output.writeInt((int) crc.getValue());
    }

    private static <T> T worker(Callable<T> action) throws Exception {
        final ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            return executor.submit(action).get(30, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
            assertTrue("precision worker did not stop", executor.awaitTermination(3, TimeUnit.SECONDS));
        }
    }
}
