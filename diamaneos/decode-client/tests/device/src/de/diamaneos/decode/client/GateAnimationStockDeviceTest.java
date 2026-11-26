/* Copyright (C) 2026 DiamaneOS */
package de.diamaneos.decode.client;

import static org.junit.Assert.*;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DecodeGate;
import android.graphics.ImageDecoder;
import android.graphics.drawable.Animatable2;
import android.graphics.drawable.AnimatedImageDrawable;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import androidx.test.platform.app.InstrumentationRegistry;
import de.diamaneos.decode.client.tests.R;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Animated drawables as stock draws them, gate off and on: the file's EXIF orientation, the image's own bounds
 * before any setBounds, and the end as the last frame of the last repetition is shown (CTS AnimatedImageDrawableTest
 * testExif, testPostProcess and testLifeCycleSoftware).
 */
public class GateAnimationStockDeviceTest {
    @Before public void prepare() {
        DecodeGate.setLocal(null);
        DecodeGate.setWaitForTest(30_000L);
        DecodeGate.resetForTest();
    }

    @After public void restore() {
        DecodeGate.setForcedForTest(null);
        DecodeGate.setWaitForTest(null);
        DecodeGate.resetForTest();
    }

    private static byte[] fixture(int resource) throws Exception {
        try (InputStream in = InstrumentationRegistry.getInstrumentation().getTargetContext().getResources()
                .openRawResource(resource)) {
            return in.readAllBytes();
        }
    }

    private static AnimatedImageDrawable decode(byte[] bytes, boolean gated) throws Exception {
        DecodeGate.setForcedForTest(gated);
        DecodeGate.resetForTest();
        final Drawable d = ImageDecoder.decodeDrawable(ImageDecoder.createSource(ByteBuffer.wrap(bytes)));
        assertTrue(d instanceof AnimatedImageDrawable);
        return (AnimatedImageDrawable) d;
    }

    // The animated WebP fixture with an EXIF chunk asking for a quarter turn clockwise (orientation 6).
    private static byte[] turned(byte[] webp) throws Exception {
        final ByteBuffer tiff = ByteBuffer.allocate(26).order(ByteOrder.LITTLE_ENDIAN);
        tiff.put(new byte[] {'I', 'I', 42, 0}).putInt(8).putShort((short) 1)
                .putShort((short) 0x0112).putShort((short) 3).putInt(1).putShort((short) 6).putShort((short) 0)
                .putInt(0);
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(webp);
        out.write(new byte[] {'E', 'X', 'I', 'F', 26, 0, 0, 0});
        out.write(tiff.array());
        final byte[] bytes = out.toByteArray();
        assertEquals("VP8X", new String(bytes, 12, 4, java.nio.charset.StandardCharsets.US_ASCII));
        bytes[20] |= 0x08; // the VP8X EXIF flag
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(4, bytes.length - 8);
        return bytes;
    }

    private static Bitmap firstFrame(AnimatedImageDrawable d, boolean setBounds) {
        final Bitmap out = Bitmap.createBitmap(d.getIntrinsicWidth(), d.getIntrinsicHeight(),
                Bitmap.Config.ARGB_8888);
        if (setBounds) d.setBounds(0, 0, out.getWidth(), out.getHeight());
        d.draw(new Canvas(out));
        return out;
    }

    private static void assertSamePixels(Bitmap stock, Bitmap gated) {
        assertEquals(stock.getWidth(), gated.getWidth());
        assertEquals(stock.getHeight(), gated.getHeight());
        int drawn = 0;
        for (int y = 0; y < stock.getHeight(); y++) {
            for (int x = 0; x < stock.getWidth(); x++) {
                final int a = stock.getPixel(x, y), b = gated.getPixel(x, y);
                if (Color.alpha(a) != 0) drawn++;
                assertTrue("pixel " + x + "," + y + " stock " + Integer.toHexString(a) + " gated "
                        + Integer.toHexString(b),
                        Math.abs(Color.alpha(a) - Color.alpha(b)) <= 8 && Math.abs(Color.red(a) - Color.red(b)) <= 8
                                && Math.abs(Color.green(a) - Color.green(b)) <= 8
                                && Math.abs(Color.blue(a) - Color.blue(b)) <= 8);
            }
        }
        assertTrue("stock drew nothing", drawn > 0);
    }

    @Test public void exifTurnedWebpIsShownUpright() throws Exception {
        final byte[] bytes = turned(fixture(R.raw.vm_animation_compat_webp));
        final AnimatedImageDrawable stock = decode(bytes, false);
        final AnimatedImageDrawable gated = decode(bytes, true);
        assertEquals("android.graphics.GatedAnimation", gated.getClass().getName());
        assertTrue("the quarter turn makes the tall fixture wide",
                stock.getIntrinsicWidth() > stock.getIntrinsicHeight());
        assertEquals(stock.getIntrinsicWidth(), gated.getIntrinsicWidth());
        assertEquals(stock.getIntrinsicHeight(), gated.getIntrinsicHeight());
        assertSamePixels(firstFrame(stock, true), firstFrame(gated, true));
    }

    @Test public void drawWithoutBoundsUsesTheImageBounds() throws Exception {
        final byte[] bytes = fixture(R.raw.vm_animation_compat_webp);
        final AnimatedImageDrawable stock = decode(bytes, false);
        final AnimatedImageDrawable gated = decode(bytes, true);
        assertEquals("android.graphics.GatedAnimation", gated.getClass().getName());
        assertTrue(stock.getBounds().isEmpty());
        assertTrue(gated.getBounds().isEmpty());
        assertSamePixels(firstFrame(stock, false), firstFrame(gated, false));
    }

    // CTS testLifeCycleSoftware: one draw a frame duration apart, repeat count 0 set after start.
    @Test public void softwareDrawsEndOnTheLastFrame() throws Exception {
        final byte[] bytes = fixture(R.raw.vm_animation_compat_webp);
        for (boolean gated : new boolean[] {false, true}) {
            final AnimatedImageDrawable d = decode(bytes, gated);
            final int frames = 3;
            final AtomicBoolean ended = new AtomicBoolean();
            final Bitmap target = Bitmap.createBitmap(Math.max(1, d.getIntrinsicWidth()),
                    Math.max(1, d.getIntrinsicHeight()), Bitmap.Config.ARGB_8888);
            final Canvas canvas = new Canvas(target);
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                d.registerAnimationCallback(new Animatable2.AnimationCallback() {
                    @Override public void onAnimationEnd(Drawable drawable) { ended.set(true); }
                });
                d.start();
                d.draw(canvas);
                d.setRepeatCount(0);
            });
            for (int i = 1; i < frames; i++) {
                SystemClock.sleep(1000);
                InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                    assertTrue("gate " + gated + ": still running before its last frame", d.isRunning());
                    d.draw(canvas);
                });
            }
            SystemClock.sleep(1000);
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            assertFalse("gate " + gated + ": running after its last frame was drawn", d.isRunning());
            assertTrue("gate " + gated + ": no end callback", ended.get());
        }
    }
}
