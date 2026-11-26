/* Copyright (C) 2026 DiamaneOS */
package de.diamaneos.decode.client;

import static org.junit.Assert.*;

import android.content.Context;
import android.graphics.DecodeGate;
import android.os.Bundle;
import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * The system server knows every required global lock once the phone has booted, Telecom's among them (it comes from
 * the telephony module on the system server class path), so an incoming binder call there may wait for the VM.
 */
@RunWith(AndroidJUnit4.class)
public class ServerWaitDeviceTest {
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();

    @Test
    public void aSystemServerBinderCallGetsItsWaitAfterBoot() throws Exception {
        // The locks are looked for at most once a second: a few calls leave time for one lookup after boot.
        long wait = -1;
        final long end = SystemClock.uptimeMillis() + 5_000;
        while (SystemClock.uptimeMillis() < end) {
            final Bundle answer = context.getContentResolver().call(DecodeGate.URI, "testServerWait", null, null);
            assertNotNull("server test entry unavailable", answer);
            wait = answer.getLong("wait", -1);
            if (wait == DecodeGate.BINDER_MS) {
                break;
            }
            SystemClock.sleep(1_100);
        }
        final String log = OwnerDeviceTest.shell("logcat -d -s DecodeGate:E");
        assertFalse("a required lock is still missing: " + log, log.contains("not found yet"));
        assertEquals("a system server binder call's wait", DecodeGate.BINDER_MS, wait);
    }
}
