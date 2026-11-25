/* Copyright (C) 2026 DiamaneOS */
package de.diamaneos.decode.client;

import static org.junit.Assert.*;

import android.graphics.DecodeGate;
import android.graphics.DecodeMemory;
import android.graphics.ImageDecoder;
import android.graphics.drawable.Drawable;
import androidx.test.platform.app.InstrumentationRegistry;
import de.diamaneos.decode.client.tests.R;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** Animations an app drops (as Glide does after a failed transform) never block the next decode, as in stock. */
public class GateDroppedDrawableDeviceTest {
    @Before public void prepare() {
        DecodeGate.setLocal(null);
        DecodeGate.setForcedForTest(true);
        DecodeGate.setWaitForTest(30_000L);
        DecodeGate.resetForTest();
    }

    @After public void restore() {
        DecodeMemory.setMaxForTest(0);
        DecodeGate.setForcedForTest(null);
        DecodeGate.setWaitForTest(null);
        DecodeGate.resetForTest();
    }

    @Test public void droppedAnimationsDoNotBlockTheNextDecode() throws Exception {
        final byte[] bytes = fixture(R.raw.vm_animation_compat_gif);
        Runtime.getRuntime().gc();
        final long before = DecodeMemory.held();
        Drawable first = decode(bytes);
        final long one = DecodeMemory.held() - before;
        assertTrue("the animation was not charged", one > 0);
        first = null;
        // The limit stays as the gate admits an animation (MAX_BYTES), and a reservation the test holds takes all of
        // it but the charges already held and one and a half animations: only collecting the dropped animations'
        // charges makes room for the next ones.
        DecodeMemory.setMaxForTest(0);
        final long filler = DecodeMemory.MAX_BYTES - DecodeMemory.held() - one - one / 2;
        assertTrue("no room to fill", filler > 0);
        try (DecodeMemory.Reservation taken = DecodeMemory.reserve(filler)) {
            for (int i = 0; i < 4; i++) {
                assertNotNull("decode " + i + " was refused while dropped animations held the budget",
                        decode(bytes));
            }
        }
    }

    private static Drawable decode(byte[] bytes) throws Exception {
        return ImageDecoder.decodeDrawable(ImageDecoder.createSource(ByteBuffer.wrap(bytes)));
    }

    private static byte[] fixture(int resource) throws Exception {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (InputStream input = InstrumentationRegistry.getInstrumentation().getTargetContext()
                .getResources().openRawResource(resource)) {
            final byte[] block = new byte[4096];
            for (int count; (count = input.read(block)) != -1;) bytes.write(block, 0, count);
        }
        return bytes.toByteArray();
    }
}
