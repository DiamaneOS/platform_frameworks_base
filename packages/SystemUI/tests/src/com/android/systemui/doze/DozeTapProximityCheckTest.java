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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
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
    public void enabled_idle_leavesTheSensorOff() {
        // GIVEN taps may check
        mCheck.setEnabled(true);

        // WHEN the device dozes untouched for hours
        mClock.advanceTime(0 /* awakeMillis */, 9 * 60 * 60 * 1000L /* sleepMillis */);
        mExecutor.runAllReady();

        // THEN the proximity sensor stays off
        verify(mSensorManager, never()).registerListener(any(), any(Sensor.class), anyInt());
    }

    @Test
    public void check_turnsTheNonWakeUpSensorOnUntilTheAnswer() {
        mCheck.setEnabled(true);

        // WHEN a tap asks
        mCheck.check(mResults::add);

        // THEN the non-wake-up sensor turns on and the tap waits for a reading
        verify(mSensorManager).registerListener(mCheck, mSensor,
                SensorManager.SENSOR_DELAY_GAME);
        verify(mSensorManager, never()).getDefaultSensor(Sensor.TYPE_PROXIMITY, true);
        verify(mSensorManager, never()).getDefaultSensor(Sensor.TYPE_PROXIMITY);
        verify(mSensorManager, never()).unregisterListener(any(SensorEventListener.class));
        assertEquals(0, mResults.size());

        // WHEN the first reading arrives
        mClock.advanceTime(50);
        sendReading(true /* near */, 0 /* ageMs */);

        // THEN the tap gets it, the sensor turns off and the timeout is gone
        assertEquals(List.of(true), mResults);
        verify(mSensorManager).unregisterListener(mCheck);
        assertEquals(0, mExecutor.numPending());
    }

    @Test
    public void check_notEnabled_isNotKnown() {
        mCheck.check(mResults::add);

        assertEquals(1, mResults.size());
        assertNull(mResults.get(0));
        verify(mSensorManager, never()).registerListener(any(), any(Sensor.class), anyInt());
    }

    @Test
    public void check_noNonWakeUpSensor_isNotKnown() {
        when(mSensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY, false)).thenReturn(null);
        mCheck = new DozeTapProximityCheck(mSensorManager, mExecutor, mClock);
        mCheck.setEnabled(true);

        mCheck.check(mResults::add);

        verify(mSensorManager, never()).registerListener(any(), any(Sensor.class), anyInt());
        assertEquals(1, mResults.size());
        assertNull(mResults.get(0));
    }

    @Test
    public void check_currentReading_answersAtOnce() {
        // GIVEN a tap got a reading
        mCheck.setEnabled(true);
        mCheck.check(mResults::add);
        sendReading(true /* near */, 0 /* ageMs */);

        // WHEN the next tap asks while that reading is current
        mClock.advanceTime(CURRENT_READING_AGE_MS);
        mCheck.check(mResults::add);

        // THEN it answers at once, without the sensor
        assertEquals(List.of(true, true), mResults);
        verify(mSensorManager, times(1)).registerListener(any(), any(Sensor.class), anyInt());
        assertEquals(0, mExecutor.numPending());

        // WHEN a tap asks once that reading is old
        mClock.advanceTime(1);
        mCheck.check(mResults::add);

        // THEN it turns the sensor on again and waits
        assertEquals(2, mResults.size());
        verify(mSensorManager, times(2)).registerListener(mCheck, mSensor,
                SensorManager.SENSOR_DELAY_GAME);
    }

    @Test
    public void check_oldFirstReading_waitsForACurrentOne() {
        // GIVEN the device slept a while
        mCheck.setEnabled(true);
        mClock.advanceTime(0 /* awakeMillis */, 60_000 /* sleepMillis */);

        // WHEN a tap asks, and a second tap right after it
        mCheck.check(mResults::add);
        mCheck.check(mResults::add);

        // THEN both wait on one sensor registration
        assertEquals(0, mResults.size());
        verify(mSensorManager, times(1)).registerListener(any(), any(Sensor.class), anyInt());

        // WHEN the sensor service first hands over a reading from before the tap
        mClock.advanceTime(20);
        sendReading(false /* near */, 5_000 /* ageMs */);

        // THEN they keep waiting with the sensor on
        assertEquals(0, mResults.size());
        verify(mSensorManager, never()).unregisterListener(any(SensorEventListener.class));

        // WHEN a current reading arrives
        mClock.advanceTime(80);
        sendReading(true /* near */, 10 /* ageMs */);

        // THEN both get it, the sensor turns off and the timeout is gone
        assertEquals(List.of(true, true), mResults);
        verify(mSensorManager).unregisterListener(mCheck);
        assertEquals(0, mExecutor.numPending());
    }

    @Test
    public void check_noReadingInTime_isNotKnownAndTurnsTheSensorOff() {
        mCheck.setEnabled(true);

        mCheck.check(mResults::add);
        mClock.advanceTime(TIMEOUT_MS - 1);
        mExecutor.runAllReady();

        assertEquals(0, mResults.size());
        verify(mSensorManager, never()).unregisterListener(any(SensorEventListener.class));

        mClock.advanceTime(1);
        mExecutor.runAllReady();

        assertEquals(1, mResults.size());
        assertNull(mResults.get(0));
        verify(mSensorManager).unregisterListener(mCheck);

        // A reading that still arrives after the timeout does not count for the next tap
        sendReading(true /* near */, 0 /* ageMs */);
        mCheck.check(mResults::add);

        assertEquals(1, mResults.size());
        verify(mSensorManager, times(2)).registerListener(mCheck, mSensor,
                SensorManager.SENSOR_DELAY_GAME);
    }

    @Test
    public void disable_endsAWaitingCheckAndTurnsTheSensorOff() {
        mCheck.setEnabled(true);
        mCheck.check(mResults::add);

        // WHEN the tap sensors stop listening while a tap waits
        mCheck.setEnabled(false);

        // THEN the tap gets "not known", the sensor turns off and the timeout is gone
        assertEquals(1, mResults.size());
        assertNull(mResults.get(0));
        verify(mSensorManager).unregisterListener(mCheck);
        assertEquals(0, mExecutor.numPending());

        // WHEN a reading still arrives after that
        sendReading(true /* near */, 0 /* ageMs */);

        // THEN it does not count once taps check again
        mCheck.setEnabled(true);
        mCheck.check(mResults::add);

        assertEquals(1, mResults.size());
    }

    @Test
    public void disable_forgetsTheLastReading() {
        mCheck.setEnabled(true);
        mCheck.check(mResults::add);
        sendReading(false /* near */, 0 /* ageMs */);

        mCheck.setEnabled(false);
        mCheck.setEnabled(true);
        mCheck.check(mResults::add);

        // THEN the second tap waits for a new reading
        assertEquals(List.of(false), mResults);
        verify(mSensorManager, times(2)).registerListener(mCheck, mSensor,
                SensorManager.SENSOR_DELAY_GAME);
    }

    @Test
    public void disable_idle_doesNotTouchTheSensor() {
        mCheck.setEnabled(true);
        mCheck.setEnabled(false);

        verify(mSensorManager, never()).registerListener(any(), any(Sensor.class), anyInt());
        verify(mSensorManager, never()).unregisterListener(any(SensorEventListener.class));
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
