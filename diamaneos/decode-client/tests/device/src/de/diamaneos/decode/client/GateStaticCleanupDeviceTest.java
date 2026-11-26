package de.diamaneos.decode.client;

import static org.junit.Assert.*;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.DecodeGate;
import android.graphics.DecodeMemory;
import android.graphics.ImageDecoder;
import android.graphics.PixelFormat;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.io.ByteArrayOutputStream;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class GateStaticCleanupDeviceTest {
    @Before public void prepare() {
        DecodeGate.setForcedForTest(true);
        DecodeGate.setWaitForTest(30_000L);
        DecodeGate.resetForTest();
    }

    @After public void cleanup() {
        DecodeGate.setForcedForTest(null);
        DecodeGate.setWaitForTest(null);
        DecodeGate.resetForTest();
    }

    private static Object field(Class<?> type, Object target, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static Set<Bitmap> charged() throws Exception {
        Set<Bitmap> result = Collections.newSetFromMap(new IdentityHashMap<>());
        Object lock = field(DecodeMemory.class, null, "sLock");
        synchronized (lock) {
            for (Object value : (Iterable<?>) field(DecodeMemory.class, null, "sPixels")) {
                Object bitmap = ((WeakReference<?>) field(value.getClass(), value, "identity")).get();
                DecodeMemory.Reservation memory = (DecodeMemory.Reservation) field(value.getClass(), value, "memory");
                if (bitmap instanceof Bitmap && memory.bytes() > 0) result.add((Bitmap) bitmap);
            }
        }
        return result;
    }

    private static byte[] png() {
        Bitmap bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888);
        try {
            bitmap.eraseColor(Color.argb(127, 40, 100, 220));
            ByteArrayOutputStream encoded = new ByteArrayOutputStream();
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, encoded));
            return encoded.toByteArray();
        } finally { bitmap.recycle(); }
    }

    private static void noNewPixels(Set<Bitmap> before) throws Exception {
        DecodeGate.resetForTest();
        Set<Bitmap> remaining = charged();
        remaining.removeAll(before);
        assertTrue("unpublished bitmaps remain charged: " + remaining.size(), remaining.isEmpty());
    }

    @Test public void scaledUnpremultipliedRejectionReleasesWorkingPixels() throws Exception {
        Set<Bitmap> before = charged();
        try {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(png())), (decoder, info, source) -> {
                decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                decoder.setTargetSize(13, 13);
                decoder.setUnpremultipliedRequired(true);
            });
            fail("scaled unpremultiplied image was accepted");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("unpremultiplied"));
        }
        noNewPixels(before);
    }

    @Test public void lateHardwareRefusalReleasesPostprocessedPixels() throws Exception {
        Set<Bitmap> before = charged();
        DecodeMemory.Reservation[] occupied = new DecodeMemory.Reservation[1];
        try {
            try {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(png())), (decoder, info, source) -> {
                    decoder.setAllocator(ImageDecoder.ALLOCATOR_HARDWARE);
                    decoder.setPostProcessor(canvas -> {
                        try { occupied[0] = DecodeMemory.reserve(DecodeMemory.MAX_BYTES - DecodeMemory.held()); }
                        catch (DecodeMemory.NoRoomException error) { throw new AssertionError(error); }
                        return PixelFormat.TRANSLUCENT;
                    });
                });
                fail("hardware allocation succeeded with all capacity reserved");
            } catch (ImageDecoder.DecodeException expected) {
                assertNotNull("late refusal did not reach the postprocessor", occupied[0]);
            }
        } finally {
            if (occupied[0] != null) occupied[0].close();
        }
        noNewPixels(before);
    }

    @Test public void returnedPixelsStayChargedUntilExplicitRecycle() throws Exception {
        Bitmap result = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(png())),
                (decoder, info, source) -> decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE));
        try {
            DecodeGate.resetForTest();
            assertFalse(result.isRecycled());
            assertTrue(charged().contains(result));
            assertEquals(64, result.getWidth());
        } finally { result.recycle(); }
        assertFalse(charged().contains(result));
    }
}
