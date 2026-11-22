package de.diamaneos.decode.client;

import static org.junit.Assert.*;

import android.graphics.DecodeGate;
import android.graphics.ImageDecoder;
import android.graphics.Movie;
import android.graphics.drawable.Drawable;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import de.diamaneos.decode.client.tests.R;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class GateFrameTimingDeviceTest {
    private static final int[] CENTISECONDS = {0, 1, 2, 65535};

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

    private static byte[] encodedDelays() throws Exception {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (InputStream input = InstrumentationRegistry.getInstrumentation().getContext()
                .getResources().openRawResource(R.raw.vm_animation_compat_gif)) {
            final byte[] block = new byte[8192];
            int count;
            while ((count = input.read(block)) != -1) bytes.write(block, 0, count);
        }
        final byte[] gif = bytes.toByteArray();
        int frame = 0;
        for (int offset = 0; offset + 7 < gif.length; offset++) {
            if ((gif[offset] & 255) != 0x21 || (gif[offset + 1] & 255) != 0xf9
                    || gif[offset + 2] != 4 || gif[offset + 7] != 0) continue;
            assertTrue("unexpected fixture graphics control", frame < CENTISECONDS.length);
            final int delay = CENTISECONDS[frame++];
            gif[offset + 4] = (byte) delay;
            gif[offset + 5] = (byte) (delay >>> 8);
            offset += 7;
        }
        assertEquals("fixture frame count", CENTISECONDS.length, frame);
        return gif;
    }

    @Test public void movieReportsEncodedDelaysLikeStock() throws Exception {
        final byte[] gif = encodedDelays();
        DecodeGate.setForcedForTest(false);
        final Movie stock = Movie.decodeByteArray(gif, 0, gif.length);
        assertNotNull("stock fixture", stock);
        assertEquals(655380, stock.duration());
        DecodeGate.setForcedForTest(true);
        final Movie gated = Movie.decodeByteArray(gif, 0, gif.length);
        assertNotNull("VM movie", gated);
        assertEquals("android.graphics.GatedMovie", gated.getClass().getName());
        assertEquals(stock.duration(), gated.duration());
    }

    @Test public void drawableRetainsRawDelaysForItsPlaybackPolicy() throws Exception {
        final Drawable drawable = ImageDecoder.decodeDrawable(
                ImageDecoder.createSource(ByteBuffer.wrap(encodedDelays())));
        assertEquals("android.graphics.GatedAnimation", drawable.getClass().getName());
        final Method duration = drawable.getClass().getDeclaredMethod("frameDuration", int.class);
        duration.setAccessible(true);
        for (int frame = 0; frame < CENTISECONDS.length; frame++) {
            assertEquals(CENTISECONDS[frame] * 10, ((Integer) duration.invoke(drawable, frame)).intValue());
        }
    }
}
