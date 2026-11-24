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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Pixels each app holds across its notifications. */
@RunWith(JUnit4.class)
public class OwnerPixelsTest {

    @Test
    public void capPerApp() {
        final OwnerPixels pool = new OwnerPixels(100);
        final Object a = new Object();
        final Object b = new Object();
        assertTrue(pool.add("app", a, 70));
        assertFalse(pool.add("app", b, 31));
        assertTrue(pool.add("app", b, 30));
        assertEquals(100, pool.held("app"));
        // Other apps have their own cap.
        assertTrue(pool.add("other", a, 100));
        // Giving back never goes below zero.
        pool.add("app", a, -500);
        assertEquals(30, pool.held("app"));
        pool.release("app", b);
        assertEquals(0, pool.held("app"));
        // Giving back for a holder that holds nothing is ignored.
        assertTrue(pool.add("app", new Object(), -10));
        assertEquals(0, pool.held("app"));
    }

    @Test
    public void totalCap_isHard_withAReserveForNewApps() {
        // 200 in all, 40 of it kept for apps that hold nothing yet (at most half of it, 20, for each).
        final OwnerPixels pool = new OwnerPixels(100, 200, 40);
        final Object h = new Object();
        // Two apps fill what is not kept back between them.
        assertTrue(pool.add("a", h, 100));
        assertFalse(pool.add("b", h, 100));
        assertTrue(pool.add("b", h, 60));
        assertEquals(160, pool.total());
        // Nobody who holds pixels gets more: the rest is the reserve.
        assertFalse(pool.add("a", new Object(), 1));
        assertFalse(pool.add("b", new Object(), 1));
        // New apps get a part of the reserve each, never past the total.
        assertFalse(pool.add("c", h, 21));
        assertTrue(pool.add("c", h, 20));
        assertFalse(pool.add("c", h, 1));
        assertTrue(pool.add("d", h, 20));
        assertEquals(200, pool.total());
        assertFalse(pool.add("e", h, 1));
        // Giving back makes room again.
        pool.release("b", h);
        assertEquals(140, pool.total());
        assertTrue(pool.add("a", new Object(), 0));
        assertTrue(pool.add("e", h, 20));
        assertEquals(160, pool.total());
    }

    @Test
    public void totalCap_neverPassed_byManyApps() {
        final OwnerPixels pool = new OwnerPixels(100, 200);
        for (int i = 0; i < 300; i++) {
            pool.add("app" + i, new Object() {
                @Override
                protected void finalize() {
                }
            }, 1 + (i * 37) % 100);
            assertTrue("total " + pool.total(), pool.total() <= 200);
        }
    }

    @Test
    public void forgottenHoldersDropOut() throws Exception {
        final OwnerPixels pool = new OwnerPixels(100);
        Object gone = new Object();
        assertTrue(pool.add("app", gone, 100));
        gone = null;
        final long end = System.currentTimeMillis() + 10_000;
        while (pool.held("app") != 0 && System.currentTimeMillis() < end) {
            System.gc();
            Thread.sleep(10);
        }
        assertEquals(0, pool.held("app"));
        assertTrue(pool.add("app", new Object(), 100));
    }

    @Test
    public void defaults() {
        assertEquals(4 * ImageBudget.MAX_PIXELS, OwnerPixels.MAX_PIXELS);
    }
}
