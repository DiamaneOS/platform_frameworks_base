/* Copyright (C) 2026 DiamaneOS */
package de.diamaneos.decode.client;

import static org.junit.Assert.*;
import android.graphics.Bitmap;
import android.graphics.ColorSpace;
import android.graphics.DecodeGate;
import android.graphics.ImageDecoder;
import android.graphics.Rect;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashSet;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.zip.CRC32;
import java.util.zip.DeflaterOutputStream;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class GateStraightDeviceTest {
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

    @Test public void softwareBytesKeepStraightTransparentColour() throws Exception { compare(false, false, false, false, false); }
    @Test public void softwareF16KeepsStraightTransparentPrecision() throws Exception { compare(true, false, false, false, false); }
    @Test public void croppedStraightPixelsMatchNative() throws Exception { compare(true, true, false, false, false); }
    @Test public void conservedStraightF16MatchesNative() throws Exception { compare(true, false, true, false, false); }
    @Test public void extendedStraightBytesMatchNative() throws Exception { compare(false, false, false, true, false); }
    @Test public void hardwareStraightPixelsMatchNative() throws Exception { compare(false, false, false, false, true); }

    private static void compare(boolean deep, boolean crop, boolean conserve, boolean extended, boolean hardware)
            throws Exception {
        final ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            executor.submit((Callable<Void>) () -> {
                final byte[] png = png(deep);
                Bitmap stock = null, gated = null, stockRead = null, gatedRead = null;
                try {
                    DecodeGate.setForcedForTest(false);
                    stock = decode(png, crop, conserve, extended, hardware);
                    assertNotNull("native reference refused the fixture", stock);
                    assertFalse("native reference did not provide straight storage", stock.isPremultiplied());
                    DecodeGate.setForcedForTest(true);
                    DecodeGate.resetForTest();
                    gated = decode(png, crop, conserve, extended, hardware);
                    assertNotNull(gated);
                    assertEquals(stock.getConfig(), gated.getConfig());
                    assertEquals(stock.getWidth(), gated.getWidth());
                    assertEquals(stock.getHeight(), gated.getHeight());
                    assertEquals(stock.getColorSpace(), gated.getColorSpace());
                    assertEquals(stock.isPremultiplied(), gated.isPremultiplied());
                    final Bitmap.Config config = deep && !conserve || extended
                            ? Bitmap.Config.RGBA_F16 : Bitmap.Config.ARGB_8888;
                    stockRead = hardware ? stock.copy(config, false) : stock;
                    gatedRead = hardware ? gated.copy(config, false) : gated;
                    assertNotNull(stockRead);
                    assertNotNull(gatedRead);
                    final ByteBuffer reference = pixels(stockRead), actual = pixels(gatedRead);
                    final boolean wide = config == Bitmap.Config.RGBA_F16;
                    final HashSet<Integer> nativeLevels = new HashSet<>(), gateLevels = new HashSet<>();
                    boolean nativeHidden = false;
                    for (int y = 0; y < stockRead.getHeight(); y++) {
                        for (int x = 0; x < stockRead.getWidth(); x++) {
                            final int before = y * stockRead.getRowBytes() + x * (wide ? 8 : 4);
                            final int after = y * gatedRead.getRowBytes() + x * (wide ? 8 : 4);
                            final int alpha = wide ? reference.getShort(before + 6) & 65535
                                    : reference.get(before + 3) & 255;
                            final int green = wide ? reference.getShort(before + 2) & 65535
                                    : reference.get(before + 1) & 255;
                            nativeHidden |= alpha == 0 && green != 0;
                            nativeLevels.add(wide ? reference.getShort(before) & 65535 : reference.get(before) & 255);
                            gateLevels.add(wide ? actual.getShort(after) & 65535 : actual.get(after) & 255);
                            for (int channel = 0; channel < 4; channel++) {
                                final int expected = wide ? reference.getShort(before + channel * 2) & 65535
                                        : reference.get(before + channel) & 255;
                                final int value = wide ? actual.getShort(after + channel * 2) & 65535
                                        : actual.get(after + channel) & 255;
                                assertTrue("straight pixel differs at " + x + "," + y + "/" + channel
                                        + " expected=" + expected + " actual=" + value,
                                        Math.abs(expected - value) <= (wide ? 1 : 0));
                            }
                        }
                    }
                    if (!hardware) assertTrue("native fixture lacks transparent RGB", nativeHidden);
                    if (deep && !conserve) {
                        assertTrue("native deep fixture lacks precision", nativeLevels.size() > 1024);
                        assertTrue("gate reduced deep pixels to eight bits", gateLevels.size() > 1024);
                    }
                    assertTrue("no actual VM decode", DecodeGate.countsForTest()[0] > 0);
                    return null;
                } finally {
                    if (stockRead != null && stockRead != stock) stockRead.recycle();
                    if (gatedRead != null && gatedRead != gated) gatedRead.recycle();
                    if (stock != null) stock.recycle();
                    if (gated != null) gated.recycle();
                }
            }).get(90, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
            assertTrue("straight fixture worker remained", executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private static Bitmap decode(byte[] png, boolean crop, boolean conserve, boolean extended, boolean hardware)
            throws Exception {
        return ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(png)),
                (decoder, info, source) -> {
                    decoder.setUnpremultipliedRequired(true);
                    decoder.setAllocator(hardware ? ImageDecoder.ALLOCATOR_HARDWARE : ImageDecoder.ALLOCATOR_SOFTWARE);
                    if (crop) decoder.setCrop(new Rect(1, 1, 63, 31));
                    if (conserve) decoder.setMemorySizePolicy(ImageDecoder.MEMORY_POLICY_LOW_RAM);
                    if (extended) decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.EXTENDED_SRGB));
                });
    }

    private static ByteBuffer pixels(Bitmap bitmap) {
        final ByteBuffer pixels = ByteBuffer.allocate(bitmap.getByteCount()).order(ByteOrder.LITTLE_ENDIAN);
        bitmap.copyPixelsToBuffer(pixels);
        return pixels;
    }

    private static void chunk(DataOutputStream out, String name, byte[] bytes) throws Exception {
        final byte[] type = name.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        out.writeInt(bytes.length);
        out.write(type);
        out.write(bytes);
        final CRC32 crc = new CRC32();
        crc.update(type);
        crc.update(bytes);
        out.writeInt((int) crc.getValue());
    }

    private static byte[] png(boolean deep) throws Exception {
        final ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        final DataOutputStream out = new DataOutputStream(encoded);
        out.writeLong(0x89504e470d0a1a0aL);
        final ByteArrayOutputStream header = new ByteArrayOutputStream();
        final DataOutputStream dimensions = new DataOutputStream(header);
        dimensions.writeInt(64);
        dimensions.writeInt(32);
        dimensions.write(new byte[] {(byte) (deep ? 16 : 8), 6, 0, 0, 0});
        chunk(out, "IHDR", header.toByteArray());
        if (deep) chunk(out, "gAMA", new byte[] {0, 1, (byte) 0x86, (byte) 0xa0});
        else chunk(out, "sRGB", new byte[] {0});
        final ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (DataOutputStream pixels = new DataOutputStream(new DeflaterOutputStream(compressed))) {
            for (int y = 0; y < 32; y++) {
                pixels.writeByte(0);
                for (int x = 0; x < 64; x++) {
                    final int index = y * 64 + x;
                    final int alpha = (index & 3) == 0 ? 0 : (index & 3) == 1 ? 1
                            : (index & 3) == 2 ? 32768 : 65535;
                    final int[] rgba = {(index * 61) & 65535, 65535, 32768, alpha};
                    for (int value : rgba) {
                        if (deep) pixels.writeShort(value);
                        else pixels.writeByte(value >>> 8);
                    }
                }
            }
        }
        chunk(out, "IDAT", compressed.toByteArray());
        chunk(out, "IEND", new byte[0]);
        return encoded.toByteArray();
    }
}
