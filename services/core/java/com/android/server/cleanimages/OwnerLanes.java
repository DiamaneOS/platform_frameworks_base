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

package com.android.server.cleanimages;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Opens of another app's input (a shortcut icon URI: its provider's open and the read of what it returns), one per
 * owner at a time, each on a thread of its own, waited for until a deadline. When the deadline passes, the input the
 * open holds is closed, which ends a read blocked on it. A provider that never answers its open keeps only its own
 * owner's lane, and that owner's next opens are refused at once until it answers: other owners' opens never wait for
 * it.
 */
final class OwnerLanes {
    /** An open: hands what it holds open (the descriptor it reads) to {@code holds} as soon as it has it. */
    interface Opener<T extends AutoCloseable> {
        T open(Consumer<AutoCloseable> holds) throws Exception;
    }

    private final String mThreadName;
    private final Set<String> mBusy = new HashSet<>();

    OwnerLanes(String threadName) {
        mThreadName = threadName;
    }

    /** True while an open of {@code owner} runs (it may outlive its caller's deadline). */
    synchronized boolean busy(String owner) {
        return mBusy.contains(owner);
    }

    /**
     * The result of {@code opener} on {@code owner}'s lane within {@code timeoutMs}, or null: when the lane is busy,
     * when the open failed and when the deadline passed (what it holds is then closed, and a late result too).
     */
    <T extends AutoCloseable> T open(String owner, Opener<T> opener, long timeoutMs) throws InterruptedException {
        synchronized (this) {
            if (!mBusy.add(owner)) {
                return null;
            }
        }
        final CompletableFuture<T> result = new CompletableFuture<>();
        final AtomicReference<AutoCloseable> held = new AtomicReference<>();
        final Thread thread = new Thread(() -> {
            try {
                final T value = opener.open(held::set);
                if (!result.complete(value)) {
                    closeQuietly(value); // Too late: the caller has gone.
                }
            } catch (Throwable e) {
                result.complete(null);
            } finally {
                synchronized (this) {
                    mBusy.remove(owner);
                }
            }
        }, mThreadName);
        thread.setDaemon(true);
        try {
            thread.start();
        } catch (Throwable e) {
            synchronized (this) {
                mBusy.remove(owner);
            }
            return null;
        }
        try {
            return result.get(Math.max(1, timeoutMs), TimeUnit.MILLISECONDS);
        } catch (TimeoutException | ExecutionException e) {
            if (result.complete(null)) {
                closeQuietly(held.get()); // Ends a read blocked on it.
            }
            final T late = result.getNow(null);
            if (late != null) {
                return late; // It came in just now.
            }
            return null;
        } catch (InterruptedException e) {
            if (result.complete(null)) {
                closeQuietly(held.get());
            }
            closeQuietly(result.getNow(null));
            throw e;
        }
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception e) {
            // Gone.
        }
    }
}
