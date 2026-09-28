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

package com.android.settingslib.spa.widget.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF

/**
 * Draws the Tally switch as the prototype's `.tsw` does (app.css):
 * - Dark, a surfaceHigh track in the 2 dp outline border.
 * - Lit, the lamp over the whole switch, its border included, edged with the lit-edge line in
 *   lampOutline (light theme; in dark theme lampOutline is the lamp, so the lamp reaches the edge).
 *   While it wipes in, the lamp covers the switch from its start up to the wipe; once fully lit the
 *   border and track are not drawn under it, so no grey shows at its anti-aliased edge.
 * - The 24 dp square thumb, onLamp when on and outline when off, the colour changing at once.
 * - Pressed, the press layer over the switch's shape (not while disabled: a restricted row that
 *   stays clickable does not press its switch); focused, the focus ring in the accent, outside the
 *   switch (it draws past the switch's bounds).
 * - Disabled, all of it at the disabled-content opacity, as one layer.
 *
 * Right to left, the switch is mirrored: the thumb is on at the left and the lamp wipes in from the
 * right. Nothing is allocated per frame.
 */
internal class TallySwitchPainter {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val rect = RectF()

    /**
     * Draws the switch in the rectangle [left], [top], [right], [bottom] (px) of [canvas].
     *
     * @param thumb the thumb's distance from its start, in dp.
     * @param wipe how far the lamp has wiped in, 0 to 1.
     * @param checked whether the switch is on: the thumb's colour.
     */
    fun draw(
        canvas: Canvas,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        spec: TallySwitchSpec,
        rtl: Boolean,
        thumb: Float,
        wipe: Float,
        checked: Boolean,
        pressed: Boolean,
        focused: Boolean,
        enabled: Boolean,
    ) {
        val width = right - left
        val height = bottom - top
        val lit = wipe.coerceIn(0f, 1f)
        val save =
            if (enabled) canvas.save()
            else {
                rect.set(left, top, right, bottom)
                canvas.saveLayerAlpha(rect, DISABLED_ALPHA)
            }

        if (lit <= FULLY_LIT) {
            // The dark track: its fill ends under the middle of the opaque border.
            rect.set(left, top, right, bottom)
            rect.inset(spec.fillInset, spec.fillInset)
            fill.color = spec.surfaceHigh
            canvas.drawRoundRect(rect, spec.borderRadius, spec.borderRadius, fill)
            rect.set(left, top, right, bottom)
            rect.inset(spec.borderWidth / 2f, spec.borderWidth / 2f)
            stroke.color = spec.outline
            stroke.strokeWidth = spec.borderWidth
            canvas.drawRoundRect(rect, spec.borderRadius, spec.borderRadius, stroke)
        }
        if (lit >= VISIBLE) {
            // The lamp, clipped to the part wiped in so far, from the switch's start.
            val clip = canvas.save()
            val reach = width * lit
            if (rtl) canvas.clipRect(right - reach, top, right, bottom)
            else canvas.clipRect(left, top, left + reach, bottom)
            rect.set(left, top, right, bottom)
            rect.inset(spec.litFillInset, spec.litFillInset)
            fill.color = spec.lamp
            canvas.drawRoundRect(rect, spec.litEdgeRadius, spec.litEdgeRadius, fill)
            rect.set(left, top, right, bottom)
            rect.inset(spec.litEdge / 2f, spec.litEdge / 2f)
            stroke.color = spec.lampOutline
            stroke.strokeWidth = spec.litEdge
            canvas.drawRoundRect(rect, spec.litEdgeRadius, spec.litEdgeRadius, stroke)
            canvas.restoreToCount(clip)
        }

        // The thumb, the same distance from every edge.
        val inset = (height - spec.thumbSize) / 2f
        val offset = thumb * spec.density
        val thumbLeft = if (rtl) right - inset - spec.thumbSize - offset else left + inset + offset
        rect.set(thumbLeft, top + inset, thumbLeft + spec.thumbSize, top + inset + spec.thumbSize)
        fill.color = if (checked) spec.onLamp else spec.outline
        canvas.drawRoundRect(rect, spec.thumbRadius, spec.thumbRadius, fill)

        if (pressed && enabled) {
            rect.set(left, top, right, bottom)
            fill.color = spec.press
            canvas.drawRoundRect(rect, spec.radius, spec.radius, fill)
        }
        canvas.restoreToCount(save)

        if (focused) {
            // The focus ring: its width, the offset clear of the switch, round the switch's shape.
            val out = spec.focusRingOffset + spec.focusRingWidth / 2f
            rect.set(left - out, top - out, right + out, bottom + out)
            stroke.color = spec.accent
            stroke.strokeWidth = spec.focusRingWidth
            canvas.drawRoundRect(rect, spec.radius + out, spec.radius + out, stroke)
        }
    }

    private companion object {
        /** The disabled-content opacity, 38 %, as the switch drawables' colour state lists. */
        const val DISABLED_ALPHA = 97
        /** The prototype hides the lamp below this wipe and drops the border above [FULLY_LIT]. */
        const val VISIBLE = 0.002f
        const val FULLY_LIT = 0.998f
    }
}
