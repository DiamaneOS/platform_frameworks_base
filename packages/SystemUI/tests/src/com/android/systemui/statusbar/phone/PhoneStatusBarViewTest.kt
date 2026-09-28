/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.statusbar.phone

import android.content.Context
import android.content.res.Configuration
import android.graphics.Insets
import android.graphics.Rect
import android.graphics.Region
import android.hardware.display.DisplayManagerGlobal
import android.platform.test.annotations.FlakyTest
import android.testing.TestableLooper.RunWithLooper
import android.view.Display
import android.view.DisplayAdjustments.DEFAULT_DISPLAY_ADJUSTMENTS
import android.view.DisplayCutout
import android.view.DisplayInfo
import android.view.DisplayShape
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.PrivacyIndicatorBounds
import android.view.RoundedCorners
import android.view.View
import android.view.ViewRootImpl
import android.view.WindowInsets
import android.widget.FrameLayout
import androidx.test.filters.SmallTest
import com.android.systemui.Gefingerpoken
import com.android.systemui.SysuiTestCase
import com.android.systemui.SysuiTestableContext
import com.android.systemui.res.R
import com.android.systemui.statusbar.gesture.StatusBarLongPressGestureDetector
import com.android.systemui.statusbar.window.StatusBarWindowController
import com.android.systemui.statusbar.window.StatusBarWindowControllerStore
import com.android.systemui.tally.TallyShell
import com.google.common.truth.Truth.assertThat
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.spy
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.eq
import org.mockito.kotlin.whenever

@FlakyTest(bugId = 406551872)
@SmallTest
@RunWithLooper(setAsMainLooper = true)
class PhoneStatusBarViewTest : SysuiTestCase() {

    private lateinit var view: PhoneStatusBarView
    private lateinit var viewForSecondaryDisplay: PhoneStatusBarView
    private val systemIconsContainer: View
        get() = view.requireViewById(R.id.system_icons)

    @Mock private lateinit var windowController: StatusBarWindowController
    @Mock private lateinit var windowControllerStore: StatusBarWindowControllerStore
    @Mock private lateinit var longPressGestureDetector: StatusBarLongPressGestureDetector

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        whenever(windowControllerStore.defaultDisplay).thenReturn(windowController)
        context.ensureTestableResources()
        view = spy(createStatusBarView(context))
        whenever(view.rootWindowInsets).thenReturn(emptyWindowInsets())
        whenever(view.viewRootImpl).thenReturn(mock(ViewRootImpl::class.java))
        view.updateTouchableRegion(DEFAULT_TOUCHABLE_REGION)

