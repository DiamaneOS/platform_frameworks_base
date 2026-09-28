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
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import androidx.annotation.ColorInt
import kotlin.math.ceil

/**
 * The background of a Tally status bar chip: a rounded rectangle in the fill colour and, just
 * outside it, the edge ([TallyIndicatorColors]). The edge is drawn outside the drawable's bounds,
 * so the fill keeps the chip's full height; the outline includes the edge, so a view that clips to
 * its outline keeps it. The view's parent must not clip its children.
 */
class TallyChipDrawable(private val cornerRadius: Float, private val edgeWidth: Float) :
    Drawable() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private var drawAlpha = 255

    @ColorInt private var fillColor = 0
    @ColorInt private var edgeColor = 0

    /** Sets the chip's fill and edge colours. */
    fun setColors(colors: TallyIndicatorColors) {
        if (fillColor == colors.fill && edgeColor == colors.edge) return
        fillColor = colors.fill
        edgeColor = colors.edge
        invalidateSelf()
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val radius = radiusFor(b.width(), b.height())
        setPaintColor(edgeColor)
        canvas.drawRoundRect(
            b.left - edgeWidth,
            b.top - edgeWidth,
            b.right + edgeWidth,
            b.bottom + edgeWidth,
            radius + edgeWidth,
            radius + edgeWidth,
            paint,
        )
        setPaintColor(fillColor)
        canvas.drawRoundRect(
            b.left.toFloat(),
            b.top.toFloat(),
            b.right.toFloat(),
            b.bottom.toFloat(),
            radius,
            radius,
            paint,
        )
    }

    override fun getOutline(outline: Outline) {
        val b = bounds
        val edge = ceil(edgeWidth).toInt()
        outline.setRoundRect(
            b.left - edge,
            b.top - edge,
            b.right + edge,
            b.bottom + edge,
            radiusFor(b.width(), b.height()) + edge,
        )
    }

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

    private fun radiusFor(width: Int, height: Int): Float =
        minOf(cornerRadius, width / 2f, height / 2f)

    private fun setPaintColor(@ColorInt color: Int) {
        paint.color = color
        paint.alpha = paint.alpha * drawAlpha / 255
    }
}
