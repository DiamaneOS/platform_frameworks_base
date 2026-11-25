/* Copyright (C) 2026 DiamaneOS */
package de.diamaneos.decode.client;

import static org.junit.Assert.*;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.DecodeGate;
import android.graphics.Gainmap;
import android.os.Bundle;
import android.os.IRemoteCallback;
import org.junit.After;
import org.junit.Test;
import java.io.ByteArrayOutputStream;

public class GainmapHandoffDeviceTest {
    @After public void restore() {
        DecodeGate.setLocal(null);
        DecodeGate.setForcedForTest(null);
        DecodeGate.resetForTest();
    }

    @Test public void bitmapFactoryRestoresStoredGainmapAndReplacesReusedMap() throws Exception {
        final byte[] bytes = gatedJpeg();
        Bitmap reused = Bitmap.createBitmap(64, 32, Bitmap.Config.ARGB_8888);
        reused.setGainmap(new Gainmap(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)));
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inBitmap = reused;
        options.inMutable = true;
        Bitmap result = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        assertSame(reused, result);
        assertTrue(result.hasGainmap());
        Bitmap gain = result.getGainmap().getGainmapContents();
        assertEquals(32, gain.getWidth());
        assertEquals(16, gain.getHeight());
        assertEquals(Color.RED, gain.getPixel(0, 0));
        assertEquals(Color.BLUE, gain.getPixel(31, 0));
        result.recycle();
    }

    // A small JPEG, with the gate forced on and a fake VM that answers with an HDR image of it.
    private static byte[] gatedJpeg() throws Exception {
        DecodeGate.setForcedForTest(false);
        Bitmap seed = Bitmap.createBitmap(64, 32, Bitmap.Config.ARGB_8888);
        seed.eraseColor(Color.RED);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertTrue(seed.compress(Bitmap.CompressFormat.JPEG, 90, out));
        seed.recycle();
        byte[] bytes = out.toByteArray();
        DecodeGate.setForcedForTest(true);
        DecodeGate.resetForTest();
        DecodeGate.setLocal((method, extras) -> {
            Bundle reply = new Bundle();
            if (DecodeGate.METHOD_PROBE.equals(method) || DecodeGate.METHOD_HEADER.equals(method)) {
                reply.putInt(DecodeGate.EXTRA_WIDTH, 64);
                reply.putInt(DecodeGate.EXTRA_HEIGHT, 32);
                reply.putInt(DecodeGate.EXTRA_FORMAT, 1);
            } else if (DecodeGate.METHOD_REGION.equals(method)) {
                Bitmap base = Bitmap.createBitmap(64, 32, Bitmap.Config.ARGB_8888);
                base.eraseColor(Color.RED);
                reply.putParcelable(DecodeGate.EXTRA_BITMAP, base);
            } else {
                Bitmap base = Bitmap.createBitmap(32, 64, Bitmap.Config.ARGB_8888);
                Bitmap gain = Bitmap.createBitmap(16, 32, Bitmap.Config.ARGB_8888);
                for (int y = 0; y < 32; y++) for (int x = 0; x < 16; x++) {
                    gain.setPixel(x, y, y < 16 ? Color.BLUE : Color.RED);
                }
                base.setGainmap(new Gainmap(gain));
                reply.putParcelable(DecodeGate.EXTRA_BITMAP, base);
                reply.putInt(DecodeGate.EXTRA_ROTATION, 90);
                reply.putBoolean(DecodeGate.EXTRA_MIRROR, true);
            }
            try {
                IRemoteCallback.Stub.asInterface(extras.getBinder(DecodeGate.EXTRA_CALLBACK)).sendResult(reply);
            } catch (android.os.RemoteException e) { throw new AssertionError(e); }
            Bundle taken = new Bundle();
            taken.putBoolean(DecodeGate.EXTRA_TAKEN, true);
            return taken;
        });
        return bytes;
    }

    // As stock BitmapFactory (Options.nativeInBitmap clears the map before any decode), a refused reuse leaves the
    // reused bitmap without its old gain map, gated or not.
    @Test public void refusedReuseClearsTheGainmapAsStock() throws Exception {
        final byte[] bytes = gatedJpeg();
        final boolean[] kept = new boolean[2];
        for (int i = 0; i < 2; i++) {
            DecodeGate.setForcedForTest(i == 1);
            final Bitmap reused = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888);
            reused.setGainmap(new Gainmap(Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)));
            final BitmapFactory.Options options = new BitmapFactory.Options();
            options.inBitmap = reused;
            options.inMutable = true;
            assertThrows(IllegalArgumentException.class,
                    () -> BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options));
            kept[i] = reused.hasGainmap();
            reused.recycle();
        }
        assertFalse("stock kept the gain map", kept[0]);
        assertEquals("gated differs from stock", kept[0], kept[1]);
    }
}
