/* Copyright (C) 2026 DiamaneOS */
package de.diamaneos.decode.client;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.DecodeGate;
import android.graphics.Gainmap;
import android.graphics.ImageDecoder;
import android.util.Log;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.security.MessageDigest;
import java.util.Random;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** Naturally encoded large inputs, with stock-positive base and gain pixel controls. */
public class GateLargeInputDeviceTest {
    @Before public void prepare() {
        DecodeGate.setWaitForTest(60_000L);
        DecodeGate.resetForTest();
    }

    @After public void restore() {
        DecodeGate.setForcedForTest(null);
        DecodeGate.setWaitForTest(null);
        DecodeGate.resetForTest();
    }

    private static void noise(Bitmap bitmap, long seed) {
        final Random random = new Random(seed);
        final int[] row = new int[bitmap.getWidth()];
        for (int y = 0; y < bitmap.getHeight(); y++) {
            for (int x = 0; x < row.length; x++) row[x] = 0xff000000 | random.nextInt(1 << 24);
            bitmap.setPixels(row, 0, row.length, 0, y, row.length, 1);
        }
    }

    private static File jpeg(boolean hdr) throws Exception {
        final File file = File.createTempFile("large-input", ".jpg",
                InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir());
        final Bitmap base = Bitmap.createBitmap(hdr ? 4096 : 8160, hdr ? 3072 : 6144, Bitmap.Config.ARGB_8888);
        Bitmap gain = null;
        try {
            noise(base, 20261126L);
            if (hdr) {
                gain = Bitmap.createBitmap(4096, 3072, Bitmap.Config.ARGB_8888);
                noise(gain, 20261127L);
                final Gainmap map = new Gainmap(gain);
                map.setRatioMax(4f, 4f, 4f);
                map.setDisplayRatioForFullHdr(4f);
                base.setGainmap(map);
            }
            try (FileOutputStream out = new FileOutputStream(file)) {
                assertTrue(base.compress(Bitmap.CompressFormat.JPEG, hdr ? 100 : 95, out));
            }
        } finally {
            base.recycle();
            if (gain != null) gain.recycle();
        }
        assertTrue("encoded pixels must cross 32 MiB", file.length() > 32L * 1024 * 1024);
        assertTrue(file.length() <= DecodeGate.MAX_BYTES);
        Log.i("LargeInputProof", "encoded " + file.length() + " bytes, HDR=" + hdr);
        return file;
    }

    private static int[] samples(Bitmap bitmap) {
        final int[] samples = new int[64];
        for (int y = 0; y < 8; y++) for (int x = 0; x < 8; x++) {
            samples[y * 8 + x] = bitmap.getPixel((2 * x + 1) * bitmap.getWidth() / 16,
                    (2 * y + 1) * bitmap.getHeight() / 16);
        }
        return samples;
    }

    private static void samePixels(int[] expected, Bitmap actual) {
        final int[] pixels = samples(actual);
        for (int i = 0; i < pixels.length; i++) for (int shift : new int[] {0, 8, 16, 24}) {
            assertTrue("pixel " + i + " channel " + shift,
                    Math.abs(((expected[i] >>> shift) & 255) - ((pixels[i] >>> shift) & 255)) <= 12);
        }
    }

    private static Bitmap decode(File file, boolean factory, int width, int height) throws Exception {
        if (factory) return BitmapFactory.decodeFile(file.getPath());
        return ImageDecoder.decodeBitmap(ImageDecoder.createSource(file), (decoder, info, source) -> {
            decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
            if (width != 0) decoder.setTargetSize(width, height);
        });
    }

