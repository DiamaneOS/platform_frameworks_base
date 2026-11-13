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

package com.android.systemui.statusbar.pipeline.mobile.ui.view

import android.content.res.ColorStateList
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import android.testing.TestableLooper.RunWithLooper
import android.testing.ViewUtils
import android.view.View
import android.widget.ImageView
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.log.table.logcatTableLogBuffer
import com.android.systemui.res.R
import com.android.systemui.statusbar.pipeline.airplane.domain.interactor.airplaneModeInteractor
import com.android.systemui.statusbar.pipeline.mobile.domain.interactor.FakeMobileIconInteractor
import com.android.systemui.statusbar.pipeline.mobile.ui.MobileViewLogger
import com.android.systemui.statusbar.pipeline.mobile.ui.viewmodel.MobileIconViewModel
import com.android.systemui.statusbar.pipeline.mobile.ui.viewmodel.ShadeCarrierGroupMobileIconViewModel
import com.android.systemui.statusbar.pipeline.shared.ConnectivityConstants
import com.android.systemui.testKosmos
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.MockitoAnnotations

/** Tests the Wi-Fi calling icon of a SIM's entry in the shade header. */
@SmallTest
@RunWith(AndroidTestingRunner::class)
@RunWithLooper(setAsMainLooper = true)
class ModernShadeCarrierGroupMobileViewTest : SysuiTestCase() {
    private val kosmos = testKosmos()

    private lateinit var testableLooper: TestableLooper
    private val testScope = TestScope(UnconfinedTestDispatcher())

    @Mock private lateinit var viewLogger: MobileViewLogger
    @Mock private lateinit var constants: ConnectivityConstants

    private lateinit var viewModel: ShadeCarrierGroupMobileIconViewModel

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        whenever(constants.hasDataCapabilities).thenReturn(true)
        testableLooper = TestableLooper.get(this)

        val interactor =
            FakeMobileIconInteractor(
                logcatTableLogBuffer(kosmos, "ModernShadeCarrierGroupMobileViewTest")
            )
        val viewModelCommon =
            MobileIconViewModel(
                subscriptionId = 1,
                interactor,
                kosmos.airplaneModeInteractor,
                constants,
                testScope.backgroundScope,
            )
        viewModel =
            ShadeCarrierGroupMobileIconViewModel(
                viewModelCommon,
                interactor,
                testScope.backgroundScope,
            )
    }

    @Test
    fun wifiCallingIcon_hiddenUntilBound() {
        val view = createView()
        ViewUtils.attachView(view)
        testableLooper.processAllMessages()

        assertThat(view.getWifiCallingIcon().visibility).isEqualTo(View.GONE)

        ViewUtils.detachView(view)
    }

    @Test
    fun wifiCallingIcon_followsWifiCallingState() {
        val isWifiCalling = MutableStateFlow(false)
        val view = createView()
        view.bindWifiCalling(isWifiCalling)
        ViewUtils.attachView(view)
        testableLooper.processAllMessages()

        assertThat(view.getWifiCallingIcon().visibility).isEqualTo(View.GONE)

        isWifiCalling.value = true
        testableLooper.processAllMessages()

        assertThat(view.getWifiCallingIcon().visibility).isEqualTo(View.VISIBLE)

        isWifiCalling.value = false
        testableLooper.processAllMessages()

        assertThat(view.getWifiCallingIcon().visibility).isEqualTo(View.GONE)

        ViewUtils.detachView(view)
    }

    @Test
    fun wifiCallingIcon_namesWifiCalling() {
        val view = createView()

        assertThat(view.getWifiCallingIcon().contentDescription)
            .isEqualTo(context.getString(com.android.internal.R.string.notification_channel_wfc))
    }

    @Test
    fun setStyleAndTint_wifiCallingIconHasForegroundColor() {
        val view = createView()
        ViewUtils.attachView(view)
        testableLooper.processAllMessages()

        val color = 0x23456789
        val contrast = 0x12344321
        view.setStyleAndTint(R.style.TextAppearance_QS_Status, color, contrast)
        testableLooper.processAllMessages()

        assertThat(view.getWifiCallingIcon().imageTintList).isEqualTo(ColorStateList.valueOf(color))

        ViewUtils.detachView(view)
    }

    private fun createView(): ModernShadeCarrierGroupMobileView =
        ModernShadeCarrierGroupMobileView.constructAndBind(
            context,
            viewLogger,
            SLOT_NAME,
            viewModel,
        )

    private fun View.getWifiCallingIcon(): ImageView {
        return this.requireViewById(R.id.mobile_wifi_calling)
    }

    private companion object {
        const val SLOT_NAME = "TestSlotName"
    }
}