        val contextForSecondaryDisplay =
            SysuiTestableContext(
                mContext.createDisplayContext(
                    Display(
                        DisplayManagerGlobal.getInstance(),
                        2,
                        DisplayInfo(),
                        DEFAULT_DISPLAY_ADJUSTMENTS,
                    )
                )
            )
        viewForSecondaryDisplay = spy(createStatusBarView(contextForSecondaryDisplay))
        whenever(viewForSecondaryDisplay.viewRootImpl).thenReturn(mock(ViewRootImpl::class.java))
        viewForSecondaryDisplay.updateTouchableRegion(DEFAULT_TOUCHABLE_REGION)
    }

    @Test
    fun dispatchTouchEvent_noInteractionGate_listenersNotified() {
        val handler = TestTouchEventHandler()
        viewForSecondaryDisplay.setTouchEventHandler(handler)
        viewForSecondaryDisplay.setLongPressGestureDetector(longPressGestureDetector)

        val event = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, 0f, 0f, 0)
        viewForSecondaryDisplay.dispatchTouchEvent(event)

        assertThat(handler.lastInterceptEvent).isEqualTo(event)
        assertThat(handler.lastEvent).isEqualTo(event)
        verify(longPressGestureDetector).handleTouch(eq(event))
    }

    @Test
    fun dispatchTouchEvent_shouldAllowInteractions_listenersNotified() {
        val handler = TestTouchEventHandler()
        viewForSecondaryDisplay.setTouchEventHandler(handler)
        viewForSecondaryDisplay.setLongPressGestureDetector(longPressGestureDetector)
        viewForSecondaryDisplay.setIsStatusBarInteractiveSupplier { true }

        val event = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, 0f, 0f, 0)
        viewForSecondaryDisplay.dispatchTouchEvent(event)

        assertThat(handler.lastInterceptEvent).isEqualTo(event)
        assertThat(handler.lastEvent).isEqualTo(event)
        verify(longPressGestureDetector).handleTouch(eq(event))
    }

    @Test
    fun dispatchTouchEvent_shouldNotAllowInteractions_consumesEventAndListenersNotNotified() {
        val handler = TestTouchEventHandler()
        viewForSecondaryDisplay.setTouchEventHandler(handler)
        viewForSecondaryDisplay.setLongPressGestureDetector(longPressGestureDetector)
        viewForSecondaryDisplay.setIsStatusBarInteractiveSupplier { false }

        val event = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, 0f, 0f, 0)

        assertThat(viewForSecondaryDisplay.dispatchTouchEvent(event)).isEqualTo(true)
        assertThat(handler.lastInterceptEvent).isNull()
        assertThat(handler.lastEvent).isNull()
        verify(longPressGestureDetector, never()).handleTouch(eq(event))
    }

    @Test
    fun dispatchHoverEvent_noInteractionGate_doesntConsumeEvent() {
        val handler = TestTouchEventHandler()
        viewForSecondaryDisplay.setTouchEventHandler(handler)
        viewForSecondaryDisplay.setLongPressGestureDetector(longPressGestureDetector)

        val event = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, 0f, 0f, 0)
        assertThat(viewForSecondaryDisplay.dispatchHoverEvent(event)).isEqualTo(false)
    }

    @Test
    fun dispatchHoverEvent_shouldAllowInteractions_doesntConsumeEvent() {
        val handler = TestTouchEventHandler()
        viewForSecondaryDisplay.setTouchEventHandler(handler)
        viewForSecondaryDisplay.setLongPressGestureDetector(longPressGestureDetector)
        viewForSecondaryDisplay.setIsStatusBarInteractiveSupplier { true }

        val event = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, 0f, 0f, 0)
        assertThat(viewForSecondaryDisplay.dispatchHoverEvent(event)).isEqualTo(false)
    }

    @Test
    fun dispatchHoverEvent_shouldNotAllowInteractions_consumesEvent() {
        val handler = TestTouchEventHandler()
        viewForSecondaryDisplay.setTouchEventHandler(handler)
        viewForSecondaryDisplay.setLongPressGestureDetector(longPressGestureDetector)
        viewForSecondaryDisplay.setIsStatusBarInteractiveSupplier { false }

        val event = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, 0f, 0f, 0)
        assertThat(viewForSecondaryDisplay.dispatchHoverEvent(event)).isEqualTo(true)
    }

    @Test
    fun onTouchEvent_listenersNotified() {
        val handler = TestTouchEventHandler()
        view.setTouchEventHandler(handler)
        view.setLongPressGestureDetector(longPressGestureDetector)

        val event = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, 0f, 0f, 0)
        view.onTouchEvent(event)

        assertThat(handler.lastEvent).isEqualTo(event)
        verify(longPressGestureDetector).handleTouch(eq(event))
    }

    @Test
    fun onInterceptTouchEvent_listenerNotified() {
        val handler = TestTouchEventHandler()
        view.setTouchEventHandler(handler)

        val event = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, 0f, 0f, 0)
        view.onInterceptTouchEvent(event)

        assertThat(handler.lastInterceptEvent).isEqualTo(event)
    }

    @Test
    fun onInterceptTouchEvent_listenerReturnsFalse_viewReturnsFalse() {
        val handler = TestTouchEventHandler()
        view.setTouchEventHandler(handler)
        val event = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, 0f, 0f, 0)

        handler.handleTouchReturnValue = false

        assertThat(view.onInterceptTouchEvent(event)).isFalse()
    }

    @Test
    fun onInterceptTouchEvent_listenerReturnsTrue_viewReturnsTrue() {
        val handler = TestTouchEventHandler()
        view.setTouchEventHandler(handler)
        val event = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, 0f, 0f, 0)

        handler.handleTouchReturnValue = true

        assertThat(view.onInterceptTouchEvent(event)).isTrue()
    }

    @Test
    fun onTouchEvent_listenerReturnsTrue_viewReturnsTrue() {
        val handler = TestTouchEventHandler()
        view.setTouchEventHandler(handler)
        val event = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, 0f, 0f, 0)

        handler.handleTouchReturnValue = true

        assertThat(view.onTouchEvent(event)).isTrue()
    }

    @Test
    fun onTouchEvent_listenerReturnsFalse_viewReturnsFalse() {
        val handler = TestTouchEventHandler()
        view.setTouchEventHandler(handler)
        val event = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, 0f, 0f, 0)

        handler.handleTouchReturnValue = false

        assertThat(view.onTouchEvent(event)).isFalse()
    }

    @Test
    fun onTouchEvent_noListener_noCrash() {
        view.onTouchEvent(MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, 0f, 0f, 0))
        // No assert needed, just testing no crash
    }

    @Test
    fun onAttachedToWindow_updatesLeftTopRightPaddingsBasedOnInsets() {
        val insets = Insets.of(/* left= */ 10, /* top= */ 20, /* right= */ 30, /* bottom= */ 40)
        view.setInsetsFetcher { insets }

        view.onAttachedToWindow()

        assertThat(view.paddingLeft).isEqualTo(insets.left)
        assertThat(view.paddingTop).isEqualTo(insets.top)
        assertThat(view.paddingRight).isEqualTo(insets.right)
        assertThat(view.paddingBottom).isEqualTo(0)
    }

    @Test
    fun onAttachedToWindow_noInsetsFetcher_noCrash() {
        // Don't call `PhoneStatusBarView.setInsetsFetcher`

        // WHEN the view is attached
        view.onAttachedToWindow()

        // THEN there's no crash, and the padding stays as it was
        assertThat(view.paddingLeft).isEqualTo(0)
        assertThat(view.paddingTop).isEqualTo(0)
        assertThat(view.paddingRight).isEqualTo(0)
        assertThat(view.paddingBottom).isEqualTo(0)
    }

    @Test
    fun onAttachedToWindow_thenGetsInsetsFetcher_insetsUpdated() {
        view.onAttachedToWindow()

        // WHEN the insets fetcher is set after the view is attached
        val insets = Insets.of(/* left= */ 10, /* top= */ 20, /* right= */ 30, /* bottom= */ 40)
        view.setInsetsFetcher { insets }

        // THEN the insets are updated
        assertThat(view.paddingLeft).isEqualTo(insets.left)
        assertThat(view.paddingTop).isEqualTo(insets.top)
        assertThat(view.paddingRight).isEqualTo(insets.right)
        assertThat(view.paddingBottom).isEqualTo(0)
    }

    @Test
    fun onConfigurationChanged_updatesLeftTopRightPaddingsBasedOnInsets() {
        val insets = Insets.of(/* left= */ 40, /* top= */ 30, /* right= */ 20, /* bottom= */ 10)
        view.setInsetsFetcher { insets }

        view.onConfigurationChanged(Configuration())

        assertThat(view.paddingLeft).isEqualTo(insets.left)
        assertThat(view.paddingTop).isEqualTo(insets.top)
        assertThat(view.paddingRight).isEqualTo(insets.right)
        assertThat(view.paddingBottom).isEqualTo(0)
    }

    @Test
    fun onConfigurationChanged_noInsetsFetcher_noCrash() {
        // Don't call `PhoneStatusBarView.setInsetsFetcher`

        // WHEN the view is attached
        view.onConfigurationChanged(Configuration())

        // THEN there's no crash, and the padding stays as it was
        assertThat(view.paddingLeft).isEqualTo(0)
        assertThat(view.paddingTop).isEqualTo(0)
        assertThat(view.paddingRight).isEqualTo(0)
        assertThat(view.paddingBottom).isEqualTo(0)
    }

    @Test
    fun onConfigurationChanged_noRelevantChange_doesNotUpdateInsets() {
        val previousInsets =
            Insets.of(/* left= */ 40, /* top= */ 30, /* right= */ 20, /* bottom= */ 10)
        val newInsets = Insets.NONE

        var useNewInsets = false
        val insetsFetcher =
            PhoneStatusBarView.InsetsFetcher {
                if (useNewInsets) {
                    newInsets
                } else {
                    previousInsets
                }
            }
        view.setInsetsFetcher(insetsFetcher)

        context.orCreateTestableResources.overrideConfiguration(Configuration())
        view.onAttachedToWindow()

        useNewInsets = true
        view.onConfigurationChanged(Configuration())

        assertThat(view.paddingLeft).isEqualTo(previousInsets.left)
        assertThat(view.paddingTop).isEqualTo(previousInsets.top)
        assertThat(view.paddingRight).isEqualTo(previousInsets.right)
        assertThat(view.paddingBottom).isEqualTo(0)
    }

    @Test
    fun onConfigurationChanged_densityChanged_updatesInsets() {
        val previousInsets =
            Insets.of(/* left= */ 40, /* top= */ 30, /* right= */ 20, /* bottom= */ 10)
        val newInsets = Insets.NONE

        var useNewInsets = false
        val insetsFetcher =
            PhoneStatusBarView.InsetsFetcher {
                if (useNewInsets) {
                    newInsets
                } else {
                    previousInsets
                }
            }
        view.setInsetsFetcher(insetsFetcher)

        val configuration = Configuration()
        configuration.densityDpi = 123
        context.orCreateTestableResources.overrideConfiguration(configuration)
        view.onAttachedToWindow()

        useNewInsets = true
        configuration.densityDpi = 456
        view.onConfigurationChanged(configuration)

        assertThat(view.paddingLeft).isEqualTo(newInsets.left)
        assertThat(view.paddingTop).isEqualTo(newInsets.top)
        assertThat(view.paddingRight).isEqualTo(newInsets.right)
        assertThat(view.paddingBottom).isEqualTo(0)
    }

    @Test
    fun onConfigurationChanged_fontScaleChanged_updatesInsets() {
        val previousInsets =
            Insets.of(/* left= */ 40, /* top= */ 30, /* right= */ 20, /* bottom= */ 10)
        val newInsets = Insets.NONE

        var useNewInsets = false
        val insetsFetcher =
            PhoneStatusBarView.InsetsFetcher {
                if (useNewInsets) {
                    newInsets
                } else {
                    previousInsets
                }
            }
        view.setInsetsFetcher(insetsFetcher)

        val configuration = Configuration()
        configuration.fontScale = 1f
        context.orCreateTestableResources.overrideConfiguration(configuration)
        view.onAttachedToWindow()

        useNewInsets = true
        configuration.fontScale = 2f
        view.onConfigurationChanged(configuration)

        assertThat(view.paddingLeft).isEqualTo(newInsets.left)
        assertThat(view.paddingTop).isEqualTo(newInsets.top)
        assertThat(view.paddingRight).isEqualTo(newInsets.right)
        assertThat(view.paddingBottom).isEqualTo(0)
    }

    @Test
    fun onConfigurationChanged_systemIconsHeightChanged_containerHeightIsUpdated() {
        val newHeight = 123456
        context.orCreateTestableResources.addOverride(
            R.dimen.status_bar_icon_container_height,
            newHeight,
        )

        view.onConfigurationChanged(Configuration())

        assertThat(systemIconsContainer.layoutParams.height).isEqualTo(newHeight)
    }

    @Test
    fun onApplyWindowInsets_updatesLeftTopRightPaddingsBasedOnInsets() {
        val insets = Insets.of(/* left= */ 90, /* top= */ 10, /* right= */ 45, /* bottom= */ 50)
        view.setInsetsFetcher { insets }

        view.onApplyWindowInsets(WindowInsets(Rect()))

        assertThat(view.paddingLeft).isEqualTo(insets.left)
        assertThat(view.paddingTop).isEqualTo(insets.top)
        assertThat(view.paddingRight).isEqualTo(insets.right)
        assertThat(view.paddingBottom).isEqualTo(0)
    }

    @Test
    fun tally_unevenInsets_sidesMeetTheCutoutsEdges() {
        assumeTrue(TallyShell.isEnabled)
        // The insets Tally gives the FP6: the rounded corner's 24 dp at the start, the privacy
        // dot's 32 dp at the end.
        attachFp6Like(Insets.of(/* left= */ 72, /* top= */ 0, /* right= */ 96, /* bottom= */ 0))

        // The start side keeps stock's 444 px (148 dp), up to the cutout's left edge; the end
        // side starts at its right edge and takes the rest.
        assertThat(cutoutSpace.visibility).isEqualTo(View.VISIBLE)
        assertThat(cutoutSpace.width).isEqualTo(60)
        assertThat(contents.left + startSide.left).isEqualTo(72 + 12)
        assertThat(contents.left + startSide.right).isEqualTo(528)
        assertThat(contents.left + endSide.left).isEqualTo(588)
        assertThat(contents.left + endSide.right).isEqualTo(1116 - 96 - 6)
    }

    @Test
    fun tally_unevenInsets_rtl_sidesMeetTheCutoutsEdges() {
        assumeTrue(TallyShell.isEnabled)
        view.layoutDirection = View.LAYOUT_DIRECTION_RTL
        // The spy's children still have the inflated view as their parent: set theirs too.
        contents.layoutDirection = View.LAYOUT_DIRECTION_RTL
        assumeTrue(view.layoutDirection == View.LAYOUT_DIRECTION_RTL)
        // In RTL the privacy dot, and its 32 dp, are at the left.
        attachFp6Like(Insets.of(/* left= */ 96, /* top= */ 0, /* right= */ 72, /* bottom= */ 0))

        // The end side, now at the left, ends at the cutout's left edge; the start side keeps
        // stock's 444 px from its right edge.
        assertThat(cutoutSpace.width).isEqualTo(60)
        assertThat(contents.left + endSide.left).isEqualTo(96 + 6)
        assertThat(contents.left + endSide.right).isEqualTo(528)
        assertThat(contents.left + startSide.left).isEqualTo(588)
        assertThat(contents.left + startSide.right).isEqualTo(1116 - 72 - 12)
    }

    @Test
    fun tally_cutoutSpaceGone_sidesShareTheContentAsInStock() {
        assumeTrue(TallyShell.isEnabled)
        attachFp6Like(Insets.of(/* left= */ 72, /* top= */ 0, /* right= */ 96, /* bottom= */ 0))

        // As on rotating to landscape, where the FP6's cutout is not at the top.
        view.setHasCornerCutoutFetcher { true }
        view.requestLayout()
        view.measure(exactly(1116), exactly(144))
        view.layout(0, 0, 1116, 144)

        assertThat(cutoutSpace.visibility).isEqualTo(View.GONE)
        assertThat(startSide.width).isEqualTo((1116 - 72 - 96 - 12 - 6) / 2)
        assertThat(endSide.width).isEqualTo(startSide.width)
    }

    @Test
    fun onTouchEvent_downEventNotHandledIfOutsideTouchableRegion_whenFlagEnabled() {
        val touchableRegion = Region.obtain().apply { set(0, 0, 200, 200) }
        view.updateTouchableRegion(touchableRegion)
        val touchEventHandler = mock(Gefingerpoken::class.java)
        view.setTouchEventHandler(touchEventHandler)

        val event = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, 250f, 250f, 0)
        view.onTouchEvent(event)

        // Assert touch event is not consumed by status bar
        assertThat(view.onTouchEvent(event)).isFalse()
        verify(touchEventHandler, never()).onTouchEvent(event)
    }

    @Test
    fun onTouchEvent_moveEventHandledEvenIfOutsideTouchableRegion() {
        val touchableRegion = Region.obtain().apply { set(100, 100, 200, 200) }
        view.updateTouchableRegion(touchableRegion)
        val touchEventHandler = mock(Gefingerpoken::class.java)
        view.setTouchEventHandler(touchEventHandler)

        val event = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_MOVE, 250f, 250f, 0)
        view.onTouchEvent(event)

        // Assert touch event is handled by status bar
        verify(touchEventHandler).onTouchEvent(event)
    }

    @Test
    fun onTouchEvent_upEventHandledEvenIfOutsideTouchableRegion() {
        val touchableRegion = Region.obtain().apply { set(100, 100, 200, 200) }
        view.updateTouchableRegion(touchableRegion)
        val touchEventHandler = mock(Gefingerpoken::class.java)
        view.setTouchEventHandler(touchEventHandler)

        val event = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_UP, 250f, 250f, 0)
        view.onTouchEvent(event)

        // Assert touch event is handled by status bar
        verify(touchEventHandler).onTouchEvent(event)
    }

    private class TestTouchEventHandler : Gefingerpoken {
        var lastInterceptEvent: MotionEvent? = null
        var lastEvent: MotionEvent? = null
        var handleTouchReturnValue: Boolean = false

        override fun onInterceptTouchEvent(event: MotionEvent?): Boolean {
            lastInterceptEvent = event
            return handleTouchReturnValue
        }

        override fun onTouchEvent(event: MotionEvent?): Boolean {
            lastEvent = event
            return handleTouchReturnValue
        }
    }

    private fun createStatusBarView(context: Context) =
        LayoutInflater.from(context)
            .inflate(
                R.layout.status_bar,
                /* root= */ FrameLayout(context),
                /* attachToRoot = */ false,
            ) as PhoneStatusBarView

    private fun emptyWindowInsets() =
        WindowInsets(
            /* typeInsetsMap = */ arrayOf(),
            /* typeMaxInsetsMap = */ arrayOf(),
            /* typeVisibilityMap = */ booleanArrayOf(),
            /* isRound = */ false,
            /* forceConsumingTypes = */ 0,
            /* forceConsumingOpaqueCaptionBar = */ false,
            /* suppressScrimTypes = */ 0,
            /* displayCutout = */ DisplayCutout.NO_CUTOUT,
            /* roundedCorners = */ RoundedCorners.NO_ROUNDED_CORNERS,
            /* privacyIndicatorBounds = */ PrivacyIndicatorBounds(),
            /* displayShape = */ DisplayShape.NONE,
            /* compatInsetsTypes = */ 0,
            /* compatIgnoreVisibility = */ false,
            /* typeBoundingRectsMap = */ arrayOf(),
            /* typeMaxBoundingRectsMap = */ arrayOf(),
            /* frameWidth = */ 0,
            /* frameHeight = */ 0,
        )

    private val contents: View
        get() = view.requireViewById(R.id.status_bar_contents)

    private val startSide: View
        get() = view.requireViewById(R.id.status_bar_start_side_container)

    private val endSide: View
        get() = view.requireViewById(R.id.status_bar_end_side_container)

    private val cutoutSpace: View
        get() = view.requireViewById(R.id.cutout_space_view)

    /**
     * Attaches, measures and lays out the view on an FP6-like display at density 3: 1116 px wide,
     * with a 60 px cutout at the top centre, and the given insets. The content's padding is 12 px
     * at the start and 6 px at the end, so that the two can be told apart.
     */
    private fun attachFp6Like(insets: Insets) {
        val cutout =
            DisplayCutout(Insets.of(0, 110, 0, 0), null, Rect(528, 0, 588, 110), null, null)
        whenever(view.rootWindowInsets)
            .thenReturn(WindowInsets.Builder().setDisplayCutout(cutout).build())
        context.orCreateTestableResources.apply {
            addOverride(R.dimen.status_bar_padding_start, 12)
            addOverride(R.dimen.status_bar_padding_end, 6)
            addOverride(R.dimen.display_cutout_margin_consumption, 0)
        }
        view.updateResources()
        view.setHasCornerCutoutFetcher { false }
        view.setInsetsFetcher { insets }

        view.onAttachedToWindow()
        view.measure(exactly(1116), exactly(144))
        view.layout(0, 0, 1116, 144)
    }

    private fun exactly(size: Int) =
        View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY)

    companion object {
        val DEFAULT_TOUCHABLE_REGION = Region(0, 0, 500, 500)
    }
}
