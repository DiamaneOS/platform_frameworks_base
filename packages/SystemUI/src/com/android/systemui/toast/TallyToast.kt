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

package com.android.systemui.toast

import android.animation.Animator
import android.animation.ValueAnimator
import android.content.res.Resources
import android.view.View
import android.view.animation.LinearInterpolator
import com.android.systemui.res.R
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import org.diamaneos.tally.R as TallyR

/**
 * How the Tally toast (the prototype's `.toast`) comes and goes. It rises into place
 * (`tally_toast_rise`) on the stone spring, starting with a tap's impulse, as it fades in on the
 * fill spring, and it fades out on the fill spring over the queue gap (`tally_toast_queue_gap_ms`):
 * a toast that waited for this one shows when that time is up, as the prototype's queue does. Both
 * are ordinary animators, so ToastUI's queue and the window's timing stay stock, and with Remove
 * animations both end at once. Where the toast sits, which toasts show and for how long do not
 * change.
 */
object TallyToast {
    /** The toast's view, in place of stock's text_toast. */
    @JvmField val LAYOUT: Int = R.layout.tally_text_toast

    /** Rises and fades [view] in. It is hidden (faded out, below its place) until this starts. */
    @JvmStatic
    fun inAnimation(view: View): Animator {
        val res = view.resources
        val rise = res.getDimension(TallyR.dimen.tally_toast_rise)
        val stone = Spring.stone(res)
        val fill = Spring.fill(res)
        // A tap's impulse: v0 = 0.6 · √k · distance, towards the place.
        val v0 = -res.getFloat(TallyR.dimen.tally_motion_tap_impulse) * sqrt(stone.stiffness) * rise
        var millis = 0L
        while (
            millis < MAX_IN_MILLIS &&
                (abs(stone.at(rise, v0, millis)) > REST_PX || fill.at(1f, 0f, millis) > REST_ALPHA)
        ) {
            millis += STEP_MILLIS
        }
        view.alpha = 0f
        view.translationY = rise
        return ValueAnimator.ofFloat(0f, 1f).apply {
            duration = millis
            interpolator = LinearInterpolator()
            addUpdateListener {
                val t = (it.animatedFraction * millis).toLong()
                val done = it.animatedFraction >= 1f
                view.translationY = if (done) 0f else stone.at(rise, v0, t)
                view.alpha = if (done) 1f else 1f - fill.at(1f, 0f, t)
            }
            view.setTag(R.id.tally_toast_in, this)
        }
    }

    /** Fades [view] out over the queue gap, from wherever its fade in has got to. */
    @JvmStatic
    fun outAnimation(view: View): Animator {
        val res = view.resources
        val fill = Spring.fill(res)
        val millis = res.getInteger(TallyR.integer.tally_toast_queue_gap_ms).toLong()
        // Only an update listener: ToastUI owns the animator's listeners (it adds and removes its
        // own to show the next toast).
        var from = Float.NaN
        return ValueAnimator.ofFloat(0f, 1f).apply {
            duration = millis
            interpolator = LinearInterpolator()
            addUpdateListener {
                if (from.isNaN()) {
                    // The first frame: stop the rise if it is still going, fade from where it is.
                    (view.getTag(R.id.tally_toast_in) as? Animator)?.cancel()
                    from = view.alpha
                }
                val t = (it.animatedFraction * millis).toLong()
                view.alpha = if (it.animatedFraction >= 1f) 0f else from * fill.at(1f, 0f, t)
            }
        }
    }

    /** One of Tally's springs (Android's SpringForce), solved exactly for any time. */
    private class Spring(val stiffness: Float, private val dampingRatio: Float) {
        /** How far from rest a value is [millis] after it was [x0] away and moving at [v0]. */
        fun at(x0: Float, v0: Float, millis: Long): Float {
            val t = millis / 1000.0
            val w = sqrt(stiffness.toDouble())
            val z = dampingRatio.toDouble()
            val x =
                when {
                    abs(z - 1.0) < 1e-4 -> (x0 + (v0 + w * x0) * t) * exp(-w * t)
                    z > 1.0 -> {
                        val root = w * sqrt(z * z - 1.0)
                        val gammaPlus = -z * w + root
                        val gammaMinus = -z * w - root
                        val b = (gammaMinus * x0 - v0) / (gammaMinus - gammaPlus)
                        (x0 - b) * exp(gammaMinus * t) + b * exp(gammaPlus * t)
                    }
                    else -> {
                        val wd = w * sqrt(1.0 - z * z)
                        exp(-z * w * t) * (x0 * cos(wd * t) + (v0 + z * w * x0) / wd * sin(wd * t))
                    }
                }
            // Effects never pass their target (Tally's springs are critically damped anyway).
            return if (x0 >= 0f) max(0.0, x).toFloat() else min(0.0, x).toFloat()
        }

        companion object {
            fun stone(res: Resources) =
                Spring(
                    res.getFloat(TallyR.dimen.tally_spring_stone_stiffness),
                    res.getFloat(TallyR.dimen.tally_spring_stone_damping_ratio),
                )

            fun fill(res: Resources) =
                Spring(
                    res.getFloat(TallyR.dimen.tally_spring_fill_stiffness),
                    res.getFloat(TallyR.dimen.tally_spring_fill_damping_ratio),
                )
        }
    }

    /** Within the window's budget for a toast's two animations (600 ms), with the fade out. */
    private const val MAX_IN_MILLIS = 400L
    private const val STEP_MILLIS = 4L
    private const val REST_PX = 0.5f
    private const val REST_ALPHA = 0.005f
}
