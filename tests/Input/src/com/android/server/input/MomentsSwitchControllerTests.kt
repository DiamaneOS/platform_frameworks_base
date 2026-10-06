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

package com.android.server.input

import android.content.Context
import android.hardware.input.IMomentsSwitchListener
import android.hardware.input.InputManager
import android.os.IBinder
import android.os.test.TestLooper
import android.view.InputDevice
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

/**
 * Tests for [MomentsSwitchController].
 *
 * Build/Install/Run: atest InputTests:MomentsSwitchControllerTests
 */
class MomentsSwitchControllerTests {

    companion object {
        const val CODE = 15
        const val SWITCH_ID = 3
        const val EXTERNAL_ID = 5
        const val LATE_ID = 9
    }

    private lateinit var context: Context
    private lateinit var native: NativeInputManagerService
    private lateinit var testLooper: TestLooper
    private var devices = arrayOf<InputDevice>()

    @Before
    fun setup() {
        context = mock(Context::class.java)
        native = mock(NativeInputManagerService::class.java)
        testLooper = TestLooper()
        // Devices without the switch report UNKNOWN.
        `when`(native.getSwitchState(anyInt(), anyInt(), anyInt()))
            .thenReturn(InputManager.SWITCH_STATE_UNKNOWN)
        devices = arrayOf(device(1, external = false), device(SWITCH_ID, external = false))
        setRaw(SWITCH_ID, 0)
    }

    private fun device(id: Int, external: Boolean): InputDevice =
        InputDevice.Builder()
            .setId(id)
            .setName("device $id")
            .setExternal(external)
            .setSources(InputDevice.SOURCE_UNKNOWN)
            .build()

    private fun setRaw(id: Int, value: Int) {
        `when`(native.getSwitchState(eq(id), anyInt(), eq(CODE))).thenReturn(value)
    }

    private fun controller(code: Int = CODE, activeValue: Int = 1) =
        MomentsSwitchController(context, native, testLooper.looper, { devices }, code, activeValue)

    private fun listener(): IMomentsSwitchListener {
        val listener = mock(IMomentsSwitchListener::class.java)
        `when`(listener.asBinder()).thenReturn(mock(IBinder::class.java))
        return listener
    }

    @Test
    fun configOff_isInert() {
        val controller = controller(code = -1)
        val listener = listener()

        controller.systemRunning()
        controller.registerListener(listener)
        controller.notifySwitch(1L, 1 shl CODE)
        testLooper.dispatchAll()

        assertThat(controller.state).isEqualTo(InputManager.SWITCH_STATE_UNKNOWN)
        verify(listener, never()).onMomentsSwitchChanged(anyLong(), anyBoolean())
        verify(native, never()).getSwitchState(anyInt(), anyInt(), anyInt())
    }

    @Test
    fun withoutPermission_everyCallThrows() {
        doThrow(SecurityException::class.java)
            .`when`(context)
            .enforceCallingOrSelfPermission(eq(MomentsSwitchController.PERMISSION), anyString())
        val controller = controller()
        val listener = listener()

        assertThrows(SecurityException::class.java) { controller.state }
        assertThrows(SecurityException::class.java) { controller.registerListener(listener) }
        assertThrows(SecurityException::class.java) { controller.unregisterListener(listener) }
        verify(listener, never()).onMomentsSwitchChanged(anyLong(), anyBoolean())
    }

    @Test
    fun register_reportsCurrentPositionAtOnce() {
        setRaw(SWITCH_ID, 1)
        val controller = controller()
        val listener = listener()

        controller.registerListener(listener)

        verify(listener).onMomentsSwitchChanged(0L, true)
        assertThat(controller.state).isEqualTo(InputManager.SWITCH_STATE_ON)
    }

