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

public class CopyRequestsTest {
    @Test
    public void grantNamesOnlyTheRequestedItemAndSubscriber() {
        final CopyRequests requests = new CopyRequests();
        requests.add(10001, "key-a", new CopyRequests.Grant("/a.apk", "a", 10, 41));
        assertNotNull(requests.get(10001, "key-a"));
        assertNull(requests.get(10001, "key-b"));
        assertNull(requests.get(10002, "key-a"));
        assertTrue(requests.interested(10001, "a", 10, 41));
        assertFalse(requests.interested(10001, "b", 10, 41));
        assertFalse(requests.interested(10001, "a", 11, 41));
        assertFalse(requests.interested(10001, "a", 10, 42));
        assertFalse(requests.interested(10002, "a", 10, 41));
    }

    @Test
    public void packageChangesAndRemovalRetireGrants() {
        final CopyRequests requests = new CopyRequests();
        requests.add(10001, "work", new CopyRequests.Grant("/a.apk", "a", 10, 41));
        requests.add(10001, "personal", new CopyRequests.Grant("/a.apk", "a", 0, 0));
        requests.removeUser(10);
        assertNull(requests.get(10001, "work"));
        assertNotNull(requests.get(10001, "personal"));
        requests.clear();
        assertNull(requests.get(10001, "personal"));
    }

    @Test
    public void outstandingGrantsStayBounded() {
        final CopyRequests requests = new CopyRequests();
        for (int i = 0; i < 2000; i++) {
            requests.add(10001, "key" + i, new CopyRequests.Grant("/a.apk", "a", 10, 41));
        }
        assertNull(requests.get(10001, "key0"));
        assertNotNull(requests.get(10001, "key1999"));
    }
}
