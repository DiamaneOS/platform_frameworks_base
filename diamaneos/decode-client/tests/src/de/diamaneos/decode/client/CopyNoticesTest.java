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

public class CopyNoticesTest {
    @Test
    public void anOldDeferredNoticeCannotConsumeTheNewIncarnationsNotice() {
        final CopyGenerations users = new CopyGenerations();
        final CopyNotices notices = new CopyNotices();
        final CopyGenerations.Token old = users.begin(10, 41);
        assertTrue(notices.mark("p", old));
        users.remove(10);
        notices.removeUser(10);
        final CopyGenerations.Token next = users.begin(10, 42);
        assertTrue(notices.mark("p", next));
        assertFalse(notices.take("p", old));
        assertTrue(notices.take("p", next));
    }

    @Test
    public void coalescingNeverSuppressesAnotherIncarnationBeforeCleanupArrives() {
        final CopyGenerations users = new CopyGenerations();
        final CopyNotices notices = new CopyNotices();
        final CopyGenerations.Token old = users.begin(10, 41);
        assertTrue(notices.mark("p", old));
        assertFalse(notices.mark("p", old));
        final CopyGenerations.Token next = users.begin(10, 42);
        assertTrue(notices.mark("p", next));
        assertFalse(notices.take("p", old));
        assertTrue(notices.take("p", next));
    }
}
