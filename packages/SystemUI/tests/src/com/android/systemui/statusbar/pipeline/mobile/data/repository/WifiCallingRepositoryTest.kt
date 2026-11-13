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

package com.android.systemui.statusbar.pipeline.mobile.data.repository

import android.telephony.SubscriptionManager.INVALID_SUBSCRIPTION_ID
import android.telephony.ims.ImsException
import android.telephony.ims.ImsManager
import android.telephony.ims.ImsMmTelManager
import android.telephony.ims.ImsReasonInfo
import android.telephony.ims.ImsRegistrationAttributes
import android.telephony.ims.ImsStateCallback
import android.telephony.ims.RegistrationManager
import android.telephony.ims.feature.MmTelFeature.MmTelCapabilities
import android.telephony.ims.stub.ImsRegistrationImplBase.REGISTRATION_TECH_CROSS_SIM
import android.telephony.ims.stub.ImsRegistrationImplBase.REGISTRATION_TECH_IWLAN
import android.telephony.ims.stub.ImsRegistrationImplBase.REGISTRATION_TECH_LTE
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.atLeastOnce
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doThrow

@SmallTest
@RunWith(AndroidJUnit4::class)
class WifiCallingRepositoryTest : SysuiTestCase() {
    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    @Mock private lateinit var imsManager: ImsManager
    @Mock private lateinit var mmTel1: ImsMmTelManager
    @Mock private lateinit var mmTel2: ImsMmTelManager

    private lateinit var underTest: WifiCallingRepository

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        whenever(imsManager.getImsMmTelManager(SUB_1_ID)).thenReturn(mmTel1)
        whenever(imsManager.getImsMmTelManager(SUB_2_ID)).thenReturn(mmTel2)
        mContext.addMockSystemService(ImsManager::class.java, imsManager)

