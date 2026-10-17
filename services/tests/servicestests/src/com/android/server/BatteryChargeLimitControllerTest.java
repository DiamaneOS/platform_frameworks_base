/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 The DiamaneOS Project
 */

package com.android.server;

import static com.google.common.truth.Truth.assertThat;

import android.hardware.health.BatteryChargingPolicy;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class BatteryChargeLimitControllerTest {
    private final List<Integer> mSent = new ArrayList<>();
    private final BatteryChargeLimitController mController =
            new BatteryChargeLimitController(null, null, mSent::add);

    @Test
    public void setsThePolicyAgainAfterAHalRestart() {
        mController.setEnabled(true);
        mController.onReportedPolicy(BatteryChargingPolicy.LONG_LIFE);
        mController.onReportedPolicy(BatteryChargingPolicy.DEFAULT);

        assertThat(mSent).containsExactly(
                BatteryChargingPolicy.LONG_LIFE, BatteryChargingPolicy.LONG_LIFE);
    }

    @Test
    public void doesNotRepeatForAHalThatIgnoresThePolicy() {
        mController.setEnabled(true);
        mController.onReportedPolicy(BatteryChargingPolicy.DEFAULT);
        mController.onReportedPolicy(BatteryChargingPolicy.DEFAULT);

        assertThat(mSent).containsExactly(
                BatteryChargingPolicy.LONG_LIFE, BatteryChargingPolicy.LONG_LIFE);
    }
}
