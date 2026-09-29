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

import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import android.view.animation.AnimationUtils
import android.widget.CompoundButton
import androidx.annotation.RequiresApi
import com.android.settingslib.widget.theme.R

/**
 * What [TallySwitch] and [TallyFrameworkSwitch] share: their drawing, motion, press layer and focus
 * ring. The views forward their checked changes, touches, draws and attachment here and leave
 * everything else (checking, clicks, dragging, accessibility, saved state) to their parents.
 */
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
internal class TallySwitchHost(private val view: CompoundButton) {
    var spec = TallySwitchSpec.from(view.context)
        private set

    val motion = TallySwitchMotion().apply { setSpec(spec) }

    /** The thumb drawable's padding on each side, px (what the parent's thumb width leaves out). */
    var thumbPadding = 0
        private set

    /** The track drawable's padding on each side, px. */
    var trackPadding = 0
        private set

    /**
     * What the system has confirmed for what the switch controls (see [TallySwitch.confirmedOn]):
     * null lights the lamp with the checked state; otherwise the lamp lights only while the switch
     * is checked and this is true.
     */
    var confirmedOn: Boolean? = null
        set(value) {
            if (field == value) return
            field = value
            onLampChanged()
        }

    private val painter = TallySwitchPainter()
    private var dragging = false
    private var focused = false
    private val focusListener =
        ViewTreeObserver.OnGlobalFocusChangeListener { _, _ -> updateFocus() }
    private val touchModeListener = ViewTreeObserver.OnTouchModeChangeListener { updateFocus() }

    init {
        motion.jumpTo(view.isChecked, view.isChecked)
        readPaddings()
    }

    /** Whether the lamp lights: never while unchecked, and while waiting only once confirmed. */
    private val lampLit: Boolean
        get() = view.isChecked && confirmedOn != false

    /** The thumb and track drawables the parent measures and places: the Tally switch's size. */
    fun thumbSizeDrawable(): Drawable {
        val res = view.resources
        val size = res.getDimensionPixelSize(R.dimen.settingslib_tally_switch_thumb_size)
        val inset = res.getDimensionPixelOffset(R.dimen.settingslib_tally_switch_thumb_inset)
        return SizeDrawable(size, size + 2 * inset, thumbPadding)
    }

    fun trackSizeDrawable(): Drawable {
        val res = view.resources
        return SizeDrawable(
            res.getDimensionPixelSize(R.dimen.settingslib_tally_switch_width),
            res.getDimensionPixelSize(R.dimen.settingslib_tally_switch_height),
            trackPadding,
        )
    }

    private fun readPaddings() {
        val res = view.resources
        thumbPadding = res.getDimensionPixelOffset(R.dimen.settingslib_tally_switch_thumb_padding)
        trackPadding = res.getDimensionPixelOffset(R.dimen.settingslib_tally_switch_track_padding)
    }

    /** After the parent's setChecked: moves to its state, or shows it at once when not shown. */
    fun onCheckedSet() {
        val checked = view.isChecked
        // As the parents do: move only when attached and laid out, so a bind shows it at once.
        if (view.isAttachedToWindow && view.isLaidOut) {
            val scale = durationScale()
            motion.animateTo(checked, lampLit, scale)
            if (dragging) motion.releaseThumb(scale)
        } else {
            motion.jumpTo(checked, lampLit)
        }
        view.invalidate()
    }

    /** After [confirmedOn] changes: the lamp moves as for a checked change; the thumb stays. */
    private fun onLampChanged() {
        if (view.isAttachedToWindow && view.isLaidOut) {
            motion.animateTo(view.isChecked, lampLit, durationScale())
        } else {
            motion.jumpTo(view.isChecked, lampLit)
        }
        view.invalidate()
    }

    /** A finger is dragging the thumb (the parent moves its own thumb position). */
    fun onDragged() {
        dragging = true
        view.invalidate()
    }

    fun onTouchEnded(event: MotionEvent) {
        val action = event.actionMasked
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            dragging = false
        }
    }

    val isDragging: Boolean
        get() = dragging

    fun jumpToCurrentState() {
        motion.jumpToTargets()
        view.invalidate()
    }

    fun onAttached() {
        view.viewTreeObserver.addOnGlobalFocusChangeListener(focusListener)
        view.viewTreeObserver.addOnTouchModeChangeListener(touchModeListener)
        updateFocus()
    }

    fun onDetached() {
        view.viewTreeObserver.removeOnGlobalFocusChangeListener(focusListener)
        view.viewTreeObserver.removeOnTouchModeChangeListener(touchModeListener)
        focused = false
        dragging = false
        motion.jumpToTargets()
    }

    /** Dark theme and density have their own values. */
    fun onConfigurationChanged() {
        spec = TallySwitchSpec.from(view.context)
        motion.setSpec(spec)
        readPaddings()
        view.invalidate()
    }

    /**
     * Draws the switch in [track], the rectangle the parent has just placed its track in, with the
     * thumb where a dragging finger holds it ([dragFraction], 0 off to 1 on) or where it moves.
     */
    fun draw(canvas: Canvas, track: Rect, dragFraction: Float) {
        if (track.isEmpty) return
        if (dragging) {
            motion.holdThumb(dragFraction.coerceIn(0f, 1f) * spec.travelDp)
        } else {
            val now = AnimationUtils.currentAnimationTimeMillis() * NANOS_PER_MILLI
            motion.advance(now, durationScale())
        }
        painter.draw(
            canvas,
            track.left.toFloat(),
            track.top.toFloat(),
            track.right.toFloat(),
            track.bottom.toFloat(),
            spec,
            rtl = view.layoutDirection == View.LAYOUT_DIRECTION_RTL,
            thumb = motion.thumb,
            wipe = motion.wipe,
            // The thumb takes the lamp's ink with the lamp: a thumb waiting for the system keeps
            // the off colour, readable on the unlit track (onLamp there is 1.2:1 in dark theme).
            checked = lampLit,
            pressed = view.isPressed,
            focused = focused,
            enabled = view.isEnabled,
        )
        if (motion.moving) view.postInvalidateOnAnimation()
    }

    private fun updateFocus() {
        val host = focusHost()
        val now = host != null && host.isFocused && !view.isInTouchMode
        if (now != focused) {
            focused = now
            view.invalidate()
        }
    }

    /** The view whose keyboard focus is the switch's: the switch itself, or the row toggling it. */
    private fun focusHost(): View? {
        var v: View? = view
        while (v != null) {
            if (v.isFocusable && v.isClickable) return v
            v = v.parent as? View
        }
        return null
    }

    private fun durationScale(): Float = ValueAnimator.getDurationScale()

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
    }

    /**
     * A drawable that only has a size and padding, so the parent lays the switch out as the Tally
     * switch drawables would (`settingslib_tally_switch_thumb` and `_track`) and draws nothing.
     */
    private class SizeDrawable(
        private val width: Int,
        private val height: Int,
        private val horizontalPadding: Int,
    ) : Drawable() {
        override fun draw(canvas: Canvas) {}

        override fun setAlpha(alpha: Int) {}

        override fun setColorFilter(colorFilter: ColorFilter?) {}

        @Deprecated("Deprecated in Drawable")
        override fun getOpacity(): Int = PixelFormat.TRANSPARENT

        override fun getIntrinsicWidth(): Int = width

        override fun getIntrinsicHeight(): Int = height

        override fun getPadding(padding: Rect): Boolean {
            padding.set(horizontalPadding, 0, horizontalPadding, 0)
            return horizontalPadding != 0
        }
    }
}
