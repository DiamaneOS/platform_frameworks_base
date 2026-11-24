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

import static org.junit.Assert.*;

import android.os.ParcelFileDescriptor;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class DeviceShellCommandDeviceTest {
    @Test
    public void compoundCommands_useTheShellRatherThanLiteralProcessArguments() throws Exception {
        final String command = "test -f /system/framework/framework.jar && echo DEVICE-SHELL-PRESENT";
        try (ParcelFileDescriptor fd = InstrumentationRegistry.getInstrumentation().getUiAutomation()
                .executeShellCommand(command); InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(fd)) {
            assertFalse(new String(input.readAllBytes(), StandardCharsets.UTF_8).contains("DEVICE-SHELL-PRESENT"));
        }
        assertTrue(DeviceShell.run(command).contains("DEVICE-SHELL-PRESENT"));
        assertFalse(DeviceShell.run("test -e /system/framework/diamaneos-nonexistent-shell-control && echo PRESENT")
                .contains("PRESENT"));
    }
}
