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

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import com.android.app.animation.Interpolators
import kotlin.math.roundToInt

/**
 * Draws the lens ring (camera in use) and the location lamp by the lens ([TallyLensGeometry]), in
 * the sensor colours for the area under them. Each one appears at once and fades out when its
 * sensor is no longer in use; [onIdle] is called once neither is in use and both have faded.
 */
@SuppressLint("ViewConstructor")
class TallyLensIndicatorView(context: Context, private val onIdle: () -> Unit) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private var geometry: TallyLensGeometry? = null
    private var colors: TallyIndicatorColors? = null

    private val ring = Lamp()
    private val lamp = Lamp()

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    }

    /** Sets where the ring and the lamp are drawn. */
    fun setGeometry(geometry: TallyLensGeometry) {
        this.geometry = geometry
        invalidate()
    }

    /** Sets the colours for the area under the ring and the lamp. */
    fun setColors(colors: TallyIndicatorColors) {
        if (this.colors == colors) return
        this.colors = colors
        invalidate()
    }

    /** Lights the ring while the camera is in use and the lamp while location is in use. */
    fun setInUse(camera: Boolean, location: Boolean) {
        ring.setOn(camera)
        lamp.setOn(location)
    }

    /** Whether the ring or the lamp is lit or still fading out. */
    val isShowing: Boolean
        get() = ring.isVisible || lamp.isVisible

    override fun onDraw(canvas: Canvas) {
        val g = geometry ?: return
        val c = colors ?: return
        drawFaded(canvas, ring.alpha) {
            val edgeBand = g.edgeBand
            val ringBand = g.ringBand
            if (edgeBand != null && ringBand != null) {
                paint.color = c.edge
                canvas.drawPath(edgeBand, paint)
                paint.color = c.fill
                canvas.drawPath(ringBand, paint)
            }
        }
        if (g.hasLamp) {
            drawFaded(canvas, lamp.alpha) {
                paint.color = c.edge
                canvas.drawCircle(g.lampX, g.lampY, g.lampEdgeRadius, paint)
                paint.color = c.fill
                canvas.drawCircle(g.lampX, g.lampY, g.lampRadius, paint)
            }
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        ring.endFade()
        lamp.endFade()
    }

    /** Draws [draw] at [alpha], through a layer while it fades so its parts do not show through. */
    private inline fun drawFaded(canvas: Canvas, alpha: Float, draw: () -> Unit) {
        if (alpha <= 0f) return
        if (alpha >= 1f) {
            draw()
            return
        }
        val saved = canvas.saveLayerAlpha(null, (alpha * 255).roundToInt())
        draw()
        canvas.restoreToCount(saved)
    }

    private fun onFadeEnded() {
        invalidate()
        if (!isShowing) onIdle()
    }

    /** One of the two lights: on at once, off with a fade. */
    private inner class Lamp {
        var alpha = 0f
            private set

        private var on = false
        private var fade: ValueAnimator? = null

        val isVisible: Boolean
            get() = on || alpha > 0f

        fun setOn(on: Boolean) {
            if (on) {
                // A lamp appears with no delay: only its disappearance animates.
                val running = fade
                fade = null
                running?.cancel()
                this.on = true
                alpha = 1f
                invalidate()
                return
            }
            if (!this.on) return
            this.on = false
            val animator = ValueAnimator.ofFloat(alpha, 0f)
            animator.duration = FADE_OUT_MS
            animator.interpolator = Interpolators.ALPHA_OUT
            animator.addUpdateListener {
                if (fade === animator) {
                    alpha = it.animatedValue as Float
                    invalidate()
                }
            }
            animator.addListener(
                object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        if (fade !== animator) return
                        fade = null
                        alpha = 0f
                        onFadeEnded()
                    }
                }
            )
            fade = animator
            animator.start()
        }

        /** Ends a running fade at once. */
        fun endFade() {
            val running = fade ?: return
            fade = null
            running.cancel()
            alpha = if (on) 1f else 0f
        }
    }

    private companion object {
        /** As the privacy dot's own fade out. */
        const val FADE_OUT_MS = 160L
    }
}
