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

import android.content.Context
import android.graphics.Rect
import android.util.DisplayMetrics
import android.util.Log
import android.view.DisplayCutout
import android.view.DisplayInfo
import android.view.Gravity
import android.view.WindowManager
import com.android.app.tracing.coroutines.launchTraced as launch
import com.android.systemui.CoreStartable
import com.android.systemui.ScreenDecorations
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.privacy.PrivacyItem
import com.android.systemui.privacy.PrivacyItemController
import com.android.systemui.privacy.PrivacyType
import com.android.systemui.settings.DisplayTracker
import com.android.systemui.tally.TallyShell
import java.io.PrintWriter
import java.util.concurrent.Executor
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope

/**
 * Tally's lens ring and the location lamp by the lens: a ring around the front camera's cutout
 * while any app uses the camera, and a lamp beside it while any app uses location.
 *
 * They add to the stock privacy chip and dot and replace nothing. Like them, they take their data
 * only from [PrivacyItemController] (with its holds, and location whenever it reports location),
 * for every app and in every state. They are drawn in a window of their own above every other
 * window, the lock screen, the shade and dialogs included, as the privacy dot is, on active pixels
 * at least [TallyLensGeometry.CLEARANCE_DP] dp outside the display cutout. The window takes no
 * touch, so it never blocks the status bar or the app under it, and it is there only while
 * something is lit or fading out.
 */
