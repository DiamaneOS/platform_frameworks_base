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

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.view.animation.AnimationUtils

/**
 * The animated Tally lamp as a drawable, for views: [TallyLampView] wraps one, and it works as an
 * image or a compound drawable (`setCompoundDrawablesRelativeWithIntrinsicBounds`). It draws the
 * lamp at [lampSizePx], centred in its bounds on whole pixels; its intrinsic size is the lamp plus
 * the live lamp's ring of light ([haloReachPx] on each side), so every state fits the same box.
 * With smaller bounds the ring of light draws past them, which is fine where the host does not
 * clip.
 *
 * It asks its host (the drawable callback: a view's choreographer) for the next frame only after
 * drawing one that still moves, so a lamp that is not drawn schedules nothing, and one that is
 * still schedules nothing at all. Hosts that pass their visibility to [setVisible] (images,
 * backgrounds, [TallyLampView]) also drop a frame already asked for. Changes made while the lamp is
 * hidden, or before its first frame, are at once.
 */
class TallyLampDrawable(context: Context) : Drawable() {
    private var resources = context.resources
    private var spec = TallyLampSpec.from(resources)
    private val geometry = TallyLampGeometry()
    private val motion = TallyLampMotion()
    private val painter = TallyLampPainter()
    private val frame = Runnable { onFrame() }
    private val still = Runnable { onStill() }
    private var drawAlpha = 255
    private var sizePx = 0
    private var drawnSinceShown = false
    private var framePending = false
    private var lastDrawMillis = 0L
    private var stillPending = false
    private var stillNotified = false

    /** The lamp's colours; the theme's by default. */
    var colors: TallyLampColors = TallyLampColors.theme(context)
        set(value) {
            if (field == value) return
            field = value
            invalidateSelf()
        }

    /**
     * Whether a lit state shows at once instead of igniting; null (the default) for the colours'
     * choice, which is at once for sensor lamps. Only a sensor lamp's disappearance may animate.
     */
    var instantAppear: Boolean? = null

    /**
     * Runs once per request, on the main thread, when the lamp has been requested for
     * `tally_lamp_requested_still_ms` (5 s): the moment its words change to "Still trying…". The
     * dashes are still by then (three whole turns). It goes through the host's choreographer, so it
     * runs while the host is attached (shown or not), or as soon as it is attached again.
     */
    var onRequestedStill: Runnable? = null
        set(value) {
            field = value
            updateStill()
        }

    /** The state the lamp shows or is moving to. */
    val state: TallyLampState
        get() = motion.state ?: TallyLampState.OFF

    /** The lamp's diameter in pixels. */
    val lampSizePx: Int
        get() = sizePx

    /** How far the live lamp's ring of light reaches past the lamp on each side, in pixels. */
    val haloReachPx: Int
        get() = geometry.reachPx

    init {
        motion.setSpec(spec)
        setLampSize(TallyLampSize.DEFAULT)
        motion.setState(
            TallyLampState.OFF,
            nowMillis = AnimationUtils.currentAnimationTimeMillis(),
            durationScale = 0f,
            animate = false,
            instantAppear = true,
            requestedSinceMillis = TallyLampState.SINCE_FIRST_SHOWN,
        )
    }

    /**
     * Shows [state]. A requested lamp's dashes turn from [requestedSinceMillis], the uptime
     * (`SystemClock.uptimeMillis`) at which the request started, so a lamp shown again later goes
     * on where it was; by default from when this lamp first shows the request.
     */
    @JvmOverloads
    fun setState(
        state: TallyLampState,
        requestedSinceMillis: Long = TallyLampState.SINCE_FIRST_SHOWN,
    ) {
        val previous = motion.state
        val previousStart = motion.requestedStartMillis
        motion.setState(
            state,
            nowMillis = AnimationUtils.currentAnimationTimeMillis(),
            durationScale = ValueAnimator.getDurationScale(),
            animate = isVisible && drawnSinceShown,
            instantAppear = instantAppear ?: colors.sensor,
            requestedSinceMillis = requestedSinceMillis,
        )
        if (state != previous || motion.requestedStartMillis != previousStart) {
            // Another state or request (with its own "Still trying…").
            stillNotified = false
            if (stillPending) {
                stillPending = false
                unscheduleSelf(still)
            }
            invalidateSelf()
        }
        updateStill()
    }

