/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.locksettings;

import static com.android.internal.widget.LockPatternUtils.StrongAuthTracker.STRONG_AUTH_NOT_REQUIRED;
import static com.android.server.locksettings.LockSettingsStrongAuth.STRONG_AUTH_TIMEOUT_ALARM_TAG;

import static junit.framework.Assert.assertNotNull;
import static junit.framework.Assert.assertTrue;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.AlarmManager;
import android.app.admin.DevicePolicyManager;
import android.content.Context;
import android.platform.test.annotations.Presubmit;
import android.platform.test.flag.junit.SetFlagsRule;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.server.locksettings.LockSettingsStrongAuth.StrongAuthTimeoutAlarmListener;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

@SmallTest
@Presubmit
@RunWith(AndroidJUnit4.class)
public class LockSettingsStrongAuthTimeoutLimitTest {
    @Rule public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();
    @Rule public MockitoRule mockito = MockitoJUnit.rule();

    private static final int PRIMARY_USER_ID = 0;

    private LockSettingsStrongAuth mStrongAuth;
    private final int mDefaultStrongAuthFlags = STRONG_AUTH_NOT_REQUIRED;
    private final boolean mDefaultIsNonStrongBiometricAllowed = true;

    @Mock
    private Context mContext;
    @Mock
    private LockSettingsStrongAuth.Injector mInjector;
    @Mock
    private AlarmManager mAlarmManager;
    @Mock
    private DevicePolicyManager mDPM;

    @Before
    public void setUp() {
        when(mInjector.getAlarmManager(mContext)).thenReturn(mAlarmManager);
        when(mInjector.getDefaultStrongAuthFlags(mContext)).thenReturn(mDefaultStrongAuthFlags);
        when(mContext.getSystemService(Context.DEVICE_POLICY_SERVICE)).thenReturn(mDPM);

        mStrongAuth = new LockSettingsStrongAuth(mContext, mInjector);
    }

    @Test
    public void testStrongAuthTimeoutLimit_shortensPrimaryAuthTimeout() {
        final long currentTime = 1000;
        final long timeout = 5000;
        final long limit = 2000;
        when(mInjector.getElapsedRealtimeMs()).thenReturn(currentTime);
        when(mDPM.getRequiredStrongAuthTimeout(null, PRIMARY_USER_ID)).thenReturn(timeout);
        mStrongAuth.setStrongAuthTimeoutLimit(limit, PRIMARY_USER_ID);
        mStrongAuth.reportSuccessfulStrongAuthUnlock(PRIMARY_USER_ID);
        waitForIdle();

        StrongAuthTimeoutAlarmListener alarm =
                mStrongAuth.mStrongAuthTimeoutAlarmListenerForUser.get(PRIMARY_USER_ID);
        assertNotNull(alarm);
        verifyAlarm(currentTime + limit, STRONG_AUTH_TIMEOUT_ALARM_TAG, alarm);

        // Without the limit the timeout of device policy applies again.
        mStrongAuth.setStrongAuthTimeoutLimit(0, PRIMARY_USER_ID);
        mStrongAuth.refreshStrongAuthTimeout(PRIMARY_USER_ID);
        waitForIdle();
        verifyAlarm(currentTime + timeout, STRONG_AUTH_TIMEOUT_ALARM_TAG, alarm);
    }

    @Test
    public void testStrongAuthTimeoutLimit_neverLengthensPrimaryAuthTimeout() {
        final long currentTime = 1000;
        final long timeout = 2000;
        final long limit = 5000;
        when(mInjector.getElapsedRealtimeMs()).thenReturn(currentTime);
        when(mDPM.getRequiredStrongAuthTimeout(null, PRIMARY_USER_ID)).thenReturn(timeout);
        mStrongAuth.setStrongAuthTimeoutLimit(limit, PRIMARY_USER_ID);
        mStrongAuth.reportSuccessfulStrongAuthUnlock(PRIMARY_USER_ID);
        waitForIdle();

        StrongAuthTimeoutAlarmListener alarm =
                mStrongAuth.mStrongAuthTimeoutAlarmListenerForUser.get(PRIMARY_USER_ID);
        assertNotNull(alarm);
        verifyAlarm(currentTime + timeout, STRONG_AUTH_TIMEOUT_ALARM_TAG, alarm);
    }

    @Test
    public void testStrongAuthTimeoutLimit_isPerUserAndGoesWithTheUser() {
        final int otherUserId = 10;
        final long currentTime = 1000;
        final long timeout = 5000;
        final long limit = 2000;
        when(mInjector.getElapsedRealtimeMs()).thenReturn(currentTime);
        when(mDPM.getRequiredStrongAuthTimeout(null, PRIMARY_USER_ID)).thenReturn(timeout);
        when(mDPM.getRequiredStrongAuthTimeout(null, otherUserId)).thenReturn(timeout);
        mStrongAuth.setStrongAuthTimeoutLimit(limit, otherUserId);
        mStrongAuth.reportSuccessfulStrongAuthUnlock(PRIMARY_USER_ID);
        waitForIdle();

        StrongAuthTimeoutAlarmListener alarm =
                mStrongAuth.mStrongAuthTimeoutAlarmListenerForUser.get(PRIMARY_USER_ID);
        assertNotNull(alarm);
        verifyAlarm(currentTime + timeout, STRONG_AUTH_TIMEOUT_ALARM_TAG, alarm);

        mStrongAuth.removeUser(otherUserId);
        waitForIdle();
        assertTrue(mStrongAuth.mStrongAuthTimeoutLimitForUser.indexOfKey(otherUserId) < 0);
    }

    private void verifyAlarm(long when, String tag, AlarmManager.OnAlarmListener alarm) {
        verify(mAlarmManager).setExact(
                eq(AlarmManager.ELAPSED_REALTIME_WAKEUP),
                eq(when),
                eq(tag),
                eq(alarm),
                eq(mStrongAuth.mHandler));
    }

    private static void waitForIdle() {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }
}