        underTest = WifiCallingRepository(mContext, testDispatcher)
    }

    @Test
    fun invalidSubscription_false() =
        testScope.runTest {
            val latest by collectLastValue(underTest.isRegistered(INVALID_SUBSCRIPTION_ID))

            assertThat(latest).isFalse()
        }

    @Test
    fun imsServiceNotAvailable_falseAndNothingElseRegistered() =
        testScope.runTest {
            val latest by collectLastValue(underTest.isRegistered(SUB_1_ID))

            assertThat(latest).isFalse()
            verify(mmTel1).registerImsStateCallback(any(), any())
            verify(mmTel1, never())
                .registerImsRegistrationCallback(
                    any(),
                    any<RegistrationManager.RegistrationCallback>(),
                )
            verify(mmTel1, never()).registerMmTelCapabilityCallback(any(), any())
        }

    @Test
    fun registeredOverWifiWithVoice_true() =
        testScope.runTest {
            val latest by collectLastValue(underTest.isRegistered(SUB_1_ID))
            runCurrent()

            imsStateCallback(mmTel1).onAvailable()
            registrationCallback(mmTel1).onRegistered(registeredOn(REGISTRATION_TECH_IWLAN))
            capabilityCallback(mmTel1).onCapabilitiesStatusChanged(VOICE)

            assertThat(latest).isTrue()
        }

    @Test
    fun registeredOverMobileNetwork_false() =
        testScope.runTest {
            val latest by collectLastValue(underTest.isRegistered(SUB_1_ID))
            runCurrent()

            imsStateCallback(mmTel1).onAvailable()
            registrationCallback(mmTel1).onRegistered(registeredOn(REGISTRATION_TECH_LTE))
            capabilityCallback(mmTel1).onCapabilitiesStatusChanged(VOICE)

            assertThat(latest).isFalse()
        }

    @Test
    fun registeredOverOtherSimsData_false() =
        testScope.runTest {
            val latest by collectLastValue(underTest.isRegistered(SUB_1_ID))
            runCurrent()

            imsStateCallback(mmTel1).onAvailable()
            registrationCallback(mmTel1).onRegistered(registeredOn(REGISTRATION_TECH_CROSS_SIM))
            capabilityCallback(mmTel1).onCapabilitiesStatusChanged(VOICE)

            assertThat(latest).isFalse()
        }

    @Test
    fun registeredOverWifiWithoutVoice_false() =
        testScope.runTest {
            val latest by collectLastValue(underTest.isRegistered(SUB_1_ID))
            runCurrent()

            imsStateCallback(mmTel1).onAvailable()
            registrationCallback(mmTel1).onRegistered(registeredOn(REGISTRATION_TECH_IWLAN))
            capabilityCallback(mmTel1).onCapabilitiesStatusChanged(SMS_ONLY)

            assertThat(latest).isFalse()
        }

    @Test
    fun followsRegistrationChanges() =
        testScope.runTest {
            val latest by collectLastValue(underTest.isRegistered(SUB_1_ID))
            runCurrent()

            imsStateCallback(mmTel1).onAvailable()
            val registration = registrationCallback(mmTel1)
            capabilityCallback(mmTel1).onCapabilitiesStatusChanged(VOICE)

            registration.onRegistered(registeredOn(REGISTRATION_TECH_IWLAN))
            assertThat(latest).isTrue()

            // Wi-Fi is lost and the registration moves to the mobile network
            registration.onRegistered(registeredOn(REGISTRATION_TECH_LTE))
            assertThat(latest).isFalse()

            // Back on Wi-Fi, registering is not registered yet
            registration.onRegistering(registeredOn(REGISTRATION_TECH_IWLAN))
            assertThat(latest).isFalse()
            registration.onRegistered(registeredOn(REGISTRATION_TECH_IWLAN))
            assertThat(latest).isTrue()

            registration.onUnregistered(ImsReasonInfo())
            assertThat(latest).isFalse()
        }

    @Test
    fun imsServiceGoesAway_falseAndCallbacksUnregistered() =
        testScope.runTest {
            val latest by collectLastValue(underTest.isRegistered(SUB_1_ID))
            runCurrent()

            val imsState = imsStateCallback(mmTel1)
            imsState.onAvailable()
            val registration = registrationCallback(mmTel1)
            val capability = capabilityCallback(mmTel1)
            registration.onRegistered(registeredOn(REGISTRATION_TECH_IWLAN))
            capability.onCapabilitiesStatusChanged(VOICE)
            assertThat(latest).isTrue()

            imsState.onUnavailable(ImsStateCallback.REASON_IMS_SERVICE_DISCONNECTED)

            assertThat(latest).isFalse()
            verify(mmTel1).unregisterImsRegistrationCallback(registration)
            verify(mmTel1).unregisterMmTelCapabilityCallback(capability)
        }

    @Test
    fun phoneProcessDies_false() =
        testScope.runTest {
            val latest by collectLastValue(underTest.isRegistered(SUB_1_ID))
            runCurrent()

            val imsState = imsStateCallback(mmTel1)
            imsState.onAvailable()
            registrationCallback(mmTel1).onRegistered(registeredOn(REGISTRATION_TECH_IWLAN))
            capabilityCallback(mmTel1).onCapabilitiesStatusChanged(VOICE)
            assertThat(latest).isTrue()

            imsState.onError()

            assertThat(latest).isFalse()
        }

    @Test
    fun callbackCannotBeRegistered_false() =
        testScope.runTest {
            doThrow(ImsException("test", ImsException.CODE_ERROR_SERVICE_UNAVAILABLE))
                .`when`(mmTel1)
                .registerImsRegistrationCallback(
                    any(),
                    any<RegistrationManager.RegistrationCallback>(),
                )
            val latest by collectLastValue(underTest.isRegistered(SUB_1_ID))
            runCurrent()

            imsStateCallback(mmTel1).onAvailable()
            capabilityCallback(mmTel1).onCapabilitiesStatusChanged(VOICE)

            assertThat(latest).isFalse()
        }

    @Test
    fun twoSubscriptions_eachHasItsOwnRegistration() =
        testScope.runTest {
            val latest1 by collectLastValue(underTest.isRegistered(SUB_1_ID))
            val latest2 by collectLastValue(underTest.isRegistered(SUB_2_ID))
            runCurrent()

            // SIM 1 is registered over Wi-Fi, SIM 2 over the mobile network
            imsStateCallback(mmTel1).onAvailable()
            registrationCallback(mmTel1).onRegistered(registeredOn(REGISTRATION_TECH_IWLAN))
            capabilityCallback(mmTel1).onCapabilitiesStatusChanged(VOICE)
            imsStateCallback(mmTel2).onAvailable()
            registrationCallback(mmTel2).onRegistered(registeredOn(REGISTRATION_TECH_LTE))
            capabilityCallback(mmTel2).onCapabilitiesStatusChanged(VOICE)

            assertThat(latest1).isTrue()
            assertThat(latest2).isFalse()

            // SIM 2 follows onto Wi-Fi and SIM 1 loses its registration
            registrationCallback(mmTel2).onRegistered(registeredOn(REGISTRATION_TECH_IWLAN))
            registrationCallback(mmTel1).onUnregistered(ImsReasonInfo())

            assertThat(latest1).isFalse()
            assertThat(latest2).isTrue()
        }

    @Test
    fun collectionEnds_callbacksUnregistered() =
        testScope.runTest {
            val job = underTest.isRegistered(SUB_1_ID).launchIn(this)
            runCurrent()

            val imsState = imsStateCallback(mmTel1)
            imsState.onAvailable()
            val registration = registrationCallback(mmTel1)
            val capability = capabilityCallback(mmTel1)

            job.cancel()
            runCurrent()

            verify(mmTel1).unregisterImsStateCallback(imsState)
            verify(mmTel1).unregisterImsRegistrationCallback(registration)
            verify(mmTel1).unregisterMmTelCapabilityCallback(capability)
        }

    private fun imsStateCallback(mmTel: ImsMmTelManager): ImsStateCallback {
        val captor = argumentCaptor<ImsStateCallback>()
        verify(mmTel, atLeastOnce()).registerImsStateCallback(any(), captor.capture())
        return captor.lastValue
    }

    private fun registrationCallback(
        mmTel: ImsMmTelManager
    ): RegistrationManager.RegistrationCallback {
        val captor = argumentCaptor<RegistrationManager.RegistrationCallback>()
        verify(mmTel, atLeastOnce()).registerImsRegistrationCallback(any(), captor.capture())
        return captor.lastValue
    }

    private fun capabilityCallback(mmTel: ImsMmTelManager): ImsMmTelManager.CapabilityCallback {
        val captor = argumentCaptor<ImsMmTelManager.CapabilityCallback>()
        verify(mmTel, atLeastOnce()).registerMmTelCapabilityCallback(any(), captor.capture())
        return captor.lastValue
    }

    private fun registeredOn(registrationTech: Int) =
        ImsRegistrationAttributes.Builder(registrationTech).build()

    private companion object {
        const val SUB_1_ID = 1
        const val SUB_2_ID = 2

        val VOICE = MmTelCapabilities(MmTelCapabilities.CAPABILITY_TYPE_VOICE)
        val SMS_ONLY = MmTelCapabilities(MmTelCapabilities.CAPABILITY_TYPE_SMS)
    }
}
