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

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import androidx.annotation.ColorInt
import androidx.core.graphics.ColorUtils
import com.android.systemui.tally.lamp.TallyLampColors
import com.android.systemui.tally.lamp.TallyLampDrawable
import com.android.systemui.tally.lamp.TallyLampSize
import com.android.systemui.tally.lamp.TallyLampState

/**
 * Tally's privacy dot: the shared live lamp ([TallyLampDrawable], 10 dp) on a backing, in the
 * colours for the area under it ([TallyIndicatorColors]), as the prototype's 16 dp dot. The backing
 * is a 15.5 dp disc of the colour drawn on the lamp's colour at 72 %; it keeps the lamp visible on
 * any picture. The lamp lights at once, as every privacy lamp.
 */
class TallyPrivacyDotDrawable(context: Context, private val sizePx: Int) :
    Drawable(), Drawable.Callback {

    private val lamp =
        TallyLampDrawable(context).also {
            it.setLampSize(TallyLampSize.SMALL)
            it.instantAppear = true
            it.callback = this
        }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private var drawAlpha = 255

    @ColorInt private var backingColor = 0

    /** The dot's size in pixels, which follows the density it was made for. */
    val dotSizePx: Int
        get() = sizePx

    /** Sets the dot's colours from the indicator colours for its area; the lamp is live. */
    fun setColors(colors: TallyIndicatorColors) {
        lamp.colors = TallyLampColors.plain(colors.fill, sensor = true)
        lamp.setState(TallyLampState.LIVE)
        val backing = ColorUtils.setAlphaComponent(colors.onFill, BACKING_ALPHA)
        if (backingColor == backing) return
        backingColor = backing
        invalidateSelf()
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        paint.color = backingColor
        paint.alpha = paint.alpha * drawAlpha / 255
        canvas.drawCircle(
            b.exactCenterX(),
            b.exactCenterY(),
            minOf(b.width(), b.height()) * BACKING_RADIUS,
            paint,
        )
        lamp.draw(canvas)
    }

    override fun onBoundsChange(bounds: Rect) {
        lamp.bounds = bounds
    }

    override fun setVisible(visible: Boolean, restart: Boolean): Boolean {
        lamp.setVisible(visible, restart)
        return super.setVisible(visible, restart)
    }

    override fun jumpToCurrentState() {
        lamp.jumpToCurrentState()
    }

    override fun getIntrinsicWidth(): Int = sizePx

    override fun getIntrinsicHeight(): Int = sizePx

    override fun setAlpha(alpha: Int) {
        if (drawAlpha == alpha) return
        drawAlpha = alpha
        lamp.alpha = alpha
        invalidateSelf()
    }

    override fun getAlpha(): Int = drawAlpha

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        lamp.colorFilter = colorFilter
        invalidateSelf()
    }

    @Suppress("DeprecatedCallableAddReplaceWith")
    @Deprecated("Deprecated in android.graphics.drawable.Drawable")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    // The lamp's frames and redraws go through the dot to its host.
    override fun invalidateDrawable(who: Drawable) {
        invalidateSelf()
    }

    override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) {
        scheduleSelf(what, `when`)
    }

    override fun unscheduleDrawable(who: Drawable, what: Runnable) {
        unscheduleSelf(what)
    }

    private companion object {
        /** The backing is the colour drawn on the lamp's colour at 72 %. */
        const val BACKING_ALPHA = 184

        /** The backing's radius as a part of the dot's size: 7.75 of 16, as in the prototype. */
        const val BACKING_RADIUS = 7.75f / 16f
    }
}
