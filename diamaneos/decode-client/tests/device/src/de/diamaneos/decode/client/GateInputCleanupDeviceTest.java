/* Copyright (C) 2026 DiamaneOS */
package de.diamaneos.decode.client;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.os.ParcelFileDescriptor;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.Constructor;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class GateInputCleanupDeviceTest {
    private static AutoCloseable acquired(FileInputStream stream, AssetFileDescriptor descriptor)
            throws Exception {
        final Class<?> type = Class.forName("android.graphics.GateInput$Acquired");
        final Constructor<?> constructor = type.getDeclaredConstructor(
                Class.forName("android.graphics.DecodeGate$Bytes"),
                FileInputStream.class, AssetFileDescriptor.class);
        constructor.setAccessible(true);
        return (AutoCloseable) constructor.newInstance(null, stream, descriptor);
    }

    private static File fixture() throws Exception {
        final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        final File file = File.createTempFile("input-cleanup-", ".bin", context.getCacheDir());
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(new byte[] {1, 2, 3});
        }
        return file;
    }

    @Test public void aByteOnlyResultHasNoStreamToClose() throws Exception {
        acquired(null, null).close();
    }

    @Test public void aDescriptorOnlyResultStillClosesItsDescriptor() throws Exception {
        final File file = fixture();
        try (AssetFileDescriptor descriptor = new AssetFileDescriptor(
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY), 0, file.length())) {
            final FileDescriptor fd = descriptor.getFileDescriptor();
            assertTrue(fd.valid());
            acquired(null, descriptor).close();
            assertFalse("descriptor leaked after null stream cleanup", fd.valid());
        } finally {
            file.delete();
        }
    }

    @Test public void anOrdinaryStreamStillCloses() throws Exception {
        final File file = fixture();
        try (FileInputStream stream = new FileInputStream(file)) {
            acquired(stream, null).close();
            assertFalse("stream leaked", stream.getChannel().isOpen());
        } finally {
            file.delete();
        }
    }
}
