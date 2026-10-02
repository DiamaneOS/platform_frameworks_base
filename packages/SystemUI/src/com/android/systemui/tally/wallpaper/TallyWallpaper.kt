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
import de.diamaneos.tally.R as TallyR

/**
 * Tally's plain wallpaper, the DiamaneOS default (the framework overlay's
 * default_wallpaper_component): the whole screen in the page colour, tally_background, the ground
 * Settings' pages sit on, so Home and the lock screen sit on the same ground. It follows dark theme
 * and the palette as the page does, and reports colours whose hints give dark text on the light
 * page and white text on the dark one.
 *
 * It is static. It draws when the system asks (a new or resized surface) and when the page colour
 * changes (dark theme or the palette), and never animates. It turns offset updates off, ignores
 * zoom and touch, and answers colour requests for any area with the page colour, so nothing reads
 * its pixels back. SystemUI declares it only with the Tally flag on (android:featureFlag).
 */
class TallyWallpaper : WallpaperService() {
    private var worker: HandlerThread? = null
    private var handler: Handler? = null

    /** The live engines (one per display, and previews); used on the worker thread only. */
    private val engines = mutableSetOf<PageEngine>()

    override fun onCreate() {
        super.onCreate()
        worker = HandlerThread(TAG).also { it.start() }
        handler = Handler(worker!!.looper)
    }

    /** Engine messages run on this service's own thread, not SystemUI's main thread. */
    override fun onProvideEngineLooper(): Looper = worker?.looper ?: super.onProvideEngineLooper()

    override fun onCreateEngine(): Engine = PageEngine()

    /** Dark theme or the palette may have changed: each engine checks its colour. */
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

    private fun pageColour(): Int = getColor(TallyR.color.tally_background)

    private inner class PageEngine : Engine() {
        /** The colour drawn and reported. */
        @Volatile private var colour = pageColour()

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

        override fun onVisibilityChanged(visible: Boolean) {
            if (visible) update()
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

        /** Draws and reports the page colour again if it changed. */
        fun update() {
            if (!takeColour()) return
            draw(surfaceHolder)
            report()
        }

        /** Takes the current page colour; true if it changed. */
        private fun takeColour(): Boolean {
            val now = pageColour()
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
    }
}
