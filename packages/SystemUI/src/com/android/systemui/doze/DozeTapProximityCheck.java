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
import android.hardware.SensorManager;

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
 * It listens to the non-wake-up proximity sensor while the tap sensors listen. A non-wake-up
 * sensor cannot wake the device or keep it awake, unlike the wake-up one where the sensor
 * reports every sample instead of changes only. Its readings stop while the device sleeps, so
 * a tap, which wakes the device, waits a moment for a current reading. That needs a proximity
 * sensor that keeps reporting readings several times a second.
 */
public class DozeTapProximityCheck implements SensorEventListener {
    /** A reading at most this old is current: nothing covers or clears the sensor faster. */
    @VisibleForTesting
    static final long CURRENT_READING_AGE_MS = 300;
    /** How long a tap waits for a current reading. */
    @VisibleForTesting
    static final long TIMEOUT_MS = 200;

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

    /** Listens to the sensor, or stops and answers the waiting checks with "not known". */
    public void setListening(boolean listening) {
        if (mSensor == null || mListening == listening) {
            return;
        }
        mListening = listening;
        if (listening) {
            // Not a sampling rate, the sensor has its own: this asks for each reading as it is
            // taken instead of in batches.
            mSensorManager.registerListener(this, mSensor, SensorManager.SENSOR_DELAY_GAME);
        } else {
            mSensorManager.unregisterListener(this);
            mHasReading = false;
            finish(null);
        }
    }

    /**
     * Calls back with true if the sensor is covered, false if it is clear, or null if there is
     * no current reading: at once where there is one, otherwise with the next reading or after
     * {@link #TIMEOUT_MS}. The caller keeps the device awake until then.
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
