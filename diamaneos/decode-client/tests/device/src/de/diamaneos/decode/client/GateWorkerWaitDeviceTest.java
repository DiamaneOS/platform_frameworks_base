/* Copyright (C) 2026 DiamaneOS */
package de.diamaneos.decode.client;

import static org.junit.Assert.*;
import android.graphics.DecodeGate;
import android.os.SystemClock;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** Runtime wait policy keeps critical threads short and ordinary image workers independent. */
public class GateWorkerWaitDeviceTest {
    private Field mCooldown;
    private Long mWait;
    private long mOldCooldown;
    private boolean mSaved;

    @Before public void prepare() throws Exception {
        mCooldown = DecodeGate.class.getDeclaredField("sNoWaitUntil");
        mCooldown.setAccessible(true);
        Field wait = DecodeGate.class.getDeclaredField("sWaitForTest");
        wait.setAccessible(true);
        mWait = (Long) wait.get(null);
        mOldCooldown = mCooldown.getLong(null);
        mSaved = true;
        DecodeGate.setWaitForTest(null);
        mCooldown.setLong(null, 0);
    }

    @After public void restore() throws Exception {
        if (mSaved) {
            mCooldown.setLong(null, mOldCooldown);
            DecodeGate.setWaitForTest(mWait);
        }
    }

    private static long allowed() throws Exception {
        Method method = DecodeGate.class.getDeclaredMethod("allowedWait");
        method.setAccessible(true);
        return (Long) method.invoke(null);
    }

    private static long workerBudget() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            return worker.submit((Callable<Long>) GateWorkerWaitDeviceTest::allowed).get(3, TimeUnit.SECONDS);
        } finally {
            worker.shutdownNow();
            assertTrue("policy worker did not stop", worker.awaitTermination(3, TimeUnit.SECONDS));
        }
    }

    private static long mainBudget() {
        AtomicLong result = new AtomicLong(-1);
        android.app.ActivityThread.currentActivityThread().getInstrumentation().runOnMainSync(() -> {
            try { result.set(allowed()); }
            catch (Exception error) { throw new AssertionError(error); }
        });
        return result.get();
    }

    @Test public void ordinaryWorkerAllowsCompleteImageBudget() throws Exception {
        assertEquals("ordinary worker complete-image budget", 15000, workerBudget());
    }

    @Test public void ordinaryWorkerIgnoresCriticalCooldown() throws Exception {
        mCooldown.setLong(null, SystemClock.uptimeMillis() + 5000);
        assertEquals("ordinary worker shares another timeout", 15000, workerBudget());
    }

    @Test public void mainThreadKeepsItsShortBudget() {
        assertEquals(200, mainBudget());
    }

    @Test public void mainThreadRetainsCriticalCooldown() throws Exception {
        mCooldown.setLong(null, SystemClock.uptimeMillis() + 5000);
        assertEquals(0, mainBudget());
    }
}
