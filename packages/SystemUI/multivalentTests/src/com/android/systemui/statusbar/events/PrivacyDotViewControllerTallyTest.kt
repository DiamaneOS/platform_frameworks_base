/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.statusbar.events

import android.graphics.Point
import android.graphics.Rect
import android.location.flags.Flags
import android.platform.test.annotations.EnableFlags
import android.platform.test.annotations.UsesFlags
import android.platform.test.flag.junit.FlagsParameterization
import android.testing.TestableLooper.RunWithLooper
import android.view.Display
import android.view.DisplayAdjustments
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.test.filters.SmallTest
import com.android.systemui.Flags.FLAG_SCENE_CONTAINER
import com.android.systemui.SysuiTestCase
import com.android.systemui.kosmos.backgroundScope
import com.android.systemui.kosmos.runTest
import com.android.systemui.kosmos.useUnconfinedTestDispatcher
import com.android.systemui.privacy.PrivacyApplication
import com.android.systemui.privacy.PrivacyItem
import com.android.systemui.privacy.PrivacyType
import com.android.systemui.res.R
import com.android.systemui.shade.domain.interactor.shadeDisplaysInteractor
import com.android.systemui.shade.domain.interactor.shadeInteractor
import com.android.systemui.statusbar.FakeStatusBarStateController
import com.android.systemui.statusbar.layout.StatusBarContentInsetsProvider
import com.android.systemui.statusbar.policy.FakeConfigurationController
import com.android.systemui.statusbar.quickactions.av.domain.interactor.fakeAvControlsChipInteractor
import com.android.systemui.tally.TallyShell
import com.android.systemui.tally.privacy.TallyIndicatorArea
import com.android.systemui.testKosmos
import com.android.systemui.util.concurrency.DelayableExecutor
import com.android.systemui.util.concurrency.FakeExecutor
import com.android.systemui.util.leak.RotationUtils.ROTATION_LANDSCAPE
import com.android.systemui.util.leak.RotationUtils.ROTATION_NONE
import com.android.systemui.util.leak.RotationUtils.ROTATION_SEASCAPE
import com.android.systemui.util.leak.RotationUtils.ROTATION_UPSIDE_DOWN
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import com.android.systemui.util.time.FakeSystemClock
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Mockito
import platform.test.runner.parameterized.ParameterizedAndroidJunit4
import platform.test.runner.parameterized.Parameters

@SmallTest
@RunWith(ParameterizedAndroidJunit4::class)
@RunWithLooper
@UsesFlags(Flags::class)
class PrivacyDotViewControllerTallyTest(flags: FlagsParameterization) : SysuiTestCase() {

    private val kosmos = testKosmos().useUnconfinedTestDispatcher()
    private val mockDisplay = createMockDisplay()
    private val mockAnimationScheduler = mock<SystemStatusAnimationScheduler>()
    private val context = getContext().createDisplayContext(mockDisplay)

    private val executor = TallyInstantExecutor()
    private val statusBarStateController = FakeStatusBarStateController()
    private val configurationController = FakeConfigurationController()
    private val contentInsetsProvider = createMockContentInsetsProvider()

    private val topLeftView = initDotView()
    private val topRightView = initDotView()
    private val bottomLeftView = initDotView()
    private val bottomRightView = initDotView()

    private fun createAndInitializeController() =
        createController().also {
            it.initialize(topLeftView, topRightView, bottomLeftView, bottomRightView)
        }

    private fun createController() =
        PrivacyDotViewControllerImpl(
            executor,
            kosmos.backgroundScope,
            statusBarStateController,
            configurationController,
            contentInsetsProvider,
            animationScheduler = mockAnimationScheduler,
            shadeInteractor = kosmos.shadeInteractor,
            avControlsChipInteractor = kosmos.fakeAvControlsChipInteractor,
            uiExecutor = executor,
            displayId = DISPLAY_ID,
            shadeDisplaysInteractor = { kosmos.shadeDisplaysInteractor },
        )

