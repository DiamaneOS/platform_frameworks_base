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
import static org.junit.Assert.fail;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

public class CallPoolTest {
    private static Runnable task(String lane, List<String> log) {
        return new CallPool.OwnedTask<Void>(lane, () -> {
            log.add(lane);
            return null;
        });
    }

    @Test
    public void fairQueue_anOwnerArrivingLast_isServedWithinOneRound() throws Exception {
        final CallPool.FairQueue queue = new CallPool.FairQueue(64, 4);
        final List<String> unused = new ArrayList<>();
        for (String owner : new String[] {"a", "b", "c"}) {
            assertTrue(queue.offer(task(owner, unused)));
            assertTrue(queue.offer(task(owner, unused)));
        }
        assertTrue(queue.offer(task("d", unused)));
        final List<String> order = new ArrayList<>();
        while (!queue.isEmpty()) {
            order.add(((CallPool.OwnedTask<?>) queue.take()).lane);
        }
        assertEquals(List.of("a", "b", "c", "d", "a", "b", "c"), order);
    }

    @Test
    public void fairQueue_capsTheTotalAndEachOwner() {
        final CallPool.FairQueue queue = new CallPool.FairQueue(5, 2);
        final List<String> unused = new ArrayList<>();
        assertTrue(queue.offer(task("a", unused)));
        assertTrue(queue.offer(task("a", unused)));
        assertFalse("past the owner's share", queue.offer(task("a", unused)));
        assertTrue(queue.offer(task("b", unused)));
        assertTrue(queue.offer(task("c", unused)));
        assertTrue(queue.offer(task("d", unused)));
        assertEquals(0, queue.remainingCapacity());
        assertFalse("past the total", queue.offer(task("e", unused)));
        // A removed task frees its place.
        final Runnable later = queue.peek();
        assertTrue(queue.remove(later));
        assertEquals(4, queue.size());
        assertTrue(queue.offer(task("e", unused)));
    }

    @Test
    public void pool_servesOwnersInTurn() throws Exception {
        final CallPool pool = new CallPool(1, 0, 64, 4);
        final CountDownLatch release = new CountDownLatch(1);
        final List<String> order = Collections.synchronizedList(new ArrayList<>());
        pool.submit("busy", false, () -> {
            release.await(5, TimeUnit.SECONDS);
            return null;
        });
        final List<Future<?>> futures = new ArrayList<>();
        for (String owner : new String[] {"x", "x", "x", "y"}) {
            futures.add(pool.submit(owner, false, () -> {
                order.add(owner);
                return null;
            }));
        }
        release.countDown();
        for (Future<?> f : futures) {
            f.get(5, TimeUnit.SECONDS);
        }
        assertEquals(List.of("x", "y", "x", "x"), order);
    }

    @Test
    public void pool_aSystemCallIsServedWhileEveryAppThreadIsBusy() throws Exception {
        final CallPool pool = new CallPool(3, 1, 64, 4);
        assertTrue(pool.hasSystemThreads());
        final CountDownLatch release = new CountDownLatch(1);
        try {
            for (int i = 0; i < 3; i++) {
                pool.submit("app" + i, false, () -> {
                    release.await(10, TimeUnit.SECONDS);
                    return null;
                });
            }
            // More app work waiting in the queue.
            for (int i = 0; i < 6; i++) {
                pool.submit("app" + i, false, () -> null);
            }
            final Future<String> system = pool.submit("", true, () -> "done");
            assertEquals("done", system.get(2, TimeUnit.SECONDS));
        } finally {
            release.countDown();
        }
    }

    @Test
    public void pool_fullQueue_refuses() {
        final CallPool pool = new CallPool(1, 0, 2, 2);
        assertFalse(pool.hasSystemThreads());
        final CountDownLatch release = new CountDownLatch(1);
        try {
            pool.submit("a", true, () -> {
                release.await(5, TimeUnit.SECONDS);
                return null;
            });
            pool.submit("b", false, () -> null);
            pool.submit("c", false, () -> null);
            try {
                pool.submit("d", false, () -> null);
                fail("a full queue takes no more calls");
            } catch (RejectedExecutionException expected) {
                // Refused.
            }
        } finally {
            release.countDown();
        }
    }
}
