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

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The decode client's recovery watch: a call that fails because the service cannot decode (its VM held down with the
 * debug property) counts as unavailable on the caller's thread, the client finds the service down, and once the hold
 * ends it finds the service answering again by itself, with no further call, and tells its recovery listeners, which
 * is what makes placeholders fill in after an outage. Needs a debuggable build with the decode service.
 */
@RunWith(AndroidJUnit4.class)
public class RecoveryDeviceTest {
    private static final String SERVICE = "de.diamaneos.sanitizer";
    // The decode service answers every request as unavailable until this time (seconds since the epoch).
    private static final String DOWN_UNTIL = "debug.diamaneos.decoder.down_until";
    private static final long HOLD_S = 10;
    // The watch probes after 1, 2, 4, 8, 16 s and then every 30 s: an end of the hold is seen within about 30 s.
    private static final long BACK_WITHIN_S = HOLD_S + 45;

    private final Context mContext = InstrumentationRegistry.getInstrumentation().getTargetContext();

    @After
    public void asBefore() throws Exception {
        shell("setprop " + DOWN_UNTIL + " 0");
    }

    private static String shell(String command) throws IOException {
        try (ParcelFileDescriptor out = InstrumentationRegistry.getInstrumentation().getUiAutomation()
                .executeShellCommand(command);
                InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(out)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    // A PNG of a colour no earlier test asked for: the service answers bytes it saw before from its cache.
    private static byte[] newPng() {
        final Bitmap bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(Color.rgb((int) (Math.random() * 255), (int) (Math.random() * 255),
                (int) (Math.random() * 255)));
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        return out.toByteArray();
    }

    private Bitmap decode(byte[] png, long timeoutMs) throws VmDecode.RejectedException,
            VmDecode.UnavailableException {
        return VmDecode.decodeBytes(mContext, png, 0, png.length, mContext.getPackageName(),
                mContext.getUserId(), VmDecode.PRIORITY_USER_WAITING, 8, 8, VmDecode.deadlineIn(timeoutMs));
    }

    @Test
    public void clientRebindsAndDecodesAgain() throws Exception {
        decodeOnNewBinding();
        decodeOnNewBinding();
    }

    private void decodeOnNewBinding() throws Exception {
        final var connected = new java.util.concurrent.ArrayBlockingQueue<android.os.IBinder>(1);
        final android.content.ServiceConnection connection = new android.content.ServiceConnection() {
            @Override
            public void onServiceConnected(android.content.ComponentName name, android.os.IBinder binder) {
                connected.offer(binder);
            }

            @Override
            public void onServiceDisconnected(android.content.ComponentName name) {}
        };
        final android.content.Intent intent = new android.content.Intent().setComponent(
                new android.content.ComponentName(SERVICE, SERVICE + ".DecodeService"));
        assertTrue(mContext.bindService(intent, connection, Context.BIND_AUTO_CREATE));
        final java.io.File image = java.io.File.createTempFile("rebind", ".png", mContext.getCacheDir());
        try {
            final var service = de.diamaneos.sanitizer.decode.IDecodeService.Stub.asInterface(
                    connected.poll(10, TimeUnit.SECONDS));
            assertNotNull("decode service did not connect", service);
            java.nio.file.Files.write(image.toPath(), newPng());
            try (ParcelFileDescriptor fd = ParcelFileDescriptor.open(image, ParcelFileDescriptor.MODE_READ_ONLY)) {
                final var request = new de.diamaneos.sanitizer.decode.DecodeRequest();
                request.file = fd;
                request.ownerPackage = mContext.getPackageName();
                request.ownerUser = mContext.getUserId();
                request.forUid = -1;
                final var reply = service.probe(request);
                assertNotNull(reply);
                assertEquals(de.diamaneos.sanitizer.decode.IDecodeService.STATUS_OK, reply.status);
            }
        } finally {
            mContext.unbindService(connection);
            image.delete();
        }
    }

    @Test
    public void listenersAreToldWhenTheServiceAnswersAgain() throws Exception {
        assumeTrue("no decode service here", shell("pm path " + SERVICE).contains("package:"));
        assumeTrue("needs a debuggable build", "1".equals(shell("getprop ro.debuggable").trim()));
        // The service answers before the hold: no outage is pending from an earlier test.
        assertNotNull(decode(newPng(), 15_000));

        final CountDownLatch back = new CountDownLatch(1);
        final AtomicInteger notDown = new AtomicInteger();
        VmDecode.addStateListener(wasDown -> {
            if (wasDown) {
                back.countDown();
            } else {
                notDown.incrementAndGet();
            }
        });
        final int recoveries = VmDecode.recoveries();
        final long until = System.currentTimeMillis() / 1000 + HOLD_S;
        shell("setprop " + DOWN_UNTIL + " " + until);
        assertEquals("the hold was not set", String.valueOf(until), shell("getprop " + DOWN_UNTIL).trim());

        final int unavailable = VmDecode.unavailableCount();
        try {
            final Bitmap made = decode(newPng(), 5_000);
            fail("decoded while the VM is held down: " + made.getWidth() + "x" + made.getHeight());
        } catch (VmDecode.UnavailableException expected) {
            // The placeholder shows.
        }
        assertTrue("the failure was not counted on this thread", VmDecode.unavailableCount() > unavailable);

        // The watch finds the service down while the hold lasts.
        final long downBy = SystemClock.uptimeMillis() + 5_000;
        while (!VmDecode.isDown() && SystemClock.uptimeMillis() < downBy) {
            SystemClock.sleep(100);
        }
        assertTrue("the client did not find the service down", VmDecode.isDown());
        assertEquals("told it answers while it was held down", 1, back.getCount());

        // No call is made: the watch alone finds it answering once the hold ends.
        assertTrue("the recovery listeners were not told within " + BACK_WITHIN_S + " s",
                back.await(BACK_WITHIN_S, TimeUnit.SECONDS));
        assertTrue("told before the hold ended", System.currentTimeMillis() / 1000 >= until);
        assertFalse("still down after the listeners were told", VmDecode.isDown());
        assertTrue("the outage was not counted", VmDecode.recoveries() > recoveries);
        assertNotNull("no decode after the service is back", decode(newPng(), 15_000));
    }
}
