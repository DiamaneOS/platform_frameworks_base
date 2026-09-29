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

package com.android.wm.shell.tally

import androidx.test.filters.SmallTest
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * Tally's page motion against the Tally prototype: values its own springs took in the running
 * prototype (harness.js steps each spring in 1 ms substeps, so they drift from the exact curve by
 * up to half of √k·1 ms of the travel early on), its Back mapping, and the springs' shape.
 */
@SmallTest
@RunWith(JUnit4::class)
class TallyPageMotionTest {
    /** The prototype's units: CSS pixels are dp. */
    private val motion = TallyPageMotion(SLAB, FILL, IMPULSE, 1f)

    @Test
    fun openFollowsThePrototype() {
        // [ms, x (dp), opacity] from the prototype's T.pushPage.
        for ((ms, x, alpha) in
            listOf(
                Triple(8.333f, 14.414664f, 0.05048f),
                Triple(50.033f, 8.123292f, 0.602746f),
                Triple(100.033f, 3.795737f, 0.90808f),
                Triple(200.133f, 0.732754f, 1f),
            )) {
            assertEquals(x, motion.openOffset(ms / 1000), tolerance(SLAB, 16f))
            if (alpha < 1f) assertEquals(alpha, motion.openAlpha(ms / 1000), tolerance(FILL, 1f))
        }
    }

    @Test
    fun closeFollowsThePrototype() {
        // [ms, scale, opacity] from the prototype's T.popPage.
        for ((ms, scale, alpha) in
            listOf(
                Triple(8.333f, 0.990092f, 0.94952f),
                Triple(50.033f, 0.95077f, 0.397126f),
                Triple(100.033f, 0.923726f, 0.091946f),
            )) {
            assertEquals(scale, motion.closeScale(1f, ms / 1000), tolerance(SLAB, 0.1f))
            assertEquals(alpha, motion.closeAlpha(ms / 1000), tolerance(FILL, 1f))
        }
    }

    @Test
    fun backFollowsThePrototype() {
        // wm.backMove from the left edge with the finger 200 px lower: translate, scale, radius.
        assertEquals(4f, motion.backShift(0.5f, 1), 1e-4f)
        assertEquals(20f, motion.backFollow(0.5f, 200f), 1e-4f)
        assertEquals(0.95f, motion.backScale(0.5f), 1e-4f)
        assertEquals(10f, motion.cornerRadius(motion.backScale(0.5f)), 1e-4f)
        assertEquals(40f, motion.backFollow(1f, 200f), 1e-4f)
        assertEquals(-4f, motion.backShift(0.5f, -1), 1e-4f)
        assertEquals(-12.5f, motion.backFollow(0.5f, -100f), 1e-4f)
        // A commit from progress 0.5 (scale 0.95), and a cancel from it.
        assertEquals(0.945046f, motion.closeScale(0.95f, 0.008333f), tolerance(SLAB, 0.05f))
        assertEquals(0.925456f, motion.closeScale(0.95f, 0.049837f), tolerance(SLAB, 0.05f))
        assertEquals(
            3.941482f,
            motion.backShift(motion.cancelProgress(0.5f, 0.008333f), 1),
            tolerance(SLAB, 4f),
        )
        assertEquals(
            1.558462f,
            motion.backShift(motion.cancelProgress(0.5f, 0.099801f), 1),
            tolerance(SLAB, 4f),
        )
    }

    @Test
    fun backProgressIsTheFingersTravelOver160Dp() {
        // On a 372 dp wide phone the platform's progress is the finger's travel over 372 dp.
        val phone = TallyPageMotion(SLAB, FILL, IMPULSE, 3f)
        for (dp in listOf(20f, 80f, 160f, 240f)) {
            val k = phone.backProgress(dp / 372f, true, 1116f)
            assertEquals(minOf(1f, dp / 160f), k, 1e-4f)
        }
        // A Back button's progress is taken as it is.
        assertEquals(0.3f, phone.backProgress(0.3f, false, 1116f), 1e-6f)
    }

    @Test
    fun durationsAreTheSettleTimes() {
        val phone = TallyPageMotion(SLAB, FILL, IMPULSE, 3f)
        assertEquals(310L, phone.openMillis)
        assertEquals(193L, phone.closeMillis)
        assertTrue(phone.cancelMillis(0.5f, 1116f, 2484f, 1, 0f) in 300L..450L)
    }

    @Test
    fun springsNeverOvershoot() {
        val slab = TallySpring(SLAB)
        val v = slab.impulse(16f, 0f, IMPULSE)
        var last = 16f
        for (ms in 1..1000) {
            val x = slab.value(16f, 0f, v, ms / 1000f)
            assertTrue(x >= 0f && x <= last)
            last = x
        }
    }

    @Test
    fun halfScaleIsTheSameCurveTwiceAsFast() {
        // The platform plays a curve in half the time at 0.5x: the spring four times as stiff.
        val slab = TallySpring(SLAB)
        val stiff = TallySpring(4 * SLAB)
        for (ms in 0..300 step 10) {
            val t = ms / 1000f
            val v = slab.impulse(1f, 0.9f, IMPULSE)
            val v4 = stiff.impulse(1f, 0.9f, IMPULSE)
            assertEquals(slab.value(1f, 0.9f, v, 2 * t), stiff.value(1f, 0.9f, v4, t), 1e-5f)
        }
    }

    private fun tolerance(stiffness: Float, travel: Float) =
        0.5f * sqrt(stiffness) * 0.001f * travel

    private companion object {
        const val SLAB = 420f
        const val FILL = 1600f
        const val IMPULSE = 0.6f
    }
}
