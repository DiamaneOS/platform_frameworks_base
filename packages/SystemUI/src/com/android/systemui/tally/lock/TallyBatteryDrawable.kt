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

package com.android.systemui.tally.lock

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import androidx.annotation.ColorInt
import kotlin.math.min

/**
 * The lamp strip's battery glyph: the Material battery the prototype's strip shows, in one colour,
 * its fill rising with the charge level, and while the battery charges a bolt that is cut out of
 * the fill and drawn in the empty part, as Material's charging glyphs. It has no size of its own
 * and draws its 24-unit box centred in its bounds. The strip gives the item its words and its
 * description; the glyph says nothing.
 */
class TallyBatteryDrawable : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path().apply { fillType = Path.FillType.EVEN_ODD }
    private val body = RectF(7f, 4f, 17f, 22f)
    private var pathValid = false
    private var drawAlpha = 255

    /** The charge level, 0 to 100 (Drawable's own level is not used). */
    var chargeLevel: Int = 0
        set(value) {
            val clamped = value.coerceIn(0, 100)
            if (field == clamped) return
            field = clamped
            pathValid = false
            invalidateSelf()
        }

    /** Whether the battery charges: the glyph shows a bolt. */
    var charging: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            pathValid = false
            invalidateSelf()
        }

    /** The glyph's colour. */
    @ColorInt
    var color: Int = Color.WHITE
        set(value) {
            if (field == value) return
            field = value
            invalidateSelf()
        }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        if (!pathValid) buildPath()
        val scale = min(b.width(), b.height()) / BOX
        paint.color = color
        paint.alpha = Color.alpha(color) * drawAlpha / 255
        val save = canvas.save()
        canvas.translate(b.exactCenterX() - BOX / 2 * scale, b.exactCenterY() - BOX / 2 * scale)
        canvas.scale(scale, scale)
        canvas.drawPath(path, paint)
        canvas.restoreToCount(save)
    }

    /**
     * The glyph in its 24-unit box, filled even-odd: the body (with its 2-unit frame) and the nub,
     * the empty part above the level as a hole in the body, and the bolt, which is a hole where the
     * body is filled and ink where it is empty.
     */
    private fun buildPath() {
        path.rewind()
        path.addRoundRect(body, 1f, 1f, Path.Direction.CW)
        path.addRect(10f, 2f, 14f, 4f, Path.Direction.CW)
        val emptyBottom = INNER_BOTTOM - INNER_HEIGHT * chargeLevel / 100f
        if (chargeLevel < 100) path.addRect(9f, INNER_TOP, 15f, emptyBottom, Path.Direction.CW)
        if (charging) {
            path.moveTo(13.2f, 7f)
            path.lineTo(9.6f, 13.4f)
            path.lineTo(11.8f, 13.4f)
            path.lineTo(10.8f, 19f)
            path.lineTo(14.4f, 12.2f)
            path.lineTo(12.2f, 12.2f)
            path.close()
        }
        pathValid = true
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

    private companion object {
        /** The glyph's box, as Material's 24-unit icons. */
        const val BOX = 24f

        /** The inside of the frame, which the charge fills from the bottom. */
        const val INNER_TOP = 6f
        const val INNER_BOTTOM = 20f
        const val INNER_HEIGHT = INNER_BOTTOM - INNER_TOP
    }
}
