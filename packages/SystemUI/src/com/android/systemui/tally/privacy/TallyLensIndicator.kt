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

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Rect
import android.graphics.RectF
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.DisplayMetrics
import android.util.DisplayUtils
import android.util.Log
import android.view.DisplayCutout
import android.view.DisplayInfo
import android.view.Gravity
import android.view.WindowManager
import androidx.annotation.VisibleForTesting
import com.android.app.tracing.coroutines.launchTraced as launch
import com.android.systemui.CoreStartable
import com.android.systemui.ScreenDecorations
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.privacy.PrivacyItem
import com.android.systemui.privacy.PrivacyItemController
import com.android.systemui.privacy.PrivacyType
import com.android.systemui.res.R
import com.android.systemui.settings.DisplayTracker
import com.android.systemui.tally.TallyShell
import com.android.systemui.util.concurrency.DelayableExecutor
import com.android.systemui.util.time.SystemClock
import java.io.PrintWriter
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope

/**
 * Tally's lens ring and the location lamp by the lens: a ring around the front camera's cutout
 * while an app uses the front camera, and a lamp beside it while any app uses location.
 *
 * They add to the stock privacy chip and dot and replace nothing: the chip and the dot still show
 * every camera. Like them, they take their data from [PrivacyItemController] (with its holds, and
 * location whenever it reports location), for every app and in every state. The ring also needs a
 * front camera to have opened during the current camera use, as the camera service reports it (as
 * stock's camera protection reads it). It then keeps the chip's holds: it stays lit until the
 * camera is no longer in use, however soon the front camera closes, unless the app switches to
 * another camera. A camera whose facing cannot be read counts as the front one, and where SystemUI
 * cannot follow cameras opening, any camera lights the ring.
 *
 * They are drawn in a window of their own above every other window, the lock screen, the shade and
 * dialogs included, as the privacy dot is, on active pixels at least
 * [TallyLensGeometry.CLEARANCE_DP] dp outside the display cutout. The window takes no touch, so it
 * never blocks the status bar or the app under it, and it is there only while something is lit or
 * fading out.
 *
 * A device overlay can set the lens as a circle (`tally_lens_center_x`, `tally_lens_center_y` and
 * `tally_lens_radius`, in the cutout path's pixels): the ring then keeps clear of it too, and the
 * lamp sits level with its centre. Without it the lens is inferred from the cutout's shape.
 */
