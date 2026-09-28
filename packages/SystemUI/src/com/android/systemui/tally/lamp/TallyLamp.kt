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
import android.view.Choreographer
import android.view.animation.AnimationUtils
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.unit.Dp

/**
 * The animated Tally lamp in Compose: the same drawing and motion as [TallyLampView].
 *
 * It takes [size] in the layout, as a lamp of that size would, and draws the live lamp's ring of
 * light a little past it (up to the token box, `tally_lamp_box_<size>`), so leave that room and do
 * not clip it. It animates only while it is drawn and schedules nothing once it is still.
 *
 * It says nothing to accessibility services: put the state in words next to it or in its item's
 * description.
 *
 * @param state what the lamp shows.
 * @param size the lamp's diameter; [TallyLampDefaults.size] gives the token sizes.
 * @param colors the lamp's colours: the theme's by default, [TallyLampDefaults.sensorColors] for a
 *   sensor lamp, [TallyLampDefaults.onLitFieldColors] on a lit field.
 * @param instantAppear whether a lit state shows at once instead of igniting. It is for sensor
 *   lamps by default: a sensor lamp appears with no spring delay, only its disappearance moves.
 * @param requestedSinceMillis when the request started, in uptime milliseconds
 *   (`SystemClock.uptimeMillis`), so that the requested dashes go on where they were when the lamp
 *   is shown again; by default the lamp counts from when it first shows the request.
 */
@Composable
fun TallyLamp(
    state: TallyLampState,
    modifier: Modifier = Modifier,
    size: Dp = TallyLampDefaults.size(),
    colors: TallyLampColors = TallyLampDefaults.colors(),
    instantAppear: Boolean = colors.sensor,
    requestedSinceMillis: Long = TallyLampState.SINCE_FIRST_SHOWN,
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val spec = remember(context, configuration) { TallyLampSpec.from(context.resources) }
    val sizePx = with(LocalDensity.current) { size.roundToPx() }
    Spacer(
        modifier
            .size(size)
            .then(
                TallyLampElement(state, sizePx, colors, spec, instantAppear, requestedSinceMillis)
            )
    )
}

/** Sizes and colours for [TallyLamp]. */
object TallyLampDefaults {
    /**
     * A token lamp size. With [growsWithText] it grows from 200 % text as tile lamps do
     * ([TallyLampSize.forFontScale]).
     */
    @Composable
    fun size(size: TallyLampSize = TallyLampSize.DEFAULT, growsWithText: Boolean = false): Dp {
        val token = if (growsWithText) size.forFontScale(LocalDensity.current.fontScale) else size
        return dimensionResource(token.sizeRes)
    }

    /** The theme's lamp ([TallyLampColors.theme]). */
    @Composable
    fun colors(): TallyLampColors {
        val context = LocalContext.current
        val configuration = LocalConfiguration.current
        return remember(context, configuration) { TallyLampColors.theme(context) }
    }

    /** A sensor lamp in the theme's sensor colour ([TallyLampColors.sensor]). */
    @Composable
    fun sensorColors(): TallyLampColors {
        val context = LocalContext.current
        val configuration = LocalConfiguration.current
        return remember(context, configuration) { TallyLampColors.sensor(context) }
    }

    /** A lamp on a lit field or tile ([TallyLampColors.onLitField]). */
    @Composable
    fun onLitFieldColors(): TallyLampColors {
        val context = LocalContext.current
        val configuration = LocalConfiguration.current
        return remember(context, configuration) { TallyLampColors.onLitField(context) }
    }
}

private data class TallyLampElement(
    val state: TallyLampState,
    val sizePx: Int,
    val colors: TallyLampColors,
    val spec: TallyLampSpec,
    val instantAppear: Boolean,
    val requestedSinceMillis: Long,
) : ModifierNodeElement<TallyLampNode>() {
    override fun create() = TallyLampNode(this)

    override fun update(node: TallyLampNode) = node.update(this)
}

/**
 * Draws the lamp and asks the choreographer for the next frame only after drawing one that still
 * moves, as [TallyLampDrawable] does, so a lamp that is not drawn or is still costs no frames.
 */
private class TallyLampNode(element: TallyLampElement) : Modifier.Node(), DrawModifierNode {
    private val geometry = TallyLampGeometry()
    private val motion = TallyLampMotion()
    private val painter = TallyLampPainter()
    private val frameCallback = Choreographer.FrameCallback { onFrame(it) }
    private var spec = element.spec
    private var sizePx = element.sizePx
    private var colors = element.colors
    private var instantAppear = element.instantAppear
    private var drawnSinceAttach = false
    private var framePosted = false
    private var lastDrawMillis = 0L

    init {
        motion.setSpec(spec)
        geometry.set(spec, sizePx.toFloat())
        motion.setLiveFill(geometry.liveFill)
        motion.setState(
            element.state,
            nowMillis = AnimationUtils.currentAnimationTimeMillis(),
            durationScale = 0f,
            animate = false,
            instantAppear = true,
            requestedSinceMillis = element.requestedSinceMillis,
        )
    }

    fun update(element: TallyLampElement) {
        if (element.spec !== spec || element.sizePx != sizePx) {
            spec = element.spec
            sizePx = element.sizePx
            motion.setSpec(spec)
            geometry.set(spec, sizePx.toFloat())
            motion.setLiveFill(geometry.liveFill)
        }
        colors = element.colors
        instantAppear = element.instantAppear
        motion.setState(
            element.state,
            nowMillis = AnimationUtils.currentAnimationTimeMillis(),
            durationScale = ValueAnimator.getDurationScale(),
            animate = drawnSinceAttach,
            instantAppear = instantAppear,
            requestedSinceMillis = element.requestedSinceMillis,
        )
        // The node invalidates its drawing after an update by itself.
    }

    override fun onDetach() {
        drawnSinceAttach = false
        if (framePosted) {
            framePosted = false
            Choreographer.getInstance().removeFrameCallback(frameCallback)
        }
    }

    override fun ContentDrawScope.draw() {
        drawContent()
        val now = AnimationUtils.currentAnimationTimeMillis()
        val durationScale = ValueAnimator.getDurationScale()
        motion.advance(now, durationScale)
        // Whole pixels for the lamp's box, so that its ring stays sharp.
        val left = ((size.width - sizePx) / 2f).toInt()
        val top = ((size.height - sizePx) / 2f).toInt()
        drawIntoCanvas {
            painter.draw(
                it.nativeCanvas,
                left + sizePx / 2f,
                top + sizePx / 2f,
                spec,
                geometry,
                colors,
                motion,
                OPAQUE,
            )
        }
        lastDrawMillis = now
        drawnSinceAttach = true
        if (motion.needsFrame(now, durationScale)) postFrame()
    }

    private fun postFrame() {
        if (framePosted) return
        framePosted = true
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    private fun onFrame(frameTimeNanos: Long) {
        framePosted = false
        if (!isAttached) return
        if (motion.shouldRedraw(frameTimeNanos / NANOS_PER_MILLI, lastDrawMillis)) {
            invalidateDraw()
        } else {
            // Only turning, and a frame drawn less than a lamp frame ago: look again next frame.
            postFrame()
        }
    }

    private companion object {
        const val OPAQUE = 255
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
