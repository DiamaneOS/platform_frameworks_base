/* Copyright (C) 2026 DiamaneOS */
package de.diamaneos.decode.client;

import static org.junit.Assert.*;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;
import androidx.test.platform.app.InstrumentationRegistry;
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

    @Test public void bitmapFactoryWideStraightStorageMatchesStock() throws Exception {
        final byte[] avif;
        try (java.io.InputStream in = InstrumentationRegistry.getInstrumentation().getTargetContext()
                .getResources().openRawResource(de.diamaneos.decode.client.tests.R.raw.vm_avif_alpha_10bit)) {
            avif = in.readAllBytes();
        }
        factoryWide(png(true), avif, 64, 32, null);
    }

    @Test public void bitmapFactoryNeutralAvifStraightSamplingPreservesAlphaEffect() throws Exception {
        try (java.io.InputStream in = InstrumentationRegistry.getInstrumentation().getTargetContext()
                .getResources().openRawResource(de.diamaneos.decode.client.tests.R.raw.vm_avif_neutral_alpha_10bit);
                java.io.InputStream opaque = InstrumentationRegistry.getInstrumentation().getTargetContext()
                .getResources().openRawResource(de.diamaneos.decode.client.tests.R.raw.vm_avif_neutral_opaque_10bit)) {
            factoryWide(null, in.readAllBytes(), 512, 32, opaque.readAllBytes());
        }
    }

    private static void factoryWide(byte[] png, byte[] avif, int sourceWidth, int sourceHeight, byte[] opaque)
            throws Exception {
        for (int kind = png == null ? 1 : 0; kind < 2; kind++) {
            final byte[] input = kind == 0 ? png : avif;
            final Bitmap.Config storage = kind == 0 ? Bitmap.Config.RGBA_F16 : Bitmap.Config.RGBA_1010102;
            for (int mode = 0; mode < 5; mode++) for (int[] scale : new int[][] {
                    {1, 0, 0}, {2, 0, 0}, {2, 160, 240}, {1, 480, 160}}) {
                Bitmap stock = null, gated = null, stockRead = null, gatedRead = null;
                Bitmap stockOpaque = null, gatedOpaque = null, stockOpaqueRead = null, gatedOpaqueRead = null;
                final BitmapFactory.Options before = straightOptions(mode, scale, storage, sourceWidth, sourceHeight);
                final BitmapFactory.Options after = straightOptions(mode, scale, storage, sourceWidth, sourceHeight);
                final int stockGeneration = before.inBitmap != null ? before.inBitmap.getGenerationId() : 0;
                final int gateGeneration = after.inBitmap != null ? after.inBitmap.getGenerationId() : 0;
                try {
                    DecodeGate.setForcedForTest(false);
                    stock = BitmapFactory.decodeByteArray(input, 0, input.length, before);
                    if (opaque != null) stockOpaque = opaqueReference(opaque, mode, scale);
                    DecodeGate.setForcedForTest(true);
                    DecodeGate.resetForTest();
                    gated = BitmapFactory.decodeByteArray(input, 0, input.length, after);
                    if (opaque != null) gatedOpaque = opaqueReference(opaque, mode, scale);
                    if (stock == null) {
                        assertEquals("only the hardware outcome may be unsupported", 3, mode);
                        assertNull(gated);
                        Log.i("StraightFactoryProof", "stock and gate refuse hardware kind=" + kind);
                        continue;
                    }
                    assertNotNull("gated wide straight", gated);
                    if (mode != 3) assertFalse("stock fixture is straight", stock.isPremultiplied());
                    assertEquals("stock alpha outcome", stock.isPremultiplied(), gated.isPremultiplied());
                    assertEquals(stock.getConfig(), gated.getConfig());
                    assertEquals(stock.getColorSpace(), gated.getColorSpace());
                    assertEquals(stock.isMutable(), gated.isMutable());
                    assertEquals(stock.getWidth(), gated.getWidth());
                    assertEquals(stock.getHeight(), gated.getHeight());
                    if (mode == 2 || mode == 4) {
                        assertTrue(stock == before.inBitmap);
                        assertTrue(gated == after.inBitmap);
                        assertTrue("stock reused pixels invalidate their generation", stockGeneration != stock.getGenerationId());
                        assertTrue("gated reused pixels invalidate their generation", gateGeneration != gated.getGenerationId());
                        assertTrue("old reused pixels were replaced", gated.getPixel(0, 0) != android.graphics.Color.MAGENTA);
                    }
                    stockRead = mode == 3 ? stock.copy(storage, false) : stock;
                    gatedRead = mode == 3 ? gated.copy(storage, false) : gated;
                    assertNotNull(stockRead);
                    assertNotNull(gatedRead);
                    final ByteBuffer expected = pixels(stockRead), actual = pixels(gatedRead);
                    ByteBuffer opaqueExpected = null, opaqueActual = null;
                    if (opaque != null) {
                        assertNotNull(stockOpaque);
                        assertNotNull(gatedOpaque);
                        stockOpaqueRead = mode == 3 ? stockOpaque.copy(storage, false) : stockOpaque;
                        gatedOpaqueRead = mode == 3 ? gatedOpaque.copy(storage, false) : gatedOpaque;
                        assertNotNull(stockOpaqueRead);
                        assertNotNull(gatedOpaqueRead);
                        assertEquals(stockRead.getWidth(), stockOpaqueRead.getWidth());
                        assertEquals(stockRead.getHeight(), stockOpaqueRead.getHeight());
                        assertEquals(gatedRead.getWidth(), gatedOpaqueRead.getWidth());
                        assertEquals(gatedRead.getHeight(), gatedOpaqueRead.getHeight());
                        opaqueExpected = pixels(stockOpaqueRead);
                        opaqueActual = pixels(gatedOpaqueRead);
                    }
                    int absoluteDifference = 0;
                    final HashSet<Integer> levels = new HashSet<>();
                    boolean hidden = false;
                    for (int y = 0; y < stockRead.getHeight(); y++) for (int x = 0; x < stockRead.getWidth(); x++) {
                        final int a = y * stockRead.getRowBytes() + x * (kind == 0 ? 8 : 4);
                        final int b = y * gatedRead.getRowBytes() + x * (kind == 0 ? 8 : 4);
                        final int alpha = kind == 0 ? expected.getShort(a + 6) & 65535 : expected.getInt(a) >>> 30;
                        final int green = kind == 0 ? expected.getShort(a + 2) & 65535 : (expected.getInt(a) >>> 10) & 1023;
                        hidden |= alpha == 0 && green != 0;
                        levels.add(kind == 0 ? expected.getShort(a) & 65535 : expected.getInt(a) & 1023);
                        for (int channel = 0; channel < 4; channel++) {
                            final int wanted = kind == 0 ? expected.getShort(a + channel * 2) & 65535
                                    : (expected.getInt(a) >>> (channel * 10)) & (channel == 3 ? 3 : 1023);
                            final int value = kind == 0 ? actual.getShort(b + channel * 2) & 65535
                                    : (actual.getInt(b) >>> (channel * 10)) & (channel == 3 ? 3 : 1023);
                            absoluteDifference = Math.max(absoluteDifference, Math.abs(wanted - value));
                            if (opaque != null && channel < 3) {
                                final int stockBase = (opaqueExpected.getInt(y * stockOpaqueRead.getRowBytes() + x * 4)
                                        >>> (channel * 10)) & 1023;
                                final int gateBase = (opaqueActual.getInt(y * gatedOpaqueRead.getRowBytes() + x * 4)
                                        >>> (channel * 10)) & 1023;
                                assertTrue("straight factory alpha effect mode=" + mode + " at " + x + "," + y
                                        + "/" + channel + " stock=" + (wanted - stockBase) + " gate=" + (value - gateBase),
                                        Math.abs((wanted - stockBase) - (value - gateBase)) <= 1);
                            } else {
                                assertTrue("straight factory raw kind=" + kind + " mode=" + mode + " at " + x + "," + y
                                        + "/" + channel + " expected=" + wanted + " actual=" + value,
                                        Math.abs(wanted - value) <= 1);
                            }
                        }
                    }
                    if (mode == 0 && scale[0] == 1 && scale[1] == 0) {
                        assertEquals(storage, stock.getConfig());
                        assertTrue("stock transparent RGB control", hidden);
                        assertTrue("stock deep precision control", levels.size() > (kind == 0 ? 1024 : 256));
                    }
                    Log.i("StraightFactoryProof", "kind=" + kind + " mode=" + mode
                            + " sample/density=" + java.util.Arrays.toString(scale)
                            + " opaqueReference=" + (opaque != null) + " absoluteMax=" + absoluteDifference);
                } finally {
                    if (stockOpaqueRead != null && stockOpaqueRead != stockOpaque) stockOpaqueRead.recycle();
                    if (gatedOpaqueRead != null && gatedOpaqueRead != gatedOpaque) gatedOpaqueRead.recycle();
                    if (stockOpaque != null) stockOpaque.recycle();
                    if (gatedOpaque != null) gatedOpaque.recycle();
                    if (stockRead != null && stockRead != stock) stockRead.recycle();
                    if (gatedRead != null && gatedRead != gated) gatedRead.recycle();
                    if (stock != null) stock.recycle();
                    if (gated != null) gated.recycle();
                    if (before.inBitmap != null && before.inBitmap != stock) before.inBitmap.recycle();
                    if (after.inBitmap != null && after.inBitmap != gated) after.inBitmap.recycle();
                }
            }
        }
    }

    private static Bitmap opaqueReference(byte[] input, int mode, int[] scale) {
        final BitmapFactory.Options options = new BitmapFactory.Options();
        options.inPremultiplied = false;
        options.inSampleSize = scale[0];
        options.inDensity = scale[1];
        options.inTargetDensity = scale[2];
        if (mode == 3) options.inPreferredConfig = Bitmap.Config.HARDWARE;
        return BitmapFactory.decodeByteArray(input, 0, input.length, options);
    }

    private static BitmapFactory.Options straightOptions(int mode, int[] scale, Bitmap.Config storage,
            int sourceWidth, int sourceHeight) {
        final BitmapFactory.Options options = new BitmapFactory.Options();
        options.inPremultiplied = false;
        options.inSampleSize = scale[0];
        options.inDensity = scale[1];
        options.inTargetDensity = scale[2];
        options.inMutable = mode == 1 || mode == 2 || mode == 4;
        if (mode == 2) options.inBitmap = Bitmap.createBitmap(Math.max(128, sourceWidth), Math.max(128, sourceHeight), Bitmap.Config.ARGB_8888, true,
                ColorSpace.get(ColorSpace.Named.DISPLAY_P3));
        if (mode == 4) {
            final float ratio = scale[1] == 0 ? 1f : (float) scale[2] / scale[1];
            options.inBitmap = Bitmap.createBitmap(Math.max(1, (int) (sourceWidth / scale[0] * ratio + 0.5f)),
                    Math.max(1, (int) (sourceHeight / scale[0] * ratio + 0.5f)), storage);
        }
        if (options.inBitmap != null) options.inBitmap.eraseColor(android.graphics.Color.MAGENTA);
        if (mode == 3) options.inPreferredConfig = Bitmap.Config.HARDWARE;
        return options;
    }

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
                    boolean nativeHidden = false, nativeTransparent = false;
                    for (int y = 0; y < stockRead.getHeight(); y++) {
                        for (int x = 0; x < stockRead.getWidth(); x++) {
                            final int before = y * stockRead.getRowBytes() + x * (wide ? 8 : 4);
                            final int after = y * gatedRead.getRowBytes() + x * (wide ? 8 : 4);
                            final int alpha = wide ? reference.getShort(before + 6) & 65535
                                    : reference.get(before + 3) & 255;
                            final int green = wide ? reference.getShort(before + 2) & 65535
                                    : reference.get(before + 1) & 255;
                            nativeHidden |= alpha == 0 && green != 0;
                            nativeTransparent |= alpha == 0;
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
                    // A cropped decode is drawn into its bitmap, so stock gives transparent pixels no colour: the
                    // fixture then only needs transparent pixels, which the comparison above covers.
                    if (!hardware && !crop) assertTrue("native fixture lacks transparent RGB", nativeHidden);
                    if (!hardware && crop) assertTrue("native fixture lacks transparent pixels", nativeTransparent);
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
