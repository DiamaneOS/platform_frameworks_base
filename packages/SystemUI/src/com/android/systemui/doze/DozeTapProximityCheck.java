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
 * A tap turns the non-wake-up proximity sensor on, waits for its first current reading and
 * turns it off again. The sensor stays off while the device dozes untouched: sampling it all
 * the time keeps the sensor hub and the SoC out of their deepest sleep. A non-wake-up sensor
 * cannot wake the device or keep it awake, and it can give its first reading much sooner than
 * the wake-up one that ProximityCheck uses. An on-change sensor reports its state when it is
 * turned on.
 */
public class DozeTapProximityCheck implements SensorEventListener {
    /** A reading at most this old is current: nothing covers or clears the sensor faster. */
    @VisibleForTesting
    static final long CURRENT_READING_AGE_MS = 300;
    /**
     * How long a tap waits for a current reading after turning the sensor on. A first reading
     * can take more than 100 ms.
     */
    @VisibleForTesting
    static final long TIMEOUT_MS = 300;

    private static final long NANOS_PER_MS = 1_000_000;

    private final AsyncSensorManager mSensorManager;
    private final DelayableExecutor mMainExecutor;
    private final SystemClock mSystemClock;
    @Nullable
    private final Sensor mSensor;

    private final List<Consumer<Boolean>> mCallbacks = new ArrayList<>();
    private boolean mEnabled;
    private boolean mRegistered;
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
     * Whether taps may check, which follows the tap sensors. Turning it off answers a waiting
     * check with "not known", turns the sensor off and forgets the last reading.
     */
    public void setEnabled(boolean enabled) {
        if (mEnabled == enabled) {
            return;
        }
        mEnabled = enabled;
        if (!enabled) {
            mHasReading = false;
            finish(null);
        }
    }

    /**
     * Calls back with true if the sensor is covered, false if it is clear, or null if that is
     * not known: at once where a current reading is known, otherwise with the first current
     * reading after turning the sensor on, or after {@link #TIMEOUT_MS}. The sensor goes off
     * again with the answer. The caller keeps the device awake until then.
     */
    public void check(Consumer<Boolean> callback) {
        if (mSensor == null || !mEnabled) {
            callback.accept(null);
            return;
        }
        if (!mCallbacks.isEmpty()) {
            mCallbacks.add(callback);
            return;
        }
        final long now = mSystemClock.elapsedRealtimeNanos();
        if (hasCurrentReading(now)) {
            callback.accept(mNear);
            return;
        }
        mCallbacks.add(callback);
        mCheckStartNanos = now;
        mCancelTimeout = mMainExecutor.executeDelayed(() -> finish(null), TIMEOUT_MS);
        mRegistered = true;
        // Each reading as it is taken, not in batches.
        mSensorManager.registerListener(this, mSensor, SensorManager.SENSOR_DELAY_GAME);
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        // Turning the sensor off takes effect later, so readings can still arrive after it.
        if (!mRegistered) {
            return;
        }
        mNear = event.values[0] < mSensor.getMaximumRange();
        mReadingTimeNanos = event.timestamp;
        mHasReading = true;
        // The first reading can be one from before the tap that the sensor service kept or
        // held back while the device slept: only a current one answers.
        if (hasCurrentReading(mCheckStartNanos)) {
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
        if (mRegistered) {
            mRegistered = false;
            mSensorManager.unregisterListener(this);
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
                + ", enabled=" + mEnabled
                + ", registered=" + mRegistered
                + ", near=" + (mHasReading ? mNear : null)
                + ", readingAgeMs=" + (mHasReading
                        ? (mSystemClock.elapsedRealtimeNanos() - mReadingTimeNanos) / NANOS_PER_MS
                        : null)
                + "}");
    }
}
