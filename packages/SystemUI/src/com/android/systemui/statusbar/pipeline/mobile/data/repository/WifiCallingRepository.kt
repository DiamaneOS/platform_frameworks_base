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

import android.content.Context
import android.telephony.SubscriptionManager
import android.telephony.ims.ImsManager
import android.telephony.ims.ImsMmTelManager
import android.telephony.ims.ImsReasonInfo
import android.telephony.ims.ImsRegistrationAttributes
import android.telephony.ims.ImsStateCallback
import android.telephony.ims.RegistrationManager
import android.telephony.ims.feature.MmTelFeature.MmTelCapabilities
import android.telephony.ims.stub.ImsRegistrationImplBase.REGISTRATION_TECH_IWLAN
import android.util.Log
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.utils.coroutines.flow.conflatedCallbackFlow
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onStart

/**
 * Tells whether a subscription is registered for calls over Wi-Fi.
 *
 * A subscription counts as registered while its IMS service is registered over Wi-Fi (IWLAN) and
 * offers voice calls on that registration. Telephony goes by the same two facts when it applies a
 * carrier's Wi-Fi calling name, which the shade's Wi-Fi calling icon stands in for. Each
 * subscription has its own IMS registration, so one SIM can be registered while the other is not.
 */
@SysUISingleton
class WifiCallingRepository
@Inject
constructor(
    @Application private val context: Context,
    @Background private val bgDispatcher: CoroutineDispatcher,
) {
    private val executor = bgDispatcher.asExecutor()

    /**
     * Emits false, then whether [subId] is registered for calls over Wi-Fi, then every change.
     *
     * The callbacks behind it are registered while the flow is collected. If the phone process
     * dies, the flow emits false and stays there until it is collected again.
     */
    fun isRegistered(subId: Int): Flow<Boolean> {
        val mmTel = mmTelManager(subId) ?: return flowOf(false)
        return isImsServiceAvailable(mmTel)
            .flatMapLatest { isAvailable ->
                if (isAvailable) {
                    combine(isRegisteredOverWifi(mmTel), isVoiceAvailable(mmTel)) {
                        overWifi,
                        voice ->
                        overWifi && voice
                    }
                } else {
                    flowOf(false)
                }
            }
            .onStart { emit(false) }
            .distinctUntilChanged()
            .flowOn(bgDispatcher)
    }

    private fun mmTelManager(subId: Int): ImsMmTelManager? {
        if (!SubscriptionManager.isValidSubscriptionId(subId)) return null
        return context.getSystemService(ImsManager::class.java)?.getImsMmTelManager(subId)
    }

    /** The other two callbacks can only be registered while the IMS service is available. */
    private fun isImsServiceAvailable(mmTel: ImsMmTelManager): Flow<Boolean> =
        conflatedCallbackFlow {
            val callback =
                object : ImsStateCallback() {
                    override fun onAvailable() {
                        trySend(true)
                    }

                    override fun onUnavailable(reason: Int) {
                        trySend(false)
                    }

                    override fun onError() {
                        trySend(false)
                    }
                }
            val registered = tryIms { mmTel.registerImsStateCallback(executor, callback) }
            awaitClose { if (registered) tryIms { mmTel.unregisterImsStateCallback(callback) } }
        }

    private fun isRegisteredOverWifi(mmTel: ImsMmTelManager): Flow<Boolean> =
        conflatedCallbackFlow {
            val callback =
                object : RegistrationManager.RegistrationCallback() {
                    override fun onRegistered(attributes: ImsRegistrationAttributes) {
                        // Not the transport type, which is WLAN for cross-SIM calling as well.
                        trySend(attributes.registrationTechnology == REGISTRATION_TECH_IWLAN)
                    }

                    override fun onRegistering(attributes: ImsRegistrationAttributes) {
                        trySend(false)
                    }

                    override fun onUnregistered(info: ImsReasonInfo) {
                        trySend(false)
                    }
                }
            val registered = tryIms { mmTel.registerImsRegistrationCallback(executor, callback) }
            awaitClose {
                if (registered) tryIms { mmTel.unregisterImsRegistrationCallback(callback) }
            }
        }

    private fun isVoiceAvailable(mmTel: ImsMmTelManager): Flow<Boolean> = conflatedCallbackFlow {
        val callback =
            object : ImsMmTelManager.CapabilityCallback() {
                override fun onCapabilitiesStatusChanged(capabilities: MmTelCapabilities) {
                    trySend(capabilities.isCapable(MmTelCapabilities.CAPABILITY_TYPE_VOICE))
                }
            }
        val registered = tryIms { mmTel.registerMmTelCapabilityCallback(executor, callback) }
        awaitClose { if (registered) tryIms { mmTel.unregisterMmTelCapabilityCallback(callback) } }
    }

    /**
     * Runs a call into the phone process, which throws when the IMS service or the phone process
     * itself is not there. Returns whether it went through.
     */
    private inline fun tryIms(call: () -> Unit): Boolean =
        try {
            call()
            true
        } catch (e: Exception) {
            Log.w(TAG, "IMS callback call failed", e)
            false
        }

    private companion object {
        const val TAG = "WifiCallingRepository"
    }
}
