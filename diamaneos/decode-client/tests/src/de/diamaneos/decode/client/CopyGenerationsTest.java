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

public class CopyGenerationsTest {
    @Test
    public void removedDuringMake_canNeverPublishOrRestartItsRetry() {
        final CopyGenerations users = new CopyGenerations();
        final CopyGenerations.Token running = users.begin(10, 41);
        assertTrue(users.current(running));
        users.remove(10);
        assertFalse(users.current(running));
        assertNull(users.begin(10, 41));
    }

    @Test
    public void reusedNumericId_isAnotherIncarnation() {
        final CopyGenerations users = new CopyGenerations();
        final CopyGenerations.Token old = users.begin(10, 41);
        users.remove(10);
        final CopyGenerations.Token replacement = users.begin(10, 42);
        assertTrue(users.current(replacement));
        assertFalse(users.current(old));
    }
}
