/* Copyright (C) 2026 DiamaneOS */
package de.diamaneos.decode.client;

import static org.junit.Assert.*;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.DecodeGate;
import android.os.ParcelFileDescriptor;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** A complete PNG in a live pipe reaches the VM without waiting for EOF. */
public class GatePngPipeDeviceTest {
    private static final byte[] PNG = {(byte) 137, (byte) 80, (byte) 78, (byte) 71, (byte) 13, (byte) 10, (byte) 26, (byte) 10, (byte) 0, (byte) 0, (byte) 0, (byte) 13, (byte) 73, (byte) 72, (byte) 68, (byte) 82, (byte) 0, (byte) 0, (byte) 0, (byte) 1, (byte) 0, (byte) 0, (byte) 0, (byte) 1, (byte) 8, (byte) 6, (byte) 0, (byte) 0, (byte) 0, (byte) 31, (byte) 21, (byte) 196, (byte) 137, (byte) 0, (byte) 0, (byte) 0, (byte) 13, (byte) 73, (byte) 68, (byte) 65, (byte) 84, (byte) 120, (byte) 156, (byte) 99, (byte) 16, (byte) 84, (byte) 50, (byte) 254, (byte) 15, (byte) 0, (byte) 2, (byte) 20, (byte) 1, (byte) 102, (byte) 4, (byte) 224, (byte) 222, (byte) 127, (byte) 0, (byte) 0, (byte) 0, (byte) 0, (byte) 73, (byte) 69, (byte) 78, (byte) 68, (byte) 174, (byte) 66, (byte) 96, (byte) 130};
    private static final byte[] BROKEN = {(byte) 137, (byte) 80, (byte) 78, (byte) 71, (byte) 13, (byte) 10, (byte) 26, (byte) 10, (byte) 0, (byte) 0, (byte) 0, (byte) 13, (byte) 73, (byte) 72, (byte) 68, (byte) 82, (byte) 0, (byte) 0, (byte) 0, (byte) 1, (byte) 0, (byte) 0, (byte) 0, (byte) 1, (byte) 8, (byte) 6, (byte) 0, (byte) 0, (byte) 0, (byte) 31, (byte) 21, (byte) 196, (byte) 137, (byte) 0, (byte) 0, (byte) 0, (byte) 24, (byte) 73, (byte) 68, (byte) 65, (byte) 84, (byte) 98, (byte) 114, (byte) 111, (byte) 107, (byte) 101, (byte) 110, (byte) 32, (byte) 99, (byte) 111, (byte) 109, (byte) 112, (byte) 114, (byte) 101, (byte) 115, (byte) 115, (byte) 101, (byte) 100, (byte) 32, (byte) 112, (byte) 105, (byte) 120, (byte) 101, (byte) 108, (byte) 115, (byte) 215, (byte) 173, (byte) 112, (byte) 4, (byte) 0, (byte) 0, (byte) 0, (byte) 0, (byte) 73, (byte) 69, (byte) 78, (byte) 68, (byte) 174, (byte) 66, (byte) 96, (byte) 130};

    @Before public void prepare() {
        DecodeGate.setLocal(null);
        DecodeGate.setForcedForTest(true);
        DecodeGate.setWaitForTest(10000L);
        DecodeGate.resetForTest();
    }

    @After public void restore() {
        DecodeGate.setLocal(null);
        DecodeGate.setForcedForTest(null);
        DecodeGate.setWaitForTest(null);
        DecodeGate.resetForTest();
    }

    @Test public void pipeCompletesWhileWriterRemainsOpen() throws Exception {
        worker(() -> {
            final Bitmap warm = BitmapFactory.decodeByteArray(PNG, 0, PNG.length);
            assertNotNull("the real VM did not decode the fixture", warm);
            warm.recycle();
            pipe(PNG, true);
            return null;
        });
    }

    @Test public void bytesAfterThePngRemainUnread() throws Exception {
        worker(() -> {
            final byte[] input = java.util.Arrays.copyOf(PNG, PNG.length + 3);
            input[PNG.length] = 42;
            final ByteArrayInputStream stream = new ByteArrayInputStream(input);
            final Bitmap bitmap = BitmapFactory.decodeStream(stream);
            assertNotNull(bitmap);
            try {
                assertEquals(1, bitmap.getWidth());
                assertEquals(1, bitmap.getHeight());
                assertEquals(42, stream.read());
                assertEquals(2, stream.available());
            } finally {
                bitmap.recycle();
            }
            return null;
        });
    }

    @Test public void completedFramingDoesNotAcceptBrokenPixels() throws Exception {
        worker(() -> { pipe(BROKEN, false); return null; });
    }

    private static void pipe(byte[] png, boolean valid) throws Exception {
        final ParcelFileDescriptor[] descriptors = ParcelFileDescriptor.createPipe();
        try (InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(descriptors[0]);
                OutputStream output = new ParcelFileDescriptor.AutoCloseOutputStream(descriptors[1])) {
            output.write(png);
            output.flush();
            final Bitmap bitmap = BitmapFactory.decodeStream(input);
            if (valid) {
                assertNotNull("a complete PNG was refused while its writer stayed open", bitmap);
                try {
                    assertEquals(1, bitmap.getWidth());
                    assertEquals(1, bitmap.getHeight());
                } finally {
                    bitmap.recycle();
                }
            } else {
                if (bitmap != null) bitmap.recycle();
                assertNull("framing bypassed VM pixel validation", bitmap);
            }
        }
    }

    private static <T> T worker(Callable<T> action) throws Exception {
        final ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<T> result = null;
        try {
            result = executor.submit(action);
            return result.get(12, TimeUnit.SECONDS);
        } finally {
            if (result != null) result.cancel(true);
            executor.shutdownNow();
            assertTrue("PNG reader did not stop", executor.awaitTermination(3, TimeUnit.SECONDS));
        }
    }
}
