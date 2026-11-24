/*
 * Copyright (C) 2026 DiamaneOS
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

package com.android.systemui.statusbar.notification.row

import android.app.Notification
import android.content.Context
import android.content.pm.LauncherApps
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import de.diamaneos.decode.client.AppResourceImages
import de.diamaneos.decode.client.ForeignIcons
import de.diamaneos.decode.client.ImageBudget
import de.diamaneos.decode.client.ImageThreads
import de.diamaneos.decode.client.NotificationImageGate
import de.diamaneos.decode.client.RetryTurns
import de.diamaneos.decode.client.VmDecode
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.IntSupplier

/**
 * DiamaneOS: the images of one notification row inflation. Every app image in the notification is decoded in the
 * protected VM first (or removed) on an image thread, so the framework's own image loading never sees another app's
 * compressed bytes. The app's own layouts are applied with resources that decode its bitmap files in the VM too, and
 * their images are made ahead of the inflation. URIs inside the app's layouts are opened only when the app may read
 * them itself. Nothing waits for the VM on the inflation thread every notification shares: the inflation starts once
 * the images are made ([start]). If converting fails, every app image is removed and the notification still shows.
 * A row whose images (message images included) were left out because the decode service did not answer is inflated
 * again once it answers ([VmDecode.addRecoveryListener]), so its images fill in with no user action.
 * The inflation reads the checked copy through [sourceOf], so no upstream signature changes for it.
 */
