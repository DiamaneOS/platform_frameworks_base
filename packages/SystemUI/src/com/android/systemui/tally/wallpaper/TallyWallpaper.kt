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

package com.android.systemui.tally.wallpaper

import android.app.WallpaperColors
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.RectF
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.service.wallpaper.WallpaperService
import android.util.Log
import android.view.SurfaceHolder
import com.android.systemui.res.R

/**
 * Tally's plain wallpaper, the DiamaneOS default (the framework overlay's
 * default_wallpaper_component): the whole screen in one colour, tally_wallpaper_plain, the same in
 * light and dark theme. Its colours carry the hints a picture of that colour gets, so Launcher, the
 * lock screen and the status bar take the ink that suits it. The lock screen draws no dim over it
 * ([isOnLockScreen]).
 *
 * It is static. It draws when the system asks (a new or resized surface) and never animates; it
 * draws again only if the colour itself changes, which happens when the colour follows the palette
 * and the palette changes. It turns offset updates off, ignores zoom and touch, and answers colour
 * requests for any area with its colour, so nothing reads its pixels back. SystemUI declares it
 * only with the Tally flag on (android:featureFlag).
 */
class TallyWallpaper : WallpaperService() {
    private var worker: HandlerThread? = null
    private var handler: Handler? = null

    /** The live engines (one per display, and previews); used on the worker thread only. */
    private val engines = mutableSetOf<PlainEngine>()

    override fun onCreate() {
        super.onCreate()
        worker = HandlerThread(TAG).also { it.start() }
        handler = Handler(worker!!.looper)
    }

    /** Engine messages run on this service's own thread, not SystemUI's main thread. */
    override fun onProvideEngineLooper(): Looper = worker?.looper ?: super.onProvideEngineLooper()

    override fun onCreateEngine(): Engine = PlainEngine()

    /** The palette may have changed (the colour can be a palette tone): each engine checks it. */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        handler?.post { engines.forEach { it.update() } }
    }

    override fun onDestroy() {
        super.onDestroy()
        worker?.quitSafely()
        worker = null
        handler = null
    }

    private fun plainColour(): Int = getColor(R.color.tally_wallpaper_plain)

    private inner class PlainEngine : Engine() {
        /** The colour drawn and reported. */
        @Volatile private var colour = plainColour()

        /** The areas whose colours the system asked for (lock clock, status bar sampling). */
        private val areas = mutableListOf<RectF>()

        init {
            setShowForAllUsers(true)
        }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            setOffsetNotificationsEnabled(false)
            engines += this
        }

        override fun onDestroy() {
            engines -= this
            super.onDestroy()
        }

        override fun onSurfaceRedrawNeeded(holder: SurfaceHolder) {
            val changed = takeColour()
            draw(holder)
            if (changed) report()
        }

        override fun onComputeColors(): WallpaperColors = colorsOf(colour)

        override fun supportsLocalColorExtraction() = true

        override fun addLocalColorsAreas(regions: List<RectF>) {
            handler?.post {
                areas += regions
                notifyAreas(regions)
            }
        }

        override fun removeLocalColorsAreas(regions: List<RectF>) {
            handler?.post { areas -= regions.toSet() }
        }

        /** Draws and reports the colour again if it changed. */
        fun update() {
            if (!takeColour()) return
            draw(surfaceHolder)
            report()
        }

        /** Takes the current colour; true if it changed. */
        private fun takeColour(): Boolean {
            val now = plainColour()
            if (now == colour) return false
            colour = now
            return true
        }

        private fun report() {
            notifyColorsChanged()
            if (areas.isNotEmpty()) notifyAreas(areas.toList())
        }

        private fun notifyAreas(regions: List<RectF>) {
            val colors = colorsOf(colour)
            try {
                notifyLocalColorsChanged(regions, regions.map { colors })
            } catch (e: RuntimeException) {
                Log.w(TAG, "Could not report the colours of ${regions.size} areas", e)
            }
        }

        private fun draw(holder: SurfaceHolder) {
            val surface = holder.surface
            if (!surface.isValid) return
            var canvas: Canvas? = null
            try {
                canvas = surface.lockHardwareCanvas()
                canvas.drawColor(colour, PorterDuff.Mode.SRC)
            } catch (e: IllegalStateException) {
                Log.w(TAG, "Could not draw the wallpaper", e)
            } finally {
                canvas?.let { surface.unlockCanvasAndPost(it) }
            }
        }
    }

    companion object {
        private const val TAG = "TallyWallpaper"

        /**
         * The colours of a wallpaper that is [colour] all over, with the hints
         * WallpaperColors.fromBitmap gives a picture of it: dark text above 70 % relative luminance
         * (its default threshold), dark theme below 30 %.
         */
        fun colorsOf(colour: Int): WallpaperColors {
            val luminance = Color.luminance(colour)
            val hints =
                when {
                    luminance > 0.7f -> WallpaperColors.HINT_SUPPORTS_DARK_TEXT
                    luminance < 0.3f -> WallpaperColors.HINT_SUPPORTS_DARK_THEME
                    else -> 0
                }
            return WallpaperColors(Color.valueOf(colour), null, null, hints)
        }

        /**
         * Whether the lock screen of [userId] (the current user) shows the plain wallpaper: as its
         * own lock wallpaper, or as Home's when there is none. Calls the wallpaper service; not on
         * the main thread.
         */
        @JvmStatic
        fun isOnLockScreen(
            context: Context,
            wallpaperManager: WallpaperManager,
            userId: Int,
        ): Boolean {
            val which =
                if (wallpaperManager.lockScreenWallpaperExists()) WallpaperManager.FLAG_LOCK
                else WallpaperManager.FLAG_SYSTEM
            return wallpaperManager.getWallpaperInfo(which, userId)?.component ==
                ComponentName(context, TallyWallpaper::class.java)
        }
    }
}