    @Test
    fun duplicateReports_areDropped() {
        val controller = controller()
        controller.systemRunning()
        testLooper.dispatchAll()
        val listener = listener()
        controller.registerListener(listener)
        verify(listener).onMomentsSwitchChanged(0L, false)

        // A report with no change, as after a resume, tells nobody.
        controller.notifySwitch(10L, 1 shl CODE)
        testLooper.dispatchAll()
        verify(listener, times(1)).onMomentsSwitchChanged(anyLong(), anyBoolean())

        setRaw(SWITCH_ID, 1)
        controller.notifySwitch(20L, 1 shl CODE)
        controller.notifySwitch(21L, 1 shl CODE)
        testLooper.dispatchAll()
        verify(listener).onMomentsSwitchChanged(20L, true)
        verify(listener, never()).onMomentsSwitchChanged(21L, true)
    }

    @Test
    fun otherSwitchCodes_areIgnored() {
        val controller = controller()
        controller.systemRunning()
        testLooper.dispatchAll()
        val listener = listener()
        controller.registerListener(listener)

        setRaw(SWITCH_ID, 1)
        controller.notifySwitch(10L, 1 shl 14)
        testLooper.dispatchAll()

        verify(listener, never()).onMomentsSwitchChanged(10L, true)
    }

    @Test
    fun externalDevice_cannotMoveTheSwitch() {
        devices = arrayOf(device(EXTERNAL_ID, external = true), device(SWITCH_ID, external = false))
        setRaw(EXTERNAL_ID, 1)
        val controller = controller()
        controller.systemRunning()
        testLooper.dispatchAll()
        val listener = listener()
        controller.registerListener(listener)

        assertThat(controller.pinnedDeviceIdForTesting).isEqualTo(SWITCH_ID)
        verify(listener).onMomentsSwitchChanged(0L, false)

        // The kernel's report has no device: the external one flipping changes nothing.
        setRaw(EXTERNAL_ID, 0)
        controller.notifySwitch(10L, 1 shl CODE)
        setRaw(EXTERNAL_ID, 1)
        controller.notifySwitch(11L, 1 shl CODE)
        testLooper.dispatchAll()

        verify(listener, times(1)).onMomentsSwitchChanged(anyLong(), anyBoolean())
    }

    @Test
    fun laterInternalDevice_doesNotTakeOver() {
        val controller = controller()
        controller.systemRunning()
        testLooper.dispatchAll()
        assertThat(controller.pinnedDeviceIdForTesting).isEqualTo(SWITCH_ID)

        devices = devices + device(LATE_ID, external = false)
        setRaw(LATE_ID, 1)
        controller.onInputDevicesChanged()
        testLooper.dispatchAll()

        assertThat(controller.pinnedDeviceIdForTesting).isEqualTo(SWITCH_ID)
        assertThat(controller.state).isEqualTo(InputManager.SWITCH_STATE_OFF)
    }

    @Test
    fun noInternalDevice_reportsUnknown() {
        devices = arrayOf(device(EXTERNAL_ID, external = true))
        setRaw(EXTERNAL_ID, 1)
        val controller = controller()
        val listener = listener()

        controller.registerListener(listener)

        assertThat(controller.state).isEqualTo(InputManager.SWITCH_STATE_UNKNOWN)
        verify(listener, never()).onMomentsSwitchChanged(anyLong(), anyBoolean())
    }

    @Test
    fun activeValueZero_flipsTheMeaning() {
        val controller = controller(activeValue = 0)

        assertThat(controller.state).isEqualTo(InputManager.SWITCH_STATE_ON)
        setRaw(SWITCH_ID, 1)
        assertThat(controller.state).isEqualTo(InputManager.SWITCH_STATE_OFF)
    }

    @Test
    fun unregister_stopsReports() {
        val controller = controller()
        controller.systemRunning()
        testLooper.dispatchAll()
        val listener = listener()
        controller.registerListener(listener)
        controller.unregisterListener(listener)

        setRaw(SWITCH_ID, 1)
        controller.notifySwitch(10L, 1 shl CODE)
        testLooper.dispatchAll()

        verify(listener, never()).onMomentsSwitchChanged(10L, true)
    }
}
