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
import androidx.annotation.RequiresApi
import com.google.android.material.materialswitch.MaterialSwitch

/**
 * The Tally switch: a [MaterialSwitch] that draws and moves as the prototype's switch (`T.tswitch`)
 * and changes nothing else.
 *
 * It subclasses [MaterialSwitch] because that is the class SettingsLib's expressive switch layouts
 * inflate and SystemUI's code casts to, so it is also the `SwitchCompat` that
 * `SwitchPreferenceCompat` binds and a `CompoundButton`. Checking, clicks, dragging, the
 * accessibility role and state descriptions, saved state and the enabled state stay its parents'.
 * Like `SwitchCompat`, it moves only when it changes while attached and laid out, so a switch bound
 * to a preference shows its state at once.
 *
 * It draws only the switch (and its own text, if it has any), from the values generated from the
 * Tally token spec: the thumb and track drawables it measures with are the Tally switch's size and
 * draw nothing, whatever its style sets. The press layer shows while it is pressed, directly or
 * through the row that toggles it; the focus ring while that row, or the switch when it takes focus
 * itself, has keyboard focus. See [TallySwitchPainter] and [TallySwitchMotion].
 *
 * The lamp lights with the checked state, unless the switch waits for the system to confirm a
 * change (see [confirmedOn]).
 *
 * From API 34, as the colour roles it reads.
 */
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
open class TallySwitch
@JvmOverloads
constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = com.google.android.material.R.attr.materialSwitchStyle,
) : MaterialSwitch(context, attrs, defStyleAttr) {

    // Null while the parent constructors run, which call some of the overrides below.
    private var host: TallySwitchHost? = null

    init {
        val h = TallySwitchHost(this)
        thumbIconDrawable = null
        trackDecorationDrawable = null
        thumbTintList = null
        trackTintList = null
        applySizes(h)
        host = h
    }

    /**
     * For a switch whose change takes effect later (Wi-Fi, Bluetooth): whether the system has
     * confirmed that what the switch controls is on. The thumb follows the checked state at once;
     * the lamp lights only while the switch is checked and this is true, so it waits for the
     * system, and a switch that is not confirmed shows unlit. Null, the default, lights the lamp
     * with the checked state, for a change that takes effect at once. Only the drawing follows it:
     * checking, clicks and accessibility stay the switch's.
     */
    var confirmedOn: Boolean?
        get() = host?.confirmedOn
        set(value) {
            host?.confirmedOn = value
        }

    override fun setChecked(checked: Boolean) {
        super.setChecked(checked)
        host?.onCheckedSet()
    }

    override fun jumpDrawablesToCurrentState() {
        super.jumpDrawablesToCurrentState()
        host?.jumpToCurrentState()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val h = host ?: return super.onTouchEvent(event)
        val before = thumbPosition
        val handled = super.onTouchEvent(event)
        // SwitchCompat moves its thumb under a dragging finger; the Tally thumb follows it.
        if (event.actionMasked == MotionEvent.ACTION_MOVE && thumbPosition != before) h.onDragged()
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
        // The parents draw only the switch's own text, if it has any: its thumb and track
        // drawables have a size and draw nothing.
        if (h == null || !text.isNullOrEmpty()) super.onDraw(canvas)
        // SwitchCompat.draw has just placed the track on the switch's rectangle.
        val track = trackDrawable ?: return
        h?.draw(canvas, track.bounds, thumbPosition)
    }

    /** The thumb and track drawables SwitchCompat measures with: the Tally switch's size. */
    private fun applySizes(h: TallySwitchHost) {
        thumbDrawable = h.thumbSizeDrawable()
        trackDrawable = h.trackSizeDrawable()
    }
}
