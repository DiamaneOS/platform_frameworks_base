/*
 * Copyright (C) 2026 The DiamaneOS Project
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.server;

import static org.junit.Assert.*;

import android.Manifest;
import android.app.BroadcastOptions;
import android.content.ContextWrapper;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.UserHandle;
import android.os.test.TestLooper;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;

/** No actual broadcast, location access, telephony or network operation. */
@RunWith(AndroidJUnit4.class)
public class EmergencyLocationDispatcherTest {
    private static final class Capture extends ContextWrapper {
        final List<Intent> events = new ArrayList<>();
        boolean fail;

        Capture() {
            super(null);
        }

        @Override
        public void sendBroadcastAsUser(
                Intent intent, UserHandle user, String permission, Bundle options) {
            assertEquals(UserHandle.SYSTEM, user);
            assertEquals(Manifest.permission.READ_PRIVILEGED_PHONE_STATE, permission);
            assertTrue(BroadcastOptions.fromBundle(options).isShareIdentityEnabled());
            if (fail) throw new IllegalStateException("synthetic failure");
            events.add(intent);
        }
    }

    @Test
    public void callAndSmsAreExplicitAsynchronousAndBounded() {
        Capture context = new Capture();
        TestLooper looper = new TestLooper();
        EmergencyLocationDispatcher dispatcher =
                new EmergencyLocationDispatcher(context, new Handler(looper.getLooper()));
        dispatcher.notify(EmergencyLocationDispatcher.CALL, 1, 7, "112");
        dispatcher.notify(EmergencyLocationDispatcher.SMS, 1, 7, "112");
        dispatcher.notify("untrusted", 1, 7, "112");
        dispatcher.notify(EmergencyLocationDispatcher.CALL, -1, 7, "112");
        dispatcher.notify(EmergencyLocationDispatcher.CALL, 1, 7, "112&bad");
        assertTrue(context.events.isEmpty());
        looper.dispatchAll();
        assertEquals(2, context.events.size());
        Intent event = context.events.get(0);
        assertEquals(
                "org.diamaneos.emergencylocation.EmergencyReceiver",
                event.getComponent().getClassName());
        assertEquals(1, event.getIntExtra("phone", -1));
        assertEquals(7, event.getIntExtra("subscription", -1));
        assertTrue(event.getLongExtra("utc", -1) > 0);
        assertTrue(event.getLongExtra("elapsed", -1) >= 0);
        assertEquals(EmergencyLocationDispatcher.SMS, context.events.get(1).getAction());
        for (int i = 0; i < 20; i++)
            dispatcher.notify(EmergencyLocationDispatcher.CALL, 0, 7, "112");
        looper.dispatchAll();
        assertEquals(10, context.events.size());
    }

    @Test
    public void failedDeliveryDoesNotPoisonLaterEvents() {
        Capture context = new Capture();
        TestLooper looper = new TestLooper();
        EmergencyLocationDispatcher dispatcher =
                new EmergencyLocationDispatcher(context, new Handler(looper.getLooper()));
        context.fail = true;
        dispatcher.notify(EmergencyLocationDispatcher.CALL, 0, -1, "112");
        looper.dispatchAll();
        context.fail = false;
        dispatcher.notify(EmergencyLocationDispatcher.CALL, 0, -1, "112");
        looper.dispatchAll();
        assertEquals(1, context.events.size());
        assertEquals(-1, context.events.get(0).getIntExtra("subscription", 99));
    }
}
