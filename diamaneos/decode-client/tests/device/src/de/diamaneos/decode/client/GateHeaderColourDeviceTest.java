/* Copyright (C) 2026 DiamaneOS */
package de.diamaneos.decode.client;

import static org.junit.Assert.*;

import android.graphics.Bitmap;
import android.graphics.ColorSpace;
import android.graphics.DecodeGate;
import android.graphics.ImageDecoder;
import android.os.Bundle;
import android.os.IRemoteCallback;
import android.os.RemoteException;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.After;
import org.junit.Test;

/** Header callbacks expose checked colour tags before any pixels are requested. */
public class GateHeaderColourDeviceTest {
    private static final class HeaderSeen extends RuntimeException { }

    @After public void restore() {
        DecodeGate.setLocal(null);
        DecodeGate.setForcedForTest(null);
        DecodeGate.resetForTest();
    }

    private static byte[] png() {
        final Bitmap bitmap = Bitmap.createBitmap(64, 32, Bitmap.Config.ARGB_8888);
        try {
            final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes));
            return bytes.toByteArray();
        } finally { bitmap.recycle(); }
    }

    private static void mode(int tag) {
        DecodeGate.resetForTest();
        final FakeGateProvider provider = new FakeGateProvider(0);
        DecodeGate.setForcedForTest(true);
        DecodeGate.setLocal((method, extras) -> {
            final IRemoteCallback callback = IRemoteCallback.Stub.asInterface(
                    extras.getBinder(DecodeGate.EXTRA_CALLBACK));
            final Bundle forwarded = new Bundle(extras);
            forwarded.putBinder(DecodeGate.EXTRA_CALLBACK, new IRemoteCallback.Stub() {
                @Override public void sendResult(Bundle reply) throws RemoteException {
                    reply.putInt("colorSpace", tag);
                    callback.sendResult(reply);
                }
            }.asBinder());
            return provider.call(method, forwarded);
        });
    }

    @Test public void headerKeepsEveryCheckedNamedColour() throws Exception {
        final byte[] png = png();
        final ColorSpace.Named[] names = {ColorSpace.Named.SRGB, ColorSpace.Named.DISPLAY_P3,
                ColorSpace.Named.ADOBE_RGB, ColorSpace.Named.PRO_PHOTO_RGB, ColorSpace.Named.BT2020,
                ColorSpace.Named.BT2020_HLG, ColorSpace.Named.BT2020_PQ, ColorSpace.Named.LINEAR_SRGB};
        for (int tag = 0; tag < names.length; tag++) {
            mode(tag);
            final ColorSpace wanted = ColorSpace.get(names[tag]);
            final AtomicBoolean called = new AtomicBoolean();
            try {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(png)), (decoder, info, source) -> {
                    called.set(true);
                    assertEquals(wanted, info.getColorSpace());
                    throw new HeaderSeen();
                });
                fail("the header callback did not stop the decode");
            } catch (HeaderSeen expected) {
                assertTrue(called.get());
            }
        }
    }

    @Test public void anUnknownHeaderColourNeverReachesTheListener() throws Exception {
        final byte[] png = png();
        mode(9);
        final AtomicBoolean called = new AtomicBoolean();
        try {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(png)),
                    (decoder, info, source) -> called.set(true));
            fail("an unknown colour was accepted");
        } catch (ImageDecoder.DecodeException expected) {
            assertFalse(called.get());
        }
    }

    @Test public void ordinaryDecodeKeepsItsColourSpace() throws Exception {
        final byte[] png = png();
        DecodeGate.setForcedForTest(false);
        final AtomicBoolean called = new AtomicBoolean();
        final Bitmap bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(png)),
                (decoder, info, source) -> {
                    called.set(true);
                    assertEquals(ColorSpace.get(ColorSpace.Named.SRGB), info.getColorSpace());
                    decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                });
        try {
            assertTrue(called.get());
            assertEquals(64, bitmap.getWidth());
            assertEquals(32, bitmap.getHeight());
            assertEquals(ColorSpace.get(ColorSpace.Named.SRGB), bitmap.getColorSpace());
        } finally { bitmap.recycle(); }
    }
}