    private static void pair(File file, boolean hdr, boolean factory, int targetWidth, int targetHeight)
            throws Exception {
        DecodeGate.setForcedForTest(false);
        final long stockStarted = android.os.SystemClock.elapsedRealtimeNanos();
        final Bitmap stock = decode(file, factory, targetWidth, targetHeight);
        final long stockNanos = android.os.SystemClock.elapsedRealtimeNanos() - stockStarted;
        Log.i("LargeInputProof", "stock api=" + (factory ? "BitmapFactory" : "ImageDecoder")
                + " bytes=" + file.length() + " elapsedNs=" + stockNanos + " hdr=" + hdr);
        assertNotNull("stock positive", stock);
        final int width = stock.getWidth(), height = stock.getHeight();
        Log.i("LargeInputProof", "stock base=" + width + "x" + height + " config=" + stock.getConfig());
        final int[] base = samples(stock);
        assertEquals(hdr, stock.hasGainmap());
        final Gainmap stockMap = stock.getGainmap();
        final int[] gain = hdr ? samples(stockMap.getGainmapContents()) : null;
        final int gainWidth = hdr ? stockMap.getGainmapContents().getWidth() : 0;
        final int gainHeight = hdr ? stockMap.getGainmapContents().getHeight() : 0;
        final float[] ratio = hdr ? stockMap.getRatioMax() : null;
        if (hdr) stockMap.getGainmapContents().recycle();
        stock.recycle();
        DecodeGate.setForcedForTest(true);
        DecodeGate.resetForTest();
        DecodeGate.setWaitForTest(null);
        final long started = android.os.SystemClock.uptimeMillis();
        final Bitmap gated = decode(file, factory, targetWidth, targetHeight);
        final long elapsed = android.os.SystemClock.uptimeMillis() - started;
        Log.i("LargeInputProof", "gated api=" + (factory ? "BitmapFactory" : "ImageDecoder")
                + " bytes=" + file.length() + " elapsedMs=" + elapsed + " returned=" + (gated != null));
        assertNotNull("gated input", gated);
        try {
            assertTrue("production worker deadline, elapsed=" + elapsed, elapsed < 15_000L);
            assertEquals(width, gated.getWidth());
            assertEquals(height, gated.getHeight());
            samePixels(base, gated);
            if ("true".equals(InstrumentationRegistry.getArguments().getString("ownedDigest"))) {
                final MessageDigest digest = MessageDigest.getInstance("SHA-256");
                final int[] row = new int[width];
                final byte[] encoded = new byte[width * 4];
                for (int y = 0; y < height; y++) {
                    gated.getPixels(row, 0, width, 0, y, width, 1);
                    for (int x = 0; x < width; x++) for (int c = 0; c < 4; c++)
                        encoded[x * 4 + c] = (byte) (row[x] >>> (c * 8));
                    digest.update(encoded);
                }
                Log.i("LargeInputProof", "gated fullPixels=" + ((long) width * height)
                        + " sha256=" + java.util.HexFormat.of().formatHex(digest.digest()));
            }
            assertEquals(hdr, gated.hasGainmap());
            if (hdr) {
                final Gainmap map = gated.getGainmap();
                assertEquals(gainWidth, map.getGainmapContents().getWidth());
                assertEquals(gainHeight, map.getGainmapContents().getHeight());
                assertArrayEquals(ratio, map.getRatioMax(), 0f);
                samePixels(gain, map.getGainmapContents());
            }
            Log.i("LargeInputProof", "stock pair factory=" + factory + " base=" + width + "x" + height
                    + " gain=" + gainWidth + "x" + gainHeight + " productionElapsedMs=" + elapsed);
        } finally {
            if (gated.hasGainmap()) gated.getGainmap().getGainmapContents().recycle();
            gated.recycle();
        }
    }

    /** Diagnostic only: the ordinary pair above retains the production worker deadline. */
    @Test public void diagnoseLargeJpegExistingRequestTimings() throws Exception {
        final android.content.Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        final String path = InstrumentationRegistry.getArguments().getString("timingJpeg");
        assumeTrue("owned timing fixture supplied", path != null);
        final File file = new File(path);
        final File directory = context.getExternalFilesDir(null);
        assertNotNull(directory);
        assertTrue(file.getCanonicalPath().startsWith(directory.getCanonicalPath() + File.separator));
        assertTrue(file.length() > 32L * 1024 * 1024 && file.length() <= DecodeGate.MAX_BYTES);
        final java.util.concurrent.atomic.AtomicInteger sequence = new java.util.concurrent.atomic.AtomicInteger();
        DecodeGate.setForcedForTest(true);
        DecodeGate.setWaitForTest(60_000L);
        DecodeGate.resetForTest();
        DecodeGate.setLocal((method, extras) -> {
            final int id = sequence.incrementAndGet();
            final long start = android.os.SystemClock.uptimeMillis();
            final int width = extras.getInt(DecodeGate.EXTRA_WIDTH), height = extras.getInt(DecodeGate.EXTRA_HEIGHT);
            final android.os.IRemoteCallback original = android.os.IRemoteCallback.Stub.asInterface(
                    extras.getBinder(DecodeGate.EXTRA_CALLBACK));
            assertNotNull(original);
            extras.putBinder(DecodeGate.EXTRA_CALLBACK, new android.os.IRemoteCallback.Stub() {
                @Override public void sendResult(android.os.Bundle result) throws android.os.RemoteException {
                    Log.i("LargeInputTiming", "callback id=" + id + " operation=" + method + " geometry="
                            + width + "x" + height + " elapsedMs=" + (android.os.SystemClock.uptimeMillis() - start));
                    original.sendResult(result);
                }
            }.asBinder());
            // Local adds a delegate field that the normal app Binder request does not carry.
            extras.remove(DecodeGate.EXTRA_FOR_UID);
            final android.os.Bundle accepted = context.getContentResolver().call(DecodeGate.URI, method, null, extras);
            Log.i("LargeInputTiming", "accepted id=" + id + " operation=" + method + " geometry="
                    + width + "x" + height + " elapsedMs=" + (android.os.SystemClock.uptimeMillis() - start));
            return accepted;
        });
        Bitmap result = null;
        final long start = android.os.SystemClock.uptimeMillis();
        try {
            Log.i("LargeInputTiming", "diagnostic waitMs=60000");
            result = BitmapFactory.decodeFile(file.getPath());
            Log.i("LargeInputTiming", "complete elapsedMs=" + (android.os.SystemClock.uptimeMillis() - start));
            assertNotNull("diagnostic image", result);
        } finally {
            DecodeGate.setLocal(null);
            if (result != null) result.recycle();
        }
    }

