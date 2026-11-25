/* Copyright (C) 2026 DiamaneOS */
package de.diamaneos.decode.client;

import static org.junit.Assert.*;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorSpace;
import android.graphics.DecodeGate;
import android.graphics.ImageDecoder;
import android.graphics.drawable.Animatable2;
import android.graphics.drawable.AnimatedImageDrawable;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import de.diamaneos.decode.client.tests.R;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class GateAnimationDeviceTest {
    private AnimatedImageDrawable drawable;
    private Bitmap canvasBitmap;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Runnable drawFrames;
    private Drawable.Callback scheduler;

    @Before public void prepare() {
        DecodeGate.setForcedForTest(true);
        DecodeGate.setWaitForTest(30_000L);
        DecodeGate.resetForTest();
    }

    @After public void cleanup() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            if (drawFrames != null) main.removeCallbacks(drawFrames);
            if (drawable != null) {
                drawable.stop();
                drawable.clearAnimationCallbacks();
                drawable.setCallback(null);
            }
            scheduler = null;
            if (canvasBitmap != null) canvasBitmap.recycle();
        });
        drawable = null;
        canvasBitmap = null;
        DecodeGate.setForcedForTest(null);
        DecodeGate.setWaitForTest(null);
        DecodeGate.resetForTest();
    }

    @Test public void webpKeepsTheStockTypeAndFinitePlayback() throws Exception {
        checkPlayback(R.raw.vm_animation_compat_webp);
    }

    @Test public void gifKeepsTheStockTypeAndFinitePlayback() throws Exception {
        checkPlayback(R.raw.vm_animation_compat_gif);
    }

    @Test public void visibilityChangesKeepTheStockRunningState() throws Exception {
        final byte[] bytes = fixture(R.raw.vm_animation_compat_gif);
        final boolean stockRunning;
        DecodeGate.setForcedForTest(false);
        final AnimatedImageDrawable stock = (AnimatedImageDrawable) ImageDecoder.decodeDrawable(
                ImageDecoder.createSource(ByteBuffer.wrap(bytes)));
        try {
            stock.start();
            stock.setVisible(false, true);
            stockRunning = stock.isRunning();
        } finally {
            stock.stop();
            DecodeGate.setForcedForTest(true);
        }
        drawable = (AnimatedImageDrawable) ImageDecoder.decodeDrawable(
                ImageDecoder.createSource(ByteBuffer.wrap(bytes)));
        drawable.start();
        drawable.setVisible(false, true);
        assertEquals("visibility changed the playback state differently from stock", stockRunning,
                drawable.isRunning());
        drawable.setVisible(true, true);
        assertEquals(stockRunning, drawable.isRunning());
    }

    @Test public void staticDecodeRejectsInvalidTargetColorSpaces() throws Exception {
        final Bitmap pixel = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
        final ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        try {
            assertTrue(pixel.compress(Bitmap.CompressFormat.PNG, 100, encoded));
        } finally {
            pixel.recycle();
        }
        checkInvalidColorSpaces(encoded.toByteArray(), false);
    }

    @Test public void animationRejectsInvalidTargetColorSpaces() throws Exception {
        checkInvalidColorSpaces(fixture(R.raw.vm_animation_compat_gif), true);
    }

    @Test public void streamExceptionRetainsItsOriginalCause() throws Exception {
        final java.io.IOException failure = new java.io.IOException("fixture input failed");
        final InputStream input = new InputStream() {
            @Override public int read() throws java.io.IOException { throw failure; }
            @Override public int read(byte[] bytes, int offset, int count) throws java.io.IOException {
                throw failure;
            }
        };
        final ImageDecoder.Source source = ImageDecoder.createSource(
                InstrumentationRegistry.getInstrumentation().getTargetContext().getResources(),
                input, Bitmap.DENSITY_NONE);
        try {
            ImageDecoder.decodeBitmap(source);
            fail("stream exception was swallowed");
        } catch (ImageDecoder.DecodeException expected) {
            assertEquals(ImageDecoder.DecodeException.SOURCE_EXCEPTION, expected.getError());
            assertSame(source, expected.getSource());
            assertSame(failure, expected.getCause());
        }
    }

    // As stock ImageDecoder (ColorSpace.getNativeInstance), a non-RGB or nonparametric target is an
    // IllegalArgumentException from the decode call.
    private void checkInvalidColorSpaces(byte[] encoded, boolean animated) throws Exception {
        final ColorSpace noParameters = new ColorSpace.Rgb("NoTransferParams",
                new float[] {0.640f, 0.330f, 0.300f, 0.600f, 0.150f, 0.060f},
                ColorSpace.ILLUMINANT_D50, value -> Math.pow(value, 1.0 / 2.2),
                value -> Math.pow(value, 2.2), 0, 1);
        for (ColorSpace color : new ColorSpace[] {ColorSpace.get(ColorSpace.Named.CIE_LAB),
                ColorSpace.get(ColorSpace.Named.CIE_XYZ), noParameters}) {
            final ImageDecoder.Source source = ImageDecoder.createSource(ByteBuffer.wrap(encoded));
            final int before = gateAnswers();
            final ImageDecoder.OnHeaderDecodedListener settings = (decoder, info, value) -> {
                assertSame(source, value);
                assertEquals(animated, info.isAnimated());
                decoder.setTargetColorSpace(color);
            };
            try {
                if (animated) ImageDecoder.decodeDrawable(source, settings);
                else ImageDecoder.decodeBitmap(source, settings);
                fail("invalid target color space accepted: " + color);
            } catch (IllegalArgumentException expected) {
                assertTrue("the test exercised the gate", gateAnswers() > before);
            }
        }
    }

    // Header probes the VM answered, from the VM or its cache.
    private static int gateAnswers() {
        final int[] counts = DecodeGate.countsForTest();
        return counts[1] + counts[4];
    }

    private byte[] fixture(int resource) throws Exception {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (InputStream input = InstrumentationRegistry.getInstrumentation().getTargetContext()
                .getResources().openRawResource(resource)) {
            final byte[] block = new byte[4096];
            for (int count; (count = input.read(block)) != -1;) bytes.write(block, 0, count);
        }
        return bytes.toByteArray();
    }

    private void checkPlayback(int resource) throws Exception {
        final byte[] bytes = fixture(resource);
        // ByteBuffer prevents the fixture's own APK asset exemption from bypassing the VM.
        final int before = DecodeGate.countsForTest()[0];
        final Drawable decoded = ImageDecoder.decodeDrawable(
                ImageDecoder.createSource(ByteBuffer.wrap(bytes)));
        assertTrue("VM animation has the stock public drawable type", decoded instanceof AnimatedImageDrawable);
        assertEquals("the test exercised VM frames", "android.graphics.GatedAnimation", decoded.getClass().getName());
        drawable = (AnimatedImageDrawable) decoded;
        final java.lang.reflect.Field stateField = AnimatedImageDrawable.class.getDeclaredField("mState");
        stateField.setAccessible(true);
        final Object state = stateField.get(drawable);
        final java.lang.reflect.Field pointer = state.getClass().getDeclaredField("mNativePtr");
        pointer.setAccessible(true);
        assertEquals("gated animation retained a native decoder", 0L, pointer.getLong(state));
        final CountDownLatch start = new CountDownLatch(1);
        final CountDownLatch end = new CountDownLatch(1);
        final AtomicInteger starts = new AtomicInteger();
        final AtomicInteger ends = new AtomicInteger();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final Set<Integer> frames = new HashSet<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            canvasBitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888);
            final Canvas canvas = new Canvas(canvasBitmap);
            drawable.setBounds(0, 0, 64, 64);
            drawable.setRepeatCount(0);
            assertEquals(0, drawable.getRepeatCount());
            scheduler = new Drawable.Callback() {
                @Override public void invalidateDrawable(Drawable value) {}
                @Override public void scheduleDrawable(Drawable value, Runnable task, long when) {
                    main.postAtTime(task, when);
                }
                @Override public void unscheduleDrawable(Drawable value, Runnable task) {
                    main.removeCallbacks(task);
                }
            };
            drawable.setCallback(scheduler);
            drawable.registerAnimationCallback(new Animatable2.AnimationCallback() {
                @Override public void onAnimationStart(Drawable value) {
                    if (value != drawable) failure.set(new AssertionError("start callback identity"));
                    starts.incrementAndGet();
                    start.countDown();
                }
                @Override public void onAnimationEnd(Drawable value) {
                    if (value != drawable) failure.set(new AssertionError("end callback identity"));
                    ends.incrementAndGet();
                    end.countDown();
                }
            });
            drawFrames = new Runnable() {
                @Override public void run() {
                    try {
                        canvasBitmap.eraseColor(0);
                        drawable.draw(canvas);
                        final int[] pixels = new int[64 * 64];
                        canvasBitmap.getPixels(pixels, 0, 64, 0, 0, 64, 64);
                        frames.add(java.util.Arrays.hashCode(pixels));
                        if (drawable.isRunning()) main.postDelayed(this, 10);
                    } catch (Throwable error) {
                        failure.set(error);
                        end.countDown();
                    }
                }
            };
            drawable.start();
            assertTrue(drawable.isRunning());
            drawFrames.run();
        });
        assertTrue("start callback missing", start.await(5, TimeUnit.SECONDS));
        assertTrue("finite animation never ended", end.await(30, TimeUnit.SECONDS));
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            assertFalse(drawable.isRunning());
            assertEquals(1, starts.get());
            assertEquals(1, ends.get());
            assertTrue("frames never advanced", frames.size() > 1);
        });
        if (failure.get() != null) throw new AssertionError("animation playback", failure.get());
        assertTrue("no actual VM decode was observed", DecodeGate.countsForTest()[0] > before);
    }
}
