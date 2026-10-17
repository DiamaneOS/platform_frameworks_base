/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 The DiamaneOS Project
 */

package com.android.server;

import android.annotation.Nullable;
import android.content.Context;
import android.ext.power.BatteryChargeLimit;
import android.hardware.health.BatteryChargingPolicy;
import android.os.Handler;
import android.os.RemoteException;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.os.BackgroundThread;
import com.android.server.health.HealthServiceWrapper;

/**
 * Passes the battery charge limit setting to the health HAL as its charging policy, on devices
 * whose HAL holds the limit ({@link BatteryChargeLimit#isHealthHalLimit}).
 */
final class BatteryChargeLimitController {
    private static final String TAG = "BatteryChargeLimit";

    interface PolicySetter {
        void setChargingPolicy(int policy) throws RemoteException;
    }

    private final Context mContext;
    private final Handler mHandler;
    private final PolicySetter mSetter;

    // Accessed on mHandler only.
    private boolean mEnabled;
    private int mReportedPolicy = BatteryChargingPolicy.INVALID;

    @VisibleForTesting
    BatteryChargeLimitController(Context context, Handler handler, PolicySetter setter) {
        mContext = context;
        mHandler = handler;
        mSetter = setter;
    }

    /** Starts following the setting, or returns null when the health HAL holds no limit. */
    static @Nullable BatteryChargeLimitController maybeStart(Context context,
            HealthServiceWrapper health) {
        if (!BatteryChargeLimit.isHealthHalLimit(context)) {
            return null;
        }
        var controller = new BatteryChargeLimitController(context, BackgroundThread.getHandler(),
                health::setChargingPolicy);
        BatteryChargeLimit.getSetting().registerObserver(context, controller.mHandler,
                setting -> controller.readSetting());
        controller.mHandler.post(controller::readSetting);
        return controller;
    }

    /** Called with the charging policy of each new HealthInfo. */
    void onHealthInfo(int reportedPolicy) {
        mHandler.post(() -> onReportedPolicy(reportedPolicy));
    }

    @VisibleForTesting
    void onReportedPolicy(int reportedPolicy) {
        // A restarted HAL starts with its default policy, so set it again. A HAL that keeps
        // reporting the same policy is not asked again.
        if (reportedPolicy == mReportedPolicy) {
            return;
        }
        mReportedPolicy = reportedPolicy;
        if (reportedPolicy != desiredPolicy()) {
            apply();
        }
    }

    @VisibleForTesting
    void setEnabled(boolean enabled) {
        mEnabled = enabled;
        apply();
    }

    private void readSetting() {
        setEnabled(BatteryChargeLimit.getSetting().get(mContext));
    }

    private int desiredPolicy() {
        return mEnabled ? BatteryChargingPolicy.LONG_LIFE : BatteryChargingPolicy.DEFAULT;
    }

    private void apply() {
        int policy = desiredPolicy();
        try {
            mSetter.setChargingPolicy(policy);
        } catch (RemoteException | RuntimeException e) {
            // A HAL error must not take down system_server.
            Slog.w(TAG, "Cannot set charging policy " + policy, e);
        }
    }
}
