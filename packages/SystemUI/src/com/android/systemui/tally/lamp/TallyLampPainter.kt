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

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.RectF
import androidx.annotation.ColorInt
import kotlin.math.min

/**
 * Draws a lamp as the prototype's `T.Lamp` does, the same for views and Compose: the ring, the live
 * lamp's ring of light, then the disc over them. The disc is the on disc scaled by the fill, edge
 * included, and fades in its last third; the ring of light fades with its value and grows from 82 %
 * as it comes. Nothing is allocated while drawing.
 */
internal class TallyLampPainter {
    private val ringPaint = strokePaint()
    private val haloPaint = strokePaint()
    private val edgePaint = strokePaint()
    private val discPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val oval = RectF()

    fun setColorFilter(filter: ColorFilter?) {
        ringPaint.colorFilter = filter
        haloPaint.colorFilter = filter
        edgePaint.colorFilter = filter
        discPaint.colorFilter = filter
    }

    /**
     * Draws the lamp centred on ([cx], [cy]) with [alpha] (0 to 255) over everything. Keep the
     * centre on the pixel grid the lamp's size needs (whole pixels for an even size in pixels) so
     * that the ring stays sharp.
     */
    fun draw(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        spec: TallyLampSpec,
        geometry: TallyLampGeometry,
        colors: TallyLampColors,
        motion: TallyLampMotion,
        alpha: Int,
    ) {
        val state = motion.state ?: return
        if (geometry.size <= 0f || alpha <= 0) return
        drawRing(canvas, cx, cy, state, spec, geometry, colors, motion.rotation, alpha)

        val halo = motion.halo
        if (halo > 0f && setColor(haloPaint, colors.halo, halo, alpha)) {
            val scale = if (halo < HALO_FULL) HALO_START + (1f - HALO_START) * halo else 1f
            haloPaint.strokeWidth = geometry.haloWidth * scale
            canvas.drawCircle(cx, cy, geometry.haloRadius * scale, haloPaint)
        }

        val fill = motion.fill
        if (fill > DISC_MIN) {
            val opacity = min(1f, fill * DISC_FADE)
            val radius = geometry.discRadius * fill
            if (setColor(discPaint, colors.lit, opacity, alpha)) {
                canvas.drawCircle(cx, cy, radius, discPaint)
            }
            if (colors.hasLitEdge && setColor(edgePaint, colors.litEdge, opacity, alpha)) {
                edgePaint.strokeWidth = geometry.litEdgeWidth * fill
                canvas.drawCircle(cx, cy, radius, edgePaint)
            }
        }
    }

    private fun drawRing(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        state: TallyLampState,
        spec: TallyLampSpec,
        geometry: TallyLampGeometry,
        colors: TallyLampColors,
        rotation: Float,
        alpha: Int,
    ) {
        val color =
            when (state) {
                TallyLampState.OFF -> colors.off
                TallyLampState.REQUESTED -> colors.requested
                TallyLampState.FAILED -> colors.failed
                TallyLampState.UNAVAILABLE -> colors.unavailable
                TallyLampState.ON,
                TallyLampState.LIVE -> return
            }
        if (!setColor(ringPaint, color, 1f, alpha)) return
        ringPaint.strokeWidth = geometry.ringWidth
        val r = geometry.ringRadius
        oval.set(cx - r, cy - r, cx + r, cy + r)
        when (state) {
            TallyLampState.REQUESTED -> {
                // Dashes from three o'clock clockwise, as an SVG circle's dash pattern starts.
                val period = spec.requestedDashDegrees + spec.requestedGapDegrees
                var start = 0f
                while (start < FULL_TURN - EPSILON) {
                    canvas.drawArc(
                        oval,
                        rotation + start,
                        spec.requestedDashDegrees,
                        false,
                        ringPaint,
                    )
                    start += period
                }
            }
            TallyLampState.FAILED ->
                canvas.drawArc(
                    oval,
                    spec.failedStartDegrees,
                    FULL_TURN - spec.failedGapDegrees,
                    false,
                    ringPaint,
                )
            else -> canvas.drawCircle(cx, cy, r, ringPaint)
        }
    }

    /** Sets [paint] to [color] at [opacity] and [alpha]; false when that draws nothing. */
    private fun setColor(paint: Paint, @ColorInt color: Int, opacity: Float, alpha: Int): Boolean {
        val a = (Color.alpha(color) * opacity * alpha / 255f + 0.5f).toInt()
        if (a <= 0) return false
        paint.color = color
        paint.alpha = min(a, 255)
        return true
    }

    private companion object {
        /** The prototype's ring of light grows from 82 % and is whole from 0.999. */
        const val HALO_START = 0.82f
        const val HALO_FULL = 0.999f
        /** The prototype's disc draws above this fill and is opaque from a third of it. */
        const val DISC_MIN = 0.001f
        const val DISC_FADE = 3f
        const val FULL_TURN = 360f
        const val EPSILON = 0.01f

        fun strokePaint() =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.BUTT
            }
    }
}
