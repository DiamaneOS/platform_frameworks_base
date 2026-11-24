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

import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.net.Uri;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** VmDecode's opens of URIs on their own threads: an interrupted caller keeps its interrupt. */
@RunWith(AndroidJUnit4.class)
public class VmDecodeOpenDeviceTest {
    private final Context mContext = InstrumentationRegistry.getInstrumentation().getTargetContext();

    @Test
    public void anInterruptedOpenKeepsTheInterrupt() throws Exception {
        ProbeProvider.ICONS.put("interrupt.png", new byte[] {(byte) 0x89, 'P', 'N', 'G'});
        final AtomicReference<Throwable> thrown = new AtomicReference<>();
        final AtomicBoolean interrupted = new AtomicBoolean();
        final CountDownLatch opening = new CountDownLatch(1);
        final Thread caller = new Thread(() -> {
            opening.countDown();
            try {
                VmDecode.decodeUri(mContext, mContext.getContentResolver(),
                        Uri.parse("content://" + ProbeProvider.AUTHORITY + "/icon-slow/interrupt.png"),
                        "owner.interrupt", mContext.getUserId(), VmDecode.PRIORITY_USER_WAITING, 32, 32,
                        VmDecode.deadlineIn(5_000));
            } catch (Throwable e) {
                thrown.set(e);
            }
            interrupted.set(Thread.currentThread().isInterrupted());
        });
        caller.start();
        assertTrue(opening.await(5, TimeUnit.SECONDS));
        Thread.sleep(300); // inside the open's wait: the provider sleeps a second
        caller.interrupt();
        caller.join(10_000);
        assertTrue("the open failed for now: " + thrown.get(), thrown.get() instanceof VmDecode.UnavailableException);
        assertTrue("the caller's interrupt is kept", interrupted.get());
        ProbeProvider.ICONS.remove("interrupt.png");
    }
}
