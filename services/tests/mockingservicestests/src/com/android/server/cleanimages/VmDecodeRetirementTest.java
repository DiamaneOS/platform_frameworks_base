/* Copyright (C) 2026 DiamaneOS */
package com.android.server.cleanimages;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.graphics.DecodeGate;
import android.os.Bundle;
import android.os.IRemoteCallback;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;

import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Controlled retirement in a private provider instance, with the real Android timer and executor. */
@RunWith(AndroidJUnit4.class)
public class VmDecodeRetirementTest {
    private static final Class<?> TASK = taskClass();

    private static Class<?> taskClass() {
        for (Class<?> type : VmDecodeProvider.class.getDeclaredClasses()) {
            if ("Task".equals(type.getSimpleName())) return type;
        }
        throw new AssertionError("provider task class");
    }

    private static Field field(String name) throws Exception {
        final Field field = VmDecodeProvider.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static ThreadPoolExecutor workers(VmDecodeProvider provider) throws Exception {
        return (ThreadPoolExecutor) field("mWorkers").get(provider);
    }

    private static Object task(String app, ParcelFileDescriptor fd, long end, Runnable work,
            IRemoteCallback callback) throws Exception {
        final Constructor<?> constructor;
        try {
            constructor = TASK.getDeclaredConstructor(String.class, String.class, ParcelFileDescriptor.class,
                    long.class, Runnable.class, IRemoteCallback.class);
        } catch (NoSuchMethodException before) {
            final Constructor<?> old = TASK.getDeclaredConstructor(String.class, String.class,
                    ParcelFileDescriptor.class, long.class, Runnable.class);
            old.setAccessible(true);
            return old.newInstance(app, "fixture", fd, end, work);
        }
        constructor.setAccessible(true);
        return constructor.newInstance(app, "fixture", fd, end, work, callback);
    }

    private static ParcelFileDescriptor descriptor() throws Exception {
        final ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
        pipe[1].close();
        return pipe[0];
    }

    private static void enqueue(VmDecodeProvider provider, Object task) throws Exception {
        final Method enqueue = VmDecodeProvider.class.getDeclaredMethod("enqueue", TASK, long.class);
        enqueue.setAccessible(true);
        assertNull("the task was accepted", enqueue.invoke(provider, task, 1L));
    }

    private static void assertClosed(ParcelFileDescriptor fd) throws Exception {
        try {
            Os.fstat(fd.getFileDescriptor());
            fail("retired input is still open");
        } catch (ErrnoException closed) {
            assertEquals(OsConstants.EBADF, closed.errno);
        } catch (IllegalStateException closed) {
            // ParcelFileDescriptor has already withdrawn the descriptor.
        }
    }

    private static int held(VmDecodeProvider provider) throws Exception {
        synchronized (field("mLock").get(provider)) {
            return field("mHeld").getInt(provider);
        }
    }

    private static void assertEmpty(VmDecodeProvider provider) throws Exception {
        final long end = SystemClock.uptimeMillis() + 5000;
        while (held(provider) != 0 && SystemClock.uptimeMillis() < end) SystemClock.sleep(10);
        synchronized (field("mLock").get(provider)) {
            assertEquals(0, field("mHeld").getInt(provider));
            assertEquals(0, field("mRunning").getInt(provider));
            assertEquals(0, field("mBytes").getLong(provider));
        }
    }

    private static final class Reply extends IRemoteCallback.Stub {
        final CountDownLatch received = new CountDownLatch(1);
        final AtomicInteger calls = new AtomicInteger();
        final AtomicReference<Bundle> answer = new AtomicReference<>();

        @Override
        public void sendResult(Bundle result) {
            calls.incrementAndGet();
            answer.set(result);
            received.countDown();
        }

        void assertTransient() throws Exception {
            assertTrue("terminal callback", received.await(5, TimeUnit.SECONDS));
            assertEquals(1, calls.get());
            assertNotNull(answer.get().getString(DecodeGate.EXTRA_ERROR));
            assertFalse(answer.get().getBoolean(DecodeGate.EXTRA_REFUSED));
        }
    }

    @Test
    public void queuedExpiry_deliversTransientOutcomeAndReleasesItsInput() throws Exception {
        final VmDecodeProvider provider = new VmDecodeProvider();
        final ThreadPoolExecutor workers = workers(provider);
        final CountDownLatch started = new CountDownLatch(VmDecodeProvider.THREADS);
        final CountDownLatch release = new CountDownLatch(1);
        final Reply reply = new Reply();
        try (ParcelFileDescriptor input = descriptor()) {
            try {
                for (int n = 0; n < VmDecodeProvider.THREADS; n++) {
                    final ParcelFileDescriptor seed = descriptor();
                    final Runnable work = () -> {
                        started.countDown();
                        try {
                            release.await();
                        } catch (InterruptedException stopped) {
                            Thread.currentThread().interrupt();
                        }
                    };
                    try {
                        enqueue(provider, task("seed" + n, seed, SystemClock.uptimeMillis() + 10000,
                                work, new Reply()));
                    } catch (Exception | Error failed) {
                        seed.close();
                        throw failed;
                    }
                }
                assertTrue("all worker slots occupied", started.await(5, TimeUnit.SECONDS));
                final AtomicInteger ran = new AtomicInteger();
                enqueue(provider, task("target", input, SystemClock.uptimeMillis() + 150,
                        ran::incrementAndGet, reply));
                reply.assertTransient();
                assertEquals(0, ran.get());
                assertEquals(VmDecodeProvider.THREADS, held(provider));
                assertClosed(input);
            } finally {
                release.countDown();
                workers.shutdown();
                assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
            }
            assertEmpty(provider);
        }
    }

    @Test
    public void executorRejection_deliversTransientOutcomeAndReleasesItsInput() throws Exception {
        final VmDecodeProvider provider = new VmDecodeProvider();
        final ThreadPoolExecutor workers = workers(provider);
        workers.shutdown();
        final Reply reply = new Reply();
        final AtomicInteger ran = new AtomicInteger();
        try (ParcelFileDescriptor input = descriptor()) {
            enqueue(provider, task("target", input, SystemClock.uptimeMillis() + 10000,
                    ran::incrementAndGet, reply));
            reply.assertTransient();
            assertEquals(0, ran.get());
            assertClosed(input);
            assertEmpty(provider);
        }
    }
}
