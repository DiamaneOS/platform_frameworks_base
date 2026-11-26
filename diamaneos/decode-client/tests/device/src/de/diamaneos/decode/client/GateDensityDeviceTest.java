/* Copyright (C) 2026 DiamaneOS */
package de.diamaneos.decode.client;

import static org.junit.Assert.*;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.DecodeGate;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.FileOutputStream;
import org.junit.After;
import org.junit.Test;

/** Public file decodes preserve the stock size and density for identical input bytes. */
public class GateDensityDeviceTest {
    @After public void restore() {
        DecodeGate.setLocal(null);
        DecodeGate.setForcedForTest(null);
        DecodeGate.resetForTest();
    }

    @Test public void fileDecodePublishesTheScaledDensity() throws Exception {
        compare(true);
    }

    @Test public void fileDecodePublishesTheUnscaledDensity() throws Exception {
        compare(false);
    }

    private static void compare(boolean scaled) throws Exception {
        final File file = File.createTempFile("vm-density-", ".png",
                InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir());
        final Bitmap source = Bitmap.createBitmap(64, 32, Bitmap.Config.ARGB_8888);
        Bitmap stock = null;
        Bitmap gated = null;
        try {
            source.eraseColor(Color.RED);
            try (FileOutputStream output = new FileOutputStream(file)) {
                assertTrue(source.compress(Bitmap.CompressFormat.PNG, 100, output));
            }
            for (boolean enabled : new boolean[] {false, true}) {
                DecodeGate.resetForTest();
                final FakeGateProvider provider = new FakeGateProvider(0);
                DecodeGate.setLocal(enabled ? provider : null);
                DecodeGate.setForcedForTest(enabled);
                final BitmapFactory.Options options = new BitmapFactory.Options();
                options.inDensity = 160;
                options.inTargetDensity = 320;
                options.inScaled = scaled;
                final Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath(), options);
                assertNotNull(bitmap);
                if (enabled) {
                    gated = bitmap;
                    assertTrue("checked provider was not called", provider.calls.get() > 0);
                } else {
                    stock = bitmap;
                }
            }
            assertEquals(stock.getDensity(), gated.getDensity());
            assertEquals(stock.getWidth(), gated.getWidth());
            assertEquals(stock.getHeight(), gated.getHeight());
            assertEquals(scaled ? 320 : 160, gated.getDensity());
        } finally {
            source.recycle();
            if (stock != null) stock.recycle();
            if (gated != null) gated.recycle();
            assertTrue("density input was not removed", file.delete());
        }
    }
}
