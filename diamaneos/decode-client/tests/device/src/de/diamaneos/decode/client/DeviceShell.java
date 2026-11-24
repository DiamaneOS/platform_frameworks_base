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

import android.os.ParcelFileDescriptor;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

final class DeviceShell {
    private DeviceShell() {}

    static String run(String command) throws IOException {
        final ParcelFileDescriptor[] pipes = InstrumentationRegistry.getInstrumentation().getUiAutomation()
                .executeShellCommandRw("/system/bin/sh");
        try (InputStream output = new ParcelFileDescriptor.AutoCloseInputStream(pipes[0])) {
            try (OutputStream input = new ParcelFileDescriptor.AutoCloseOutputStream(pipes[1])) {
                input.write((command + "\n").getBytes(StandardCharsets.UTF_8));
            }
            return new String(output.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