    companion object {
        @JvmStatic
        @Parameters(name = "{0}")
        fun getParams(): List<FlagsParameterization> {
            return FlagsParameterization.allCombinationsOf(
                Flags.FLAG_LOCATION_INDICATORS_ENABLED,
                FLAG_SCENE_CONTAINER,
            )
        }
    }

    init {
        mSetFlagsRule.setFlagsParameterization(flags)
    }

    @Test
    @EnableFlags(Flags.FLAG_LOCATION_INDICATORS_ENABLED)
    fun animationCallback_tally_screenCaptureAloneTakesTheCaptureColour() =
        kosmos.runTest {
            assumeTrue(TallyShell.isEnabled)
            val captor = ArgumentCaptor.forClass(SystemStatusAnimationCallback::class.java)
            val controller = createAndInitializeController()
            Mockito.verify(mockAnimationScheduler).addCallback(captor.capture())
            val callback: SystemStatusAnimationCallback = captor.value
            fakeAvControlsChipInteractor.isShowingAvChip.value = false
            val capture =
                PrivacyItem(
                    privacyType = PrivacyType.TYPE_MEDIA_PROJECTION,
                    application = PrivacyApplication(packageName = "com.android", uid = 1),
                )
            val camera =
                PrivacyItem(
                    privacyType = PrivacyType.TYPE_CAMERA,
                    application = PrivacyApplication(packageName = "com.android", uid = 2),
                )

            // Screen capture alone takes the capture colour.
            callback.onSystemStatusAnimationTransitionToPersistentDot(null, listOf(capture))
            assertThat(controller.tallyCaptureOnly).isTrue()

            // A sensor in use as well wins: the sensor colour.
            callback.onSystemStatusAnimationTransitionToPersistentDot(null, listOf(capture, camera))
            assertThat(controller.tallyCaptureOnly).isFalse()

            // Unknown items keep the sensor colour.
            callback.onSystemStatusAnimationTransitionToPersistentDot(null, null)
            assertThat(controller.tallyCaptureOnly).isFalse()
        }

    @Test
    fun tally_areaFlippingEvery50Ms_doesNotHoldBackTheDot() =
        kosmos.runTest {
            assumeTrue(TallyShell.isEnabled)
            // A delayed executor on a fake clock, so the stock 100 ms wait before a state is
            // applied really waits.
            val clock = FakeSystemClock()
            val uiExecutor = FakeExecutor(clock)
            // The area under the dot, which apps influence, flipping every 50 ms.
            val areaDark = MutableStateFlow(true)
            val area = mock<TallyIndicatorArea>()
            whenever(area.isOverlayAreaDark).thenReturn(areaDark)
            val controller =
                PrivacyDotViewControllerImpl(
                    executor,
                    kosmos.backgroundScope,
                    statusBarStateController,
                    configurationController,
                    contentInsetsProvider,
                    animationScheduler = mockAnimationScheduler,
                    shadeInteractor = kosmos.shadeInteractor,
                    avControlsChipInteractor = kosmos.fakeAvControlsChipInteractor,
                    uiExecutor = uiExecutor,
                    displayId = DISPLAY_ID,
                    shadeDisplaysInteractor = { kosmos.shadeDisplaysInteractor },
                    tallyIndicatorArea = { area },
                )
            // Invisible, as privacy_dot_*.xml inflates them.
            val corners = listOf(topLeftView, topRightView, bottomLeftView, bottomRightView)
            corners.forEach { it.visibility = View.INVISIBLE }
            controller.initialize(topLeftView, topRightView, bottomLeftView, bottomRightView)
            val captor = ArgumentCaptor.forClass(SystemStatusAnimationCallback::class.java)
            Mockito.verify(mockAnimationScheduler).addCallback(captor.capture())
            val callback: SystemStatusAnimationCallback = captor.value
            fakeAvControlsChipInteractor.isShowingAvChip.value = false
            fun flipAreaFor(millis: Long) {
                repeat((millis / 50).toInt()) {
                    areaDark.value = !areaDark.value
                    clock.advanceTime(50)
                }
            }
            flipAreaFor(200)

            // A sensor comes into use: the dot shows within the stock 100 ms.
            callback.onSystemStatusAnimationTransitionToPersistentDot(null, null)
            flipAreaFor(100)
            assertThat(controller.currentViewState.shouldShowDot()).isTrue()
            assertThat(topRightView.visibility).isEqualTo(View.VISIBLE)

            // A rotation hides every corner until its state is applied: the dot comes back in its
            // new corner within the same 100 ms.
            setRotation(ROTATION_LANDSCAPE)
            controller.setNewRotation(ROTATION_LANDSCAPE)
            assertThat(bottomRightView.visibility).isEqualTo(View.INVISIBLE)
            flipAreaFor(100)
            assertThat(controller.currentViewState.designatedCorner).isEqualTo(bottomRightView)
            assertThat(bottomRightView.visibility).isEqualTo(View.VISIBLE)

            // The colour still follows the area.
            assertThat(controller.tallyAreaDark).isEqualTo(areaDark.value)
        }

