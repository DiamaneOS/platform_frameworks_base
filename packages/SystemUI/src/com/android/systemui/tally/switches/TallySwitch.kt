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

package com.android.systemui.tally.switches

import android.animation.ValueAnimator
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.shape
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.android.settingslib.widget.TallySwitchMotion
import com.android.settingslib.widget.TallySwitchPainter
import com.android.settingslib.widget.TallySwitchSpec
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The Tally switch for SystemUI's Compose screens, as the prototype's switch (`T.tswitch`): the
 * same drawing, motion and values as SettingsLib's `TallySwitch` view (SettingsLib's
 * [TallySwitchPainter], [TallySwitchMotion] and [TallySwitchSpec]), in place of Material's pill
 * switch. Callers choose it only while `TallyShell.isEnabled` and keep Material's `Switch` for the
 * flag off.
 *
 * It takes Material's `Switch` arguments and has its semantics and layout: with [onCheckedChange]
 * it is toggleable, with the switch role, and at least the minimum touch target; without, it only
 * draws, for a row that toggles it. Its content description is the caller's [modifier]'s, it tells
 * accessibility its shape as Material's does, and it is 52 × 32 dp inside whatever size the
 * modifier gives, as Material's is. There is no thumb content: the thumb's position and the lamp
 * carry the state.
 *
 * The thumb moves on the pebble spring and the lamp wipes in on the fill spring when [checked]
 * changes after it is first drawn, following the animator duration scale (with animations removed
 * the thumb jumps and the lamp fades). The press layer shows while [interactionSource] is pressed,
 * and the focus ring while it has keyboard focus.
 */
@Composable
fun TallySwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    interactionSource: MutableInteractionSource? = null,
) {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val spec = remember(context, configuration) { TallySwitchSpec.from(context) }
    // Its shape, which Material's switch gives accessibility through its track's background.
    val switchShape = remember(spec) { RoundedCornerShape((spec.radius / spec.density).dp) }
    val pressed by source.collectIsPressedAsState()
    val focused by source.collectIsFocusedAsState()
    // Only keyboard focus shows the ring, as the prototype's :focus-visible.
    val keyboard = LocalInputModeManager.current.inputMode == InputMode.Keyboard
    val toggleable =
        if (onCheckedChange != null) {
            Modifier.minimumInteractiveComponentSize()
                .toggleable(
                    value = checked,
                    onValueChange = onCheckedChange,
                    enabled = enabled,
                    role = Role.Switch,
                    interactionSource = source,
                    indication = null,
                )
        } else {
            Modifier
        }
    Spacer(
        modifier
            .then(toggleable)
            .wrapContentSize(Alignment.Center)
            .requiredSize((spec.width / spec.density).dp, (spec.height / spec.density).dp)
            .semantics { shape = switchShape }
            .then(
                TallySwitchElement(
                    checked = checked,
                    lit = checked,
                    enabled = enabled,
                    pressed = pressed,
                    focused = focused && keyboard,
                    spec = spec,
                )
            )
    )
}

private data class TallySwitchElement(
    val checked: Boolean,
    val lit: Boolean,
    val enabled: Boolean,
    val pressed: Boolean,
    val focused: Boolean,
    val spec: TallySwitchSpec,
) : ModifierNodeElement<TallySwitchNode>() {
    override fun create() = TallySwitchNode(this)

    override fun update(node: TallySwitchNode) = node.update(this)
}

/**
 * Draws the switch, and while it moves advances its motion on the composition's frame clock, one
 * frame at a time, so a still switch costs no frames.
 */
private class TallySwitchNode(element: TallySwitchElement) : Modifier.Node(), DrawModifierNode {
    private val motion = TallySwitchMotion()
    private val painter = TallySwitchPainter()
    private var spec = element.spec
    private var checked = element.checked
    private var lit = element.lit
    private var enabled = element.enabled
    private var pressed = element.pressed
    private var focused = element.focused
    private var drawnSinceAttach = false
    private var frames: Job? = null

    // Only a change to what is drawn redraws the switch.
    override val shouldAutoInvalidate: Boolean
        get() = false

    init {
        motion.setSpec(spec)
        motion.jumpTo(checked, lit)
    }

    fun update(element: TallySwitchElement) {
        if (element.spec !== spec) {
            spec = element.spec
            motion.setSpec(spec)
        }
        if (element.checked != checked || element.lit != lit) {
            checked = element.checked
            lit = element.lit
            // A switch not yet shown takes its state at once, as a bound view does.
            if (isAttached && drawnSinceAttach) {
                motion.animateTo(checked, lit, durationScale())
                runFrames()
            } else {
                motion.jumpTo(checked, lit)
            }
        }
        enabled = element.enabled
        pressed = element.pressed
        focused = element.focused
        invalidateDraw()
    }

    override fun onDetach() {
        frames = null
        drawnSinceAttach = false
        motion.jumpToTargets()
    }

    override fun ContentDrawScope.draw() {
        drawContent()
        drawIntoCanvas {
            painter.draw(
                it.nativeCanvas,
                0f,
                0f,
                size.width,
                size.height,
                spec,
                rtl = layoutDirection == LayoutDirection.Rtl,
                thumb = motion.thumb,
                wipe = motion.wipe,
                checked = checked,
                pressed = pressed,
                focused = focused,
                enabled = enabled,
            )
        }
        drawnSinceAttach = true
    }

    private fun runFrames() {
        if (frames?.isActive == true) return
        frames =
            coroutineScope.launch {
                while (motion.moving) {
                    withFrameNanos { now -> motion.advance(now, durationScale()) }
                    invalidateDraw()
                }
            }
    }

    /**
     * The animator duration scale, as Compose's own animations read it, or the system's where the
     * composition does not carry one.
     */
    private fun durationScale(): Float =
        coroutineScope.coroutineContext[MotionDurationScale]?.scaleFactor
            ?: ValueAnimator.getDurationScale()
}