@SysUISingleton
class TallyLensIndicator
@Inject
constructor(
    @Main private val context: Context,
    @Main private val mainExecutor: Executor,
    @Application private val scope: CoroutineScope,
    private val windowManager: WindowManager,
    private val displayTracker: DisplayTracker,
    private val privacyItemController: PrivacyItemController,
    private val indicatorArea: TallyIndicatorArea,
) : CoreStartable {

    private var view: TallyLensIndicatorView? = null
    private var cameraInUse = false
    private var locationInUse = false
    private var isAreaDark = true

    private var geometryComputed = false
    private var geometryKey: GeometryKey? = null
    private var geometry: TallyLensGeometry? = null

    // PrivacyItemController keeps its callbacks as weak references: this field holds this one.
    private val privacyItemsCallback =
        object : PrivacyItemController.Callback {
            override fun onPrivacyItemsChanged(privacyItems: List<PrivacyItem>) {
                cameraInUse = privacyItems.any { it.privacyType == PrivacyType.TYPE_CAMERA }
                locationInUse = privacyItems.any { it.privacyType == PrivacyType.TYPE_LOCATION }
                update()
            }
        }

    private val displayCallback =
        object : DisplayTracker.Callback {
            override fun onDisplayChanged(displayId: Int) {
                if (displayId == displayTracker.defaultDisplayId) relayout()
            }
        }

    override fun start() {
        if (TallyShell.isUnexpectedlyInLegacyMode()) return
        privacyItemController.addCallback(privacyItemsCallback)
        displayTracker.addDisplayChangeCallback(displayCallback, mainExecutor)
        scope.launch {
            indicatorArea.isOverlayAreaDark.collect { dark ->
                isAreaDark = dark
                view?.setColors(TallyIndicatorColors.sensor(context, dark))
            }
        }
    }

    /** Shows what is in use now; the window is added when something lights up. */
    private fun update() {
        val lit = cameraInUse || locationInUse
        val current = view ?: if (lit) addWindow() else null
        current?.setInUse(camera = cameraInUse, location = locationInUse)
    }

    private fun addWindow(): TallyLensIndicatorView? {
        val g = currentGeometry() ?: return null
        val newView = TallyLensIndicatorView(context, onIdle = ::removeWindowIfIdle)
        newView.setGeometry(g)
        newView.setColors(TallyIndicatorColors.sensor(context, isAreaDark))
        try {
            windowManager.addView(newView, layoutParams(g.windowBounds))
        } catch (e: RuntimeException) {
            Log.e(TAG, "Unable to add the lens indicator window", e)
            return null
        }
        view = newView
        return newView
    }

    private fun removeWindowIfIdle() {
        val current = view ?: return
        if (cameraInUse || locationInUse || current.isShowing) return
        removeWindow(current)
    }

    private fun removeWindow(current: TallyLensIndicatorView) {
        view = null
        try {
            windowManager.removeView(current)
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Unable to remove the lens indicator window", e)
        }
    }

    /** Follows the display: its rotation, size, density and cutout. */
    private fun relayout() {
        val current = view
        if (current == null) {
            // Something may be lit that could not be drawn before this change.
            update()
            return
        }
        if (geometryComputed && readGeometryKey() == geometryKey) return
        val g = currentGeometry()
        if (g == null) {
            removeWindow(current)
            return
        }
        current.setGeometry(g)
        windowManager.updateViewLayout(current, layoutParams(g.windowBounds))
    }

    /** The display's rotation, size, density and cutout, all from one snapshot. */
    private fun readGeometryKey(): GeometryKey? {
        val info = DisplayInfo()
        if (!displayTracker.getDisplay(displayTracker.defaultDisplayId).getDisplayInfo(info)) {
            return null
        }
        val cutout = info.displayCutout ?: return null
        val cutoutBounds = Rect()
        cutout.boundingRects.forEach { cutoutBounds.union(it) }
        return GeometryKey(
            rotation = info.rotation,
            cutoutBounds = cutoutBounds,
            displayBounds = Rect(0, 0, info.logicalWidth, info.logicalHeight),
            densityDpi = info.logicalDensityDpi,
            cutout = cutout,
        )
    }

    private fun currentGeometry(): TallyLensGeometry? {
        val key = readGeometryKey()
        if (geometryComputed && key == geometryKey) return geometry
        val cutoutPath = key?.cutout?.cutoutPath
        val g =
            if (key != null && cutoutPath != null) {
                TallyLensGeometry.compute(
                    cutoutPath,
                    key.displayBounds,
                    key.rotation,
                    key.densityDpi.toFloat() / DisplayMetrics.DENSITY_DEFAULT,
                )
            } else {
                null
            }
        geometryComputed = true
        geometryKey = key
        geometry = g
        if (g == null) Log.w(TAG, "No display cutout to draw the lens indicator around")
        return g
    }

    private fun layoutParams(bounds: Rect): WindowManager.LayoutParams =
        ScreenDecorations.getWindowLayoutBaseParams().apply {
            // Only drawn: every touch goes to the window under it.
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            width = bounds.width()
            height = bounds.height()
            gravity = Gravity.TOP or Gravity.LEFT
            x = bounds.left
            y = bounds.top
            title = WINDOW_TITLE
        }

    override fun dump(pw: PrintWriter, args: Array<out String>) {
        pw.println("$TAG: cameraInUse=$cameraInUse locationInUse=$locationInUse")
        pw.println("  isAreaDark=$isAreaDark windowAdded=${view != null}")
        pw.println("  windowBounds=${geometry?.windowBounds} hasLamp=${geometry?.hasLamp}")
    }

    /** What the geometry depends on; the cutout itself is compared through its bounds. */
    private class GeometryKey(
        val rotation: Int,
        val cutoutBounds: Rect,
        val displayBounds: Rect,
        val densityDpi: Int,
        val cutout: DisplayCutout,
    ) {
        override fun equals(other: Any?): Boolean =
            other is GeometryKey &&
                rotation == other.rotation &&
                cutoutBounds == other.cutoutBounds &&
                displayBounds == other.displayBounds &&
                densityDpi == other.densityDpi

        override fun hashCode(): Int =
            ((rotation * 31 + cutoutBounds.hashCode()) * 31 + displayBounds.hashCode()) * 31 +
                densityDpi
    }

    private companion object {
        const val TAG = "TallyLensIndicator"
        const val WINDOW_TITLE = "TallyLensIndicator"
    }
}
