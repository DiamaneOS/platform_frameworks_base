/*
 * Copyright (C) 2026 The DiamaneOS Project
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

package com.android.server.am;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads enabled kernel consoles without assuming a particular boot argument. */
final class SerialConsoleState {
    // Kernel flags have ordered slots: enabled, preferred, boot, print-buffer,
    // braille and anytime. Newer kernels add one for nbcon. Any other format is
    // an error, so that the caller falls back to the boot arguments.
    private static final Pattern ENTRY = Pattern.compile(
            "(\\S+)\\s+[R-][W-][U-]\\s+\\(([E ][C ][B ][p ][b ][a ])[N ]?\\)"
                    + "(?:\\s+\\d+:\\d+)?");
    private static final Pattern LOCAL_SINK = Pattern.compile(
            "ramoops-?\\d+|ttynull-?\\d+|tty\\d+");

    private SerialConsoleState() {}

    static boolean hasEnabledSerialConsole(Reader input) throws IOException {
        BufferedReader reader = new BufferedReader(input);
        boolean found = false;
        for (String line; (line = reader.readLine()) != null;) {
            Matcher entry = ENTRY.matcher(line);
            if (!entry.matches()) {
                throw new IOException("Invalid kernel console entry");
            }
            found = true;
            // E is CON_ENABLED. Null, persistent-memory and display consoles do
            // not expose a serial terminal. Any other enabled console counts.
            if (entry.group(2).indexOf('E') >= 0
                    && !LOCAL_SINK.matcher(entry.group(1)).matches()) {
                return true;
            }
        }
        if (!found) {
            throw new IOException("Missing kernel console entries");
        }
        return false;
    }
}
