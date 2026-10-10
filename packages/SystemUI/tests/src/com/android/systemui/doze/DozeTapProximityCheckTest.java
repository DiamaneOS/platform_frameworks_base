/*
 * Copyright (C) 2026 The DiamaneOS Project
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

package com.android.systemui.doze;

import static com.android.systemui.doze.DozeTapProximityCheck.CURRENT_READING_AGE_MS;
import static com.android.systemui.doze.DozeTapProximityCheck.TIMEOUT_MS;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorManager;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.util.concurrency.FakeExecutor;
import com.android.systemui.util.sensors.AsyncSensorManager;
import com.android.systemui.util.time.FakeSystemClock;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class DozeTapProximityCheckTest extends SysuiTestCase {
    private static final float MAX_RANGE = 5f;

    @Mock
    private AsyncSensorManager mSensorManager;
    @Mock
    private Sensor mSensor;

    private final FakeSystemClock mClock = new FakeSystemClock();
    private final FakeExecutor mExecutor = new FakeExecutor(mClock);
    private final List<Boolean> mResults = new ArrayList<>();
    private DozeTapProximityCheck mCheck;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        when(mSensor.getMaximumRange()).thenReturn(MAX_RANGE);
        when(mSensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY, false)).thenReturn(mSensor);
        mCheck = new DozeTapProximityCheck(mSensorManager, mExecutor, mClock);
    }

    @Test
    public void setListening_usesTheNonWakeUpSensor() {
        mCheck.setListening(true);
        mCheck.setListening(true);

        verify(mSensorManager).registerListener(mCheck, mSensor,
                SensorManager.SENSOR_DELAY_GAME);
        verify(mSensorManager, never()).getDefaultSensor(Sensor.TYPE_PROXIMITY, true);
        verify(mSensorManager, never()).getDefaultSensor(Sensor.TYPE_PROXIMITY);

        mCheck.setListening(false);

        verify(mSensorManager).unregisterListener(mCheck);
    }

    @Test
    public void check_notListening_isNotKnown() {
        mCheck.check(mResults::add);

        assertEquals(1, mResults.size());
        assertNull(mResults.get(0));
    }

    @Test
    public void check_noNonWakeUpSensor_isNotKnown() {
        when(mSensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY, false)).thenReturn(null);
        mCheck = new DozeTapProximityCheck(mSensorManager, mExecutor, mClock);
        mCheck.setListening(true);

        mCheck.check(mResults::add);

        verify(mSensorManager, never()).registerListener(any(), any(Sensor.class), anyInt());
        assertEquals(1, mResults.size());
        assertNull(mResults.get(0));
    }

    @Test
    public void check_currentReading_answersAtOnce() {
        mCheck.setListening(true);
        sendReading(true /* near */, 0 /* ageMs */);
        mClock.advanceTime(CURRENT_READING_AGE_MS);

        mCheck.check(mResults::add);

        assertEquals(List.of(true), mResults);

        sendReading(false /* near */, 0 /* ageMs */);
        mCheck.check(mResults::add);

        assertEquals(List.of(true, false), mResults);
        assertEquals(0, mExecutor.numPending());
    }

    @Test
    public void check_oldReading_waitsForACurrentOne() {
        // GIVEN the last reading arrived before the device slept
        mCheck.setListening(true);
        sendReading(false /* near */, 0 /* ageMs */);
        mClock.advanceTime(0 /* awakeMillis */, 60_000 /* sleepMillis */);

        // WHEN a tap asks, and a second tap right after it
        mCheck.check(mResults::add);
        mCheck.check(mResults::add);

        // THEN it waits
        assertEquals(0, mResults.size());

        // WHEN a reading from before the tap arrives late
        mClock.advanceTime(20);
        sendReading(false /* near */, 5_000 /* ageMs */);

        // THEN it keeps waiting
        assertEquals(0, mResults.size());

        // WHEN a current reading arrives
        mClock.advanceTime(80);
        sendReading(true /* near */, 10 /* ageMs */);

        // THEN both get it, and the timeout is gone
        assertEquals(List.of(true, true), mResults);
        assertEquals(0, mExecutor.numPending());
    }

    @Test
    public void check_noReadingInTime_isNotKnown() {
        mCheck.setListening(true);

        mCheck.check(mResults::add);
        mClock.advanceTime(TIMEOUT_MS - 1);
        mExecutor.runAllReady();

        assertEquals(0, mResults.size());

        mClock.advanceTime(1);
        mExecutor.runAllReady();

        assertEquals(1, mResults.size());
        assertNull(mResults.get(0));

        // A later reading answers the next check, not the one that timed out
        sendReading(true /* near */, 0 /* ageMs */);
        assertEquals(1, mResults.size());
        mCheck.check(mResults::add);
        assertEquals(Boolean.TRUE, mResults.get(1));
    }

    @Test
    public void stopListening_answersWaitingChecksAndForgetsTheReading() {
        mCheck.setListening(true);
        mCheck.check(mResults::add);

        mCheck.setListening(false);

        assertEquals(1, mResults.size());
        assertNull(mResults.get(0));
        assertEquals(0, mExecutor.numPending());

        // A reading that still arrives after stopping, and one from before, do not count
        sendReading(true /* near */, 0 /* ageMs */);
        mCheck.setListening(true);
        mCheck.check(mResults::add);

        assertEquals(1, mResults.size());
    }

    private void sendReading(boolean near, long ageMs) {
        try {
            Constructor<SensorEvent> constructor =
                    SensorEvent.class.getDeclaredConstructor(Integer.TYPE);
            constructor.setAccessible(true);
            SensorEvent event = constructor.newInstance(1);
            event.sensor = mSensor;
            event.timestamp = mClock.elapsedRealtimeNanos() - ageMs * 1_000_000;
            event.values[0] = near ? 0f : MAX_RANGE;
            mCheck.onSensorChanged(event);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }
}
