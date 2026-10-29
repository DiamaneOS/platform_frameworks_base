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

import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;

import androidx.annotation.Nullable;

import com.android.internal.annotations.VisibleForTesting;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.util.concurrency.DelayableExecutor;
import com.android.systemui.util.sensors.AsyncSensorManager;
import com.android.systemui.util.time.SystemClock;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import javax.inject.Inject;

/**
 * Tells {@link DozeTriggers} whether the proximity sensor is covered when a tap arrives, for
 * devices whose tap sensors do not reject pocket touches themselves.
 *
 * It listens to the non-wake-up proximity sensor five times a second while the tap sensors
 * listen. Turning the sensor on only when a tap arrives is too slow, since its first reading
 * can take longer than a tap may wait. A non-wake-up sensor cannot wake the device or keep it
 * awake. Its readings can stop or be held back while the device sleeps, so a tap without a
 * current reading waits a moment for the next one.
 */
public class DozeTapProximityCheck implements SensorEventListener {
    /** Five readings a second: a low rate keeps the sensor hub and the SoC asleep longer. */
    @VisibleForTesting
    static final int SAMPLING_PERIOD_US = 200_000;
    /**
     * A reading at most this old is current: nothing covers or clears the sensor faster. One
     * sampling period and a margin for the time a tap takes to arrive.
     */
    @VisibleForTesting
    static final long CURRENT_READING_AGE_MS = 300;
    /** How long a tap waits for a current reading: two sampling periods. */
    @VisibleForTesting
    static final long TIMEOUT_MS = 400;

    private static final long NANOS_PER_MS = 1_000_000;

    private final AsyncSensorManager mSensorManager;
    private final DelayableExecutor mMainExecutor;
    private final SystemClock mSystemClock;
    @Nullable
    private final Sensor mSensor;

    private final List<Consumer<Boolean>> mCallbacks = new ArrayList<>();
    private boolean mListening;
    private boolean mHasReading;
    private boolean mNear;
    private long mReadingTimeNanos;
    private long mCheckStartNanos;
    @Nullable
    private Runnable mCancelTimeout;

    @Inject
    public DozeTapProximityCheck(AsyncSensorManager sensorManager,
            @Main DelayableExecutor mainExecutor, SystemClock systemClock) {
        mSensorManager = sensorManager;
        mMainExecutor = mainExecutor;
        mSystemClock = systemClock;
        mSensor = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY, false /* wakeUp */);
    }

    /**
     * Listens to the sensor while the tap sensors listen. Stopping answers the waiting checks
     * with "not known" and forgets the last reading.
     */
    public void setListening(boolean listening) {
        if (mSensor == null || mListening == listening) {
            return;
        }
        mListening = listening;
        if (listening) {
            // Each reading as it is taken, not in batches.
            mSensorManager.registerListener(this, mSensor, SAMPLING_PERIOD_US,
                    0 /* maxReportLatencyUs */);
        } else {
            mSensorManager.unregisterListener(this);
            mHasReading = false;
            finish(null);
        }
    }

    /**
     * Calls back with true if the sensor is covered, false if it is clear, or null if there is
     * no current reading: at once where there is one, otherwise with the next current reading
     * or after {@link #TIMEOUT_MS}. The caller keeps the device awake until then.
     */
    public void check(Consumer<Boolean> callback) {
        if (!mListening) {
            callback.accept(null);
            return;
        }
        final long now = mSystemClock.elapsedRealtimeNanos();
        if (mCallbacks.isEmpty()) {
            if (hasCurrentReading(now)) {
                callback.accept(mNear);
                return;
            }
            mCheckStartNanos = now;
            mCancelTimeout = mMainExecutor.executeDelayed(() -> finish(null), TIMEOUT_MS);
        }
        mCallbacks.add(callback);
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (!mListening) {
            return;
        }
        mNear = event.values[0] < mSensor.getMaximumRange();
        mReadingTimeNanos = event.timestamp;
        mHasReading = true;
        // Readings held back while the device slept can still arrive after the tap: only one
        // that is current for the waiting check answers it.
        if (!mCallbacks.isEmpty() && hasCurrentReading(mCheckStartNanos)) {
            finish(mNear);
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    private boolean hasCurrentReading(long nowNanos) {
        return mHasReading
                && mReadingTimeNanos >= nowNanos - CURRENT_READING_AGE_MS * NANOS_PER_MS;
    }

    private void finish(@Nullable Boolean near) {
        if (mCancelTimeout != null) {
            mCancelTimeout.run();
            mCancelTimeout = null;
        }
        if (mCallbacks.isEmpty()) {
            return;
        }
        final List<Consumer<Boolean>> callbacks = new ArrayList<>(mCallbacks);
        mCallbacks.clear();
        callbacks.forEach(callback -> callback.accept(near));
    }

    /** Dump current state */
    public void dump(PrintWriter pw) {
        pw.println("TapProximityCheck: {sensor=" + (mSensor != null ? mSensor.getName() : null)
                + ", listening=" + mListening
                + ", near=" + (mHasReading ? mNear : null)
                + ", readingAgeMs=" + (mHasReading
                        ? (mSystemClock.elapsedRealtimeNanos() - mReadingTimeNanos) / NANOS_PER_MS
                        : null)
                + "}");
    }
}