@SysUISingleton
class TallyLensIndicator
@Inject
constructor(
    @Main private val context: Context,
    @Main private val mainExecutor: DelayableExecutor,
    @Application private val scope: CoroutineScope,
    private val windowManager: WindowManager,
    private val displayTracker: DisplayTracker,
    private val privacyItemController: PrivacyItemController,
    private val indicatorArea: TallyIndicatorArea,
    private val cameraManager: CameraManager,
    private val systemClock: SystemClock,
) : CoreStartable {

    private var view: TallyLensIndicatorView? = null
    private var cameraInUse = false
    // Whether the camera service's open and close reports are followed; if not, any camera counts.
    private var followsCameras = false
    private val openFrontCameras = mutableSetOf<String>()
    private val openOtherCameras = mutableSetOf<String>()
    // Whether a front camera opened during the current camera use; see [frontCameraInUse].
    private var frontCameraLatched = false
    // When the last open front camera closed (elapsed realtime), or null if none has.
    private var frontClosedAt: Long? = null
    private var recheckCanceler: Runnable? = null
    private val frontFacing = mutableMapOf<String, Boolean>()
    private var locationInUse = false
    private var isAreaDark = true

    private var geometryComputed = false
    private var geometryKey: GeometryKey? = null
    private var geometry: TallyLensGeometry? = null

    /**
     * The lens circle a device overlay sets: its centre's x from the display's horizontal centre,
     * its centre's y and its radius, in pixels of the display's highest resolution in its natural
     * orientation, as the cutout path. Null when the radius is not set.
     */
    private val lensConfig: FloatArray? by lazy {
        val res = context.resources
        val radius = res.getDimension(R.dimen.tally_lens_radius)
        if (radius > 0f) {
            floatArrayOf(
                res.getDimension(R.dimen.tally_lens_center_x),
                res.getDimension(R.dimen.tally_lens_center_y),
                radius,
            )
        } else {
            null
        }
    }

    // PrivacyItemController keeps its callbacks as weak references: this field holds this one.
    private val privacyItemsCallback =
        object : PrivacyItemController.Callback {
            override fun onPrivacyItemsChanged(privacyItems: List<PrivacyItem>) {
                cameraInUse = privacyItems.any { it.privacyType == PrivacyType.TYPE_CAMERA }
                locationInUse = privacyItems.any { it.privacyType == PrivacyType.TYPE_LOCATION }
                if (!cameraInUse && openFrontCameras.isEmpty() && !inFrontCloseWindow()) {
                    frontCameraLatched = false
                }
                update()
            }
        }

    // Cameras already open when this registers are reported at once.
    private val cameraCallback =
        object : CameraManager.AvailabilityCallback() {
            override fun onCameraOpened(cameraId: String, packageId: String) {
                if (facesFront(cameraId)) {
                    openFrontCameras.add(cameraId)
                    frontCameraLatched = true
                } else {
                    openOtherCameras.add(cameraId)
                    // The app switched to another camera, unless the front one only just closed:
                    // then the camera's privacy item may still be on its way, so wait.
                    if (openFrontCameras.isEmpty()) {
                        if (inFrontCloseWindow()) scheduleRecheck() else frontCameraLatched = false
                    }
                }
                update()
            }

            // Reported straight from the camera service, so it can come before or after the
            // privacy item of the same use: the latch never clears here.
            override fun onCameraClosed(cameraId: String) {
                openOtherCameras.remove(cameraId)
                if (openFrontCameras.remove(cameraId) && openFrontCameras.isEmpty()) {
                    frontClosedAt = systemClock.elapsedRealtime()
                    scheduleRecheck()
                }
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
        followsCameras =
            context.checkSelfPermission(Manifest.permission.CAMERA_OPEN_CLOSE_LISTENER) ==
                PackageManager.PERMISSION_GRANTED
        if (followsCameras) cameraManager.registerAvailabilityCallback(mainExecutor, cameraCallback)
        privacyItemController.addCallback(privacyItemsCallback)
        displayTracker.addDisplayChangeCallback(displayCallback, mainExecutor)
        scope.launch {
            indicatorArea.isOverlayAreaDark.collect { dark ->
                isAreaDark = dark
                view?.setColors(TallyIndicatorColors.sensor(context, dark))
            }
        }
    }

    /**
     * Whether the ring shows: an app uses a camera (holds included) and, if cameras are followed, a
     * front camera is open, opened during this use (the latch), or closed within [FRONT_CLOSE_HOLD]
     * ms. The camera service reports opens and closes on its own path, unordered with the privacy
     * items, so a short front camera use stays lit for the privacy item's hold whichever comes
     * first. The latch is set when a front camera opens and clears only once that window has passed
     * with no front camera open: when the camera is no longer in use, or when the app has another
     * camera open instead.
     */
    private val frontCameraInUse: Boolean
        get() =
            cameraInUse &&
                (!followsCameras ||
                    openFrontCameras.isNotEmpty() ||
                    frontCameraLatched ||
                    inFrontCloseWindow())

    private fun inFrontCloseWindow(): Boolean {
        val closedAt = frontClosedAt ?: return false
        return systemClock.elapsedRealtime() - closedAt <= FRONT_CLOSE_HOLD
    }

    /** Checks the latch again once the front camera's close window has passed. */
    private fun scheduleRecheck() {
        recheckCanceler?.run()
        recheckCanceler = mainExecutor.executeDelayed(::recheck, FRONT_CLOSE_HOLD + 1)
    }

    private fun recheck() {
        recheckCanceler = null
        if (openFrontCameras.isNotEmpty() || inFrontCloseWindow()) return
        if (!cameraInUse || openOtherCameras.isNotEmpty()) frontCameraLatched = false
        update()
    }

    /** Whether the ring is lit now. */
    @VisibleForTesting
    internal val isRingLit: Boolean
        get() = frontCameraInUse

    private fun facesFront(cameraId: String): Boolean =
        frontFacing.getOrPut(cameraId) {
            val facing =
                try {
                    cameraManager
                        .getCameraCharacteristics(cameraId)
                        .get(CameraCharacteristics.LENS_FACING)
                } catch (e: CameraAccessException) {
                    null
                } catch (e: RuntimeException) {
                    // An unknown id, or a camera this user may not read (SecurityException): on
                    // the main thread, a failure here must never take SystemUI down.
                    Log.w(TAG, "Unable to read camera $cameraId's facing", e)
                    null
                }
            facing == null || facing == CameraCharacteristics.LENS_FACING_FRONT
        }

    /** Shows what is in use now; the window is added when something lights up. */
    private fun update() {
        val camera = frontCameraInUse
        val lit = camera || locationInUse
        val current = view ?: if (lit) addWindow() else null
        current?.setInUse(camera = camera, location = locationInUse)
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
        if (frontCameraInUse || locationInUse || current.isShowing) return
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
        // As ScreenDecorations scales the corners: the highest resolution against the size now.
        val maxMode = DisplayUtils.getMaximumResolutionDisplayMode(info.supportedModes)
        return GeometryKey(
            rotation = info.rotation,
            cutoutBounds = cutoutBounds,
            displayBounds = Rect(0, 0, info.logicalWidth, info.logicalHeight),
            densityDpi = info.logicalDensityDpi,
            cutout = cutout,
            naturalWidth = info.naturalWidth,
            naturalHeight = info.naturalHeight,
            physicalWidth = maxMode?.physicalWidth ?: info.naturalWidth,
            physicalRatio =
                if (maxMode != null) {
                    DisplayUtils.getPhysicalPixelDisplaySizeRatio(
                        maxMode.physicalWidth,
                        maxMode.physicalHeight,
                        info.naturalWidth,
                        info.naturalHeight,
                    )
                } else {
                    1f
                },
        )
    }

    /** The lens circle the device sets, on the display now, or null when it sets none. */
    private fun lensCircle(key: GeometryKey): RectF? {
        val (x, y, radius) = lensConfig ?: return null
        return TallyLensGeometry.lensCircle(
            x,
            y,
            radius,
            key.physicalWidth,
            key.physicalRatio,
            key.naturalWidth,
            key.naturalHeight,
            key.rotation,
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
                    lensCircle(key),
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
        pw.println(
            "  followsCameras=$followsCameras openFrontCameras=$openFrontCameras" +
                " openOtherCameras=$openOtherCameras frontCameraLatched=$frontCameraLatched" +
                " frontClosedAt=$frontClosedAt"
        )
        pw.println("  isAreaDark=$isAreaDark windowAdded=${view != null}")
        pw.println("  windowBounds=${geometry?.windowBounds} hasLamp=${geometry?.hasLamp}")
        pw.println("  lensConfig=${lensConfig?.contentToString() ?: "inferred from the cutout"}")
    }

    /** What the geometry depends on; the cutout itself is compared through its bounds. */
    private class GeometryKey(
        val rotation: Int,
        val cutoutBounds: Rect,
        val displayBounds: Rect,
        val densityDpi: Int,
        val cutout: DisplayCutout,
        val naturalWidth: Int,
        val naturalHeight: Int,
        val physicalWidth: Int,
        val physicalRatio: Float,
    ) {
        override fun equals(other: Any?): Boolean =
            other is GeometryKey &&
                rotation == other.rotation &&
                cutoutBounds == other.cutoutBounds &&
                displayBounds == other.displayBounds &&
                densityDpi == other.densityDpi &&
                physicalWidth == other.physicalWidth &&
                physicalRatio == other.physicalRatio

        override fun hashCode(): Int =
            ((rotation * 31 + cutoutBounds.hashCode()) * 31 + displayBounds.hashCode()) * 31 +
                densityDpi
    }

    private companion object {
        const val TAG = "TallyLensIndicator"

        /**
         * How long after the last front camera closes it still counts as in use while a camera is:
         * the longest of PrivacyItemController's holds, which the camera's privacy item can take.
         */
        val FRONT_CLOSE_HOLD =
            maxOf(
                PrivacyItemController.TIME_TO_HOLD_INDICATORS,
                PrivacyItemController.TIME_TO_HOLD_INDICATORS_FOR_LOCATION,
            )
        const val WINDOW_TITLE = "TallyLensIndicator"
    }
}
