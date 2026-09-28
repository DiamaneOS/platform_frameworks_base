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

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import androidx.annotation.ColorInt
import androidx.core.graphics.ColorUtils

/**
 * Tally's privacy dot: a live lamp on a backing, in the sensor colour for the area under it
 * ([TallyIndicatorColors]). On a 16 dp dot, as in the prototype: the backing is a 15.5 dp disc of
 * the colour drawn on the sensor colour at 72 %, the lamp an 8.5 dp disc with a 1.5 dp ring of
 * light around it (12.2 dp across its middle). The backing keeps the lamp visible on any picture.
 */
class TallyPrivacyDotDrawable(private val sizePx: Int) : Drawable() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var drawAlpha = 255

    @ColorInt private var lampColor = 0
    @ColorInt private var backingColor = 0

    /** Sets the dot's colours from the indicator colours for its area. */
    fun setColors(colors: TallyIndicatorColors) {
        val backing = ColorUtils.setAlphaComponent(colors.onFill, BACKING_ALPHA)
        if (lampColor == colors.fill && backingColor == backing) return
        lampColor = colors.fill
        backingColor = backing
        invalidateSelf()
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val cx = b.exactCenterX()
        val cy = b.exactCenterY()
        // The prototype draws the dot on a square 16 units across: these are its radii in units.
        val unit = minOf(b.width(), b.height()) / 16f
        paint.style = Paint.Style.FILL
        setPaintColor(backingColor)
        canvas.drawCircle(cx, cy, 7.75f * unit, paint)
        setPaintColor(lampColor)
        canvas.drawCircle(cx, cy, 4.25f * unit, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.5f * unit
        canvas.drawCircle(cx, cy, 6.1f * unit, paint)
    }

    override fun getIntrinsicWidth(): Int = sizePx

    override fun getIntrinsicHeight(): Int = sizePx

    override fun setAlpha(alpha: Int) {
        if (drawAlpha == alpha) return
        drawAlpha = alpha
        invalidateSelf()
    }

    override fun getAlpha(): Int = drawAlpha

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Suppress("DeprecatedCallableAddReplaceWith")
    @Deprecated("Deprecated in android.graphics.drawable.Drawable")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    private fun setPaintColor(@ColorInt color: Int) {
        paint.color = color
        paint.alpha = paint.alpha * drawAlpha / 255
    }

    private companion object {
        /** The backing is the colour drawn on the sensor colour at 72 %. */
        const val BACKING_ALPHA = 184
    }
}
