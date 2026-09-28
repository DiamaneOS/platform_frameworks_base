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
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.view.View
import com.android.systemui.tally.lamp.TallyLampColors
import com.android.systemui.tally.lamp.TallyLampDrawable
import com.android.systemui.tally.lamp.TallyLampState
import de.diamaneos.tally.R as TallyR
import kotlin.math.roundToInt

/**
 * Draws the lens ring (camera in use) and the location lamp by the lens ([TallyLensGeometry]), in
 * the sensor colours for the area under them. Each one appears at once. When its sensor is no
 * longer in use, the ring fades out on the prototype's fill spring ([TallyFillSpringFade]) and the
 * lamp, the shared Tally lamp, goes out as every lamp does, on the same spring; [onIdle] is called
 * once neither is in use and both are out.
 */
@SuppressLint("ViewConstructor")
class TallyLensIndicatorView(context: Context, private val onIdle: () -> Unit) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private var geometry: TallyLensGeometry? = null
    private var colors: TallyIndicatorColors? = null
    private val fadeOut = TallyFillSpringFade.from(resources)

    /** The lens ring's opacity. */
    private val ring = Light()

    /** The location lamp by the lens: lit at once, as a privacy lamp, and going out on its own. */
    private val lamp =
        TallyLampDrawable(context).also {
            it.instantAppear = true
            it.callback = this
        }

    /** Keeps the window while the lamp goes out: the same spring, so both are done together. */
    private val lampOut = Light()

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    }

    /**
     * Sets where the ring and the lamp are drawn. The lamp is 8 dp lit, with its lit edge
     * (`tally_stroke_lit_edge`, 1.5 dp, the lens lamp's edge) just outside that.
     */
    fun setGeometry(geometry: TallyLensGeometry) {
        this.geometry = geometry
        lamp.reloadResources(resources)
        val litEdge = resources.getDimension(TallyR.dimen.tally_stroke_lit_edge)
        lamp.setLampSizePx((2 * geometry.lampRadius + litEdge).roundToInt())
        val width = lamp.intrinsicWidth
        val height = lamp.intrinsicHeight
        val left = (geometry.lampX - width / 2f).roundToInt()
        val top = (geometry.lampY - height / 2f).roundToInt()
        lamp.setBounds(left, top, left + width, top + height)
        invalidate()
    }

    /** Sets the colours for the area under the ring and the lamp. */
    fun setColors(colors: TallyIndicatorColors) {
        if (this.colors == colors) return
        this.colors = colors
        // One colour lit, edged with the indicators' edge; nothing is drawn once it is out.
        lamp.colors =
            TallyLampColors.plain(colors.fill, sensor = true)
                .copy(litEdge = colors.edge, off = Color.TRANSPARENT)
        invalidate()
    }

    /** Lights the ring while the camera is in use and the lamp while location is in use. */
    fun setInUse(camera: Boolean, location: Boolean) {
        ring.setOn(camera)
        lampOut.setOn(location)
        lamp.setState(if (location) TallyLampState.ON else TallyLampState.OFF)
    }

    /** Whether the ring or the lamp is lit or still going out. */
    val isShowing: Boolean
        get() = ring.isVisible || lampOut.isVisible

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
        if (g.hasLamp) lamp.draw(canvas)
    }

    override fun verifyDrawable(who: Drawable): Boolean = who === lamp || super.verifyDrawable(who)

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        lamp.setVisible(isVisible, false)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        ring.endFade()
        lampOut.endFade()
        lamp.jumpToCurrentState()
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

    /** A light's visibility: on at once, out with a fade on the fill spring. */
    private inner class Light {
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
            animator.duration = fadeOut.durationMillis
            animator.interpolator = fadeOut.interpolator
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
}