class NotificationRowImages(
    private val context: Context,
    private val entry: NotificationEntry,
    private val row: ExpandableNotificationRow,
) {
    // Made by prepare before the inflation runs: the checked copy and the context to apply it with.
    @Volatile private var checkedNotification: Notification? = null
    @Volatile private var gatedPackageContext: Context? = null
    @Volatile private var imageTask: ImageThreads.Task? = null
    @Volatile private var urgentImages = false
    // Set by abort. The row's image gate keeps only this flag, never the task: the task holds the row, and the gate
    // lives in a map keyed by the row, which must be able to forget it.
    private val imagesCancelled = AtomicBoolean()
    // The budget of this inflation's images: given back to the app's pixel pool when it is aborted.
    @Volatile private var imageBudget: ImageBudget? = null

    /**
     * Starts the image work, then [inflate] (the inflation task) once the images are made. Calls, alarms and other
     * urgent notifications use the urgent image threads, which normal image work never holds up, and a
     * notification with no app image is inflated at once. [isCancelled] stops it, [beforePrepare] runs on the image
     * thread first (the application info of the notification is brought up to date).
     */
    fun start(isCancelled: () -> Boolean, beforePrepare: Runnable, inflate: Runnable) {
        val sbn = entry.sbn
        val run = Runnable {
            if (isCancelled()) {
                return@Runnable
            }
            // The whole time of the image work is charged to the app (reserved before, settled after).
            beforePrepare.run()
            prepare()
            if (!isCancelled()) {
                inflate.run()
            }
        }
        var lane =
            NotificationImageGate.lane(context, sbn.notification, sbn.packageName, sbn.uid, sbn.normalizedUserId)
        if (lane == NotificationImageGate.Lane.NONE && entry.ranking.conversationShortcutInfo != null) {
            // The conversation's shortcut icon is made on an image thread too.
            lane = NotificationImageGate.Lane.NORMAL
        }
        // A task that gave way to another app's (the lane was full and this app had the most waiting) is inflated
        // at once, with only the images made before, like a refused one.
        val dropped = Runnable {
            if (!isCancelled()) {
                urgentImages = false
                inflate.run()
            }
        }
        when (lane) {
            NotificationImageGate.Lane.NONE -> inflate.run()
            NotificationImageGate.Lane.URGENT -> {
                urgentImages = true
                imageTask = ImageThreads.executeUrgent(sbn.packageName, run, dropped)
            }
            else -> imageTask = ImageThreads.execute(sbn.packageName, run, dropped)
        }
        if (lane != NotificationImageGate.Lane.NONE && imageTask == null) {
            // The image threads are full and this app has the most waiting: inflated at once (nothing waits for the
            // VM on the inflation thread).
            urgentImages = false
            inflate.run()
        }
    }

    /**
     * Makes the checked copy and the gated package context. On an image thread the VM is asked, elsewhere only what
     * was made before is used.
     */
    fun prepare() {
        val sbn = entry.sbn
        // The message images load apart from this work: one left out for the decode service asks for the same
        // inflation again.
        row.imageResolver?.outcomes()?.let { outcomes ->
            outcomes.setRetry(
                { leftOut(row, true, outcomes.capacityWakes(), outcomes.capacityObserved()) },
                { inlineLeftOutRows.done(row) },
            )
        }
        val packageContext = sbn.getPackageContext(context)
        var imageGate: NotificationImageGate? = null
        val unavailable = VmDecode.unavailableCount()
        try {
            val metrics = context.resources.displayMetrics
            val gate =
                NotificationImageGate.forNotification(
                    context,
                    sbn.packageName,
                    // A system notice names the app's user for its icon (overlay window, accessibility).
                    NotificationImageGate.imageUserOf(sbn),
                    (VM_ICON_DP * metrics.density).toInt().coerceAtLeast(1),
                    minOf(metrics.widthPixels, metrics.heightPixels).coerceAtLeast(1),
                    (VM_PICTURE_DP * metrics.density).toInt().coerceAtLeast(1),
                    rowImages[row],
                )
            imageGate = gate
            imageBudget = gate.budget()
            // The row's views keep its gate: each inflation starts a new round of it with a fresh budget.
            rowImages[row] = gate.resourceImages()
            // A cancelled inflation (a newer update, a removed notification) stops its image work.
            gate.setCancelled(imagesCancelled)
            // URIs inside the app's layouts (a Bundle's people, which the notification service does not check) are
            // opened only when the posting app may read them itself.
            gate.checkUrisAs(sbn.uid, sbn.packageName, sbn.normalizedUserId)
            val gated = gate.packageContext(packageContext)
            val copy = gate.sanitizedCopy(sbn.notification)
            gate.prewarm(copy, gated)
            if (ImageThreads.isImageThread()) {
                // The conversation's shortcut icon is decoded here, the inflation finds it made.
                entry.ranking.conversationShortcutInfo?.let {
                    ForeignIcons.shortcutIcon(
                        context,
                        context.getSystemService(LauncherApps::class.java),
                        it,
                        ForeignIcons.shortcutPx(context),
                        /* mayWait= */ true,
                    )
                }
            }
            gatedPackageContext = gated
            checkedNotification = copy
        } catch (e: Throwable) {
            // Any Error too (a stack overflow, out of memory): it costs the images, not the notification.
            strip(e, packageContext)
        } finally {
            // What the image work took is settled with the app's quota.
            imageGate?.finish(urgentImages)
        }
        checkedNotification?.let { remember(sbn.notification, it) }
        if (VmDecode.unavailableCount() != unavailable && !imagesCancelled.get()) {
            leftOut(row)
        } else if (!imagesCancelled.get()) {
            leftOutRows.done(row)
        }
    }

    private fun strip(e: Throwable, packageContext: Context) {
        Log.e(TAG, "cannot convert notification images, showing it without them", e)
        try {
            checkedNotification = NotificationImageGate.strippedCopy(context, entry.sbn.notification)
            gatedPackageContext = packageContext
        } catch (e2: Throwable) {
            // Only if that fails too is the notification not shown.
            Log.e(TAG, "cannot remove notification images", e2)
            checkedNotification = null
        }
    }

    /** The checked copy to inflate from (made now if [start] did not), or null when none could be made. */
    fun checked(): Notification? {
        if (checkedNotification == null) {
            prepare()
        }
        return checkedNotification
    }

    /** The context to apply the checked copy with (its app's images through the gate). */
    fun packageContext(): Context = gatedPackageContext ?: entry.sbn.getPackageContext(context)

    /** The inflation was aborted: image work not started is dropped, running work stops at its next check. */
    fun abort() {
        imagesCancelled.set(true)
        // Its pixels no longer count against the app (a removed notification, or a newer update's round).
        imageBudget?.release()
        imageTask?.cancel()
    }

    companion object {
        private const val TAG = "NotifRowImages"

        /** Icons and people decoded in the VM for a notification's views are bounded to this size. */
        private const val VM_ICON_DP = 128

        /** The big picture and the images of an app's own layouts are decoded at most this high. */
        private const val VM_PICTURE_DP = 320

        // The image gate of each row, kept with its views (they hold the resources it is set on).
        private val rowImages: MutableMap<ExpandableNotificationRow, AppResourceImages> =
            Collections.synchronizedMap(WeakHashMap())

        // The checked copy of each notification (by identity: Notification has no equals of its own) whose images
        // were made, for the inflation code to read. Gone with the notification: a notification that needed no
        // change maps to SAME, as a value naming its own key would keep it for good.
        private val sources: MutableMap<Notification, Notification> = Collections.synchronizedMap(WeakHashMap())
        private val SAME = Notification()

        private fun remember(notification: Notification, checked: Notification) {
            sources[notification] = if (checked === notification) SAME else checked
        }

        // Rows with images left out for the decode service and their turns (RetryTurns): a row is inflated again at
        // most MAX_RECOVERY_REBINDS times, so an image the VM can never make in time is not asked for again and again,
        // and again as often after each genuine change of the service (an outage that ended, room again).
        private val leftOutRows = RetryTurns<ExpandableNotificationRow>(MAX_RECOVERY_REBINDS)
        // Inline work ends independently of prepare. Success there cannot erase its missing URIs.
        private val inlineLeftOutRows = RetryTurns<ExpandableNotificationRow>(MAX_RECOVERY_REBINDS)
        private const val MAX_RECOVERY_REBINDS = 3
        private val main = Handler(Looper.getMainLooper())
        private val recoveryListening = AtomicBoolean()

        private fun leftOut(
            row: ExpandableNotificationRow,
            inline: Boolean = false,
            capacity: IntSupplier? = VmDecode.capacityWakes(),
            observed: Int = capacity?.asInt ?: 0,
        ) {
            (if (inline) inlineLeftOutRows else leftOutRows).leftOut(row, VmDecode.wakes(), capacity, observed)
            if (recoveryListening.compareAndSet(false, true)) {
                VmDecode.addRecoveryListener { main.post { rebindLeftOut() } }
            }
            // A lane event may have arrived before the main-thread callback registered this row.
            if (capacity != null && capacity.asInt != observed) {
                main.post { rebindLeftOut() }
            }
        }

        // The decode service answers again: every row with images left out and turns left is inflated again (on the
        // main thread).
        private fun rebindLeftOut() {
            val wakes = VmDecode.wakes()
            val rows = (leftOutRows.due(wakes) + inlineLeftOutRows.due(wakes)).toSet()
            for (row in rows) {
                row.rebindForMissingImages()
            }
        }

        /**
         * What views of [notification] (an app's, as posted) are made from: the checked copy its image work made,
         * else a copy with every app image removed. Never the app's own notification, whose images were not checked.
         */
        @JvmStatic
        fun sourceOf(notification: Notification, context: Context): Notification =
            sources[notification].let { checked ->
                when {
                    checked == null -> NotificationImageGate.strippedCopy(context, notification)
                    checked === SAME -> notification
                    else -> checked
                }
            }
    }
}
