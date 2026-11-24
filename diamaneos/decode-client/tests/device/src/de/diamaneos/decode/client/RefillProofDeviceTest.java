/*
 * Copyright (C) 2026 DiamaneOS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package de.diamaneos.decode.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.DecodeGate;
import android.graphics.DecodeMemory;
import android.graphics.GateRefill;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.DrawableWrapper;
import android.graphics.drawable.Icon;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.util.Log;
import android.widget.ImageView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.internal.diamaneos.cleancopies.VmIconLoads;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Visible images followed on the phone through an outage of the decode service (its VM held down with the debug
 * property), memory pressure in this process and the recovery: an icon shown as a placeholder (VmIconLoads) and an
 * image loaded through the gate with a refill registration, one of them too large for the gate's cache. Each fills in
 * by itself after the outage and after the pressure, with no new call by the test. Logs when. Needs a debuggable
 * build with the decode service.
 */
@RunWith(AndroidJUnit4.class)
public class RefillProofDeviceTest {
    private static final String TAG = "RefillProof";
    private static final String DOWN_UNTIL = "debug.diamaneos.decoder.down_until";
    private static final long HOLD_S = 8;

    private final Context mContext = InstrumentationRegistry.getInstrumentation().getTargetContext();

    @After
    public void asBefore() throws Exception {
        shell("setprop " + DOWN_UNTIL + " 0");
        DecodeMemory.setMaxForTest(0);
        DecodeGate.setForcedForTest(null);
        DecodeGate.setWaitForTest(null);
        DecodeGate.resetForTest();
        VmIconLoads.setForcedForTest(null);
        VmIconLoads.resetForTest();
    }

    private static String shell(String command) throws IOException {
        try (ParcelFileDescriptor out = InstrumentationRegistry.getInstrumentation().getUiAutomation()
                .executeShellCommand(command);
                InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(out)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static byte[] png(int width, int height) {
        final Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(Color.rgb((int) (Math.random() * 200), (int) (Math.random() * 200), 90));
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out));
        return out.toByteArray();
    }

    private void needService() throws Exception {
        assumeTrue("no decode service here", shell("pm path de.diamaneos.sanitizer").contains("package:"));
        assumeTrue("needs a debuggable build", "1".equals(shell("getprop ro.debuggable").trim()));
    }

    private void holdDown() throws Exception {
        shell("setprop " + DOWN_UNTIL + " " + (System.currentTimeMillis() / 1000 + HOLD_S));
    }

    private static Bitmap shown(Drawable drawable) {
        Drawable d = drawable;
        if (d instanceof DrawableWrapper) {
            d = ((DrawableWrapper) d).getDrawable();
        }
        return d instanceof BitmapDrawable ? ((BitmapDrawable) d).getBitmap() : null;
    }

    @Test
    public void anIconPlaceholder_fillsInAfterAnOutage() throws Exception {
        needService();
        VmIconLoads.resetForTest();
        VmIconLoads.setForcedForTest(true);
        final byte[] data = png(48, 48);
        holdDown();
        final long start = SystemClock.uptimeMillis();
        final AtomicReference<Drawable> drawable = new AtomicReference<>();
        final ImageView view = new ImageView(mContext);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            drawable.set(VmIconLoads.loadAsUserNoWait(Icon.createWithData(data, 0, data.length), mContext,
                    mContext.getUserId()));
            view.setImageDrawable(drawable.get());
        });
        SystemClock.sleep(2_000);
        Bitmap b = shown(drawable.get());
        Log.i(TAG, "icon during the outage: " + (b == null ? "none" : b.getWidth() + "x" + b.getHeight()));
        assertTrue("a placeholder during the outage", b == null || b.getWidth() == 1);
        final long end = start + GateRefill.MAX_AGE_MS;
        while (SystemClock.uptimeMillis() < end) {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            b = shown(drawable.get());
            if (b != null && b.getWidth() == 48) {
                break;
            }
            SystemClock.sleep(200);
        }
        Log.i(TAG, "icon filled in " + (SystemClock.uptimeMillis() - start) + " ms after the load (hold "
                + HOLD_S + " s)");
        assertNotNull(b);
        assertEquals(48, b.getWidth());
    }

    // A worker load of png through the gate with nothing waited for: only the refill brings the image.
    private long refill(byte[] png, Runnable during) throws Exception {
        DecodeGate.setForcedForTest(true);
        DecodeGate.resetForTest();
        DecodeGate.setWaitForTest(0L);
        final AtomicReference<Bitmap> shown = new AtomicReference<>();
        final GateRefill[] refill = new GateRefill[1];
        final Runnable load = () -> {
            final Bitmap b = refill[0].load(() -> BitmapFactory.decodeByteArray(png, 0, png.length));
            if (b != null) {
                shown.set(b);
            }
        };
        refill[0] = new GateRefill(Executors.newSingleThreadExecutor(), load);
        final long start = SystemClock.uptimeMillis();
        Executors.newSingleThreadExecutor().execute(load);
        if (during != null) {
            during.run();
        }
        while (shown.get() == null && SystemClock.uptimeMillis() < start + GateRefill.MAX_AGE_MS) {
            SystemClock.sleep(100);
        }
        assertNotNull("never filled in, " + refill[0].roundsForTest() + " reloads", shown.get());
        Log.i(TAG, "gate image " + shown.get().getWidth() + "x" + shown.get().getHeight() + " filled in after "
                + (SystemClock.uptimeMillis() - start) + " ms, " + refill[0].roundsForTest() + " reloads");
        return SystemClock.uptimeMillis() - start;
    }

    @Test
    public void aGateImageTooLargeForTheCache_fillsInAfterAnOutage() throws Exception {
        needService();
        final byte[] big = png(2000, 1700); // 13.6 MB of pixels: never kept in the gate's cache
        holdDown();
        refill(big, null);
        // Not cached: without its refill the same call has nothing.
        DecodeGate.setWaitForTest(0L);
        assertNull(BitmapFactory.decodeByteArray(big, 0, big.length));
    }

    @Test
    public void aGateImage_fillsInAfterMemoryPressure() throws Exception {
        needService();
        final byte[] png = png(600, 400);
        // Other images hold all but 512 KiB of 8 MiB: the decode (about 2 MB) has no room now, and gets it later.
        DecodeMemory.setMaxForTest(8L << 20);
        final DecodeMemory.Reservation others = DecodeMemory.reserve((8L << 20) - (512 << 10));
        refill(png, () -> {
            SystemClock.sleep(3_000);
            Log.i(TAG, "memory pressure ends");
            others.close();
        });
    }
}