    @Test public void fiftyMegapixelJpegAbove32MiBMatchesStock() throws Exception {
        final File file = jpeg(false);
        try {
            pair(file, false, true, 0, 0);
            pair(file, false, false, 0, 0);
        } finally { assertTrue(file.delete()); }
    }

    private static File ownedDiagnosticImage() throws Exception {
        final File directory = InstrumentationRegistry.getInstrumentation().getTargetContext().getExternalFilesDir(null);
        assertNotNull(directory);
        final String path = InstrumentationRegistry.getArguments().getString("ownedImage");
        assumeTrue("owned diagnostic input supplied", path != null);
        final File file = new File(path);
        assertTrue(file.getCanonicalPath().startsWith(directory.getCanonicalPath() + File.separator));
        assertTrue(file.length() > 0 && file.length() <= DecodeGate.MAX_BYTES);
        final MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (FileInputStream input = new FileInputStream(file)) {
            final byte[] block = new byte[65536];
            for (int n; (n = input.read(block)) != -1;) digest.update(block, 0, n);
        }
        assertEquals(InstrumentationRegistry.getArguments().getString("ownedSha256"),
                java.util.HexFormat.of().formatHex(digest.digest()));
        return file;
    }

    @Test public void ownedColdLargePairUsesProductionDeadlines() throws Exception {
        final File file = ownedDiagnosticImage();
        pair(file, false, !"ImageDecoder".equals(InstrumentationRegistry.getArguments().getString("api")), 0, 0);
    }

    @Test public void ownedLargeCallerCancellationIsObserved() throws Exception {
        final File file = ownedDiagnosticImage();
        final android.content.Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        final java.util.concurrent.CountDownLatch cancelled = new java.util.concurrent.CountDownLatch(1);
        DecodeGate.setForcedForTest(true);
        DecodeGate.setWaitForTest(null);
        final Thread worker = new Thread(() -> {
            Bitmap bitmap = null;
            try { bitmap = BitmapFactory.decodeFile(file.getPath()); }
            catch (Throwable result) { Log.i("LargeInputProof", "cancelled worker result=" + result.getClass().getName()); }
            finally { if (bitmap != null) bitmap.recycle(); }
            Log.i("LargeInputProof", "cancelled worker endedNs=" + android.os.SystemClock.elapsedRealtimeNanos());
        }, "owned-cancelled-image");
        final android.content.BroadcastReceiver receiver = new android.content.BroadcastReceiver() {
            @Override public void onReceive(android.content.Context ignored, android.content.Intent intent) {
                Log.i("LargeInputProof", "caller cancellationNs=" + android.os.SystemClock.elapsedRealtimeNanos());
                worker.interrupt();
                cancelled.countDown();
            }
        };
        context.registerReceiver(receiver, new android.content.IntentFilter("de.diamaneos.decode.client.tests.ADpfCancel"),
                "android.permission.DUMP", null, android.content.Context.RECEIVER_EXPORTED);
        try {
            worker.start();
            assertTrue("controlled cancellation requested", cancelled.await(30, java.util.concurrent.TimeUnit.SECONDS));
            worker.join(30000);
            assertFalse("owned caller ended", worker.isAlive());
        } finally { context.unregisterReceiver(receiver); worker.interrupt(); }
    }

    @Test public void largeUltraHdrDiscoversAndKeepsItsGainmap() throws Exception {
        final File file = jpeg(true);
        try {
            pair(file, true, true, 0, 0);
            pair(file, true, false, 2047, 1535);
        } finally { assertTrue(file.delete()); }
    }

    @Test public void ownedLargestObservedStockPositiveHeifGridMatchesStock() throws Exception {
        final File ownedDirectory = InstrumentationRegistry.getInstrumentation().getTargetContext().getExternalFilesDir(null);
        assertNotNull("owned external fixture directory", ownedDirectory);
        Log.i("LargeInputProof", "owned HEIF fixture directory=" + ownedDirectory);
        final String path = InstrumentationRegistry.getArguments().getString("largeHeif");
        assumeTrue("owned HEIF grid fixture supplied by the run", path != null);
        final String hash = InstrumentationRegistry.getArguments().getString("largeHeifSha256");
        assertNotNull(hash);
        final File file = new File(path);
        assertTrue(file.length() > 0 && file.length() <= DecodeGate.MAX_BYTES);
        final MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (FileInputStream in = new FileInputStream(file)) {
            final byte[] block = new byte[65536];
            for (int n; (n = in.read(block)) != -1;) digest.update(block, 0, n);
        }
        final StringBuilder actual = new StringBuilder();
        for (byte b : digest.digest()) actual.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        assertEquals(hash, actual.toString());
        pair(file, false, true, 0, 0);
        pair(file, false, false, 0, 0);
    }
}
