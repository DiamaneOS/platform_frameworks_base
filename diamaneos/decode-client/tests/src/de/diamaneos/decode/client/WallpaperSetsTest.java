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

public class WallpaperSetsTest {
    @Test
    public void setAndClearRetireAPreparedPublication() {
        final WallpaperSets sets = new WallpaperSets();
        final long first = sets.begin("/0/home");
        assertTrue(sets.current("/0/home", first));
        final long second = sets.begin("/0/home");
        assertFalse(sets.current("/0/home", first));
        assertTrue(sets.current("/0/home", second));
        sets.begin("/0/home");
        assertFalse(sets.current("/0/home", second));
    }

    @Test
    public void anotherUserAndLockSlotHaveIndependentSets() {
        final WallpaperSets sets = new WallpaperSets();
        final long home = sets.begin("/0/home");
        sets.begin("/10/home");
        sets.begin("/0/lock");
        assertTrue(sets.current("/0/home", home));
    }
}
