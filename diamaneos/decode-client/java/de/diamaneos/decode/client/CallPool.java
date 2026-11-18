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

import java.util.AbstractQueue;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The threads that make calls to the decode service. Apps' calls wait in a queue served round robin across owners
 * (lanes), so a few apps with work waiting cannot make another app wait behind all of theirs: an owner that arrives
 * last is served within one round. When asked for, one more thread serves only the process's own calls (the system
 * server's, made without an owner), so app traffic can never hold every thread. Plain Java, tested on the host.
 */
final class CallPool {
    private final ThreadPoolExecutor mApps;
    private final ThreadPoolExecutor mSystem; // null without a system thread

    /**
     * {@code appThreads} threads for apps behind a fair queue of at most {@code maxQueued} calls, at most
     * {@code maxQueuedPerOwner} of one owner, and {@code systemThreads} (0 or more) for the process's own calls.
     */
    CallPool(int appThreads, int systemThreads, int maxQueued, int maxQueuedPerOwner) {
        mApps = new ThreadPoolExecutor(appThreads, appThreads, 30, TimeUnit.SECONDS,
                new FairQueue(maxQueued, maxQueuedPerOwner), new ThreadPoolExecutor.AbortPolicy());
        mApps.allowCoreThreadTimeOut(true);
        if (systemThreads > 0) {
            mSystem = new ThreadPoolExecutor(systemThreads, systemThreads, 30, TimeUnit.SECONDS,
                    new LinkedBlockingQueue<>(maxQueued), new ThreadPoolExecutor.AbortPolicy());
            mSystem.allowCoreThreadTimeOut(true);
        } else {
            mSystem = null;
        }
    }

    /** Whether this pool has threads for the process's own calls. */
    boolean hasSystemThreads() {
        return mSystem != null;
    }

    /**
     * Runs {@code callable} for {@code lane}: on the system thread when {@code system} and the pool has one, else in
     * the apps' fair queue. Throws RejectedExecutionException when the queue (or the lane's share of it) is full.
     */
    <T> Future<T> submit(String lane, boolean system, Callable<T> callable) {
        final OwnedTask<T> task = new OwnedTask<>(lane == null ? "" : lane, callable);
        (system && mSystem != null ? mSystem : mApps).execute(task);
        return task;
    }

    /** A call and the lane it is charged to. */
    static final class OwnedTask<T> extends FutureTask<T> {
        final String lane;

        OwnedTask(String lane, Callable<T> callable) {
            super(callable);
            this.lane = lane;
        }
    }

    private static String laneOf(Object task) {
        return task instanceof OwnedTask ? ((OwnedTask<?>) task).lane : "";
    }

    /**
     * A blocking queue that hands out one task per owner in turn: one deque per owner, in the order the owners first
     * had work waiting. The owner served goes to the back while it still has work.
     */
    static final class FairQueue extends AbstractQueue<Runnable> implements BlockingQueue<Runnable> {
        private final int mMax;
        private final int mMaxPerOwner;
        private final ReentrantLock mLock = new ReentrantLock();
        private final Condition mNotEmpty = mLock.newCondition();
        private final LinkedHashMap<String, ArrayDeque<Runnable>> mQueues = new LinkedHashMap<>(); // mLock
        private int mSize; // mLock

        FairQueue(int max, int maxPerOwner) {
            mMax = max;
            mMaxPerOwner = maxPerOwner;
        }

        @Override
        public boolean offer(Runnable task) {
            if (task == null) {
                throw new NullPointerException();
            }
            mLock.lock();
            try {
                final String lane = laneOf(task);
                final ArrayDeque<Runnable> own = mQueues.get(lane);
                if (mSize >= mMax || (own != null && own.size() >= mMaxPerOwner)) {
                    return false;
                }
                mQueues.computeIfAbsent(lane, k -> new ArrayDeque<>()).add(task);
                mSize++;
                mNotEmpty.signal();
                return true;
            } finally {
                mLock.unlock();
            }
        }

        @Override
        public void put(Runnable task) throws InterruptedException {
            if (!offer(task)) {
                throw new RejectedExecutionException("decode call queue full");
            }
        }

        @Override
        public boolean offer(Runnable task, long timeout, TimeUnit unit) {
            return offer(task);
        }

        // The next task in turn, or null. Under mLock.
        private Runnable next() {
            final Iterator<Map.Entry<String, ArrayDeque<Runnable>>> it = mQueues.entrySet().iterator();
            if (!it.hasNext()) {
                return null;
            }
            final Map.Entry<String, ArrayDeque<Runnable>> first = it.next();
            final Runnable task = first.getValue().poll();
            it.remove();
            if (!first.getValue().isEmpty()) {
                // Still has work: to the back of the round.
                mQueues.put(first.getKey(), first.getValue());
            }
            mSize--;
            return task;
        }

        @Override
        public Runnable poll() {
            mLock.lock();
            try {
                return next();
            } finally {
                mLock.unlock();
            }
        }

        @Override
        public Runnable take() throws InterruptedException {
            mLock.lockInterruptibly();
            try {
                while (mSize == 0) {
                    mNotEmpty.await();
                }
                return next();
            } finally {
                mLock.unlock();
            }
        }

        @Override
        public Runnable poll(long timeout, TimeUnit unit) throws InterruptedException {
            long nanos = unit.toNanos(timeout);
            mLock.lockInterruptibly();
            try {
                while (mSize == 0) {
                    if (nanos <= 0) {
                        return null;
                    }
                    nanos = mNotEmpty.awaitNanos(nanos);
                }
                return next();
            } finally {
                mLock.unlock();
            }
        }

        @Override
        public Runnable peek() {
            mLock.lock();
            try {
                final Iterator<ArrayDeque<Runnable>> it = mQueues.values().iterator();
                return it.hasNext() ? it.next().peek() : null;
            } finally {
                mLock.unlock();
            }
        }

        @Override
        public int size() {
            mLock.lock();
            try {
                return mSize;
            } finally {
                mLock.unlock();
            }
        }

        @Override
        public int remainingCapacity() {
            mLock.lock();
            try {
                return mMax - mSize;
            } finally {
                mLock.unlock();
            }
        }

        @Override
        public boolean remove(Object o) {
            mLock.lock();
            try {
                final String lane = laneOf(o);
                final ArrayDeque<Runnable> own = mQueues.get(lane);
                if (own == null || !own.remove(o)) {
                    return false;
                }
                if (own.isEmpty()) {
                    mQueues.remove(lane);
                }
                mSize--;
                return true;
            } finally {
                mLock.unlock();
            }
        }

        @Override
        public int drainTo(Collection<? super Runnable> to) {
            return drainTo(to, Integer.MAX_VALUE);
        }

        @Override
        public int drainTo(Collection<? super Runnable> to, int max) {
            mLock.lock();
            try {
                int n = 0;
                while (n < max && mSize > 0) {
                    to.add(next());
                    n++;
                }
                return n;
            } finally {
                mLock.unlock();
            }
        }

        // A snapshot, in no particular order (the executor only reads it when it shuts down).
        @Override
        public Iterator<Runnable> iterator() {
            mLock.lock();
            try {
                final List<Runnable> all = new ArrayList<>(mSize);
                for (ArrayDeque<Runnable> queue : mQueues.values()) {
                    all.addAll(queue);
                }
                return all.iterator();
            } finally {
                mLock.unlock();
            }
        }
    }
}
