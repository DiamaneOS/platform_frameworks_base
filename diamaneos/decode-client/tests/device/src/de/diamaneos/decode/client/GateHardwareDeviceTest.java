/* Copyright (C) 2026 DiamaneOS */
package de.diamaneos.decode.client;

import static org.junit.Assert.*;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.DecodeMemory;
import android.graphics.Gainmap;
import android.util.Log;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class GateHardwareDeviceTest {
    private Bitmap source;
    private Bitmap sourceGain;
    private Bitmap hardware;
    private Bitmap hardwareGain;

    @After public void cleanup() {
        if (hardware != null) hardware.recycle();
        if (hardwareGain != null) hardwareGain.recycle();
        if (source != null) source.recycle();
        if (sourceGain != null) sourceGain.recycle();
        DecodeMemory.setMaxForTest(0);
    }

    private static Object field(Class<?> type, Object object, String name) throws Exception {
        final Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(object);
    }

    // Observe only the two fixture identities, independently of unrelated collection in a full run.
    private static long charge(Bitmap base, Bitmap gain) throws Exception {
        final Object lock = field(DecodeMemory.class, null, "sLock");
        synchronized (lock) {
            final Iterable<?> pixels = (Iterable<?>) field(DecodeMemory.class, null, "sPixels");
            long bytes = 0;
            for (Object held : pixels) {
                final Object bitmap = ((WeakReference<?>) field(held.getClass(), held, "identity")).get();
                if (bitmap == base || bitmap == gain) {
                    bytes += ((DecodeMemory.Reservation) field(held.getClass(), held, "memory")).bytes();
                }
            }
            return bytes;
        }
    }

    @Test public void requiredHardwareKeepsBothDriverAllocationsCharged() throws Exception {
        source = Bitmap.createBitmap(17, 13, Bitmap.Config.ARGB_8888);
        source.eraseColor(Color.RED);
        sourceGain = Bitmap.createBitmap(5, 3, Bitmap.Config.ARGB_8888);
        sourceGain.eraseColor(Color.WHITE);
        source.setGainmap(new Gainmap(sourceGain));
        DecodeMemory.keep(source);
        final long logical = (long) source.getAllocationByteCount() + sourceGain.getAllocationByteCount();
        final Method query = Bitmap.class.getDeclaredMethod("hardwareAllocationBytes", Bitmap.class);
        query.setAccessible(true);
        final long predicted = (Long) query.invoke(null, source);
        assertTrue("allocator cannot describe the actual FP6 copy", predicted >= logical);
        final Class<?> pixels = Class.forName("android.graphics.GatePixels");
        final Method copy = pixels.getDeclaredMethod("copy", Bitmap.class, Bitmap.Config.class, boolean.class);
        copy.setAccessible(true);
        hardware = (Bitmap) copy.invoke(null, source, Bitmap.Config.HARDWARE, false);
        assertEquals(Bitmap.Config.HARDWARE, hardware.getConfig());
        assertTrue("gain map lost", hardware.hasGainmap());
        hardwareGain = hardware.getGainmap().getGainmapContents();
        final long actual = (long) hardware.getAllocationByteCount() + hardwareGain.getAllocationByteCount();
        Log.i("GateHardware", "logical=" + logical + " reserved=" + predicted + " driver=" + actual);
        assertTrue("driver exceeded its descriptor", actual <= predicted);
        assertEquals("postallocation metadata differs from either plane", actual, ((Long) query.invoke(null, hardware)).longValue());
        assertEquals("complete live hardware charge", actual, charge(hardware, hardwareGain));
        assertEquals("source and hardware charged independently", logical, charge(source, sourceGain));
        hardware.recycle();
        hardwareGain.recycle();
        assertEquals("recycle did not release both driver charges", 0, charge(hardware, hardwareGain));
        assertEquals("hardware recycle released source storage", logical, charge(source, sourceGain));
    }
    @Test public void softwareCopyChargesTheConvertedGainFormat() throws Exception {
        source = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888);
        sourceGain = Bitmap.createBitmap(5, 5, Bitmap.Config.ALPHA_8);
        source.setGainmap(new Gainmap(sourceGain));
        DecodeMemory.keep(source);
        final Class<?> pixels = Class.forName("android.graphics.GatePixels");
        final Method copy = pixels.getDeclaredMethod("copy", Bitmap.class, Bitmap.Config.class, boolean.class);
        copy.setAccessible(true);
        hardware = (Bitmap) copy.invoke(null, source, Bitmap.Config.ARGB_8888, false);
        assertNotNull(hardware);
        assertTrue(hardware.hasGainmap());
        hardwareGain = hardware.getGainmap().getGainmapContents();
        assertEquals(Bitmap.Config.ARGB_8888, hardwareGain.getConfig());
        final long actual = (long) hardware.getAllocationByteCount() + hardwareGain.getAllocationByteCount();
        Log.i("GateHardware", "convertedGain=" + hardwareGain.getAllocationByteCount() + " copiedStorage=" + actual);
        assertEquals(actual, charge(hardware, hardwareGain));
        hardware.recycle();
        hardwareGain.recycle();
        assertEquals(0, charge(hardware, hardwareGain));
    }

}
