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

import static org.junit.Assert.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.hardware.Sensor;
import android.hardware.TriggerEvent;
import android.hardware.TriggerEventListener;
import android.hardware.display.AmbientDisplayConfiguration;
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
    @Captor
    private ArgumentCaptor<DozeHost.Callback> mHostCallbackCaptor;

    private DozeTriggers mTriggers;
    private FakeSensorManager mSensors;
    private Sensor mTapSensor;
    private FakeProximitySensor mProximitySensor;
    private final FakeExecutor mExecutor = new FakeExecutor(new FakeSystemClock());

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
        mSensors = spy(new FakeSensorManager(mContext));
        mTapSensor = mSensors.getFakeTapSensor().getSensor();
        WakeLock wakeLock = new WakeLockFake();
        AsyncSensorManager asyncSensorManager =
                new AsyncSensorManager(mSensors, new FakeThreadFactory(mExecutor), null);

        FakeThresholdSensor thresholdSensor = new FakeThresholdSensor();
        thresholdSensor.setLoaded(true);
        mProximitySensor = new FakeProximitySensor(thresholdSensor,  null, mExecutor);

        mTriggers = new DozeTriggers(mContext, mHost, config, dozeParameters,
                asyncSensorManager, wakeLock, mDockManager, mProximitySensor,
                mProximityCheck, mTapProximityCheck, mDozeLog, mBroadcastDispatcher,
                new FakeSettings(),
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
    public void transitionToDoze_tapProxCheck_listensWithoutWakeUpProximity() throws Exception {
        setupDozeTriggersWithTapProxCheck(mConfig);

        mTriggers.transitionTo(DozeMachine.State.INITIALIZED, DozeMachine.State.DOZE);
        mTriggers.onScreenState(Display.STATE_OFF);

        // THEN the tap proximity check listens, and the wake-up proximity sensor stays off
        verify(mTapProximityCheck).setListening(true);
        assertFalse(mProximitySensor.isRegistered());
    }

    @Test
    public void transitionToPulsing_tapProxCheck_stopsListening() throws Exception {
        setupDozeTriggersWithTapProxCheck(mConfig);
        mTriggers.transitionTo(DozeMachine.State.INITIALIZED, DozeMachine.State.DOZE);
        clearInvocations(mTapProximityCheck);

        // WHEN the touch sensors stop listening
        mTriggers.transitionTo(DozeMachine.State.DOZE, DozeMachine.State.DOZE_REQUEST_PULSE);
        mTriggers.transitionTo(DozeMachine.State.DOZE_REQUEST_PULSE,
                DozeMachine.State.DOZE_PULSING);

        verify(mTapProximityCheck).setListening(false);
    }

    @Test
    public void transitionToDoze_tapProxCheck_tapsOff_doesNotListen() throws Exception {
        AmbientDisplayConfiguration config = DozeConfigurationUtil.createMockConfig();
        when(config.tapGestureEnabled(anyInt())).thenReturn(false);
        setupDozeTriggersWithTapProxCheck(config);

        mTriggers.transitionTo(DozeMachine.State.INITIALIZED, DozeMachine.State.DOZE);
        mTriggers.onScreenState(Display.STATE_OFF);

        verify(mTapProximityCheck, never()).setListening(true);
    }

    @Test
    public void transitionToDoze_noTapProxCheck_doesNotListen() {
        mTriggers.transitionTo(DozeMachine.State.INITIALIZED, DozeMachine.State.DOZE);
        mTriggers.onScreenState(Display.STATE_OFF);

        verify(mTapProximityCheck, never()).setListening(true);
        assertFalse(mProximitySensor.isRegistered());
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
        DozeParameters dozeParameters = DozeConfigurationUtil.createMockParameters();
        when(dozeParameters.getProxCheckBeforeTap()).thenReturn(true);
        setupDozeTriggers(config, dozeParameters);
    }
}
