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

package com.android.systemui.globalactions

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.view.View
import android.view.Window
import android.widget.ImageView
import android.widget.TextView
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.FloatValueHolder
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import com.android.internal.widget.LockPatternUtils
import com.android.systemui.res.R
import de.diamaneos.tally.R as TallyR
import kotlin.math.sqrt

/**
 * Tally's power menu (the prototype's `.pm`) for [GlobalActionsDialogLite]: which item an action
 * takes, how an item looks once the stock code has filled it in, the Emergency row, the note under
 * Restart and Lockdown, the scrim, and how the sheet comes out of the power key. The actions, which
 * of them show (the keyguard's and setup's filtering included) and what each does stay stock.
 */
object TallyGlobalActions {
    /** The item [action] takes: a small key for screenshot and bug report, a row otherwise. */
    @JvmStatic
    fun itemLayout(action: GlobalActionsDialogLite.Action): Int =
        if (isKey(action)) R.layout.tally_global_actions_key else R.layout.tally_global_actions_row

    private fun isKey(action: GlobalActionsDialogLite.Action): Boolean =
        action is GlobalActionsDialogLite.ScreenshotAction ||
            action is GlobalActionsDialogLite.BugReportAction

    /**
     * Draws [item] (inflated from [itemLayout] and filled in by the stock code) in Tally's type,
     * with [note] under the words of a row.
     */
    @JvmStatic
    fun styleItem(item: View, action: GlobalActionsDialogLite.Action, note: CharSequence?) {
        val isKey = isKey(action)
        item.setTag(R.id.tally_power_menu_key, isKey)
        // The stock code sets its own typeface on the words; Tally's type comes back here.
        item
            .requireViewById<TextView>(com.android.internal.R.id.message)
            .setTextAppearance(
                if (isKey) {
                    TallyR.style.TextAppearance_Tally_Caption
                } else {
                    TallyR.style.TextAppearance_Tally_Label
                }
            )
        item.findViewById<TextView>(R.id.tally_power_menu_note)?.apply {
            text = note
            visibility = if (note.isNullOrEmpty()) View.GONE else View.VISIBLE
        }
    }

    /** Draws [item] as the Emergency row: outlined, its icon and words in the error colour. */
    @JvmStatic
    fun styleEmergency(item: View) {
        val error = item.context.getColor(TallyR.color.tally_error)
        item.setBackgroundResource(R.drawable.tally_power_menu_emergency_background)
        item.requireViewById<TextView>(com.android.internal.R.id.message).setTextColor(error)
        item.requireViewById<ImageView>(com.android.internal.R.id.icon).imageTintList =
            ColorStateList.valueOf(error)
    }

    /**
     * The note under Restart or Lockdown: that the next unlock needs the PIN, pattern or password,
     * in the lock screen's own translated words. None for any other action, or without a secure
     * lock.
     */
    @JvmStatic
    fun note(
        context: Context,
        action: GlobalActionsDialogLite.Action,
        lockPatternUtils: LockPatternUtils,
        userId: Int,
        isSecure: Boolean,
    ): CharSequence? {
        val isRestart = action is GlobalActionsDialogLite.RestartAction
        if (!isSecure || (!isRestart && action !is GlobalActionsDialogLite.LockDownAction)) {
            return null
        }
        val words =
            when (lockPatternUtils.getCredentialTypeForUser(userId)) {
                LockPatternUtils.CREDENTIAL_TYPE_PATTERN ->
                    if (isRestart) {
                        R.string.kg_prompt_reason_restart_pattern
                    } else {
                        R.string.kg_prompt_after_user_lockdown_pattern
                    }
                LockPatternUtils.CREDENTIAL_TYPE_PIN ->
                    if (isRestart) {
                        R.string.kg_prompt_reason_restart_pin
                    } else {
                        R.string.kg_prompt_after_user_lockdown_pin
                    }
                LockPatternUtils.CREDENTIAL_TYPE_PASSWORD ->
                    if (isRestart) {
                        R.string.kg_prompt_reason_restart_password
                    } else {
                        R.string.kg_prompt_after_user_lockdown_password
                    }
                else -> return null
            }
        return context.getString(words)
    }

    /** The window's dim behind the menu: Tally's scrim, as far as a dim can show it. */
    @JvmStatic
    fun dimAmount(context: Context): Float =
        Color.alpha(context.getColor(TallyR.color.tally_scrim)) / 255f

    /**
     * Brings the menu's sheet ([menu]) out of the power key on the stone spring, starting with a
     * tap's impulse, with the window's dim (up to [dimAmount]) following it, or back in when not
     * [isEnter], then runs [then]. Out of portrait, where the sheet is centred, it fades instead.
     */
    @JvmStatic
    fun animate(menu: View, window: Window, dimAmount: Float, isEnter: Boolean, then: Runnable?) {
        val motion =
            menu.getTag(R.id.tally_power_menu_spring) as? SheetMotion
                ?: SheetMotion(menu, window, dimAmount).also {
                    menu.setTag(R.id.tally_power_menu_spring, it)
                }
        motion.moveTo(if (isEnter) SHOWN else HIDDEN, then)
    }

    /** The sheet's way out of the power key: 0 in the key, 1 out. */
    private class SheetMotion(
        private val menu: View,
        private val window: Window,
        private val dimAmount: Float,
    ) {
        private val position = FloatValueHolder(HIDDEN)
        private val slides =
            menu.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        private val travel = menu.resources.getDimension(R.dimen.tally_power_menu_travel)
        private val stiffness = menu.resources.getFloat(TallyR.dimen.tally_spring_stone_stiffness)
        private val impulse =
            menu.resources.getFloat(TallyR.dimen.tally_motion_tap_impulse) * sqrt(stiffness)
        private val spring =
            SpringAnimation(position)
                .setSpring(
                    SpringForce()
                        .setStiffness(stiffness)
                        .setDampingRatio(
                            menu.resources.getFloat(TallyR.dimen.tally_spring_stone_damping_ratio)
                        )
                )
                .setMinimumVisibleChange(MIN_VISIBLE_CHANGE)
                .addUpdateListener { _, value, _ -> apply(value) }

        init {
            apply(HIDDEN)
        }

        fun moveTo(target: Float, then: Runnable?) {
            if (then != null) {
                spring.addEndListener(
                    object : DynamicAnimation.OnAnimationEndListener {
                        override fun onAnimationEnd(
                            animation: DynamicAnimation<*>,
                            canceled: Boolean,
                            value: Float,
                            velocity: Float,
                        ) {
                            animation.removeEndListener(this)
                            then.run()
                        }
                    }
                )
            }
            // No hardware layer, unlike stock: it would clip the sheet's shadow as it moves.
            if (!spring.isRunning) {
                spring.setStartVelocity(if (target > position.value) impulse else -impulse)
            }
            spring.animateToFinalPosition(target)
        }

        private fun apply(value: Float) {
            val shown = value.coerceIn(HIDDEN, SHOWN)
            if (slides) {
                menu.translationX = (SHOWN - value) * travel
            } else {
                menu.alpha = shown
            }
            window.setDimAmount(dimAmount * shown)
        }
    }

    private const val HIDDEN = 0f
    private const val SHOWN = 1f
    private const val MIN_VISIBLE_CHANGE = 1f / 500f
}
