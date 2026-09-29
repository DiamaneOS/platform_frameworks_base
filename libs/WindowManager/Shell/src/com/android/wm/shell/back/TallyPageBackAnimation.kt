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

package com.android.wm.shell.back

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN
import android.content.Context
import android.os.Handler
import android.view.RemoteAnimationTarget
import android.view.SurfaceControl
import android.window.BackEvent
import android.window.BackMotionEvent
import com.android.internal.jank.Cuj
import com.android.wm.shell.RootTaskDisplayAreaOrganizer
import com.android.wm.shell.bubbles.BubbleController
import com.android.wm.shell.shared.animation.Interpolators
import com.android.wm.shell.shared.annotations.ShellMainThread
import com.android.wm.shell.tally.TallyMotionTokens
import com.android.wm.shell.tally.TallyPageMotion
import java.util.Optional
import kotlin.math.max

/**
 * DiamaneOS Tally's predictive Back between activities and between tasks: the Tally prototype's
 * page Back ([TallyPageMotion]). The closing activity or task is the page. It scales 1 → 0.9 with
 * the gesture, moves 8 dp the way the finger travels, follows the finger's vertical travel by a
 * quarter (at most 40 dp) and rounds its corners from 0 to 20 dp; what it goes back to shows under
 * it, whole and still, with no scrim. Commit: the page scales on to 0.9 on the slab spring and
 * fades out on the fill spring, from where it is. Cancel: it goes back on slab.
 *
 * Which windows animate, the background under them, letterboxes, the status bar's appearance and
 * the finish stay [CrossActivityBackAnimation]'s, as stock's: only the motion is Tally's. It takes
 * the place of stock's default cross-activity and cross-task animations when Tally is on
 * (`ShellBackAnimationModule`); an app with a custom cross-activity animation keeps it
 * ([CustomCrossActivityBackAnimation] is chosen before this one), and Back to Home stays
 * Launcher's.
 *
 * Springs run in closed form under ValueAnimators, which follow the animator duration scale as
 * stock's post-commit animation does: 0.5x halves them and Remove animations ends them at once.
 * Nothing is allocated per frame.
 */
