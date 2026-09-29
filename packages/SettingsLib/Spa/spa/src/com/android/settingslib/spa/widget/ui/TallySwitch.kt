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

import android.os.Build
import androidx.annotation.RequiresApi
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
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.shape
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.android.settingslib.spa.R
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The Tally switch in Compose, as the prototype's switch (`T.tswitch`): the same drawing and motion
 * as SettingsLib's `TallySwitch` view ([TallySwitchPainter], [TallySwitchMotion], from the values
 * generated from the Tally token spec), in place of Material's pill switch.
 *
 * Its semantics are Material's Switch's: with [onCheckedChange] it is toggleable, with the switch
 * role, and at least the minimum touch target; without, it only draws, for a row that toggles it
 * (share the row's [interactionSource] so that the switch shows the row's press and focus). It
 * moves only when [checked] changes after it is first shown, and follows the animator duration
 * scale, as the view does.
 *
 * The lamp lights with [checked], unless [confirmedOn] is given: for a switch whose change takes
 * effect later, whether the system has confirmed that what it controls is on. Then the thumb still
 * follows [checked] at once, and the lamp lights only while [checked] and [confirmedOn] are both
 * true, so a switch that is not confirmed shows unlit, as the view's `confirmedOn`. Only the
 * drawing follows it; the semantics stay [checked]'s.
 *
 * From API 34, as the colour roles it reads.
 */
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
@Composable
internal fun TallySwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    interactionSource: MutableInteractionSource? = null,
    confirmedOn: Boolean? = null,
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
            .requiredSize(
                dimensionResource(R.dimen.settingslib_tally_switch_width),
                dimensionResource(R.dimen.settingslib_tally_switch_height),
            )
            .semantics { shape = switchShape }
            .then(
                TallySwitchElement(
                    checked = checked,
                    lit = checked && confirmedOn != false,
                    enabled = enabled,
                    pressed = pressed,
                    focused = focused && keyboard,
                    spec = spec,
                )
            )
    )
}

@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
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
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
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

    /** The animator duration scale, as Compose's own animations read it. */
    private fun durationScale(): Float =
        coroutineScope.coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f
}
