/*
 * Copyright (C) 2017 The Android Open Source Project
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.TriggerEvent;
import android.hardware.TriggerEventListener;
import android.hardware.display.AmbientDisplayConfiguration;
import android.os.UserHandle;
import android.provider.Settings;
import android.testing.TestableLooper;
import android.testing.TestableLooper.RunWithLooper;
import android.view.Display;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.internal.logging.UiEventLogger;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.biometrics.AuthController;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.dock.DockManager;
import com.android.systemui.log.SessionTracker;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.statusbar.phone.DozeParameters;
import com.android.systemui.statusbar.policy.DevicePostureController;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.user.domain.interactor.SelectedUserInteractor;
import com.android.systemui.util.concurrency.FakeExecutor;
import com.android.systemui.util.concurrency.FakeThreadFactory;
import com.android.systemui.util.sensors.AsyncSensorManager;
import com.android.systemui.util.sensors.FakeProximitySensor;
import com.android.systemui.util.sensors.FakeSensorManager;
import com.android.systemui.util.sensors.FakeThresholdSensor;
import com.android.systemui.util.sensors.ProximityCheck;
import com.android.systemui.util.sensors.ThresholdSensorEvent;
import com.android.systemui.util.settings.FakeSettings;
import com.android.systemui.util.time.FakeSystemClock;
import com.android.systemui.util.wakelock.WakeLock;
import com.android.systemui.util.wakelock.WakeLockFake;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.lang.reflect.Constructor;
import java.util.function.Consumer;

@SmallTest
@RunWith(AndroidJUnit4.class)
@RunWithLooper(setAsMainLooper = true)
public class DozeTriggersTapProximityTest extends SysuiTestCase {

    @Mock
    private DozeMachine mMachine;
    @Mock
    private DozeHost mHost;
    @Mock
    private BroadcastDispatcher mBroadcastDispatcher;
    private final AmbientDisplayConfiguration mConfig = DozeConfigurationUtil.createMockConfig();
    @Mock
    private DockManager mDockManager;
    @Mock
    private ProximityCheck mProximityCheck;
    @Mock
    private DozeTapProximityCheck mTapProximityCheck;
    @Mock
    private DozeLog mDozeLog;
    @Mock
    private AuthController mAuthController;
    @Mock
    private UiEventLogger mUiEventLogger;
    @Mock
    private KeyguardStateController mKeyguardStateController;
    @Mock
    private DevicePostureController mDevicePostureController;
    @Mock
    private UserTracker mUserTracker;
    @Mock
    private SelectedUserInteractor mSelectedUserInteractor;
    @Mock
    private SessionTracker mSessionTracker;
    @Mock
    private AsyncSensorManager mTapProximitySensorManager;
    @Mock
    private Sensor mNonWakeUpProximity;
    @Captor
    private ArgumentCaptor<DozeHost.Callback> mHostCallbackCaptor;
    @Captor
    private ArgumentCaptor<UserTracker.Callback> mUserCallbackCaptor;

    private DozeTriggers mTriggers;
    private FakeSensorManager mSensors;
    private Sensor mTapSensor;
    private FakeProximitySensor mProximitySensor;
    private FakeSettings mSettings;
    private final FakeExecutor mExecutor = new FakeExecutor(new FakeSystemClock());
    private final FakeSystemClock mClock = new FakeSystemClock();
    private final FakeExecutor mCheckExecutor = new FakeExecutor(mClock);

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        ArgumentCaptor<Boolean> boolCaptor = ArgumentCaptor.forClass(Boolean.class);
        doAnswer(invocation ->
                when(mHost.isPulsePending()).thenReturn(boolCaptor.getValue())
        ).when(mHost).setPulsePending(boolCaptor.capture());

        setupDozeTriggers(
                mConfig,
                DozeConfigurationUtil.createMockParameters());

        doAnswer(invocation -> null).when(mHost).addCallback(mHostCallbackCaptor.capture());
    }

    private void setupDozeTriggers(
            AmbientDisplayConfiguration config,
            DozeParameters dozeParameters) throws Exception {
        setupDozeTriggers(config, dozeParameters, mTapProximityCheck);
    }

    private void setupDozeTriggers(
            AmbientDisplayConfiguration config,
            DozeParameters dozeParameters,
            DozeTapProximityCheck tapProximityCheck) throws Exception {
        mSensors = spy(new FakeSensorManager(mContext));
        mTapSensor = mSensors.getFakeTapSensor().getSensor();
        WakeLock wakeLock = new WakeLockFake();
        AsyncSensorManager asyncSensorManager =
                new AsyncSensorManager(mSensors, new FakeThreadFactory(mExecutor), null);

        FakeThresholdSensor thresholdSensor = new FakeThresholdSensor();
        thresholdSensor.setLoaded(true);
        mProximitySensor = new FakeProximitySensor(thresholdSensor,  null, mExecutor);

        mSettings = new FakeSettings();
        mTriggers = new DozeTriggers(mContext, mHost, config, dozeParameters,
                asyncSensorManager, wakeLock, mDockManager, mProximitySensor,
                mProximityCheck, tapProximityCheck, mDozeLog, mBroadcastDispatcher,
                mSettings,
                mAuthController, mUiEventLogger, mSessionTracker, mKeyguardStateController,
                mDevicePostureController, mUserTracker, mSelectedUserInteractor);
        mTriggers.setDozeMachine(mMachine);
        waitForSensorManager();
    }

    @Test
    public void test_onSensor_tap_proxCheck_near_dropsTap() throws Exception {
        setupDozeTriggersWithTapProxCheck(mConfig);
        answerTapProximityCheck(true);

        mTriggers.onSensor(DozeLog.REASON_SENSOR_TAP, 100, 200, null);

        verify(mHost, never()).onSlpiTap(anyFloat(), anyFloat());
        verify(mMachine, never()).wakeUp(anyInt());
        verify(mDozeLog).traceSensorEventDropped(DozeLog.REASON_SENSOR_TAP, "prox reporting near");
    }

    @Test
    public void test_onSensor_tap_proxCheck_far_wakesUp() throws Exception {
        setupDozeTriggersWithTapProxCheck(mConfig);
        answerTapProximityCheck(false);

        mTriggers.onSensor(DozeLog.REASON_SENSOR_TAP, 100, 200, null);

        verify(mHost).onSlpiTap(100, 200);
        verify(mMachine).wakeUp(DozeLog.REASON_SENSOR_TAP);
    }

    @Test
    public void test_onSensor_tap_proxCheck_unknown_wakesUp() throws Exception {
        // GIVEN no current proximity reading arrives in time
        setupDozeTriggersWithTapProxCheck(mConfig);
        answerTapProximityCheck(null);

        mTriggers.onSensor(DozeLog.REASON_SENSOR_TAP, 100, 200, null);

        // THEN the tap wakes the device: a sensor that stays silent must not disable the taps
        verify(mHost).onSlpiTap(100, 200);
        verify(mMachine).wakeUp(DozeLog.REASON_SENSOR_TAP);
    }

    @Test
    public void test_onSensor_tap_proxCheck_waitsForReading() throws Exception {
        setupDozeTriggersWithTapProxCheck(mConfig);
        ArgumentCaptor<Consumer<Boolean>> callback = ArgumentCaptor.forClass(Consumer.class);

        mTriggers.onSensor(DozeLog.REASON_SENSOR_TAP, 100, 200, null);

        // THEN nothing happens until the proximity check answers
        verify(mTapProximityCheck).check(callback.capture());
        verify(mMachine, never()).wakeUp(anyInt());

        callback.getValue().accept(false);

        verify(mMachine).wakeUp(DozeLog.REASON_SENSOR_TAP);
    }

    @Test
    public void test_onSensor_tap_proxCheck_doesNotUseWakeUpProximity() throws Exception {
        // The wake-up proximity sensor can keep a device awake: taps must not turn it on.
        setupDozeTriggersWithTapProxCheck(mConfig);
        answerTapProximityCheck(false);
        mProximitySensor.setLastEvent(new ThresholdSensorEvent(true, 1));

        mTriggers.onSensor(DozeLog.REASON_SENSOR_TAP, 100, 200, null);

        verify(mProximityCheck, never()).check(anyLong(), any());
        verify(mMachine).wakeUp(DozeLog.REASON_SENSOR_TAP);
    }

    @Test
    public void test_onSensor_doubleTap_proxCheck_near_dropsTap() throws Exception {
        setupDozeTriggersWithTapProxCheck(mConfig);
        answerTapProximityCheck(true);

        mTriggers.onSensor(DozeLog.REASON_SENSOR_DOUBLE_TAP, 100, 200, null);

        verify(mHost, never()).onSlpiTap(anyFloat(), anyFloat());
        verify(mMachine, never()).wakeUp(anyInt());
        verify(mDozeLog).traceSensorEventDropped(DozeLog.REASON_SENSOR_DOUBLE_TAP,
                "prox reporting near");
    }

    @Test
    public void test_onSensor_doubleTap_proxCheck_far_wakesUp() throws Exception {
        setupDozeTriggersWithTapProxCheck(mConfig);
        answerTapProximityCheck(false);

        mTriggers.onSensor(DozeLog.REASON_SENSOR_DOUBLE_TAP, 100, 200, null);

        verify(mMachine).wakeUp(DozeLog.REASON_SENSOR_DOUBLE_TAP);
    }

    @Test
    public void test_onSensor_tap_noProxCheck_wakesUpUnchecked() {
        // GIVEN a device that does not ask for the tap proximity check
        mTriggers.onSensor(DozeLog.REASON_SENSOR_TAP, 100, 200, null);

        // THEN a tap wakes the device without asking the proximity sensor
        verify(mTapProximityCheck, never()).check(any());
        verify(mMachine).wakeUp(DozeLog.REASON_SENSOR_TAP);
    }

    @Test
    public void tapSensor_proxCheck_near_dropsTapAndListensAgain() throws Exception {
        // GIVEN the device dozes with the tap sensor listening
        setupDozeTriggersWithTapProxCheck(mConfig);
        answerTapProximityCheck(true);
        mTriggers.transitionTo(DozeMachine.State.INITIALIZED, DozeMachine.State.DOZE);
        mTriggers.onScreenState(Display.STATE_OFF);
        waitForSensorManager();
        ArgumentCaptor<TriggerEventListener> tapListener =
                ArgumentCaptor.forClass(TriggerEventListener.class);
        verify(mSensors).requestTriggerSensor(tapListener.capture(), eq(mTapSensor));
        clearInvocations(mSensors);

        // WHEN the one-shot tap sensor fires while the proximity sensor is covered
        Constructor<TriggerEvent> constructor =
                TriggerEvent.class.getDeclaredConstructor(Integer.TYPE);
        constructor.setAccessible(true);
        tapListener.getValue().onTrigger(constructor.newInstance(2));
        TestableLooper.get(this).processAllMessages();
        waitForSensorManager();

        // THEN the tap is dropped and the sensor listens for the next tap
        verify(mMachine, never()).wakeUp(anyInt());
        verify(mSensors).requestTriggerSensor(any(), eq(mTapSensor));
    }

    @Test
    public void transitionToDoze_tapProxCheck_listensOnceAtFiveHertz() throws Exception {
        DozeTapProximityCheck check = setupDozeTriggersWithRealTapProxCheck();

        startDozing();
        mTriggers.onScreenState(Display.STATE_DOZE);
        mTriggers.onScreenState(Display.STATE_OFF);

        // THEN the non-wake-up proximity sensor listens once without batching, and the
        // wake-up one stays off
        verify(mTapProximitySensorManager, times(1))
                .registerListener(check, mNonWakeUpProximity, 200_000, 0);
        assertFalse(mProximitySensor.isRegistered());
    }

    @Test
    public void transitionToDoze_tapProxCheck_tapsOff_doesNotListen() throws Exception {
        setupDozeTriggersWithRealTapProxCheck();
        when(mConfig.tapGestureEnabled(anyInt())).thenReturn(false);

        startDozing();

        verify(mTapProximitySensorManager, never())
                .registerListener(any(), any(Sensor.class), anyInt(), anyInt());
    }

    @Test
    public void transitionToDoze_noTapProxCheck_doesNotListen() {
        startDozing();

        verify(mTapProximityCheck, never()).setListening(true);
        assertFalse(mProximitySensor.isRegistered());
    }

    @Test
    public void tap_tapProxCheck_coveredReading_dropsTapAtOnce() throws Exception {
        DozeTapProximityCheck check = setupDozeTriggersWithRealTapProxCheck();
        startDozing();
        sendTapProximityReading(check, true /* near */);

        mTriggers.onSensor(DozeLog.REASON_SENSOR_DOUBLE_TAP, 100, 200, null);

        verify(mMachine, never()).wakeUp(anyInt());
        verify(mDozeLog).traceSensorEventDropped(DozeLog.REASON_SENSOR_DOUBLE_TAP,
                "prox reporting near");
        assertEquals(0, mCheckExecutor.numPending());
    }

    @Test
    public void tap_tapProxCheck_clearReading_wakesUpAtOnce() throws Exception {
        DozeTapProximityCheck check = setupDozeTriggersWithRealTapProxCheck();
        startDozing();
        sendTapProximityReading(check, false /* near */);

        mTriggers.onSensor(DozeLog.REASON_SENSOR_TAP, 100, 200, null);

        verify(mMachine).wakeUp(DozeLog.REASON_SENSOR_TAP);
        assertEquals(0, mCheckExecutor.numPending());
    }

    @Test
    public void tap_tapProxCheck_oldReading_waitsForTheNextOne() throws Exception {
        // GIVEN the last reading, covered, came before the device slept
        DozeTapProximityCheck check = setupDozeTriggersWithRealTapProxCheck();
        startDozing();
        sendTapProximityReading(check, true /* near */);
        mClock.advanceTime(0 /* awakeMillis */, 60_000 /* sleepMillis */);

        // WHEN a tap arrives
        mTriggers.onSensor(DozeLog.REASON_SENSOR_TAP, 100, 200, null);

        // THEN it waits
        verify(mMachine, never()).wakeUp(anyInt());

        // WHEN the next reading says clear
        mClock.advanceTime(DozeTapProximityCheck.SAMPLING_PERIOD_US / 1000);
        sendTapProximityReading(check, false /* near */);

        // THEN the tap wakes the device
        verify(mMachine).wakeUp(DozeLog.REASON_SENSOR_TAP);
        assertEquals(0, mCheckExecutor.numPending());
    }

    @Test
    public void tap_tapProxCheck_noReading_wakesUpAtTheTimeout() throws Exception {
        DozeTapProximityCheck check = setupDozeTriggersWithRealTapProxCheck();
        startDozing();

        mTriggers.onSensor(DozeLog.REASON_SENSOR_TAP, 100, 200, null);
        mClock.advanceTime(DozeTapProximityCheck.TIMEOUT_MS);
        mCheckExecutor.runAllReady();

        // THEN a sensor that stays silent does not disable the taps, and it keeps listening
        verify(mMachine).wakeUp(DozeLog.REASON_SENSOR_TAP);
        verify(mTapProximitySensorManager, never()).unregisterListener(check);
    }

    @Test
    public void transitionToPulsing_tapProxCheck_stopsListening() throws Exception {
        DozeTapProximityCheck check = setupDozeTriggersWithRealTapProxCheck();
        startDozing();

        // WHEN the touch sensors stop listening
        mTriggers.transitionTo(DozeMachine.State.DOZE, DozeMachine.State.DOZE_REQUEST_PULSE);
        mTriggers.transitionTo(DozeMachine.State.DOZE_REQUEST_PULSE,
                DozeMachine.State.DOZE_PULSING);

        verify(mTapProximitySensorManager).unregisterListener(check);
    }

    @Test
    public void dozeEndsWhileATapWaits_stopsListening() throws Exception {
        DozeTapProximityCheck check = setupDozeTriggersWithRealTapProxCheck();
        startDozing();
        mTriggers.onSensor(DozeLog.REASON_SENSOR_TAP, 100, 200, null);

        // WHEN the screen turns on before a current reading arrives
        mTriggers.transitionTo(DozeMachine.State.DOZE, DozeMachine.State.FINISH);

        // THEN the sensor stops and the timeout is gone
        verify(mTapProximitySensorManager).unregisterListener(check);
        assertEquals(0, mCheckExecutor.numPending());
    }

    @Test
    public void destroy_tapProxCheck_stopsListening() throws Exception {
        DozeTapProximityCheck check = setupDozeTriggersWithRealTapProxCheck();
        startDozing();

        mTriggers.destroy();

        verify(mTapProximitySensorManager, times(1)).unregisterListener(check);
    }

    @Test
    public void tapToWakeSwitchedOffDuringDoze_stopsListening() throws Exception {
        // GIVEN the device dozes with the proximity sensor listening for taps
        DozeTapProximityCheck check = setupDozeTriggersWithRealTapProxCheck();
        startDozing();
        verify(mTapProximitySensorManager)
                .registerListener(check, mNonWakeUpProximity, 200_000, 0);

        // WHEN tap to wake is switched off while the device dozes
        switchTapToWake(false);

        // THEN the tap sensor and the proximity sensor stop
        verify(mSensors).cancelTriggerSensor(any(), eq(mTapSensor));
        verify(mTapProximitySensorManager).unregisterListener(check);

        // WHEN it is switched on again
        switchTapToWake(true);

        // THEN the proximity sensor listens again
        verify(mTapProximitySensorManager, times(2))
                .registerListener(check, mNonWakeUpProximity, 200_000, 0);
    }

    @Test
    public void test_onSensor_tap_pocketCheckOff_wakesUpUnchecked() throws Exception {
        // GIVEN a device that asks for the check and the user turned the pocket check off
        setupDozeTriggersWithTapProxCheck(mConfig);
        setPocketCheck(false);
        answerTapProximityCheck(true);

        mTriggers.onSensor(DozeLog.REASON_SENSOR_TAP, 100, 200, null);

        // THEN a tap wakes the device without asking any proximity sensor
        verify(mTapProximityCheck, never()).check(any());
        verify(mProximityCheck, never()).check(anyLong(), any());
        verify(mHost).onSlpiTap(100, 200);
        verify(mMachine).wakeUp(DozeLog.REASON_SENSOR_TAP);
    }

    @Test
    public void test_onSensor_doubleTap_pocketCheckOff_wakesUpUnchecked() throws Exception {
        setupDozeTriggersWithTapProxCheck(mConfig);
        setPocketCheck(false);
        answerTapProximityCheck(true);

        mTriggers.onSensor(DozeLog.REASON_SENSOR_DOUBLE_TAP, 100, 200, null);

        verify(mTapProximityCheck, never()).check(any());
        verify(mProximityCheck, never()).check(anyLong(), any());
        verify(mHost).onSlpiTap(100, 200);
        verify(mMachine).wakeUp(DozeLog.REASON_SENSOR_DOUBLE_TAP);
    }

    @Test
    public void test_onSensor_doubleTap_pocketCheckOn_near_dropsTap() throws Exception {
        setupDozeTriggersWithTapProxCheck(mConfig);
        setPocketCheck(true);
        answerTapProximityCheck(true);

        mTriggers.onSensor(DozeLog.REASON_SENSOR_DOUBLE_TAP, 100, 200, null);

        verify(mHost, never()).onSlpiTap(anyFloat(), anyFloat());
        verify(mMachine, never()).wakeUp(anyInt());
    }

    @Test
    public void test_onSensor_tap_pocketCheckOffForAnotherUser_stillChecks() throws Exception {
        // GIVEN the pocket check is off for user 10 and on for the selected user
        setupDozeTriggersWithTapProxCheck(mConfig);
        mSettings.putIntForUser(Settings.Secure.TALLY_POCKET_CHECK, 0, 10);
        answerTapProximityCheck(true);

        mTriggers.onSensor(DozeLog.REASON_SENSOR_TAP, 100, 200, null);

        // THEN the selected user's tap is checked
        verify(mTapProximityCheck).check(any());
        verify(mMachine, never()).wakeUp(anyInt());
    }

    @Test
    public void transitionToDoze_tapProxCheck_pocketCheckOff_doesNotListen() throws Exception {
        setupDozeTriggersWithRealTapProxCheck();
        setPocketCheck(false);

        startDozing();

        // THEN no proximity sensor is registered for taps
        verify(mTapProximitySensorManager, never())
                .registerListener(any(), any(Sensor.class), anyInt(), anyInt());
        assertFalse(mProximitySensor.isRegistered());
    }

    @Test
    public void tap_pocketCheckOff_coveredReading_wakesUp() throws Exception {
        DozeTapProximityCheck check = setupDozeTriggersWithRealTapProxCheck();
        setPocketCheck(false);
        startDozing();
        sendTapProximityReading(check, true /* near */);

        mTriggers.onSensor(DozeLog.REASON_SENSOR_DOUBLE_TAP, 100, 200, null);

        // THEN the tap wakes the device at once, with no wait for a reading
        verify(mMachine).wakeUp(DozeLog.REASON_SENSOR_DOUBLE_TAP);
        assertEquals(0, mCheckExecutor.numPending());
    }

    @Test
    public void pocketCheckSwitchedOff_whileDozing_stopsListening() throws Exception {
        // GIVEN the device dozes with the proximity sensor listening for taps
        DozeTapProximityCheck check = setupDozeTriggersWithRealTapProxCheck();
        mTriggers.transitionTo(DozeMachine.State.UNINITIALIZED, DozeMachine.State.INITIALIZED);
        startDozing();
        verify(mTapProximitySensorManager)
                .registerListener(check, mNonWakeUpProximity, 200_000, 0);

        // WHEN the user turns the pocket check off
        setPocketCheck(false);

        // THEN the proximity sensor stops and the tap sensor keeps listening
        verify(mTapProximitySensorManager).unregisterListener(check);
        verify(mSensors, never()).cancelTriggerSensor(any(), eq(mTapSensor));
    }

    @Test
    public void pocketCheckSwitchedOn_whileDozing_startsListening() throws Exception {
        // GIVEN the device dozes with the pocket check off
        DozeTapProximityCheck check = setupDozeTriggersWithRealTapProxCheck();
        setPocketCheck(false);
        mTriggers.transitionTo(DozeMachine.State.UNINITIALIZED, DozeMachine.State.INITIALIZED);
        startDozing();
        verify(mTapProximitySensorManager, never())
                .registerListener(any(), any(Sensor.class), anyInt(), anyInt());

        // WHEN the user turns the pocket check on
        setPocketCheck(true);

        // THEN the proximity sensor listens
        verify(mTapProximitySensorManager)
                .registerListener(check, mNonWakeUpProximity, 200_000, 0);
    }

    @Test
    public void pocketCheckChangedForAnotherUser_keepsListening() throws Exception {
        DozeTapProximityCheck check = setupDozeTriggersWithRealTapProxCheck();
        mTriggers.transitionTo(DozeMachine.State.UNINITIALIZED, DozeMachine.State.INITIALIZED);
        startDozing();

        mSettings.putIntForUser(Settings.Secure.TALLY_POCKET_CHECK, 0, 10);
        TestableLooper.get(this).processAllMessages();

        verify(mTapProximitySensorManager, never()).unregisterListener(check);
    }

    @Test
    public void userSwitch_pocketCheckOffThenOn_startsListening() throws Exception {
        // GIVEN the selected user turned the pocket check off and another user left it on
        DozeTapProximityCheck check = setupDozeTriggersWithRealTapProxCheck();
        setPocketCheck(false, UserHandle.USER_SYSTEM);
        setPocketCheck(true, 10);
        mTriggers.transitionTo(DozeMachine.State.UNINITIALIZED, DozeMachine.State.INITIALIZED);
        startDozing();
        verify(mTapProximitySensorManager, never())
                .registerListener(any(), any(Sensor.class), anyInt(), anyInt());

        // WHEN the other user becomes the selected one while the device dozes
        switchUser(10);

        // THEN the proximity sensor listens for that user's taps
        verify(mTapProximitySensorManager)
                .registerListener(check, mNonWakeUpProximity, 200_000, 0);
    }

    @Test
    public void userSwitch_pocketCheckOnThenOff_stopsListening() throws Exception {
        DozeTapProximityCheck check = setupDozeTriggersWithRealTapProxCheck();
        setPocketCheck(true, UserHandle.USER_SYSTEM);
        setPocketCheck(false, 10);
        mTriggers.transitionTo(DozeMachine.State.UNINITIALIZED, DozeMachine.State.INITIALIZED);
        startDozing();
        verify(mTapProximitySensorManager)
                .registerListener(check, mNonWakeUpProximity, 200_000, 0);

        switchUser(10);

        // THEN the sensor stops and the tap sensor keeps listening
        verify(mTapProximitySensorManager).unregisterListener(check);
        verify(mSensors, never()).cancelTriggerSensor(any(), eq(mTapSensor));
    }

    @Test
    public void pocketCheckStoredAsFalse_isOff() throws Exception {
        // BoolSetting reads "false" as off, so does the check
        setupDozeTriggersWithRealTapProxCheck();
        mSettings.putStringForUser(Settings.Secure.TALLY_POCKET_CHECK, "false",
                UserHandle.USER_SYSTEM);

        startDozing();

        verify(mTapProximitySensorManager, never())
                .registerListener(any(), any(Sensor.class), anyInt(), anyInt());
    }

    @Test
    public void parsePocketCheck_readsLikeBoolSetting() {
        assertTrue(DozeTriggers.parsePocketCheck(null));
        assertTrue(DozeTriggers.parsePocketCheck("true"));
        assertTrue(DozeTriggers.parsePocketCheck("1"));
        assertFalse(DozeTriggers.parsePocketCheck("false"));
        assertFalse(DozeTriggers.parsePocketCheck("0"));
        // Anything else is the default, on.
        assertTrue(DozeTriggers.parsePocketCheck(""));
        assertTrue(DozeTriggers.parsePocketCheck("2"));
        assertTrue(DozeTriggers.parsePocketCheck("off"));
        assertTrue(DozeTriggers.parsePocketCheck("False"));
    }

    @Test
    public void finish_stopsWatchingPocketCheck() throws Exception {
        setupDozeTriggersWithRealTapProxCheck();
        mTriggers.transitionTo(DozeMachine.State.UNINITIALIZED, DozeMachine.State.INITIALIZED);
        assertTrue(hasPocketCheckObserver());

        mTriggers.transitionTo(DozeMachine.State.DOZE, DozeMachine.State.FINISH);

        assertFalse(hasPocketCheckObserver());
    }

    private void waitForSensorManager() {
        mExecutor.runAllReady();
    }

    /** Makes the tap proximity check answer at once: covered, clear, or null for not known. */
    private void answerTapProximityCheck(Boolean near) {
        doAnswer(invocation -> {
            invocation.<Consumer<Boolean>>getArgument(0).accept(near);
            return null;
        }).when(mTapProximityCheck).check(any());
    }

    /** Sets up the triggers for a device that drops taps while its proximity sensor is covered. */
    private void setupDozeTriggersWithTapProxCheck(AmbientDisplayConfiguration config)
            throws Exception {
        setupDozeTriggers(config, createTapProxCheckParameters());
    }

    /** Sets up the same with a real tap proximity check on a non-wake-up proximity sensor. */
    private DozeTapProximityCheck setupDozeTriggersWithRealTapProxCheck() throws Exception {
        when(mNonWakeUpProximity.getMaximumRange()).thenReturn(5f);
        when(mTapProximitySensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY, false))
                .thenReturn(mNonWakeUpProximity);
        DozeTapProximityCheck check = new DozeTapProximityCheck(
                mTapProximitySensorManager, mCheckExecutor, mClock);
        setupDozeTriggers(mConfig, createTapProxCheckParameters(), check);
        return check;
    }

    private DozeParameters createTapProxCheckParameters() {
        DozeParameters dozeParameters = DozeConfigurationUtil.createMockParameters();
        when(dozeParameters.getProxCheckBeforeTap()).thenReturn(true);
        return dozeParameters;
    }

    /** Switches tap to wake as Settings does, which the doze sensors observe. */
    private void switchTapToWake(boolean on) {
        when(mConfig.tapGestureEnabled(anyInt())).thenReturn(on);
        mSettings.putIntForUser(Settings.Secure.DOZE_TAP_SCREEN_GESTURE, on ? 1 : 0,
                mSelectedUserInteractor.getSelectedUserId());
        TestableLooper.get(this).processAllMessages();
        waitForSensorManager();
    }

    /** Switches the user's pocket check and lets the observers run. */
    private void setPocketCheck(boolean on) {
        setPocketCheck(on, UserHandle.USER_SYSTEM);
    }

    private void setPocketCheck(boolean on, int userId) {
        mSettings.putIntForUser(Settings.Secure.TALLY_POCKET_CHECK, on ? 1 : 0, userId);
        TestableLooper.get(this).processAllMessages();
        waitForSensorManager();
    }

    /** The selected user changes, as the user tracker reports it. */
    private void switchUser(int userId) {
        when(mSelectedUserInteractor.getSelectedUserId()).thenReturn(userId);
        verify(mUserTracker).addCallback(mUserCallbackCaptor.capture(), any());
        mUserCallbackCaptor.getValue().onUserChanged(userId, mContext);
        TestableLooper.get(this).processAllMessages();
        waitForSensorManager();
    }

    private boolean hasPocketCheckObserver() {
        return !mSettings.getContentObservers(
                mSettings.getUriFor(Settings.Secure.TALLY_POCKET_CHECK),
                UserHandle.USER_ALL).isEmpty();
    }

    private void startDozing() {
        mTriggers.transitionTo(DozeMachine.State.INITIALIZED, DozeMachine.State.DOZE);
        mTriggers.onScreenState(Display.STATE_OFF);
        waitForSensorManager();
    }

    /** Sends a reading taken now from the non-wake-up proximity sensor. */
    private void sendTapProximityReading(DozeTapProximityCheck check, boolean near)
            throws Exception {
        Constructor<SensorEvent> constructor =
                SensorEvent.class.getDeclaredConstructor(Integer.TYPE);
        constructor.setAccessible(true);
        SensorEvent event = constructor.newInstance(1);
        event.sensor = mNonWakeUpProximity;
        event.timestamp = mClock.elapsedRealtimeNanos();
        event.values[0] = near ? 0f : 5f;
        check.onSensorChanged(event);
    }
}
