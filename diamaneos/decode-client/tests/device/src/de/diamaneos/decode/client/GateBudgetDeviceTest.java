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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.DecodeGate;
import android.graphics.DecodeMemory;
import android.graphics.Gainmap;
import android.graphics.GateRefill;
import android.graphics.ImageDecoder;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The decode gate's time, memory and refill rules (forced on here as in a gated process), most with its provider
 * played in this process (FakeGateProvider): a whole call on the main thread takes at most the main thread's time,
 * input from a slow provider or a slow pipe included, a stream this thread may not read is not read, requests whose
 * answer never came leave the table, a reused bitmap loses its gain map as in stock, the cache charges gain maps, the
 * memory is reserved before it is allocated, and a refill registration gets late answers (one too large for the cache
 * too) and stops at a refusal.
 */
@RunWith(AndroidJUnit4.class)
public class GateBudgetDeviceTest {
    private static final String TAG = "GateBudgetTest";
    private static final long MARGIN_MS = 150;

    private final Context mContext = InstrumentationRegistry.getInstrumentation().getTargetContext();

    @Before
    public void gated() {
        DecodeGate.setForcedForTest(true);
        DecodeGate.resetForTest();
        ProbeProvider.ICONS.clear();
    }

    @After
    public void asBefore() {
        DecodeGate.setLocal(null);
        DecodeGate.setForcedForTest(null);
        DecodeGate.setWaitForTest(null);
        DecodeGate.resetForTest();
        DecodeMemory.setMaxForTest(0);
        ProbeProvider.ICONS.clear();
    }