    /** Draws the lamp at a token size; the host lays out again for the new size. */
    fun setLampSize(size: TallyLampSize) {
        setLampSizePx(resources.getDimensionPixelSize(size.sizeRes))
    }

    /** Sets the lamp's diameter; the host lays out again for the new [getIntrinsicWidth]. */
    fun setLampSizePx(px: Int) {
        if (px == sizePx) return
        sizePx = px
        geometry.set(spec, px.toFloat())
        motion.setLiveFill(geometry.liveFill)
        invalidateSelf()
    }

    /** Reads the tokens again, after a density change. Set the size again after this. */
    fun reloadResources(resources: Resources) {
        this.resources = resources
        spec = TallyLampSpec.from(resources)
        motion.setSpec(spec)
        geometry.set(spec, sizePx.toFloat())
        motion.setLiveFill(geometry.liveFill)
        invalidateSelf()
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val now = AnimationUtils.currentAnimationTimeMillis()
        val durationScale = ValueAnimator.getDurationScale()
        motion.advance(now, durationScale)
        // Whole pixels for the lamp's box, so that its ring stays sharp.
        val left = b.left + (b.width() - sizePx) / 2
        val top = b.top + (b.height() - sizePx) / 2
        painter.draw(
            canvas,
            left + sizePx / 2f,
            top + sizePx / 2f,
            spec,
            geometry,
            colors,
            motion,
            drawAlpha,
        )
        lastDrawMillis = now
        drawnSinceShown = true
        if (isVisible && motion.needsFrame(now, durationScale)) scheduleFrame()
        // A host that took the lamp after its state was set gets the request's timer now.
        updateStill()
    }

    override fun setVisible(visible: Boolean, restart: Boolean): Boolean {
        val changed = super.setVisible(visible, restart)
        if (!visible) {
            drawnSinceShown = false
            if (framePending) {
                framePending = false
                unscheduleSelf(frame)
            }
        } else if (changed) {
            invalidateSelf()
        }
        return changed
    }

    override fun jumpToCurrentState() {
        motion.jumpToTargets()
        invalidateSelf()
    }

    override fun getIntrinsicWidth(): Int = sizePx + 2 * haloReachPx

    override fun getIntrinsicHeight(): Int = sizePx + 2 * haloReachPx

    override fun setAlpha(alpha: Int) {
        if (drawAlpha == alpha) return
        drawAlpha = alpha
        invalidateSelf()
    }

    override fun getAlpha(): Int = drawAlpha

    override fun setColorFilter(colorFilter: ColorFilter?) {
        painter.setColorFilter(colorFilter)
        invalidateSelf()
    }

    @Suppress("DeprecatedCallableAddReplaceWith")
    @Deprecated("Deprecated in android.graphics.drawable.Drawable")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    private fun scheduleFrame() {
        if (framePending || callback == null) return
        framePending = true
        // The next frame: a view's choreographer runs it before that frame draws.
        scheduleSelf(frame, SystemClock.uptimeMillis())
    }

    private fun onFrame() {
        framePending = false
        if (!isVisible) return
        if (motion.shouldRedraw(AnimationUtils.currentAnimationTimeMillis(), lastDrawMillis)) {
            invalidateSelf()
        } else {
            // Only turning, and a frame drawn less than a lamp frame ago: look again next frame.
            scheduleFrame()
        }
    }

    private fun updateStill() {
        val wanted =
            onRequestedStill != null && motion.state == TallyLampState.REQUESTED && !stillNotified
        if (!wanted) {
            if (stillPending) {
                stillPending = false
                unscheduleSelf(still)
            }
            return
        }
        if (stillPending || callback == null) return
        stillPending = true
        scheduleSelf(still, motion.requestedStillAtMillis)
    }

    private fun onStill() {
        stillPending = false
        if (motion.state != TallyLampState.REQUESTED || stillNotified) return
        if (SystemClock.uptimeMillis() < motion.requestedStillAtMillis) {
            // A choreographer runs delayed work up to a frame early: wait for the moment.
            updateStill()
            return
        }
        stillNotified = true
        onRequestedStill?.run()
    }
}
