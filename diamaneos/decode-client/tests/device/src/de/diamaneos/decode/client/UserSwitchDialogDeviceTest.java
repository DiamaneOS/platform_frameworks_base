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

import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.SystemClock;
import android.os.UserManager;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The user switching dialog shows the icon of the user switched to, decoded in the VM by the system server: the decode
 * service gets it as that user's image (shared, the system server's own work), not as user 0's. A secondary user with
 * an icon is made for the test, switched to and back, and removed. Reads the service's per-request DecoderUsers lines
 * (debuggable builds).
 */
@RunWith(AndroidJUnit4.class)
public class UserSwitchDialogDeviceTest {
    private static final String TAG = "UserSwitchDialogTest";

    @Test
    public void theDialogsIconIsTheTargetUsersImage() throws Exception {
        final String made = OwnerDeviceTest.shell("pm create-user owner-dialog");
        final Matcher m = Pattern.compile("created user id (\\d+)").matcher(made);
        assertTrue("no user made: " + made, m.find());
        final int user = Integer.parseInt(m.group(1));
        final double since = System.currentTimeMillis() / 1000.0;
        try {
            final Bitmap icon = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888);
            icon.eraseColor(Color.rgb(40, 120, (int) (SystemClock.uptimeMillis() % 200)));
            InstrumentationRegistry.getInstrumentation().getTargetContext().getSystemService(UserManager.class)
                    .setUserIcon(user, icon);
            Log.i(TAG, "switch to " + user + ": " + OwnerDeviceTest.shell("am switch-user -w " + user).trim());
            SystemClock.sleep(4_000);
            Log.i(TAG, "switch back: " + OwnerDeviceTest.shell("am switch-user -w 0").trim());
            SystemClock.sleep(3_000);
        } finally {
            OwnerDeviceTest.shell("am switch-user -w 0");
            Log.i(TAG, "user " + user + " removed: " + OwnerDeviceTest.shell("pm remove-user " + user).trim());
        }
        final String log = OwnerDeviceTest.shell(String.format(java.util.Locale.ROOT,
                "logcat -d -v epoch -T %.3f -s DecoderUsers:I DecodeGate:W VmDecodeGate:W", since));
        final Pattern dialog = Pattern.compile("request of user " + user
                + " shared (in VM start \\d+|not sent), .*, lane " + "\\?gate:uid1000");
        assertTrue("the dialog's icon reached the service as user " + user + "'s image: " + log,
                dialog.matcher(log).find());
    }
}
