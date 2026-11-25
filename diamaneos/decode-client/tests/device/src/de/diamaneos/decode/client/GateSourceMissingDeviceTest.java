/* Copyright (C) 2026 DiamaneOS */
package de.diamaneos.decode.client;

import static org.junit.Assert.*;

import android.content.ContentResolver;
import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.graphics.DecodeGate;
import android.graphics.ImageDecoder;
import android.net.Uri;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.concurrent.Callable;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** A missing URI, file or callable source throws what stock throws, with the gate off and on. */
public class GateSourceMissingDeviceTest {
    private final Context mContext = InstrumentationRegistry.getInstrumentation().getTargetContext();

    @Before public void prepare() {
        DecodeGate.setLocal(null);
        DecodeGate.setWaitForTest(10000L);
        DecodeGate.resetForTest();
    }

    @After public void restore() {
        DecodeGate.setLocal(null);
        DecodeGate.setForcedForTest(null);
        DecodeGate.setWaitForTest(null);
        DecodeGate.resetForTest();
    }

    @Test public void missingResourceUri() throws Exception {
        sameAsStock(() -> ImageDecoder.createSource(resolver(), new Uri.Builder()
                .scheme(ContentResolver.SCHEME_ANDROID_RESOURCE).authority("authority")
                .appendPath("drawable").appendPath("bad").build()), FileNotFoundException.class);
    }

    @Test public void uriWithoutScheme() throws Exception {
        sameAsStock(() -> ImageDecoder.createSource(resolver(), new Uri.Builder()
                .authority("authority").appendPath("missing").appendPath("scheme").build()),
                FileNotFoundException.class);
    }

    @Test public void contentUriWithoutProvider() throws Exception {
        sameAsStock(() -> ImageDecoder.createSource(resolver(),
                Uri.parse("content://de.diamaneos.no.such.provider/image")), FileNotFoundException.class);
    }

    @Test public void callableGivingNothing() throws Exception {
        sameAsStock(() -> ImageDecoder.createSource(() -> null), FileNotFoundException.class);
    }

    @Test public void callableThrowingIoException() throws Exception {
        final IOException thrown = new IOException("fixture callable failed");
        final Callable<AssetFileDescriptor> callable = () -> { throw thrown; };
        for (boolean gated : new boolean[] {false, true}) {
            assertSame("gate " + gated, thrown, failure(ImageDecoder.createSource(callable), gated));
        }
    }

    @Test public void missingFile() throws Exception {
        final File missing = new File(mContext.getCacheDir(), "round2-missing.png");
        assertFalse(missing.exists());
        sameAsStock(() -> ImageDecoder.createSource(missing), FileNotFoundException.class);
    }

    private ContentResolver resolver() {
        return mContext.getContentResolver();
    }

    private interface SourceMaker {
        ImageDecoder.Source make();
    }

    private static void sameAsStock(SourceMaker maker, Class<? extends IOException> stock) throws Exception {
        final Throwable off = failure(maker.make(), false);
        final Throwable on = failure(maker.make(), true);
        assertTrue("stock gave " + off, stock.isInstance(off) && !(off instanceof ImageDecoder.DecodeException));
        assertEquals("gated gave " + on, off.getClass(), on.getClass());
    }

    private static Throwable failure(ImageDecoder.Source source, boolean gated) {
        DecodeGate.setForcedForTest(gated);
        DecodeGate.resetForTest();
        try {
            ImageDecoder.decodeDrawable(source);
            fail("decoded a missing source, gate " + gated);
            return null;
        } catch (IOException e) {
            return e;
        } finally {
            DecodeGate.setForcedForTest(null);
        }
    }
}
