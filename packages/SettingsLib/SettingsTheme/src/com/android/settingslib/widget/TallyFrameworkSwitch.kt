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

package com.android.settingslib.widget

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.os.Build
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.widget.Switch
import androidx.annotation.RequiresApi
import kotlin.math.abs

/**
 * The Tally switch as an [android.widget.Switch], for layouts whose code takes their switch as a
 * `Switch`: the same drawing and motion as [TallySwitch], and nothing else changed. Checking,
 * clicks, dragging, accessibility, saved state and the enabled state stay [Switch]'s.
 *
 * [Switch] slides its own thumb over 250 ms when it changes; that slide is ended at once, so the
 * thumb moves on the Tally spring instead, and a finger dragging the thumb still moves it. It draws
 * no background (the press layer is its press feedback) and only its own text, if any.
 *
 * From API 34, as the colour roles it reads.
 */
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
open class TallyFrameworkSwitch
@JvmOverloads
constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.switchStyle,
    defStyleRes: Int = 0,
) : Switch(context, attrs, defStyleAttr, defStyleRes) {

    // Null while the parent constructors run, which call some of the overrides below.
    private var host: TallySwitchHost? = null
    private var touching = false

    init {
        val h = TallySwitchHost(this)
        background = null
        thumbTintList = null
        trackTintList = null
        applySizes(h)
        host = h
    }

    override fun setChecked(checked: Boolean) {
        super.setChecked(checked)
        val h = host ?: return
        // End Switch's own slide: the thumb moves on the Tally spring.
        super.jumpDrawablesToCurrentState()
        h.onCheckedSet()
    }

    override fun jumpDrawablesToCurrentState() {
        super.jumpDrawablesToCurrentState()
        host?.jumpToCurrentState()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val h = host ?: return super.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> touching = true
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> touching = false
        }
        val handled = super.onTouchEvent(event)
        // Switch moves its thumb under a dragging finger when it next draws: look then.
        if (event.actionMasked == MotionEvent.ACTION_MOVE) invalidate()
        h.onTouchEnded(event)
        return handled
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        // The press layer follows the pressed state.
        if (host != null) invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        host?.onAttached()
    }

    override fun onDetachedFromWindow() {
        touching = false
        host?.onDetached()
        super.onDetachedFromWindow()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val h = host ?: return
        h.onConfigurationChanged()
        applySizes(h)
    }

    override fun onDraw(canvas: Canvas) {
        val h = host
        // Switch draws only the switch's own text, if it has any: its thumb and track drawables
        // have a size and draw nothing.
        if (h == null || !text.isNullOrEmpty()) super.onDraw(canvas)
        if (h == null) return
        // Switch.draw has just placed the track on the switch's rectangle and the thumb where
        // Switch holds it.
        val track = trackDrawable?.bounds ?: return
        val thumb = thumbDrawable?.bounds ?: return
        val range = track.width() - (thumb.width() - 2 * h.thumbPadding) - 2 * h.trackPadding
        var fraction = 0f
        if (range > 0) {
            val offset = thumb.left - track.left - h.trackPadding + h.thumbPadding
            fraction = offset.toFloat() / range
            if (layoutDirection == View.LAYOUT_DIRECTION_RTL) fraction = 1f - fraction
            // Away from where the switch rests while a finger is down: it is being dragged.
            val rest = if (isChecked) 1f else 0f
            if (touching && !h.isDragging && abs(fraction - rest) * range >= 1f) h.onDragged()
        }
        h.draw(canvas, track, fraction)
    }

    /** The thumb and track drawables Switch measures with: the Tally switch's size. */
    private fun applySizes(h: TallySwitchHost) {
        thumbDrawable = h.thumbSizeDrawable()
        trackDrawable = h.trackSizeDrawable()
    }
}
