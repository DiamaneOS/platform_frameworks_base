/* Copyright (C) 2026 DiamaneOS */
package de.diamaneos.decode.client;

import static org.junit.Assert.*;
import android.app.ActivityThread;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.DecodeGate;
import android.graphics.DecodeMemory;
import android.graphics.ImageDecoder;
import android.graphics.Movie;
import android.graphics.drawable.AnimatedImageDrawable;
import android.graphics.drawable.Drawable;
import android.os.Debug;
import android.os.SystemClock;
import android.util.Log;
import de.diamaneos.decode.client.tests.R;
import de.diamaneos.sanitizer.decode.IDecodeService;
import java.io.InputStream;
import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** Long timelines and large canvases remain bounded and retain native public geometry. */
public class GateAnimationLimitsDeviceTest {
    private final List<Drawable> drawables = new ArrayList<>();
    private final List<Movie> movies = new ArrayList<>();

    @Before public void prepare() {
        DecodeGate.setLocal(null);
        DecodeGate.setWaitForTest(30_000L);
        DecodeGate.resetForTest();
    }

    private static Object field(Object target, String name) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(target);
    }

    // The frames of the window the drawable shows (every frame when they fit one VM reply).
    private static Bitmap[] frames(Drawable drawable) throws Exception {
        assertEquals("android.graphics.GatedAnimation", drawable.getClass().getName());
        return (Bitmap[]) field(field(field(drawable, "mWindows"), "mShown"), "frames");
    }

    private static Bitmap frameBitmap(Drawable drawable, int frame) throws Exception {
        java.lang.reflect.Method m = drawable.getClass().getDeclaredMethod("frameBitmap", int.class);
        m.setAccessible(true);
        return (Bitmap) m.invoke(drawable, frame);
    }

    @After public void restore() throws Exception {
        try {
            Set<Bitmap> pixels = Collections.newSetFromMap(new IdentityHashMap<>());
            for (Movie movie : movies) {
                if (movie.getClass().getName().equals("android.graphics.GatedMovie")) {
                    drawables.add((Drawable) field(movie, "mAnimation"));
                }
            }
            for (Drawable drawable : drawables) {
                if (drawable instanceof AnimatedImageDrawable) ((AnimatedImageDrawable) drawable).stop();
                if (drawable.getClass().getName().equals("android.graphics.GatedAnimation")) {
                    pixels.addAll(Arrays.asList(frames(drawable)));
                }
            }
            for (Bitmap bitmap : pixels) DecodeMemory.recycle(bitmap, null);
        } finally {
            movies.clear();
            drawables.clear();
            DecodeGate.setLocal(null);
            DecodeGate.setForcedForTest(null);
            DecodeGate.setWaitForTest(null);
            DecodeGate.resetForTest();
            System.gc();
        }
    }

    private static byte[] fixture(int resource) throws Exception {
        try (InputStream in = ActivityThread.currentApplication().getResources().openRawResource(resource)) {
            return in.readAllBytes();
        }
    }

    private static void worker(Callable<Void> work) throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try { executor.submit(work).get(120, TimeUnit.SECONDS); }
        finally { executor.shutdownNow(); assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS)); }
    }

    private Drawable decode(byte[] bytes, boolean gated) throws Exception {
        DecodeGate.setForcedForTest(gated);
        DecodeGate.resetForTest();
        Drawable result = ImageDecoder.decodeDrawable(ImageDecoder.createSource(ByteBuffer.wrap(bytes)),
                (decoder, info, source) -> decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE));
        assertNotNull(result);
        drawables.add(result);
        return result;
    }

    private void movie(int resource, int expectedFrames, int delay) throws Exception {
        worker(() -> {
            byte[] bytes = fixture(resource);
            DecodeGate.setForcedForTest(false);
            Movie stock = Movie.decodeByteArray(bytes, 0, bytes.length);
            assertNotNull("native Movie fixture refused", stock);
            movies.add(stock);
            assertEquals((int) ((long) expectedFrames * delay), stock.duration());
            DecodeGate.setForcedForTest(true);
            DecodeGate.resetForTest();
            long nativeBefore = Debug.getNativeHeapAllocatedSize();
            Runtime runtime = Runtime.getRuntime();
            long javaBefore = runtime.totalMemory() - runtime.freeMemory();
            Movie gated = Movie.decodeByteArray(bytes, 0, bytes.length);
            assertNotNull(gated);
            movies.add(gated);
            assertEquals(stock.duration(), gated.duration());
            assertEquals(stock.width(), gated.width());
            assertEquals(stock.height(), gated.height());
            Bitmap[] frames = frames((Drawable) field(gated, "mAnimation"));
            assertEquals(expectedFrames, frames.length);
            long nativeDelta = Math.max(0, Debug.getNativeHeapAllocatedSize() - nativeBefore);
            long javaDelta = Math.max(0, runtime.totalMemory() - runtime.freeMemory() - javaBefore);
            long pixels = 0;
            for (Bitmap bitmap : frames) pixels += bitmap.getAllocationByteCount();
            long allowance = (long) frames.length * IDecodeService.FRAME_OBJECT_BYTES;
            Log.i("GateAnimationLimits", "frames=" + frames.length + " nativeDelta=" + nativeDelta
                    + " javaDelta=" + javaDelta + " framePixels=" + pixels + " objectAllowance=" + allowance);
            assertTrue("persistent frame storage exceeded its conservative allowance",
                    nativeDelta + javaDelta <= allowance + 4 * pixels + (2L << 20));
            return null;
        });
    }

    @Test public void gifKeepsEveryFramePast1024() throws Exception {
        movie(R.raw.vm_animation_long_gif, 1025, 40);
    }

    @Test public void movieDurationKeepsNativeUnsignedWrapping() throws Exception {
        movie(R.raw.vm_animation_overflow_gif, 4000, 655350);
    }

    @Test public void webpKeepsEveryFramePast1024() throws Exception {
        worker(() -> {
            byte[] bytes = fixture(R.raw.vm_animation_long_webp);
            assertTrue(decode(bytes, false) instanceof AnimatedImageDrawable);
            Drawable gated = decode(bytes, true);
            assertTrue(gated instanceof AnimatedImageDrawable);
            Bitmap[] frames = frames(gated);
            assertEquals(1025, frames.length);
            assertTrue((frames[frames.length - 1].getPixel(0, 0) >>> 16 & 255) > 200);
            return null;
        });
    }

    private void canvas(int resource, int width, int height) throws Exception {
        worker(() -> {
            byte[] bytes = fixture(resource);
            Drawable stock = decode(bytes, false);
            assertTrue(stock instanceof AnimatedImageDrawable);
            assertEquals(width, stock.getIntrinsicWidth());
            assertEquals(height, stock.getIntrinsicHeight());
            drawables.remove(stock);
            ((AnimatedImageDrawable) stock).stop();
            stock = null;
            System.gc();
            Drawable gated = decode(bytes, true);
            assertTrue(gated instanceof AnimatedImageDrawable);
            assertEquals(width, gated.getIntrinsicWidth());
            assertEquals(height, gated.getIntrinsicHeight());
            // Above 8 MiPix a frame comes at full size in bands, one frame a window, the next asked on demand.
            Bitmap[] frames = frames(gated);
            assertEquals(1, frames.length);
            assertEquals(width, frames[0].getWidth());
            assertEquals(height, frames[0].getHeight());
            Bitmap second = null;
            for (long end = SystemClock.uptimeMillis() + 30_000; second == null && SystemClock.uptimeMillis() < end; ) {
                second = frameBitmap(gated, 1);
                if (second == null) SystemClock.sleep(50);
            }
            assertNotNull("the second frame's window never came", second);
            assertEquals(frames[0].getWidth(), second.getWidth());
            frames = new Bitmap[] {frames[0], second};
            Bitmap target = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888);
            try {
                Canvas canvas = new Canvas(target);
                gated.setBounds(0, 0, 64, 64);
                gated.draw(canvas);
                canvas.setBitmap(null);
                assertTrue((target.getPixel(32, 32) >>> 16 & 255) > 200);
                assertTrue((frames[1].getPixel(0, 0) >>> 8 & 255) > 200);
            } finally { target.recycle(); }
            return null;
        });
    }

    @Test public void largerGifCanvasPlaysAtFullSizeInBands() throws Exception {
        canvas(R.raw.vm_animation_canvas_gif, 4096, 2304);
    }

    @Test public void largerWebpCanvasPlaysAtFullSizeInBands() throws Exception {
        canvas(R.raw.vm_animation_canvas_webp, 4096, 3328);
    }

    private static DecodeMemory.Reservation objectCharge(Bitmap[] frames) throws Exception {
        Field lock = DecodeMemory.class.getDeclaredField("sLock");
        Field pixels = DecodeMemory.class.getDeclaredField("sPixels");
        lock.setAccessible(true);
        pixels.setAccessible(true);
        synchronized (lock.get(null)) {
            for (Object held : (Iterable<?>) pixels.get(null)) {
                WeakReference<?> identity = (WeakReference<?>) field(held, "identity");
                if (identity.get() == frames) return (DecodeMemory.Reservation) field(held, "memory");
            }
        }
        return null;
    }

    private static final class SharedFrames {
        final Drawable target;
        final WeakReference<Drawable> original;
        final DecodeMemory.Reservation charge;
        final long expected;
        SharedFrames(Drawable target, Drawable original, DecodeMemory.Reservation charge, long expected) {
            this.target = target;
            this.original = new WeakReference<>(original);
            this.charge = charge;
            this.expected = expected;
        }
    }

    private SharedFrames replaceXmlSource(byte[] bytes) throws Exception {
        Drawable original = decode(bytes, true);
        Drawable target = decode(bytes, true);
        Bitmap[] shared = frames(original);
        DecodeMemory.Reservation charge = objectCharge(shared);
        assertNotNull("frame-object metadata was never charged", charge);
        long expected = (long) shared.length * IDecodeService.FRAME_OBJECT_BYTES;
        assertEquals(expected, charge.bytes());
        Bitmap[] replaced = frames(target);
        java.lang.reflect.Method replace = target.getClass().getDeclaredMethod("replaceFrames", target.getClass());
        replace.setAccessible(true);
        replace.invoke(target, original);
        for (Bitmap bitmap : replaced) bitmap.recycle();
        assertSame(shared, frames(target));
        drawables.remove(original);
        return new SharedFrames(target, original, charge, expected);
    }

    @Test public void xmlFrameReplacementRetainsTheArraysObjectCharge() throws Exception {
        worker(() -> {
            SharedFrames shared = replaceXmlSource(fixture(R.raw.vm_animation_long_gif));
            long end = SystemClock.uptimeMillis() + 2_000;
            do { System.gc(); SystemClock.sleep(20); }
            while (shared.original.get() != null && SystemClock.uptimeMillis() < end);
            assertNull("original XML source was retained", shared.original.get());
            assertEquals("live XML target lost its metadata charge", shared.expected, shared.charge.bytes());
            Reference.reachabilityFence(shared.target);
            return null;
        });
    }
}
