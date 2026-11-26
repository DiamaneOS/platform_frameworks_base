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
import org.junit.Test;
import java.io.*;

public class BoundedImageInputTest {
    @Test
    public void declaredTooLarge_readsAndWritesNothing() throws Exception {
        final ByteArrayInputStream in = new ByteArrayInputStream(new byte[33]);
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            BoundedImageInput.copy(in, out, 33, 32);
            fail("accepted over-limit input");
        } catch (BoundedImageInput.TooLarge expected) {
            assertEquals(33, in.available());
            assertEquals(0, out.size());
        }
    }

    @Test
    public void growsAfterStat_stopsAtTheRunningLimit() throws Exception {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            BoundedImageInput.copy(new ByteArrayInputStream(new byte[1000000]), out, 1, 32);
            fail("accepted input that grew");
        } catch (BoundedImageInput.TooLarge expected) {
            assertTrue(out.size() <= 32);
        }
    }

    @Test
    public void exactLimit_isKeptWhole() throws Exception {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertEquals(32, BoundedImageInput.copy(new ByteArrayInputStream(new byte[32]), out, 32, 32));
        assertEquals(32, out.size());
    }
}