    private static byte[] png(int width, int height, int color) {
        final Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(color);
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out));
        return out.toByteArray();
    }

    // Runs work on the main thread and returns how long it took there.
    private static long onMain(Runnable work) {
        final AtomicLong took = new AtomicLong();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            final long start = SystemClock.uptimeMillis();
            work.run();
            took.set(SystemClock.uptimeMillis() - start);
        });
        return took.get();
    }

    // H1

    @Test
    public void mainThread_theWholeCallHasTheMainThreadsTime() {
        // Each answer takes 190 ms: a probe and a region together take longer than the main thread's time.
        DecodeGate.setLocal(new FakeGateProvider(190));
        final byte[] png = png(64, 32, Color.RED);
        final AtomicReference<Bitmap> got = new AtomicReference<>();
        final long took = onMain(() -> got.set(BitmapFactory.decodeByteArray(png, 0, png.length)));
        Log.i(TAG, "main thread, two answers of 190 ms each: " + took + " ms, bitmap " + got.get());
        assertTrue("took " + took + " ms", took <= DecodeGate.MAIN_MS + MARGIN_MS);
        assertNull(got.get());
    }

    @Test
    public void mainThread_aSlowProviderIsWithinTheCallsTime() throws Exception {
        ProbeProvider.ICONS.put("slow", png(64, 32, Color.BLUE));
        final Uri uri = Uri.parse("content://" + ProbeProvider.AUTHORITY + "/icon-slow/slow");
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final long took = onMain(() -> {
            try {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(mContext.getContentResolver(), uri));
                failure.set(new AssertionError("decoded"));
            } catch (IOException e) {
                failure.set(e);
            }
        });
        Log.i(TAG, "main thread, a provider that opens in " + ProbeProvider.SLOW_MS + " ms: " + took + " ms, "
                + failure.get());
        assertTrue("took " + took + " ms", took <= DecodeGate.MAIN_MS + MARGIN_MS);
        assertTrue(String.valueOf(failure.get()), failure.get() instanceof ImageDecoder.DecodeException);
        SystemClock.sleep(ProbeProvider.SLOW_MS); // the input thread's open ends before the next test
    }

    @Test
    public void mainThread_aSlowPipeIsWithinTheCallsTime() throws Exception {
        final byte[] png = png(64, 32, Color.BLUE);
        final ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
        final Thread writer = new Thread(() -> {
            try (OutputStream out = new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])) {
                SystemClock.sleep(1_000);
                out.write(png);
            } catch (IOException e) {
                // The reader left: its read was stopped.
            }
        });
        writer.start();
        final AtomicReference<Bitmap> got = new AtomicReference<>();
        final long took;
        try (InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(pipe[0])) {
            took = onMain(() -> got.set(BitmapFactory.decodeStream(in)));
        }
        writer.join(5_000);
        Log.i(TAG, "main thread, a pipe that gives its bytes after 1000 ms: " + took + " ms");
        assertTrue("took " + took + " ms", took <= DecodeGate.MAIN_MS + MARGIN_MS);
        assertNull(got.get());
    }

    @Test
    public void aStreamThatCannotBeReadElsewhere_isNotReadOnTheMainThread_butOnAWorker() {
        DecodeGate.setLocal(new FakeGateProvider(0));
        final byte[] png = png(64, 32, Color.RED);
        final AtomicInteger reads = new AtomicInteger();
        final InputStream counting = new FilterInputStream(new ByteArrayInputStream(png)) {
            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                reads.incrementAndGet();
                return super.read(b, off, len);
            }
        };
        final AtomicReference<Bitmap> got = new AtomicReference<>();
        final long took = onMain(() -> got.set(BitmapFactory.decodeStream(counting)));
        assertNull(got.get());
        assertEquals("reads on the main thread", 0, reads.get());
        assertTrue("took " + took + " ms", took <= DecodeGate.MAIN_MS + MARGIN_MS);
        // A worker reads it itself, as before.
        final Bitmap worker = BitmapFactory.decodeStream(counting);
        assertNotNull(worker);
        assertTrue(reads.get() > 0);
    }

    // Requests whose answer never came

    @Test
    public void requestsWhoseAnswerNeverCame_leaveTheTable() {
        DecodeGate.setLocal(new FakeGateProvider(-1)); // takes every request, never answers
        DecodeGate.setWaitForTest(0L);
        for (int i = 0; i < 20; i++) {
            final byte[] png = png(8 + i, 8, Color.RED); // other bytes each time: other keys
            assertNull(BitmapFactory.decodeByteArray(png, 0, png.length));
        }
        assertTrue("under way: " + DecodeGate.callsForTest(), DecodeGate.callsForTest() >= 20);
        SystemClock.sleep(DecodeGate.SERVER_MS + 600);
        final byte[] last = png(64, 64, Color.RED);
        assertNull(BitmapFactory.decodeByteArray(last, 0, last.length));
        assertEquals("only the newest is under way", 1, DecodeGate.callsForTest());
    }

    // M8

    @Test
    public void aReusedBitmap_losesItsGainMap_asInStock() {
        final byte[] png = png(64, 32, Color.RED);
        for (boolean gated : new boolean[] {false, true}) {
            DecodeGate.setForcedForTest(gated);
            if (gated) {
                DecodeGate.setLocal(new FakeGateProvider(0));
            }
            final Bitmap reused = Bitmap.createBitmap(64, 32, Bitmap.Config.ARGB_8888);
            reused.setGainmap(new Gainmap(Bitmap.createBitmap(32, 16, Bitmap.Config.ARGB_8888)));
            assertTrue(reused.hasGainmap());
            final BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inMutable = true;
            opts.inBitmap = reused;
            final Bitmap got = BitmapFactory.decodeByteArray(png, 0, png.length, opts);
            assertSame(reused, got);
            assertFalse((gated ? "gated" : "stock") + ": the gain map of the image before", got.hasGainmap());
        }
    }

    // H2

    @Test
    public void theCache_chargesTheGainMapsPixels() {
        final Bitmap bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888);
        final Bundle plain = new Bundle();
        plain.putParcelable(DecodeGate.EXTRA_BITMAP, bitmap.copy(Bitmap.Config.ARGB_8888, false));
        final int charge = DecodeGate.cacheChargeForTest(plain);
        bitmap.setGainmap(new Gainmap(Bitmap.createBitmap(50, 50, Bitmap.Config.ARGB_8888)));
        final Bundle hdr = new Bundle();
        hdr.putParcelable(DecodeGate.EXTRA_BITMAP, bitmap);
        assertEquals(charge + 50 * 50 * 4, DecodeGate.cacheChargeForTest(hdr));
    }

    @Test
    public void memoryIsReservedFirst_noRoomNowIsForNow_tooLargeEverIsRefused() throws Exception {
        final FakeGateProvider provider = new FakeGateProvider(0);
        DecodeGate.setLocal(provider);
        final byte[] png = png(64, 32, Color.RED);
        DecodeMemory.setMaxForTest(1L << 20);
        assertNotNull(BitmapFactory.decodeByteArray(png, 0, png.length));
        try (DecodeMemory.Reservation others = DecodeMemory.reserve((1L << 20) - 1024)) {
            DecodeGate.resetForTest();
            assertNull(BitmapFactory.decodeByteArray(png, 0, png.length));
            try {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(png));
                fail("decoded without room");
            } catch (ImageDecoder.DecodeException e) {
                assertEquals("a failure of the moment", ImageDecoder.DecodeException.SOURCE_EXCEPTION, e.getError());
            }
        }
        DecodeGate.resetForTest();
        assertNotNull("room again", BitmapFactory.decodeByteArray(png, 0, png.length));
        // An image whose decode needs more than this process takes at all.
        provider.width = 1024;
        provider.height = 1024;
        DecodeGate.resetForTest();
        try {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(png));
            fail("decoded what could never fit");
        } catch (ImageDecoder.DecodeException e) {
            assertEquals("refused", ImageDecoder.DecodeException.SOURCE_MALFORMED_DATA, e.getError());
        }
        assertEquals(0, DecodeMemory.held());
    }

    // M1

    @Test
    public void aRefill_reloadsOnceTheAnswerIsIn_alsoOneTooLargeForTheCache() throws Exception {
        final FakeGateProvider provider = new FakeGateProvider(300);
        provider.width = 2000; // 12.8 MB of pixels: above half the gate's cache, never kept there
        provider.height = 1600;
        DecodeGate.setLocal(provider);
        DecodeGate.setWaitForTest(0L); // nothing is waited for: only the refill brings the image
        final byte[] png = png(64, 32, Color.RED);
        final AtomicReference<Bitmap> shown = new AtomicReference<>();
        final AtomicInteger loads = new AtomicInteger();
        final Executor executor = Executors.newSingleThreadExecutor();
        final GateRefill[] refill = new GateRefill[1];
        final Runnable load = () -> {
            loads.incrementAndGet();
            final Bitmap b = refill[0].load(() -> BitmapFactory.decodeByteArray(png, 0, png.length));
            if (b != null) {
                shown.set(b);
            }
        };
        refill[0] = new GateRefill(executor, load);
        executor.execute(load);
        final long end = SystemClock.uptimeMillis() + 10_000;
        while (shown.get() == null && SystemClock.uptimeMillis() < end) {
            SystemClock.sleep(50);
        }
        Log.i(TAG, "refill: " + loads.get() + " loads, " + refill[0].roundsForTest() + " reloads");
        assertNotNull("filled in", shown.get());
        assertEquals(2000, shown.get().getWidth());
        assertTrue(loads.get() <= 1 + GateRefill.MAX_ROUNDS);
        SystemClock.sleep(200);
        assertFalse("ended once had", refill[0].isActive());
        // Not in the gate's cache: without the refill the same call has nothing.
        assertNull(BitmapFactory.decodeByteArray(png, 0, png.length));
    }

    @Test
    public void aRefill_stopsAtARefusal() throws Exception {
        final FakeGateProvider provider = new FakeGateProvider(100);
        provider.refuse = true;
        DecodeGate.setLocal(provider);
        DecodeGate.setWaitForTest(0L);
        final byte[] png = png(64, 32, Color.RED);
        final AtomicInteger loads = new AtomicInteger();
        final Executor executor = Executors.newSingleThreadExecutor();
        final GateRefill[] refill = new GateRefill[1];
        final Runnable load = () -> {
            loads.incrementAndGet();
            assertNull(refill[0].load(() -> BitmapFactory.decodeByteArray(png, 0, png.length)));
        };
        refill[0] = new GateRefill(executor, load);
        executor.execute(load);
        SystemClock.sleep(2_000);
        assertEquals("loaded once", 1, loads.get());
        assertFalse(refill[0].isActive());
    }

    // Picture

    @Test
    public void picturesAreNotReadFromStreams_inAGatedProcess() throws Exception {
        final AtomicInteger reads = new AtomicInteger();
        final InputStream counting = new InputStream() {
            @Override
            public int read() {
                reads.incrementAndGet();
                return -1;
            }

            @Override
            public int read(byte[] b, int off, int len) {
                reads.incrementAndGet();
                return -1;
            }
        };
        final java.lang.reflect.Method createFromStream =
                android.graphics.Picture.class.getMethod("createFromStream", InputStream.class);
        try {
            createFromStream.invoke(null, counting);
            fail("a picture was read");
        } catch (java.lang.reflect.InvocationTargetException e) {
            assertTrue(String.valueOf(e.getCause()), e.getCause() instanceof IllegalArgumentException);
        }
        assertEquals("bytes read natively", 0, reads.get());
    }
}
