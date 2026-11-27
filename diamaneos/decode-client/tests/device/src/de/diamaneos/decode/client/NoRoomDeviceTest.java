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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The decode service's "no room now" answer (STATUS_NO_ROOM) as the client takes it. Two large images of this app at
 * once hold more than the service gives one app: it reads them into memory from their pipes, and the second is
 * refused while the first holds its share. The refusal counts as unavailable on its thread, the client does not
 * suspect the service, and the recovery listeners run shortly, on an image thread only once its task ended (it
 * records what it left out first). Asked again, the image is made. Needs the decode service on a debuggable build.
 */
@RunWith(AndroidJUnit4.class)
public class NoRoomDeviceTest {
    private static final String SERVICE = "de.diamaneos.sanitizer";
    // Above a quarter of the service's buffered limit: its read of the pipe grows to the whole limit.
    private static final int PADDED_BYTES = 12 << 20;
    private static final int ATTEMPTS = 8;

    private final Context mContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private final List<Long> mRuns = new CopyOnWriteArrayList<>();

    private static String shell(String command) throws IOException {
        try (ParcelFileDescriptor out = InstrumentationRegistry.getInstrumentation().getUiAutomation()
                .executeShellCommand(command);
                InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(out)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private void assumeService() throws IOException {
        assumeTrue("no decode service here", shell("pm path " + SERVICE).contains("package:"));
        assumeTrue("needs a debuggable build", "1".equals(shell("getprop ro.debuggable").trim()));
    }

    // An 8 x 8 PNG of a new colour, with zeros after its end up to PADDED_BYTES: the service holds all of it.
    private static byte[] largePng() {
        final Bitmap bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(Color.rgb((int) (Math.random() * 255), (int) (Math.random() * 255),
                (int) (Math.random() * 255)));
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        final byte[] png = out.toByteArray();
        final byte[] padded = new byte[PADDED_BYTES];
        System.arraycopy(png, 0, padded, 0, png.length);
        return padded;
    }

    /** One decode's end: the bitmap, or the failure, when it came, and the thread's unavailable count before. */
    private static final class Outcome {
        Bitmap bitmap;
        Exception failure;
        long at;
        long taskEnd;
        boolean counted;

        boolean noRoom() {
            return failure instanceof VmDecode.UnavailableException
                    && ((VmDecode.UnavailableException) failure).isNoRoom();
        }
    }

    private Outcome decode(byte[] bytes) {
        final Outcome o = new Outcome();
        final int before = VmDecode.unavailableCount();
        try {
            o.bitmap = VmDecode.decodeBytes(mContext, bytes, 0, bytes.length, mContext.getPackageName(),
                    mContext.getUserId(), VmDecode.PRIORITY_USER_WAITING, 8, 8, VmDecode.deadlineIn(15_000));
        } catch (VmDecode.UnavailableException | VmDecode.RejectedException e) {
            o.failure = e;
        }
        o.at = SystemClock.uptimeMillis();
        o.counted = VmDecode.unavailableCount() > before;
        return o;
    }

    private Thread inThread(byte[] bytes, AtomicReference<Outcome> into) {
        final Thread t = new Thread(() -> into.set(decode(bytes)));
        t.start();
        return t;
    }

    // Waits for the recovery listeners to run after {@code since}, at most {@code withinMs}: when they ran, or -1.
    private long runAfter(long since, long withinMs) {
        final long end = SystemClock.uptimeMillis() + withinMs;
        while (SystemClock.uptimeMillis() < end) {
            for (long at : mRuns) {
                if (at > since) {
                    return at;
                }
            }
            SystemClock.sleep(20);
        }
        return -1;
    }

    @Test
    public void aRefusedImageIsAskedForAgainShortly() throws Exception {
        assumeService();
        VmDecode.addRecoveryListener(() -> mRuns.add(SystemClock.uptimeMillis()));
        final AtomicReference<Boolean> told = new AtomicReference<>();
        VmDecode.addStateListener(wasDown -> told.set(wasDown));
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            // Clear of the last attempt's runs (at most one per 2 s).
            SystemClock.sleep(LateRuns.GAP_MS + 500);
            told.set(null);
            final byte[] a = largePng();
            final byte[] b = largePng();
            final AtomicReference<Outcome> oa = new AtomicReference<>();
            final AtomicReference<Outcome> ob = new AtomicReference<>();
            final Thread ta = inThread(a, oa);
            final Thread tb = inThread(b, ob);
            ta.join(30_000);
            tb.join(30_000);
            final Outcome refused = oa.get().noRoom() ? oa.get() : ob.get().noRoom() ? ob.get() : null;
            if (refused == null) {
                continue;
            }
            assertTrue("the refusal was not counted as unavailable on its thread", refused.counted);
            assertFalse("the client suspects a service that answered", VmDecode.isSuspect());
            final long ran = runAfter(refused.at, LateRuns.GAP_MS + 1_000);
            assertTrue("the recovery listeners did not run after the refusal", ran > 0);
            assertEquals("the state listeners were not told the service answers", Boolean.FALSE, told.get());
            // What a listener does: ask again. The image is made now.
            final Outcome again = decode(refused == oa.get() ? a : b);
            assertNotNull("not made when asked again: " + again.failure, again.bitmap);
            assertEquals(8, again.bitmap.getWidth());
            return;
        }
        fail("no \"no room now\" answer in " + ATTEMPTS + " attempts of two large images at once");
    }

    private Runnable imageTask(byte[] input, CountDownLatch ready, CountDownLatch done,
            Outcome[] results, int index) {
        return () -> {
            try {
                ready.countDown();
                if (!ready.await(10, TimeUnit.SECONDS)) return;
                Outcome result = decode(input);
                results[index] = result;
                if (result.noRoom()) {
                    // Keep the refused task alive beyond the normal retry interval.
                    SystemClock.sleep(LateRuns.GAP_MS + LateRuns.QUIET_MS);
                }
                result.taskEnd = SystemClock.uptimeMillis();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                done.countDown();
            }
        };
    }

    @Test
    public void onAnImageThreadTheRetryWaitsForTheTaskEnd() throws Exception {
        assumeService();
        VmDecode.addRecoveryListener(() -> mRuns.add(SystemClock.uptimeMillis()));
        final String owner = mContext.getPackageName();
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            SystemClock.sleep(LateRuns.GAP_MS + 500);
            final byte[] first = largePng();
            final byte[] second = largePng();
            final CountDownLatch ready = new CountDownLatch(2);
            final CountDownLatch done = new CountDownLatch(2);
            final Outcome[] results = new Outcome[2];
            // Separate image executors can run the same owner's calls concurrently.
            assertNotNull(ImageThreads.execute(owner, imageTask(first, ready, done, results, 0)));
            assertNotNull(ImageThreads.executeUrgent(owner, imageTask(second, ready, done, results, 1)));
            assertTrue("image tasks did not finish", done.await(60, TimeUnit.SECONDS));
            assertNotNull("normal image task missed the start", results[0]);
            assertNotNull("urgent image task missed the start", results[1]);
            if (results[0].noRoom() == results[1].noRoom()) continue;
            final Outcome refused = results[0].noRoom() ? results[0] : results[1];
            final Outcome served = results[0].noRoom() ? results[1] : results[0];
            assertNotNull("the competing image was not served: " + served.failure, served.bitmap);
            served.bitmap.recycle();
            assertTrue("the service refusal was not counted on its image thread", refused.counted);
            for (long at : mRuns) {
                assertFalse("the listeners ran before the task ended", at > refused.at && at < refused.taskEnd);
            }
            assertTrue("the recovery listeners did not run after the task",
                    runAfter(refused.taskEnd - 1, LateRuns.GAP_MS + 1_000) > 0);
            return;
        }
        fail("expected one image task to receive a real service capacity refusal in " + ATTEMPTS + " attempts");
    }
}
