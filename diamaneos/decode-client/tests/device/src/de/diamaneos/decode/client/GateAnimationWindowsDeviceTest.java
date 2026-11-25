/* Copyright (C) 2026 DiamaneOS */
package de.diamaneos.decode.client;

import static org.junit.Assert.*;

import android.app.ActivityThread;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DecodeGate;
import android.graphics.ImageDecoder;
import android.graphics.drawable.Animatable2;
import android.graphics.drawable.AnimatedImageDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IRemoteCallback;
import android.os.SystemClock;
import de.diamaneos.decode.client.tests.R;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Animations whose frames do not fit one VM reply at their size play as stock shows them: every frame at the
 * drawable's size, the whole timeline, windows of frames asked as playback reaches them, off the drawing thread. A
 * window the VM refuses keeps the last frame shown.
 */
public class GateAnimationWindowsDeviceTest {
    // 120 frames of 480 x 360, frame i the colour (2i, 255 - 2i, 64), 20 ms each: 83 MB of frames at full size.
    private static final int FRAMES = 120;

    @Before public void prepare() {
        DecodeGate.setLocal(null);
        DecodeGate.setWaitForTest(30_000L);
        DecodeGate.resetForTest();
    }

    @After public void restore() {
        DecodeGate.setLocal(null);
        DecodeGate.setForcedForTest(null);
        DecodeGate.setWaitForTest(null);
        DecodeGate.resetForTest();
    }

    private static byte[] fixture(int resource) throws Exception {
        try (InputStream in = ActivityThread.currentApplication().getResources().openRawResource(resource)) {
            return in.readAllBytes();
        }
    }

    private static Drawable decode(byte[] bytes, boolean gated) throws Exception {
        DecodeGate.setForcedForTest(gated);
        DecodeGate.resetForTest();
        return ImageDecoder.decodeDrawable(ImageDecoder.createSource(ByteBuffer.wrap(bytes)));
    }

