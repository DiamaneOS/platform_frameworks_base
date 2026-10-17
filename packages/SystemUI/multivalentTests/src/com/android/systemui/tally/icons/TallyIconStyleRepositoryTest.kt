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

package com.android.systemui.tally.icons

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.shared.settings.data.repository.FakeSecureSettingsRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

/** The icon style SystemUI reads from the setting Launcher keeps for each user. */
@SmallTest
@RunWith(AndroidJUnit4::class)
class TallyIconStyleRepositoryTest : SysuiTestCase() {
    private val testScope = TestScope(StandardTestDispatcher())
    private val settings = FakeSecureSettingsRepository()
    private val repository = TallyIconStyleRepository(testScope.backgroundScope, settings)

    @Test
    fun noSetting_colour() =
        testScope.runTest {
            repository.style.launchIn(backgroundScope)
            runCurrent()
            assertThat(repository.style.value).isEqualTo(TallyIconStyle.COLOUR)
        }

    @Test
    fun followsTheSetting() =
        testScope.runTest {
            repository.style.launchIn(backgroundScope)
            settings.setString(TallyIconStyle.SETTING, "minimal")
            runCurrent()
            assertThat(repository.style.value).isEqualTo(TallyIconStyle.MINIMAL)

            settings.setString(TallyIconStyle.SETTING, "none")
            runCurrent()
            assertThat(repository.style.value).isEqualTo(TallyIconStyle.NONE)

            settings.setString(TallyIconStyle.SETTING, "colour")
            runCurrent()
            assertThat(repository.style.value).isEqualTo(TallyIconStyle.COLOUR)
        }

    @Test
    fun unknownValue_colour() {
        assertThat(TallyIconStyle.fromSetting("glass")).isEqualTo(TallyIconStyle.COLOUR)
        assertThat(TallyIconStyle.fromSetting(null)).isEqualTo(TallyIconStyle.COLOUR)
    }
}