    private fun setRotation(rotation: Int) {
        whenever(mockDisplay.rotation).thenReturn(rotation)
    }

    private fun initDotView(): View {
        val privacyDot = ImageView(context).also { it.id = R.id.privacy_dot }
        return FrameLayout(context).also {
            it.layoutParams = FrameLayout.LayoutParams(/* width= */ 0, /* height= */ 0)
            it.addView(privacyDot)
        }
    }
}

private const val DISPLAY_WIDTH = 1234
private const val DISPLAY_HEIGHT = 2345
private val CONTENT_AREA_ROTATION_SEASCAPE = Rect(left = 10, top = 40, right = 990, bottom = 100)
private val CONTENT_AREA_ROTATION_NONE = Rect(left = 20, top = 30, right = 980, bottom = 100)
private val CONTENT_AREA_ROTATION_LANDSCAPE = Rect(left = 30, top = 20, right = 970, bottom = 100)
private val CONTENT_AREA_ROTATION_UPSIDE_DOWN = Rect(left = 40, top = 10, right = 960, bottom = 100)
private const val DISPLAY_ID = 1

private class TallyInstantExecutor : DelayableExecutor {
    override fun execute(runnable: Runnable) {
        runnable.run()
    }

    override fun executeDelayed(runnable: Runnable, delay: Long, unit: TimeUnit) =
        runnable.apply { run() }

    override fun executeAtTime(runnable: Runnable, uptimeMillis: Long, unit: TimeUnit) =
        runnable.apply { run() }
}

private fun Rect(left: Int, top: Int, right: Int, bottom: Int) = Rect(left, top, right, bottom)

private fun createMockDisplay() =
    mock<Display>().also { display ->
        whenever(display.getRealSize(any(Point::class.java))).thenAnswer { invocation ->
            val output = invocation.arguments[0] as Point
            output.x = DISPLAY_WIDTH
            output.y = DISPLAY_HEIGHT
            return@thenAnswer Unit
        }
        whenever(display.displayAdjustments).thenReturn(DisplayAdjustments())
    }

private fun createMockContentInsetsProvider() =
    mock<StatusBarContentInsetsProvider>().also {
        whenever(it.getStatusBarContentAreaForRotation(ROTATION_SEASCAPE))
            .thenReturn(CONTENT_AREA_ROTATION_SEASCAPE)
        whenever(it.getStatusBarContentAreaForRotation(ROTATION_NONE))
            .thenReturn(CONTENT_AREA_ROTATION_NONE)
        whenever(it.getStatusBarContentAreaForRotation(ROTATION_LANDSCAPE))
            .thenReturn(CONTENT_AREA_ROTATION_LANDSCAPE)
        whenever(it.getStatusBarContentAreaForRotation(ROTATION_UPSIDE_DOWN))
            .thenReturn(CONTENT_AREA_ROTATION_UPSIDE_DOWN)
    }
