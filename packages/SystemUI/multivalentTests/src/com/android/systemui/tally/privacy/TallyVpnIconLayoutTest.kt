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

package com.android.systemui.tally.privacy

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class TallyVpnIconLayoutTest : SysuiTestCase() {

    // Five icons 20 wide and 4 apart, laid out from an end edge at 100: they start at -16, 8, 32,
    // 56 and 80. The overflow dot takes 12 from 0, so stock puts the first two in the overflow.
    private val x = floatArrayOf(-16f, 8f, 32f, 56f, 80f)

    private fun keptStart(
        cut: Int,
        maxVisible: Int = 7,
        restricted: Boolean = true,
        endEdge: Float = 100f,
        positions: FloatArray = x,
    ) =
        TallyVpnIconLayout.keptStart(
            positions,
            cut = cut,
            vpnWidth = 20f,
            spacing = 4f,
            endEdge = endEdge,
            contentStart = 0f,
            underflowWidth = 12f,
            maxVisible = maxVisible,
            restricted = restricted,
        )

    @Test
    fun vpnIconTakesTheRoomOfTheNearestIconAfterIt() {
        // Just before the icon at 32 the VPN icon would start at 8, over the dot: the icon at 32
        // gives way, and the VPN icon goes just before the icon at 56.
        assertThat(keptStart(cut = 1)).isEqualTo(3)
    }

    @Test
    fun atMostMaxVisibleIconsShow() {
        // Eight icons that all fit before an end edge at 200, of which stock shows the last six:
        // to keep the VPN icon, one more of them gives way.
        val eight = floatArrayOf(12f, 36f, 60f, 84f, 108f, 132f, 156f, 180f)
        assertThat(keptStart(cut = 1, maxVisible = 6, endEdge = 200f, positions = eight))
            .isEqualTo(3)
    }

    @Test
    fun withoutRestrictionOnlyTheWidthCounts() {
        // Eight icons before an end edge at 184: only the first is over the dot. Restricted to six,
        // two give way; without the restriction one does.
        val eight = floatArrayOf(-4f, 20f, 44f, 68f, 92f, 116f, 140f, 164f)
        assertThat(keptStart(cut = 0, maxVisible = 6, endEdge = 184f, positions = eight))
            .isEqualTo(3)
        assertThat(
                keptStart(
                    cut = 0,
                    maxVisible = 6,
                    restricted = false,
                    endEdge = 184f,
                    positions = eight,
                )
            )
            .isEqualTo(2)
    }

    @Test
    fun whenEvenTheVpnIconAloneDoesNotFit_stockLayoutStays() {
        assertThat(keptStart(cut = 0, endEdge = 30f, positions = floatArrayOf(10f))).isEqualTo(-1)
    }
}
