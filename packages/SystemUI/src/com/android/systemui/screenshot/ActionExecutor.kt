/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.screenshot

import android.app.ActivityOptions
import android.app.BroadcastOptions
import android.app.ExitTransitionCoordinator
import android.app.ExitTransitionCoordinator.ExitTransitionCallbacks
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PersistableBundle
import android.os.UserHandle
import android.util.Log
import android.util.Pair
import android.view.Window
import androidx.annotation.VisibleForTesting
import com.android.app.tracing.coroutines.launchTraced as launch
import com.android.internal.app.ChooserActivity
import com.android.systemui.clipboardoverlay.ClipboardListener.EXTRA_SUPPRESS_OVERLAY
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.user.data.repository.UserRepository
import com.android.systemui.user.utils.UserScopedService
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withContext

class ActionExecutor
@AssistedInject
constructor(
    private val context: Context,
    private val intentExecutor: ActionIntentExecutor,
    private val userRepository: UserRepository,
    private val clipboardManager: UserScopedService<ClipboardManager>,
    @Application private val applicationScope: CoroutineScope,
    @Background private val backgroundDispatcher: CoroutineDispatcher,
    @Assisted val window: Window,
    @Assisted val viewProxy: ScreenshotShelfViewProxy,
    @Assisted val finishDismiss: () -> Unit,
) {

    var isPendingSharedTransition = false
        private set

    // Tally: counts shared transitions and preview dismissals, so a transition's late callback
    // cannot close a later preview.
    private var transitionGeneration = 0

    private val currentUserHandle: UserHandle
        get() = userRepository.getSelectedUserInfo().userHandle

    fun startSharedTransition(intent: Intent, user: UserHandle, overrideTransition: Boolean) {
        val callbacks = startTransitionCallbacks()
        viewProxy.fadeForSharedTransition()
        val windowTransition = createWindowTransition(callbacks)
        applicationScope.launch("$TAG#launchIntentAsync") {
            intentExecutor.launchIntent(
                intent,
                user,
                overrideTransition,
                windowTransition.first,
                windowTransition.second,
            )
        }
    }

    fun sendPendingIntent(pendingIntent: PendingIntent) {
        try {
            val options = BroadcastOptions.makeBasic()
            options.setInteractive(true)
            options.setPendingIntentBackgroundActivityStartMode(
                ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
            )
            pendingIntent.send(options.toBundle())
            viewProxy.requestDismissal(null)
        } catch (e: PendingIntent.CanceledException) {
            Log.e(TAG, "Intent cancelled", e)
        }
    }

    // TODO(b/458072887): Fix the image data being pasteable as text when right-clicking.
    // Copying the screenshot to clipboard avoids the Clipboard overlay UI.
    fun copyScreenshotToClipboard(uri: Uri) {
        val clipData = ClipData.newUri(context.contentResolver, "Screenshot", uri)
        clipData.description.extras =
            PersistableBundle().apply { putBoolean(EXTRA_SUPPRESS_OVERLAY, true) }
        clipboardManager.forUser(currentUserHandle).setPrimaryClip(clipData)
        viewProxy.requestDismissal(null)
    }

    /**
     * Tally: deletes the just-saved screenshot and dismisses the overlay. SystemUI wrote the file,
     * so it deletes it the same way the long screenshot flow deletes its original.
     */
    fun deleteScreenshot(uri: Uri) {
        viewProxy.requestDismissal(null)
        applicationScope.launch("$TAG#deleteScreenshot") {
            withContext(backgroundDispatcher) {
                try {
                    if (context.contentResolver.delete(uri, null) < 1) {
                        Log.w(TAG, "Screenshot to delete was already gone")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to delete screenshot", e)
                }
            }
        }
    }

    /**
     * Tally: the preview was closed. A shared transition still in flight no longer owns it, so its
     * late callbacks are ignored and closing system dialogs dismisses the next preview again.
     */
    fun onPreviewDismissed() {
        isPendingSharedTransition = false
        transitionGeneration++
    }

    /** Marks a shared transition as pending and returns the callbacks that end it. */
    @VisibleForTesting
    internal fun startTransitionCallbacks(): ExitTransitionCallbacks {
        isPendingSharedTransition = true
        val generation = ++transitionGeneration
        return object : ExitTransitionCallbacks {
            override fun isReturnTransitionAllowed(): Boolean {
                return false
            }

            override fun hideSharedElements() {
                if (generation != transitionGeneration) {
                    return
                }
                isPendingSharedTransition = false
                finishDismiss.invoke()
            }

            // Tally: for this one-way transition onFinish only comes when the opened app cancels
            // the hand-off (for example when it stops or finishes before its enter transition
            // runs). hideSharedElements then never comes, so close the preview here instead of
            // leaving it, still tappable, over that app until the timeout.
            override fun onFinish() {
                if (generation != transitionGeneration || !isPendingSharedTransition) {
                    return
                }
                Log.d(TAG, "Shared transition cancelled, dismissing")
                isPendingSharedTransition = false
                finishDismiss.invoke()
            }
        }
    }

    /**
     * Supplies the necessary bits for the shared element transition to share sheet. Note that once
     * called, the action intent to share must be sent immediately after.
     */
    private fun createWindowTransition(
        callbacks: ExitTransitionCallbacks
    ): Pair<ActivityOptions, ExitTransitionCoordinator> {
        val transition =
            ActivityOptions.startSharedElementAnimation(
                window,
                callbacks,
                null,
                Pair.create(
                    viewProxy.screenshotPreview,
                    ChooserActivity.FIRST_IMAGE_PREVIEW_TRANSITION_NAME,
                ),
            )
        transition.first.launchDisplayId = window.context.displayId
        return transition
    }

    @AssistedFactory
    interface Factory {
        fun create(
            window: Window,
            viewProxy: ScreenshotShelfViewProxy,
            finishDismiss: (() -> Unit),
        ): ActionExecutor
    }

    companion object {
        private const val TAG = "ActionExecutor"
    }
}
