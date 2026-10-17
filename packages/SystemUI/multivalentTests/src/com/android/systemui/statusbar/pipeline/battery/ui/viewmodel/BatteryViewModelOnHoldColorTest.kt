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

package com.android.systemui.statusbar.pipeline.battery.ui.viewmodel

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.runTest
import com.android.systemui.kosmos.testScope
import com.android.systemui.kosmos.useUnconfinedTestDispatcher
import com.android.systemui.lifecycle.activateIn
import com.android.systemui.statusbar.pipeline.battery.shared.ui.BatteryColors
import com.android.systemui.statusbar.pipeline.battery.shared.ui.BatteryGlyph
import com.android.systemui.statusbar.policy.batteryController
import com.android.systemui.statusbar.policy.fake
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The battery is green only while charging. On hold it keeps the normal colour and the shield. */
@SmallTest
@RunWith(AndroidJUnit4::class)
class BatteryViewModelOnHoldColorTest : SysuiTestCase() {
    val kosmos = testKosmos()
    val Kosmos.underTest by Kosmos.Fixture { batteryViewModelBasedOnSettingFactory.create() }

    @Before
    fun setUp() {
        kosmos.useUnconfinedTestDispatcher()
        kosmos.underTest.activateIn(kosmos.testScope)
    }

    @Test
    fun charging_isGreen() =
        kosmos.runTest {
            batteryController.fake._level = 60
            batteryController.fake._isPluggedIn = true
            batteryController.fake._isDefender = false

            assertThat(underTest.colorProfile)
                .isEqualTo(
                    ColorProfile(
                        dark = BatteryColors.DarkTheme.Charging,
                        light = BatteryColors.LightTheme.Charging,
                    )
                )
        }

    @Test
    fun onHold_keepsTheNormalColourWithTheShield() =
        kosmos.runTest {
            batteryController.fake._level = 80
            batteryController.fake._isPluggedIn = true
            batteryController.fake._isDefender = true

            assertThat(underTest.attribution).isEqualTo(BatteryGlyph.Defend)
            assertThat(underTest.colorProfile)
                .isEqualTo(
                    ColorProfile(
                        dark = BatteryColors.DarkTheme.Default,
                        light = BatteryColors.LightTheme.Default,
                    )
                )
        }
}