class TallyPageBackAnimation(
    context: Context,
    background: BackAnimationBackground,
    rootTaskDisplayAreaOrganizer: RootTaskDisplayAreaOrganizer,
    @ShellMainThread handler: Handler,
    bubbleController: Optional<BubbleController>,
    @Cuj.CujType cujType: Int,
    backgroundColor: Int,
) :
    CrossActivityBackAnimation(
        context,
        background,
        rootTaskDisplayAreaOrganizer,
        SurfaceControl.Transaction(),
        handler,
        bubbleController,
        cujType,
    ) {

    private val resources = context.resources
    private var motion = TallyMotionTokens.pageMotion(resources)

    /** +1 for a swipe from the left edge, −1 from the right edge, 0 for a Back button. */
    private var direction = 0
    private var startTouchY = 0f
    private var touchY = 0f

    /** The Back progress the page is at, 0 to 1 (see [TallyPageMotion.backProgress]). */
    private var pageProgress = 0f

    /**
     * The corners the windows keep at least: none in a full-screen task, where the display rounds
     * them, and stock's radius in a bubble, a freeform or a split task, which round their own.
     */
    private var radiusFloor = 0f
    private var animator: ValueAnimator? = null

    init {
        // Between tasks, stock's background colour for cross-task Back; between activities, the
        // entering task's own, as stock's cross-activity Back takes it.
        customizedBackgroundColor = backgroundColor
    }

    override val allowEnteringYShift = false

    override val showsScrim: Boolean
        get() = false

    override fun preparePreCommitClosingRectMovement(swipeEdge: Int) {
        startClosingRect.set(backAnimRect)
        targetClosingRect.set(backAnimRect)
    }

    override fun preparePreCommitEnteringRectMovement() {
        startEnteringRect.set(backAnimRect)
        targetEnteringRect.set(backAnimRect)
    }

    override fun getPostCommitAnimationDuration() = motion.closeMillis

    override fun startBackAnimation(backMotionEvent: BackMotionEvent) {
        // A gesture that starts again during a cancel starts the page from rest, as stock's does.
        stopAnimator()
        motion = TallyMotionTokens.pageMotion(resources)
        direction =
            when (backMotionEvent.swipeEdge) {
                BackEvent.EDGE_LEFT -> 1
                BackEvent.EDGE_RIGHT -> -1
                else -> 0
            }
        startTouchY = backMotionEvent.touchY
        touchY = startTouchY
        pageProgress = 0f
        super.startBackAnimation(backMotionEvent)
        // What the page goes back to shows under it, whole and still.
        val entering = enteringTarget ?: return
        val closing = closingTarget ?: return
        radiusFloor =
            if (closing.taskInfo.windowingMode == WINDOWING_MODE_FULLSCREEN) 0f else cornerRadius
        currentEnteringRect.set(backAnimRect)
        applyTransform(entering.leash, currentEnteringRect, 1f, radius = radiusFloor)
        applyTransaction()
    }

    override fun onGestureProgress(backEvent: BackEvent) {
        val closing = closingTarget ?: return
        touchY = backEvent.touchY
        pageProgress =
            motion.backProgress(backEvent.progress, direction != 0, backAnimRect.width().toFloat())
        placePage(closing, pageProgress, 1f)
        applyTransaction()
        updateStatusBarAppearance()
    }

    override fun onGestureCommitted(velocity: Float) {
        stopAnimator()
        val closing = closingTarget
        val entering = enteringTarget
        if (
            closing?.leash == null ||
                entering?.leash == null ||
                !closing.leash.isValid ||
                !entering.leash.isValid
        ) {
            finishAnimation()
            return
        }
        // The prototype's T.popPage: the page scales on to 0.9 on slab with a tap impulse and
        // fades out on fill; its shift and vertical follow stay where the gesture left them.
        val fromScale = motion.backScale(pageProgress)
        val dx = motion.backShift(pageProgress, direction)
        val dy = motion.backFollow(pageProgress, touchY - startTouchY)
        val millis = motion.closeMillis
        start(millis) { fraction ->
            val t = fraction * millis / 1000f
            val scale = motion.closeScale(fromScale, t)
            val alpha = if (fraction >= 1f) 0f else motion.closeAlpha(t)
            placePage(closing, scale, dx, dy, alpha)
            applyTransaction()
            if (fraction > 1 - BackAnimationConstants.UPDATE_SYSUI_FLAGS_THRESHOLD) {
                resetStatusBarAppearance()
            }
        }
    }

    override fun onGestureCancelled() {
        stopAnimator()
        resetGestureProgress()
        val closing = closingTarget
        if (closing == null) {
            finishAnimation()
            return
        }
        // The prototype's cancel: the page goes back on slab from where it is, at rest.
        val from = pageProgress
        val millis =
            motion.cancelMillis(
                from,
                backAnimRect.width().toFloat(),
                backAnimRect.height().toFloat(),
                direction,
                touchY - startTouchY,
            )
        start(millis) { fraction ->
            pageProgress =
                if (fraction >= 1f) 0f else motion.cancelProgress(from, fraction * millis / 1000f)
            placePage(closing, pageProgress, 1f)
            applyTransaction()
            updateStatusBarAppearance()
        }
    }

    override fun finishAnimation() {
        stopAnimator()
        pageProgress = 0f
        super.finishAnimation()
    }

    /** Places the page at Back progress [k], with the finger's vertical travel. */
    private fun placePage(closing: RemoteAnimationTarget, k: Float, alpha: Float) {
        placePage(
            closing,
            motion.backScale(k),
            motion.backShift(k, direction),
            motion.backFollow(k, touchY - startTouchY),
            alpha,
        )
    }

    /** Places the page at [scale] about its centre, moved by [dx] and [dy]. */
    private fun placePage(
        closing: RemoteAnimationTarget,
        scale: Float,
        dx: Float,
        dy: Float,
        alpha: Float,
    ) {
        currentClosingRect.set(backAnimRect)
        currentClosingRect.scaleCentered(scale)
        currentClosingRect.offset(dx, dy)
        applyTransform(
            closing.leash,
            currentClosingRect,
            alpha,
            radius = max(radiusFloor, motion.cornerRadius(scale)),
        )
    }

    /** Runs [frame] with the animation's fraction for [millis], then finishes the animation. */
    private fun start(millis: Long, frame: Frame) {
        animator =
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = millis
                interpolator = Interpolators.LINEAR
                addUpdateListener { frame.onFrame(it.animatedFraction) }
                addListener(
                    object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator) {
                            animator = null
                            resetStatusBarAppearance()
                            finishAnimation()
                        }
                    }
                )
                start()
            }
    }

    /** One frame of an animation, at [fraction] of its time (a primitive: no boxing per frame). */
    private fun interface Frame {
        fun onFrame(fraction: Float)
    }

    private fun stopAnimator() {
        animator?.apply {
            removeAllListeners()
            removeAllUpdateListeners()
            cancel()
        }
        animator = null
    }

    companion object {
        /** Stock's background under cross-task Back (CrossTaskBackAnimation.BACKGROUNDCOLOR). */
        private const val CROSS_TASK_BACKGROUND = 0x43433A

        /** Tally's Back between activities, measured as stock's. */
        @JvmStatic
        fun crossActivity(
            context: Context,
            background: BackAnimationBackground,
            rootTaskDisplayAreaOrganizer: RootTaskDisplayAreaOrganizer,
            @ShellMainThread handler: Handler,
            bubbleController: Optional<BubbleController>,
        ) =
            TallyPageBackAnimation(
                context,
                background,
                rootTaskDisplayAreaOrganizer,
                handler,
                bubbleController,
                Cuj.CUJ_PREDICTIVE_BACK_CROSS_ACTIVITY,
                backgroundColor = 0,
            )

        /** Tally's Back between tasks, measured as stock's. */
        @JvmStatic
        fun crossTask(
            context: Context,
            background: BackAnimationBackground,
            rootTaskDisplayAreaOrganizer: RootTaskDisplayAreaOrganizer,
            @ShellMainThread handler: Handler,
            bubbleController: Optional<BubbleController>,
        ) =
            TallyPageBackAnimation(
                context,
                background,
                rootTaskDisplayAreaOrganizer,
                handler,
                bubbleController,
                Cuj.CUJ_PREDICTIVE_BACK_CROSS_TASK,
                CROSS_TASK_BACKGROUND,
            )
    }
}
