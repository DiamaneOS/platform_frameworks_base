/*
 * Copyright (C) 2017 The Android Open Source Project
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

import static com.android.systemui.doze.DozeMachine.State.DOZE_AOD;
import static com.android.systemui.doze.DozeMachine.State.INITIALIZED;
import static com.android.systemui.doze.DozeMachine.State.UNINITIALIZED;

import static org.junit.Assert.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.StatusBarManager;
import android.hardware.Sensor;
import android.hardware.TriggerEvent;
import android.hardware.TriggerEventListener;
import android.hardware.display.AmbientDisplayConfiguration;
import android.platform.test.annotations.EnableFlags;
import android.testing.TestableLooper;
import android.testing.TestableLooper.RunWithLooper;
import android.view.Display;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.internal.logging.InstanceId;
import com.android.internal.logging.UiEventLogger;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.biometrics.AuthController;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.dock.DockManager;
import com.android.systemui.doze.DozeTriggers.DozingUpdateUiEvent;
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
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.lang.reflect.Constructor;
import java.util.function.Consumer;

@SmallTest
@RunWith(AndroidJUnit4.class)
@RunWithLooper(setAsMainLooper = true)
public class DozeTriggersTest extends SysuiTestCase {

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
    public void testOnNotification_stillWorksAfterOneFailedProxCheck() {
        when(mMachine.getState()).thenReturn(DozeMachine.State.DOZE);
        ArgumentCaptor<DozeHost.Callback> captor = ArgumentCaptor.forClass(DozeHost.Callback.class);
        doAnswer(invocation -> null).when(mHost).addCallback(captor.capture());

        mTriggers.transitionTo(UNINITIALIZED, DozeMachine.State.INITIALIZED);
        mTriggers.transitionTo(DozeMachine.State.INITIALIZED, DozeMachine.State.DOZE);
        clearInvocations(mMachine);

        when(mHost.isPulsingBlocked()).thenReturn(false);
        mProximitySensor.setLastEvent(new ThresholdSensorEvent(true, 1));
        captor.getValue().onNotificationAlerted(null /* pulseSuppressedListener */);
        mProximitySensor.alertListeners();

        verify(mMachine, never()).requestState(any());
        verify(mMachine, never()).requestPulse(anyInt());

        mProximitySensor.setLastEvent(new ThresholdSensorEvent(false, 2));
        mProximitySensor.alertListeners();
        waitForSensorManager();
        captor.getValue().onNotificationAlerted(null /* pulseSuppressedListener */);

        verify(mMachine).requestPulse(anyInt());
    }

    @Test
    public void testOnNotification_startsPulseRequest() {
        // GIVEN device is dozing
        Runnable pulseSuppressListener = mock(Runnable.class);
        mTriggers.transitionTo(UNINITIALIZED, DozeMachine.State.INITIALIZED);
        mTriggers.transitionTo(DozeMachine.State.INITIALIZED, DozeMachine.State.DOZE);
        when(mMachine.getState()).thenReturn(DozeMachine.State.DOZE);
        clearInvocations(mMachine);

        // WHEN receive an alerting notification
        mHostCallbackCaptor.getValue().onNotificationAlerted(pulseSuppressListener);

        // THEN entering to pulse
        verify(mHost).setPulsePending(true);
        // AND suppress listeners are NOT notified
        verify(pulseSuppressListener, never()).run();
    }

    @Test
    public void testOnNotification_cannotPulse_notificationSuppressed() {
        // GIVEN device is dozing
        Runnable pulseSuppressListener = mock(Runnable.class);
        when(mMachine.getState()).thenReturn(DozeMachine.State.DOZE);
        mTriggers.transitionTo(UNINITIALIZED, DozeMachine.State.INITIALIZED);
        mTriggers.transitionTo(DozeMachine.State.INITIALIZED, DozeMachine.State.DOZE);
        clearInvocations(mMachine);
        // AND pulsing is disabled
        when(mConfig.pulseOnNotificationEnabled(anyInt())).thenReturn(false);

        // WHEN receive an alerting notification
        mHostCallbackCaptor.getValue().onNotificationAlerted(pulseSuppressListener);

        // THEN NOT starting pulse
        verify(mHost, never()).setPulsePending(anyBoolean());
        // AND the notification is suppressed
        verify(pulseSuppressListener).run();
    }

    @Test
    public void testOnNotification_alreadyPulsing_notificationNotSuppressed() {
        // GIVEN device is pulsing
        Runnable pulseSuppressListener = mock(Runnable.class);
        when(mMachine.getState()).thenReturn(DozeMachine.State.DOZE_PULSING);
        mTriggers.transitionTo(UNINITIALIZED, DozeMachine.State.INITIALIZED);
        mTriggers.transitionTo(DozeMachine.State.INITIALIZED, DozeMachine.State.DOZE_PULSING);
        clearInvocations(mMachine);

        // WHEN receive an alerting notification
        mHostCallbackCaptor.getValue().onNotificationAlerted(pulseSuppressListener);

        // THEN entering to pulse
        verify(mHost, never()).setPulsePending(anyBoolean());
        // AND suppress listeners are NOT notified
        verify(pulseSuppressListener, never()).run();
    }

    @Test
    public void testOnNotification_noPulseIfPulseIsNotPendingAnymore() {
        when(mMachine.getState()).thenReturn(DozeMachine.State.DOZE);

        mTriggers.transitionTo(UNINITIALIZED, DozeMachine.State.INITIALIZED);
        mTriggers.transitionTo(DozeMachine.State.INITIALIZED, DozeMachine.State.DOZE);
        clearInvocations(mMachine);
        when(mHost.isPulsingBlocked()).thenReturn(false);

        // GIVEN pulsePending = false
        Mockito.reset(mHost); // remove the isPulsePending mocked answer in setup
        when(mHost.isPulsePending()).thenReturn(false);

        // WHEN prox check returns FAR
        mProximitySensor.setLastEvent(new ThresholdSensorEvent(false, 2));
        mHostCallbackCaptor.getValue().onNotificationAlerted(null /* pulseSuppressedListener */);
        mProximitySensor.alertListeners();

        // THEN don't request pulse because the pending pulse was abandoned early
        verify(mMachine, never()).requestPulse(anyInt());
    }

    @Test
    public void testTransitionTo_disablesAndEnablesTouchSensors() {
        when(mMachine.getState()).thenReturn(DozeMachine.State.DOZE);

        mTriggers.transitionTo(DozeMachine.State.INITIALIZED, DozeMachine.State.DOZE);
        mTriggers.onScreenState(Display.STATE_OFF);
        waitForSensorManager();
        verify(mSensors).requestTriggerSensor(any(), eq(mTapSensor));

        clearInvocations(mSensors);
        mTriggers.transitionTo(DozeMachine.State.DOZE,
                DozeMachine.State.DOZE_REQUEST_PULSE);
        mTriggers.transitionTo(DozeMachine.State.DOZE_REQUEST_PULSE,
                DozeMachine.State.DOZE_PULSING);
        mTriggers.onScreenState(Display.STATE_DOZE);
        waitForSensorManager();
        verify(mSensors).cancelTriggerSensor(any(), eq(mTapSensor));

        clearInvocations(mSensors);
        mTriggers.transitionTo(DozeMachine.State.DOZE_PULSING, DozeMachine.State.DOZE_PULSE_DONE);
        mTriggers.transitionTo(DozeMachine.State.DOZE_PULSE_DONE, DOZE_AOD);
        waitForSensorManager();
        verify(mSensors).requestTriggerSensor(any(), eq(mTapSensor));
    }

    @Test
    public void transitionToDockedAod_disablesTouchSensors() {
        mTriggers.transitionTo(DozeMachine.State.INITIALIZED, DozeMachine.State.DOZE);
        mTriggers.onScreenState(Display.STATE_OFF);
        waitForSensorManager();
        verify(mSensors).requestTriggerSensor(any(), eq(mTapSensor));

        mTriggers.transitionTo(DozeMachine.State.DOZE, DozeMachine.State.DOZE_AOD_DOCKED);
        mTriggers.onScreenState(Display.STATE_DOZE);
        waitForSensorManager();

        verify(mSensors).cancelTriggerSensor(any(), eq(mTapSensor));
    }

    @Test
    public void transitionToDozeSuspendTriggers_disablesAllCallbacks() {
        mTriggers.transitionTo(UNINITIALIZED, INITIALIZED);
        when(mMachine.getState()).thenReturn(DozeMachine.State.DOZE_SUSPEND_TRIGGERS);

        mTriggers.transitionTo(DozeMachine.State.INITIALIZED,
                DozeMachine.State.DOZE_SUSPEND_TRIGGERS);

        verify(mDockManager).removeListener(any());
        verify(mBroadcastDispatcher).unregisterReceiver(any());
        verify(mHost).removeCallback(any());
    }

    @Test
    public void testDockEventListener_registerAndUnregister() {
        mTriggers.transitionTo(UNINITIALIZED, DozeMachine.State.INITIALIZED); // beverlyt
        verify(mDockManager).addListener(any());

        mTriggers.transitionTo(DozeMachine.State.DOZE, DozeMachine.State.FINISH);
        verify(mDockManager).removeListener(any());
    }

    @Test
    public void testProximitySensorNotAvailable() {
        mProximitySensor.setSensorAvailable(false);
        mTriggers.onSensor(DozeLog.PULSE_REASON_SENSOR_LONG_PRESS, 100, 100, null);
        mTriggers.onSensor(DozeLog.PULSE_REASON_SENSOR_WAKE_REACH, 100, 100,
                new float[]{1});
        mTriggers.onSensor(DozeLog.REASON_SENSOR_TAP, 100, 100, null);
    }

    @Test
    public void testQuickPickup() {
        // GIVEN device is in doze
        when(mMachine.getState()).thenReturn(DozeMachine.State.DOZE);
        InstanceId keyguardSessionId = InstanceId.fakeInstanceId(99);
        when(mSessionTracker.getSessionId(StatusBarManager.SESSION_KEYGUARD))
                .thenReturn(keyguardSessionId);

        // WHEN quick pick up is triggered
        mTriggers.onSensor(DozeLog.REASON_SENSOR_QUICK_PICKUP, 100, 100, null);

        // THEN request pulse
        verify(mMachine).requestPulse(DozeLog.REASON_SENSOR_QUICK_PICKUP);

        // THEN a log is taken that quick pick up was triggered
        verify(mUiEventLogger)
                .log(DozingUpdateUiEvent.DOZING_UPDATE_QUICK_PICKUP, keyguardSessionId);
    }

    @Test
    public void quickPickup_whileAlreadyPulsing() {
        // GIVEN device is already pulsing
        when(mMachine.getState()).thenReturn(DozeMachine.State.DOZE_PULSING);

        // WHEN quick pick up is triggered
        mTriggers.onSensor(DozeLog.REASON_SENSOR_QUICK_PICKUP, 100, 100, null);

        // THEN don't request pulse
        verify(mMachine, never()).requestPulse(anyInt());
    }

    @Test
    public void quickPickup_whileAlreadyPulsingWithoutUI() {
        // GIVEN device is already pulsing without UI
        when(mMachine.getState()).thenReturn(DozeMachine.State.DOZE_PULSING_WITHOUT_UI);

        // WHEN quick pick up is triggered
        mTriggers.onSensor(DozeLog.REASON_SENSOR_QUICK_PICKUP, 100, 100, null);

        // THEN don't request pulse
        verify(mMachine, never()).requestPulse(anyInt());
    }

    @Test
    public void quickPickup_whileAlreadyPulsingAuthUI() {
        // GIVEN device is already pulsing auth UI
        when(mMachine.getState()).thenReturn(DozeMachine.State.DOZE_PULSING_AUTH_UI);

        // WHEN udpfs longpress is triggered
        mTriggers.onSensor(DozeLog.REASON_SENSOR_QUICK_PICKUP, 100, 100, null);

        // THEN don't request pulse
        verify(mMachine, never()).requestPulse(anyInt());
    }

    @Test
    public void udfpsLongpress() {
        // GIVEN device is in doze
        when(mMachine.getState()).thenReturn(DozeMachine.State.DOZE);

        // WHEN udfps longpress up is triggered
        mTriggers.onSensor(DozeLog.REASON_SENSOR_UDFPS_LONG_PRESS, 100, 100, null);

        // THEN request pulse
        verify(mMachine).requestPulse(DozeLog.REASON_SENSOR_UDFPS_LONG_PRESS);
    }

    @Test
    public void udfpsLongpress_whileAlreadyPulsingWithoutUI() {
        // GIVEN device is already pulsing auth UI
        when(mMachine.getState()).thenReturn(DozeMachine.State.DOZE_PULSING_WITHOUT_UI);

        // WHEN udpfs longpress is triggered
        mTriggers.onSensor(DozeLog.PULSE_REASON_SENSOR_LONG_PRESS, 100, 100, null);

        // THEN don't request pulse
        verify(mMachine, never()).requestPulse(anyInt());
    }

    @Test
    public void udfpsLongpress_whileAlreadyPulsingAuthUI() {
        // GIVEN device is already pulsing auth UI
        when(mMachine.getState()).thenReturn(DozeMachine.State.DOZE_PULSING_AUTH_UI);

        // WHEN udpfs longpress is triggered
        mTriggers.onSensor(DozeLog.PULSE_REASON_SENSOR_LONG_PRESS, 100, 100, null);

        // THEN don't request pulse
        verify(mMachine, never()).requestPulse(anyInt());
    }

    @Test
    public void udfpsLongpress_whileAlreadyPulsing() {
        // GIVEN device is already pulsing auth UI
        when(mMachine.getState()).thenReturn(DozeMachine.State.DOZE_PULSING);

        // WHEN quick pick up is triggered
        mTriggers.onSensor(DozeLog.PULSE_REASON_SENSOR_LONG_PRESS, 100, 100, null);

        // THEN don't request pulse
        verify(mMachine, never()).requestPulse(anyInt());
    }

    @Test
    public void fingerprintPulseShowAuthUI() {
        // GIVEN device is in DOZE
        mTriggers.transitionTo(UNINITIALIZED, DozeMachine.State.INITIALIZED);
        mTriggers.transitionTo(DozeMachine.State.INITIALIZED, DozeMachine.State.DOZE);
        when(mMachine.getState()).thenReturn(DozeMachine.State.DOZE);

        // WHEN PULSE_REASON_FINGERPRINT_PULSE_SHOW_AUTH_UI is triggered
        mHostCallbackCaptor.getValue().onFingerprintPulseWhileScreenOff(null);

        // THEN request pulse
        verify(mMachine).requestPulse(DozeLog.PULSE_REASON_FINGERPRINT_PULSE_SHOW_AUTH_UI);
    }

    @Test
    public void fingerprintPulseShowAuthUI_whileAlreadyPulsingWithoutUI() {
        // GIVEN device is pulsing showing no UI
        mTriggers.transitionTo(UNINITIALIZED, DozeMachine.State.INITIALIZED);
        mTriggers.transitionTo(DozeMachine.State.INITIALIZED,
                DozeMachine.State.DOZE_PULSING_WITHOUT_UI);
        when(mMachine.getState()).thenReturn(DozeMachine.State.DOZE_PULSING_WITHOUT_UI);

        // WHEN PULSE_REASON_FINGERPRINT_PULSE_SHOW_AUTH_UI is triggered
        mHostCallbackCaptor.getValue().onFingerprintPulseWhileScreenOff(null);

        // THEN immediately requests state
        verify(mMachine).requestState(DozeMachine.State.DOZE_PULSING_AUTH_UI);
    }

    @Test
    public void fingerprintPulseShowAuthUI_whileAlreadyPulsing() {
        // GIVEN device is pulsing showing no UI
        mTriggers.transitionTo(UNINITIALIZED, DozeMachine.State.INITIALIZED);
        mTriggers.transitionTo(DozeMachine.State.INITIALIZED, DozeMachine.State.DOZE_PULSING);
        when(mMachine.getState()).thenReturn(DozeMachine.State.DOZE_PULSING);

        // WHEN PULSE_REASON_FINGERPRINT_PULSE_SHOW_AUTH_UI is triggered
        mHostCallbackCaptor.getValue().onFingerprintPulseWhileScreenOff(null);

        // THEN doesn't request a new state since device is already pulsing
        verify(mMachine, never()).requestState(any());
    }

    @Test
    public void fingerprintPulseShowAuthUI_whileAlreadyShowingAuthUI() {
        // GIVEN device is pulsing showing no UI
        mTriggers.transitionTo(UNINITIALIZED, DozeMachine.State.INITIALIZED);
        mTriggers.transitionTo(DozeMachine.State.INITIALIZED,
                DozeMachine.State.DOZE_PULSING_AUTH_UI);
        when(mMachine.getState()).thenReturn(DozeMachine.State.DOZE_PULSING_AUTH_UI);

        // WHEN PULSE_REASON_FINGERPRINT_PULSE_SHOW_AUTH_UI is triggered
        mHostCallbackCaptor.getValue().onFingerprintPulseWhileScreenOff(null);

        // THEN doesn't request a new state since device is already showing auth ui
        verify(mMachine, never()).requestState(any());
    }


    @Test
    public void fingerprintPulseShowFullUI_whileAlreadyPulsingWithoutUI() {
        // GIVEN device is pulsing showing no UI
        mTriggers.transitionTo(UNINITIALIZED, DozeMachine.State.INITIALIZED);
        mTriggers.transitionTo(DozeMachine.State.INITIALIZED,
                DozeMachine.State.DOZE_PULSING_WITHOUT_UI);
        when(mMachine.getState()).thenReturn(DozeMachine.State.DOZE_PULSING_WITHOUT_UI);

        // WHEN PULSE_REASON_FINGERPRINT_PULSE_SHOW_AUTH_UI is triggered
        mHostCallbackCaptor.getValue().onFingerprintPulseWhileScreenOff(null);
        mHostCallbackCaptor.getValue().onFingerprintPulseWhileScreenOff(null);
        mHostCallbackCaptor.getValue().onFingerprintPulseWhileScreenOff(null);

        // THEN immediately requests state
        verify(mMachine).requestState(DozeMachine.State.DOZE_PULSING);
    }

    @Test
    public void fingerprintPulseShowFullUI_whileAlreadyPulsing() {
        // GIVEN device is pulsing showing no UI
        mTriggers.transitionTo(UNINITIALIZED, DozeMachine.State.INITIALIZED);
        mTriggers.transitionTo(DozeMachine.State.INITIALIZED, DozeMachine.State.DOZE_PULSING);
        when(mMachine.getState()).thenReturn(DozeMachine.State.DOZE_PULSING);

        // WHEN PULSE_REASON_FINGERPRINT_PULSE_SHOW_FULL_UI is triggered
        mHostCallbackCaptor.getValue().onFingerprintPulseWhileScreenOff(null);
        mHostCallbackCaptor.getValue().onFingerprintPulseWhileScreenOff(null);
        mHostCallbackCaptor.getValue().onFingerprintPulseWhileScreenOff(null);

        // THEN doesn't request a new state since device is already pulsing
        verify(mMachine, never()).requestState(any());
    }

    @Test
    public void fingerprintPulseShowFullUI_whileAlreadyShowingAuthUI() {
        // GIVEN device is pulsing showing auth UI
        mTriggers.transitionTo(UNINITIALIZED, DozeMachine.State.INITIALIZED);
        mTriggers.transitionTo(DozeMachine.State.INITIALIZED,
                DozeMachine.State.DOZE_PULSING_AUTH_UI);
        when(mMachine.getState()).thenReturn(DozeMachine.State.DOZE_PULSING_AUTH_UI);

        // WHEN PULSE_REASON_FINGERPRINT_PULSE_SHOW_FULL_UI is triggered
        mHostCallbackCaptor.getValue().onFingerprintPulseWhileScreenOff(null);
        mHostCallbackCaptor.getValue().onFingerprintPulseWhileScreenOff(null);
        mHostCallbackCaptor.getValue().onFingerprintPulseWhileScreenOff(null);

        // THEN immediately requests state
        verify(mMachine).requestState(DozeMachine.State.DOZE_PULSING);
    }

    @Test
    public void testPickupGesture() {
        // GIVEN device is in doze (screen blank, but running doze sensors)
        when(mMachine.getState()).thenReturn(DozeMachine.State.DOZE);

        // WHEN the pick up gesture is triggered and keyguard isn't occluded
        when(mKeyguardStateController.isOccluded()).thenReturn(false);
        mTriggers.onSensor(DozeLog.REASON_SENSOR_PICKUP, 100, 100, null);

        // THEN wakeup
        verify(mMachine).wakeUp(DozeLog.REASON_SENSOR_PICKUP);
    }

    @Test
    public void test_onSensor_tap() {
        mTriggers.onSensor(DozeLog.REASON_SENSOR_TAP, 100, 200, null);

        verify(mHost).onSlpiTap(100, 200);
        verify(mMachine).wakeUp(DozeLog.REASON_SENSOR_TAP);
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

    @Test
    public void test_onSensor_double_tap() {
        mTriggers.onSensor(DozeLog.REASON_SENSOR_DOUBLE_TAP, 100, 200, null);

        verify(mHost).onSlpiTap(100, 200);
        verify(mMachine).wakeUp(DozeLog.REASON_SENSOR_DOUBLE_TAP);
    }

    @Test
    public void testPickupGestureDroppedKeyguardOccluded() {
        // GIVEN device is in doze (screen blank, but running doze sensors)
        when(mMachine.getState()).thenReturn(DozeMachine.State.DOZE);

        // WHEN the pick up gesture is triggered and keyguard IS occluded
        when(mKeyguardStateController.isOccluded()).thenReturn(true);
        mTriggers.onSensor(DozeLog.REASON_SENSOR_PICKUP, 100, 100, null);

        // THEN never wakeup
        verify(mMachine, never()).wakeUp(DozeLog.REASON_SENSOR_PICKUP);
    }

    @Test
    public void testOnSensor_Fingerprint() {
        // GIVEN dozing state
        when(mMachine.getState()).thenReturn(DOZE_AOD);
        final int screenX = 100;
        final int screenY = 100;
        final float misc = -1;
        final float minor = 2f;
        final float major = 3f;
        final int reason = DozeLog.REASON_SENSOR_UDFPS_LONG_PRESS;
        float[] rawValues = new float[]{screenX, screenY, misc, major, minor};

        // WHEN longpress gesture is triggered
        mTriggers.onSensor(reason, screenX, screenY, rawValues);

        // THEN
        // * don't immediately send interrupt
        // * immediately extend pulse
        verify(mAuthController, never()).onAodInterrupt(anyInt(), anyInt(), anyFloat(), anyFloat());
        verify(mHost).extendPulse(reason);

        // WHEN display state changes to ON
        mTriggers.onScreenState(Display.STATE_ON);

        // THEN send interrupt
        verify(mAuthController).onAodInterrupt(eq(screenX), eq(screenY), eq(major), eq(minor));
    }

    @Test
    public void testDestroy() {
        mTriggers.destroy();
        verify(mProximityCheck).destroy();
    }

    @Test
    public void nullState_dropUdfpsLongPressPulse() {
        when(mHost.isPulsePending()).thenReturn(false);
        when(mMachine.getState()).thenReturn(null);

        mTriggers.onSensor(DozeLog.PULSE_REASON_SENSOR_LONG_PRESS, 100, 100, null);

        verify(mDozeLog).tracePulseDropped(anyString(), eq(null));
    }

    @Test
    public void testIsExecutingTransition_dropPulseForUdfpsLongPress() {
        when(mHost.isPulsePending()).thenReturn(false);
        when(mMachine.isExecutingTransition()).thenReturn(true);

        mTriggers.onSensor(DozeLog.REASON_SENSOR_UDFPS_LONG_PRESS, 100, 100, null);

        // THEN pulse is dropped
        verify(mDozeLog).tracePulseDropped(anyString(), eq(null));

        // THEN aod interrupt is not sent
        verify(mAuthController, never()).onAodInterrupt(anyInt(), anyInt(), anyFloat(), anyFloat());
    }

    @Test
    public void udfpsLongPress_triggeredWhenAodPaused() {
        // GIVEN device is DOZE_AOD_PAUSED
        when(mMachine.getState()).thenReturn(DozeMachine.State.DOZE_AOD_PAUSED);

        // WHEN udfps long-press is triggered
        mTriggers.onSensor(DozeLog.REASON_SENSOR_UDFPS_LONG_PRESS, 100, 100,
                new float[]{0, 1, 2, 3, 4});

        // THEN the pulse is NOT dropped
        verify(mDozeLog, never()).tracePulseDropped(anyString(), any());

        // WHEN the screen state is ON
        mTriggers.onScreenState(Display.STATE_ON);

        // THEN aod interrupt is sent
        verify(mAuthController).onAodInterrupt(anyInt(), anyInt(), anyFloat(), anyFloat());
    }

    @Test
    public void udfpsLongPress_triggeredWhenAodPausing() {
        // GIVEN device is DOZE_AOD_PAUSED
        when(mMachine.getState()).thenReturn(DozeMachine.State.DOZE_AOD_PAUSING);

        // WHEN udfps long-press is triggered
        mTriggers.onSensor(DozeLog.REASON_SENSOR_UDFPS_LONG_PRESS, 100, 100,
                new float[]{0, 1, 2, 3, 4});

        // THEN the pulse is NOT dropped
        verify(mDozeLog, never()).tracePulseDropped(anyString(), any());

        // WHEN the screen state is ON
        mTriggers.onScreenState(Display.STATE_ON);

        // THEN aod interrupt is sent
        verify(mAuthController).onAodInterrupt(anyInt(), anyInt(), anyFloat(), anyFloat());
    }

    @Test
    @EnableFlags(android.hardware.biometrics.Flags.FLAG_SCREEN_OFF_UNLOCK_UDFPS)
    public void udfpsLongPress_triggeredWhenDoze() {
        // GIVEN device is DOZE
        when(mMachine.getState()).thenReturn(DozeMachine.State.DOZE);

        // WHEN udfps long-press is triggered
        mTriggers.onSensor(DozeLog.REASON_SENSOR_UDFPS_LONG_PRESS, 100, 100,
                new float[]{0, 1, 2, 3, 4});

        // THEN the pulse is NOT dropped
        verify(mDozeLog, never()).tracePulseDropped(anyString(), any());

        // WHEN the screen state is OFF
        mTriggers.onScreenState(Display.STATE_OFF);

        // THEN aod interrupt never be sent
        verify(mAuthController, never()).onAodInterrupt(anyInt(), anyInt(), anyFloat(), anyFloat());
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
