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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.io.StringReader;

import org.junit.Test;

public class SerialConsoleStateTest {
    @Test
    public void memoryAndNullConsolesDoNotExposeSerial() throws IOException {
        assertFalse(enabled("ramoops-1 -W- (EC p a)\n"
                + "ttynull0 --- (E     ) 235:0\n"));
    }

    @Test
    public void displayConsoleIsLocal() throws IOException {
        assertFalse(enabled("tty0 -WU (EC p a) 4:1\n"));
    }

    @Test
    public void enabledUartStillWarnsWithNullConsole() throws IOException {
        assertTrue(enabled("ttynull0 --- (E     ) 235:0\n"
                + "ttyMSM0 -W- (E  p a) 246:0\n"));
    }

    @Test
    public void enabledBootConsoleWarns() throws IOException {
        assertTrue(enabled("ttyS0 -W- (E B   ) 4:64\n"));
    }

    @Test
    public void disabledUartDoesNotWarn() throws IOException {
        assertFalse(enabled("ttyS0 -W- (   p a) 4:64\n"
                + "ttynull0 --- (E     ) 235:0\n"));
    }

    @Test
    public void unknownEnabledConsoleWarns() throws IOException {
        assertTrue(enabled("hvc0 -W- (EC p a) 229:0\n"));
    }

    @Test
    public void similarNamesCannotSuppressWarning() throws IOException {
        assertTrue(enabled("ttynull_external0 -W- (EC p a) 235:0\n"));
        assertTrue(enabled("ramoops_external0 -W- (EC p a)\n"));
    }

    @Test
    public void malformedOrEmptyStateRequiresFallback() {
        assertRejected("");
        assertRejected("ttyS0 -W- E 4:64\n");
        assertRejected("ttyS0 -W- (X) 4:64\n");
        assertRejected("ttyS0 -W- (      X) 4:64\n");
        assertRejected("ttynull0 --- (E     ) 235:0\nbad\n");
    }

    private static boolean enabled(String state) throws IOException {
        return SerialConsoleState.hasEnabledSerialConsole(new StringReader(state));
    }

    private static void assertRejected(String state) {
        try {
            enabled(state);
            fail("Invalid kernel console state must require the fallback");
        } catch (IOException expected) {}
    }
}
