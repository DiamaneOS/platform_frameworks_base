/* Copyright (C) 2026 DiamaneOS */
package de.diamaneos.decode.client;

import static org.junit.Assert.*;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.DecodeGate;
import android.graphics.DecodeMemory;
import android.graphics.GateRefill;
import android.os.SystemClock;
import java.io.ByteArrayOutputStream;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.concurrent.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** Public decodes release originals without waiting for garbage collection. */
public class GateReplyLifetimeDeviceTest {
    private byte[] encoded;
    private long baseline;
    private final List<Object> priorStorage = new ArrayList<>();
    private FakeGateProvider provider;

    @Before public void prepare() throws Exception {
        DecodeGate.setForcedForTest(true);
        DecodeGate.setWaitForTest(30_000L);
        DecodeGate.resetForTest();
        Bitmap pixel = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            assertTrue(pixel.compress(Bitmap.CompressFormat.PNG, 100, bytes));
            encoded = bytes.toByteArray();
        } finally { pixel.recycle(); }
        provider = new FakeGateProvider(0);
        provider.width = 4096;
        provider.height = 2048;
        DecodeGate.setLocal(provider);
        Field lock = DecodeMemory.class.getDeclaredField("sLock");
        lock.setAccessible(true);
        Field pixels = DecodeMemory.class.getDeclaredField("sPixels");
        pixels.setAccessible(true);
        synchronized (lock.get(null)) {
            for (Object held : (Iterable<?>) pixels.get(null)) {
                Field identity = held.getClass().getDeclaredField("identity");
                identity.setAccessible(true);
                Object live = ((WeakReference<?>) identity.get(held)).get();
                if (live != null) priorStorage.add(live);
            }
            baseline = DecodeMemory.held();
        }
    }

    @After public void restore() {
        DecodeGate.setLocal(null);
        DecodeGate.setForcedForTest(null);
        DecodeGate.setWaitForTest(null);
        DecodeGate.resetForTest();
        priorStorage.clear();
    }

    private Bitmap decode() {
        return BitmapFactory.decodeByteArray(encoded, 0, encoded.length);
    }

    private void held(long expected) {
        long end = SystemClock.uptimeMillis() + 2_000;
        while (DecodeMemory.held() != expected && SystemClock.uptimeMillis() < end) SystemClock.sleep(10);
        assertEquals("reply originals retained without a consumer", expected, DecodeMemory.held());
    }

    @Test public void uncachedOriginalEndsWithItsReader() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Bitmap result = null;
        try {
            result = executor.submit(this::decode).get(30, TimeUnit.SECONDS);
            assertNotNull(result);
            assertEquals(32L << 20, result.getAllocationByteCount());
            held(baseline + result.getAllocationByteCount());
            assertFalse(result.isRecycled());
        } finally {
            if (result != null) result.recycle();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
        held(baseline);
    }

    @Test public void cacheEvictionKeepsOnlyTheCallerCopy() throws Exception {
        provider.width = provider.height = 64;
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Bitmap result = null;
        try {
            result = executor.submit(this::decode).get(30, TimeUnit.SECONDS);
            assertNotNull(result);
            DecodeGate.resetForTest();
            held(baseline + result.getAllocationByteCount());
            assertFalse(result.isRecycled());
            assertEquals(0xffff0000, result.getPixel(0, 0));
        } finally {
            if (result != null) result.recycle();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
        held(baseline);
    }

    @Test public void joinedReadersKeepIndependentCopies() throws Exception {
        provider.delayMs = 200;
        AtomicInteger decodes = new AtomicInteger();
        DecodeGate.setLocal((method, extras) -> {
            if (!DecodeGate.METHOD_PROBE.equals(method) && !DecodeGate.METHOD_HEADER.equals(method)) {
                decodes.incrementAndGet();
            }
            return provider.call(method, extras);
        });
        ExecutorService executor = Executors.newFixedThreadPool(2);
        Bitmap first = null, second = null;
        List<Bitmap> produced = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch start = new CountDownLatch(1);
        try {
            Callable<Bitmap> load = () -> {
                start.await();
                Bitmap result = decode();
                if (result != null) produced.add(result);
                return result;
            };
            Future<Bitmap> a = executor.submit(load);
            Future<Bitmap> b = executor.submit(load);
            start.countDown();
            first = a.get(30, TimeUnit.SECONDS);
            second = b.get(30, TimeUnit.SECONDS);
            assertNotNull(first);
            assertNotNull(second);
            assertNotSame(first, second);
            assertEquals("requests did not join", 1, decodes.get());
            held(baseline + first.getAllocationByteCount() + second.getAllocationByteCount());
            first.recycle();
            held(baseline + second.getAllocationByteCount());
            assertFalse(second.isRecycled());
            assertEquals(0xffff0000, second.getPixel(0, 0));
        } finally {
            executor.shutdownNow();
            boolean stopped = executor.awaitTermination(5, TimeUnit.SECONDS);
            for (Bitmap result : produced) result.recycle();
            assertTrue(stopped);
        }
        held(baseline);
    }
}
