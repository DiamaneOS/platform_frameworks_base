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

package com.android.systemui.tally.lamp

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.View
import kotlin.math.roundToInt

/**
 * The animated Tally lamp as a view. It measures the lamp plus the live lamp's ring of light, so
 * every state fits without moving anything: to line the lamp itself up with other content, give the
 * view a margin (or an offset) of -[haloReachPx] on each side, and the lamp sits where a lamp of
 * its size would. The view's box is at least the token box `tally_lamp_box_<size>`, rounded up so
 * that the lamp lies on whole pixels.
 *
 * The lamp says nothing to accessibility services: put the state in words in the text next to it or
 * in its item's description, as every lamp carries words. Colours follow the view's theme unless
 * [colors] is set; pass colours from a context in the theme of the surface under the lamp (the lock
 * screen's wallpaper theme, for example) and set them again when that theme changes.
 */
class TallyLampView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    View(context, attrs) {

    /** The lamp this view draws. */
    val lamp = TallyLampDrawable(context).also { it.callback = this }

    private var sizeToken: TallyLampSize? = TallyLampSize.DEFAULT
    private var sizeDp = 0f
    private var themeColors = true

    /** The state the lamp shows or is moving to; see [setState]. */
    val state: TallyLampState
        get() = lamp.state

    /** The lamp's colours; setting them stops the view following its theme. */
    var colors: TallyLampColors
        get() = lamp.colors
        set(value) {
            themeColors = false
            lamp.colors = value
        }

    /** See [TallyLampDrawable.instantAppear]: null for at once with sensor colours only. */
    var instantAppear: Boolean?
        get() = lamp.instantAppear
        set(value) {
            lamp.instantAppear = value
        }

    /** The lamp's diameter in pixels. */
    val lampSizePx: Int
        get() = lamp.lampSizePx

    /** How far the view reaches past the lamp on each side, in pixels. */
    val haloReachPx: Int
        get() = lamp.haloReachPx

    /** Shows [state]; see [TallyLampDrawable.setState] for [requestedSinceMillis]. */
    @JvmOverloads
    fun setState(
        state: TallyLampState,
        requestedSinceMillis: Long = TallyLampState.SINCE_FIRST_SHOWN,
    ) = lamp.setState(state, requestedSinceMillis)

    /** Draws the lamp at a token size. */
    fun setLampSize(size: TallyLampSize) {
        sizeToken = size
        applySize()
    }

    /** Draws the lamp at [dp] across (the privacy chip's lamp is 8 dp, for example). */
    fun setLampSizeDp(dp: Float) {
        sizeToken = null
        sizeDp = dp
        applySize()
    }

    /** Follows the view's theme again after [colors] was set. */
    fun useThemeColors() {
        themeColors = true
        lamp.colors = TallyLampColors.theme(context)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            resolveSize(lamp.intrinsicWidth + paddingLeft + paddingRight, widthMeasureSpec),
            resolveSize(lamp.intrinsicHeight + paddingTop + paddingBottom, heightMeasureSpec),
        )
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        lamp.setBounds(paddingLeft, paddingTop, width - paddingRight, height - paddingBottom)
    }

    override fun onDraw(canvas: Canvas) {
        lamp.draw(canvas)
    }

    override fun verifyDrawable(who: Drawable): Boolean = who === lamp || super.verifyDrawable(who)

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        lamp.setVisible(isVisible, false)
    }

    override fun jumpDrawablesToCurrentState() {
        super.jumpDrawablesToCurrentState()
        lamp.jumpToCurrentState()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        lamp.reloadResources(resources)
        applySize()
        requestLayout()
        if (themeColors) lamp.colors = TallyLampColors.theme(context)
    }

    private fun applySize() {
        val px =
            sizeToken?.let { resources.getDimensionPixelSize(it.sizeRes) }
                ?: (sizeDp * resources.displayMetrics.density).roundToInt()
        if (px == lamp.lampSizePx) return
        lamp.setLampSizePx(px)
        requestLayout()
    }
}
