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

import de.diamaneos.decode.client.ImageBudget.Admission;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** The caps of one notification's images. */
@RunWith(JUnit4.class)
public class ImageBudgetTest {

    @Test
    public void imageCap() {
        final ImageBudget budget = new ImageBudget(1_000, 3, 1_000);
        assertEquals(Admission.NEW, budget.admit("a"));
        assertEquals(Admission.NEW, budget.admit("b"));
        assertEquals(Admission.SEEN, budget.admit("a"));
        assertEquals(Admission.NEW, budget.admit("c"));
        // Over the cap: refused, also when it comes again. Admitted images stay admitted.
        assertEquals(Admission.REFUSED, budget.admit("d"));
        assertEquals(Admission.REFUSED, budget.admit("e"));
        assertEquals(Admission.SEEN, budget.admit("b"));
        assertEquals(3, budget.images());
        assertEquals(2, budget.refused());
    }

    @Test
    public void pixelCap() {
        final ImageBudget budget = new ImageBudget(1_000, 10, 100);
        assertEquals(Admission.NEW, budget.admit("a"));
        assertTrue(budget.charge("a", 60));
        assertEquals(Admission.NEW, budget.admit("b"));
        assertFalse(budget.charge("b", 41));
        // Refused for its pixels: refused when it comes again too.
        assertEquals(Admission.REFUSED, budget.admit("b"));
        assertEquals(Admission.NEW, budget.admit("c"));
        assertTrue(budget.charge("c", 40));
        assertEquals(100, budget.pixels());
        assertFalse(budget.charge("d", -1));
    }

    @Test
    public void chargedBeforeAndRefundedAfter() {
        final ImageBudget budget = new ImageBudget(1_000, 10, 100);
        assertEquals(Admission.NEW, budget.admit("a"));
        assertTrue(budget.charge("a", 80));
        // Came back smaller: the rest is given back.
        budget.refund(50);
        assertEquals(30, budget.pixels());
        budget.refund(1_000);
        assertEquals(0, budget.pixels());
    }

    @Test
    public void failedImagesAreNotAskedForAgain() {
        final ImageBudget budget = new ImageBudget();
        assertFalse(budget.hasFailed("a"));
        budget.failed("a");
        assertTrue(budget.hasFailed("a"));
        assertFalse(budget.hasFailed("b"));
    }

    @Test
    public void spentTimeReachesTheQuota() {
        final long[] told = {0};
        final ImageBudget budget = new ImageBudget(2_000, 10, 100, ms -> told[0] += ms);
        budget.spent(300);
        budget.spent(0);
        budget.spent(-5);
        assertEquals(300, told[0]);
        assertEquals(1_700, budget.remainingMs());
    }

    @Test
    public void time() {
        final ImageBudget budget = new ImageBudget(2_000, 10, 100);
        assertEquals(2_000, budget.remainingMs());
        budget.spent(1_500);
        budget.spent(-5);
        assertEquals(500, budget.remainingMs());
        budget.spent(900);
        assertEquals(0, budget.remainingMs());
        assertEquals(0, new ImageBudget(-1, 1, 1).remainingMs());
    }

    @Test
    public void defaults() {
        final ImageBudget budget = new ImageBudget();
        assertEquals(ImageBudget.TIME_MS, budget.remainingMs());
        for (int i = 0; i < ImageBudget.MAX_IMAGES; i++) {
            assertEquals(Admission.NEW, budget.admit("i" + i));
        }
        assertEquals(Admission.REFUSED, budget.admit("over"));
    }
}
