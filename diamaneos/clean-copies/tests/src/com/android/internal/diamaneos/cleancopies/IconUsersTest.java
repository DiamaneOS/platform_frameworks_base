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


package com.android.internal.diamaneos.cleancopies;

import static org.junit.Assert.*;
import org.junit.Test;
import java.util.HashMap;
import java.util.Map;

public class IconUsersTest {
    @Test
    public void sameUserIdWithAnotherSerialNeverHitsTheOldCacheOrRefusal() {
        final IconUsers old = new IconUsers(10, 0, u -> u == 10 ? 41 : 0);
        final IconUsers next = new IconUsers(10, 0, u -> u == 10 ? 42 : 0);
        assertNotEquals(old.key(), next.key());
    }

    @Test
    public void resolverIncarnationAlsoPartitionsUriReadback() {
        final IconUsers old = new IconUsers(10, 11, u -> u == 10 ? 41 : 51);
        final IconUsers next = new IconUsers(10, 11, u -> u == 10 ? 41 : 52);
        assertNotEquals(old.key(), next.key());
    }

    @Test
    public void removalOrReuseRetiresAPendingSourceAndResolver() {
        final Map<Integer, Long> serials = new HashMap<>();
        serials.put(10, 41L);
        serials.put(11, 51L);
        final IconUsers users = new IconUsers(10, 11, u -> serials.getOrDefault(u, -1L));
        assertTrue(users.current(u -> serials.getOrDefault(u, -1L)));
        serials.put(10, -1L);
        assertFalse(users.current(u -> serials.getOrDefault(u, -1L)));
        serials.put(10, 42L);
        assertFalse(users.current(u -> serials.getOrDefault(u, -1L)));
        serials.put(10, 41L);
        serials.put(11, 52L);
        assertFalse(users.current(u -> serials.getOrDefault(u, -1L)));
    }
}
