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

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Waiting for the VM per app across its notifications. */
@RunWith(JUnit4.class)
public class OwnerQuotaTest {

    @Test
    public void burstThenRefill() {
        final OwnerQuota quota = new OwnerQuota(4_000, 200);
        assertEquals(4_000, quota.available("a", 0));
        quota.spend("a", 2_000, 0);
        quota.spend("a", 1_500, 100);
        assertEquals(540, quota.available("a", 200));
        // A fifth of the time comes back: 10 s later 2 s more.
        assertEquals(2_540, quota.available("a", 10_200));
        // Never more than the burst.
        assertEquals(4_000, quota.available("a", 100_000));
    }

    @Test
    public void reservedBeforeSettledAfter() {
        final OwnerQuota quota = new OwnerQuota(4_000, 200);
        assertEquals(2_000, quota.reserve("a", 2_000, 0));
        assertEquals(2_000, quota.available("a", 0));
        // Two tasks at once cannot both take the whole bucket.
        assertEquals(2_000, quota.reserve("a", 2_000, 0));
        assertEquals(0, quota.reserve("a", 2_000, 0));
        // One took less than it reserved: the rest comes back.
        quota.settle("a", 2_000, 500, 0);
        assertEquals(1_500, quota.available("a", 0));
    }

    @Test
    public void overrunIsDebtWaitedOut() {
        final OwnerQuota quota = new OwnerQuota(4_000, 200);
        final long granted = quota.reserve("a", 2_000, 0);
        // A task that ran 9 s on its 2 s: the app owes the rest.
        quota.settle("a", granted, 9_000, 0);
        assertEquals(-5_000, quota.balance("a", 0));
        assertEquals(0, quota.available("a", 0));
        assertEquals(0, quota.reserve("a", 2_000, 0));
        // 25 s later the debt is paid off, then the time comes back.
        assertEquals(0, quota.available("a", 25_000));
        assertEquals(200, quota.available("a", 26_000));
        // The debt never goes below the floor.
        quota.spend("b", 1_000_000, 0);
        assertEquals(OwnerQuota.DEBT_FLOOR_MS, quota.balance("b", 0));
    }

    @Test
    public void frequentCallsStillRefill() {
        final OwnerQuota quota = new OwnerQuota(4_000, 200);
        quota.spend("a", 4_000, 0);
        // Asked every millisecond: each call alone buys less than a millisecond, together they buy 200.
        for (long t = 1; t <= 1_000; t++) {
            quota.available("a", t);
        }
        assertEquals(200, quota.available("a", 1_000));
    }

    @Test
    public void appsAreSeparate() {
        final OwnerQuota quota = new OwnerQuota(4_000, 200);
        quota.spend("a", 4_000, 0);
        assertEquals(0, quota.available("a", 0));
        assertEquals(4_000, quota.available("b", 0));
        assertEquals(4_000, quota.available(null, 0));
    }

    @Test
    public void manyAppsStayBounded() {
        final OwnerQuota quota = new OwnerQuota(4_000, 200);
        for (int i = 0; i < 1_000; i++) {
            quota.spend("app" + i, 10, 0);
        }
        quota.spend("x", 4_000, 0);
        assertEquals(0, quota.available("x", 0));
        assertEquals(4_000, quota.available("new", 100_000));
    }
}
