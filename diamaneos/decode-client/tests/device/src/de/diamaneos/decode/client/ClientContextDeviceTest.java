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
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;

import android.content.Context;
import android.os.UserHandle;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * The decode client looks the service up and binds it with the process's own context, never with the context its
 * first caller passed. SystemUI passes a notification's package context (of the notification's user, a work profile
 * too), whose application context is null: a client that kept it found no service for good once the profile was
 * removed and the service restarted.
 */
@RunWith(AndroidJUnit4.class)
public class ClientContextDeviceTest {
    // Another app that runs on every build, visible to this package through the manifest's queries.
    private static final String OTHER_APP = "com.android.systemui";

    @Test
    public void bindsWithTheProcessOwnContextNeverACallers() throws Exception {
        final Context own = InstrumentationRegistry.getInstrumentation().getTargetContext().getApplicationContext();
        // A package context of another app, as SystemUI makes one for a notification: no application of its own.
        // Not "android": Android gives that package's context the caller's own application.
        final Context caller = own.createPackageContext(OTHER_APP, 0);
        assertNull("a package context of another app has no application context", caller.getApplicationContext());

        final Context used = DecodeClient.ownContext(caller);
        assertNotSame("the caller's context was kept", caller, used);
        assertEquals(own.getPackageName(), used.getPackageName());
        assertEquals(UserHandle.myUserId(), used.getUserId());
    }

    @Test
    public void keepsTheProcessOwnApplicationContext() {
        final Context own = InstrumentationRegistry.getInstrumentation().getTargetContext().getApplicationContext();
        final Context used = DecodeClient.ownContext(own);
        assertEquals(own.getPackageName(), used.getPackageName());
        assertEquals(UserHandle.myUserId(), used.getUserId());
    }
}