    private static Object field(Object target, String name) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(target);
    }

    private static Object call(Object target, String name, Class<?>[] types, Object... args) throws Exception {
        Method m = target.getClass().getDeclaredMethod(name, types);
        m.setAccessible(true);
        return m.invoke(target, args);
    }

    // Frame i once its window is here, as playback asks for it (showing moves the held windows on).
    private static Bitmap frame(Drawable drawable, int i) throws Exception {
        for (long end = SystemClock.uptimeMillis() + 30_000; SystemClock.uptimeMillis() < end; ) {
            Bitmap b = (Bitmap) call(drawable, "frameBitmap", new Class<?>[] {int.class}, i);
            if (b != null) {
                call(field(drawable, "mWindows"), "showing", new Class<?>[] {int.class}, i);
                return b;
            }
            SystemClock.sleep(20);
        }
        throw new AssertionError("frame " + i + " never came");
    }

    @Test public void longGifHasEveryFrameAtFullSize() throws Exception {
        final byte[] bytes = fixture(R.raw.vm_animation_windows_gif);
        final Drawable stock = decode(bytes, false);
        final Drawable gated = decode(bytes, true);
        assertEquals("android.graphics.GatedAnimation", gated.getClass().getName());
        assertTrue(gated instanceof AnimatedImageDrawable);
        assertEquals(stock.getIntrinsicWidth(), gated.getIntrinsicWidth());
        assertEquals(stock.getIntrinsicHeight(), gated.getIntrinsicHeight());
        assertEquals(FRAMES, (int) (Integer) call(gated, "frameCount", new Class<?>[0]));
        final Bitmap[] first = (Bitmap[]) field(field(field(gated, "mWindows"), "mShown"), "frames");
        assertTrue("the first window holds part of the animation", first.length < FRAMES);
        for (int i = 0; i < FRAMES; i++) {
            final Bitmap b = frame(gated, i);
            assertEquals("frame " + i + " width", 480, b.getWidth());
            assertEquals("frame " + i + " height", 360, b.getHeight());
            final int c = b.getPixel(240, 180);
            assertTrue("frame " + i + " is " + Integer.toHexString(c),
                    Math.abs(Color.red(c) - 2 * i) <= 6 && Math.abs(Color.green(c) - (255 - 2 * i)) <= 6);
            assertEquals("frame " + i + " time", 20, (int) (Integer) call(gated, "frameDuration",
                    new Class<?>[] {int.class}, i));
        }
        assertTrue(stock instanceof AnimatedImageDrawable);
    }

    // Plays on its own looper as a view would: the end callback after the whole timeline, gated as stock.
    private static long playOnce(Drawable drawable) throws Exception {
        final HandlerThread thread = new HandlerThread("animation-player");
        thread.start();
        try {
            final Handler handler = new Handler(thread.getLooper());
            final Bitmap target = Bitmap.createBitmap(48, 36, Bitmap.Config.ARGB_8888);
            final Canvas canvas = new Canvas(target);
            final CountDownLatch ended = new CountDownLatch(1);
            final AtomicInteger draws = new AtomicInteger();
            final long[] started = new long[1];
            handler.post(() -> {
                drawable.setBounds(0, 0, 48, 36);
                drawable.setCallback(new Drawable.Callback() {
                    @Override public void invalidateDrawable(Drawable who) {
                        handler.post(() -> { draws.incrementAndGet(); who.draw(canvas); });
                    }
                    @Override public void scheduleDrawable(Drawable who, Runnable what, long when) {
                        handler.postAtTime(what, who, when);
                    }
                    @Override public void unscheduleDrawable(Drawable who, Runnable what) {
                        handler.removeCallbacks(what, who);
                    }
                });
                final AnimatedImageDrawable animated = (AnimatedImageDrawable) drawable;
                animated.registerAnimationCallback(new Animatable2.AnimationCallback() {
                    @Override public void onAnimationEnd(Drawable d) { ended.countDown(); }
                });
                animated.setRepeatCount(0);
                started[0] = SystemClock.uptimeMillis();
                animated.start();
                drawable.draw(canvas);
            });
            assertTrue("the animation never ended", ended.await(90, TimeUnit.SECONDS));
            return SystemClock.uptimeMillis() - started[0];
        } finally {
            thread.quitSafely();
        }
    }

    @Test public void longGifPlaysItsWholeTimelineAndEnds() throws Exception {
        final byte[] bytes = fixture(R.raw.vm_animation_windows_gif);
        final long stock = playOnce(decode(bytes, false));
        final long gated = playOnce(decode(bytes, true));
        // 120 frames of 20 ms: neither may end before its whole timeline.
        assertTrue("stock ended after " + stock + " ms", stock >= FRAMES * 20 - 40);
        assertTrue("gated ended after " + gated + " ms", gated >= FRAMES * 20 - 40);
    }

    // 9000 frames, past the every-frame limit: the late frames come in their windows, each naming its index.
    @Test public void timelinePastEveryFrameLimitPlaysToItsEnd() throws Exception {
        final byte[] bytes = fixture(R.raw.vm_animation_timeline_gif);
        final Drawable stock = decode(bytes, false);
        assertTrue(stock instanceof AnimatedImageDrawable);
        final Drawable gated = decode(bytes, true);
        assertEquals("android.graphics.GatedAnimation", gated.getClass().getName());
        assertEquals(9000, (int) (Integer) call(gated, "frameCount", new Class<?>[0]));
        for (int i : new int[] {0, 4000, 8192, 8999}) {
            final int c = frame(gated, i).getPixel(1, 1);
            assertTrue("frame " + i + " is " + Integer.toHexString(c), Math.abs(Color.red(c) - i % 256) <= 2);
            assertEquals(20, (int) (Integer) call(gated, "frameDuration", new Class<?>[] {int.class}, i));
        }
    }

    // Frames held live: the shown window's frames before the one shown are let go.
    @Test public void shownFramesAreLetGo() throws Exception {
        final Drawable gated = decode(fixture(R.raw.vm_animation_windows_gif), true);
        frame(gated, 5);
        final Bitmap[] window = (Bitmap[]) field(field(field(gated, "mWindows"), "mShown"), "frames");
        for (int i = 0; i < 5; i++) assertNull("frame " + i + " still held", window[i]);
        assertNotNull(window[5]);
        assertTrue("a window holds at most 8 MiB of frames", (long) window.length * 480 * 360 * 4 <= 8L << 20);
    }

    @Test public void refusedWindowKeepsTheLastFrame() throws Exception {
        // A fake VM: 4 frames of 1500 x 1500 (one a window, each above the window budget), the second window claims
        // another frame count.
        final int side = 1500;
        final AtomicInteger windows = new AtomicInteger();
        final byte[] bytes = fixture(R.raw.vm_animation_windows_gif);
        DecodeGate.setForcedForTest(true);
        DecodeGate.resetForTest();
        DecodeGate.setLocal((method, extras) -> {
            Bundle reply = new Bundle();
            if (DecodeGate.METHOD_PROBE.equals(method) || DecodeGate.METHOD_HEADER.equals(method)) {
                reply.putInt(DecodeGate.EXTRA_WIDTH, side);
                reply.putInt(DecodeGate.EXTRA_HEIGHT, side);
                reply.putInt(DecodeGate.EXTRA_FORMAT, 3);
                reply.putBoolean(DecodeGate.EXTRA_ANIMATED, true);
            } else if (DecodeGate.METHOD_FRAME_WINDOW.equals(method)) {
                windows.incrementAndGet();
                final int first = extras.getInt(DecodeGate.EXTRA_LEFT);
                final Bitmap atlas = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888);
                atlas.eraseColor(first == 0 ? Color.RED : Color.BLUE);
                reply.putParcelable(DecodeGate.EXTRA_BITMAP, atlas);
                reply.putInt(DecodeGate.EXTRA_FRAME_COUNT, 1);
                reply.putIntArray(DecodeGate.EXTRA_DURATIONS, new int[] {20});
                reply.putInt(DecodeGate.EXTRA_LOOPS, 0);
                reply.putInt(DecodeGate.EXTRA_FIRST, first);
                reply.putInt(DecodeGate.EXTRA_TOTAL, first == 0 ? 4 : 5);
            } else {
                reply.putString(DecodeGate.EXTRA_ERROR, "not asked in this test");
            }
            try {
                IRemoteCallback.Stub.asInterface(extras.getBinder(DecodeGate.EXTRA_CALLBACK)).sendResult(reply);
            } catch (android.os.RemoteException e) { throw new AssertionError(e); }
            Bundle taken = new Bundle();
            taken.putBoolean(DecodeGate.EXTRA_TAKEN, true);
            return taken;
        });
        final Drawable gated = ImageDecoder.decodeDrawable(ImageDecoder.createSource(ByteBuffer.wrap(bytes)));
        assertEquals("android.graphics.GatedAnimation", gated.getClass().getName());
        final Object source = field(gated, "mWindows");
        assertNull("frame 1 is not here before its window", call(gated, "frameBitmap", new Class<?>[] {int.class}, 1));
        for (long end = SystemClock.uptimeMillis() + 10_000;
                !(Boolean) call(source, "ended", new Class<?>[0]) && SystemClock.uptimeMillis() < end; ) {
            SystemClock.sleep(20);
        }
        assertTrue("a window that differs from the animation ends the fetching",
                (Boolean) call(source, "ended", new Class<?>[0]));
        assertNull("no frame from the differing window", call(gated, "frameBitmap", new Class<?>[] {int.class}, 1));
        assertEquals("the first window and one later window were asked", 2, windows.get());
        // Playback holds the last good frame: it shows frame 0 and never a frame of the refused window.
        frame(gated, 0);
        final Bitmap target = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888);
        gated.setBounds(0, 0, 10, 10);
        ((AnimatedImageDrawable) gated).start();
        for (int i = 0; i < 5; i++) {
            gated.draw(new Canvas(target));
            SystemClock.sleep(30);
        }
        assertEquals(Color.RED, target.getPixel(5, 5));
        ((AnimatedImageDrawable) gated).stop();
        target.recycle();
    }
}
