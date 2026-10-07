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

import android.app.UiModeManager
import android.app.WallpaperColors
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Context
import android.content.res.Configuration
import android.content.theming.ThemeStyle
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.service.wallpaper.WallpaperService
import android.util.Log
import android.view.SurfaceHolder
import com.android.systemui.monet.ColorScheme

/**
 * Tally's Paper wallpaper, the DiamaneOS default (the framework overlay's
 * default_wallpaper_component): stone-coloured paper with a fine grain and a slight vignette
 * ([TallyPaper]), light or dark with the theme, in the colour preset's hue. Its colours carry the
 * hint of the ink that reads best on its average colour (dark text on the light paper, dark theme
 * on the dark one), so Launcher, the lock screen and the status bar take that ink. The lock screen
 * draws no dim over it ([isOnLockScreen]).
 *
 * It is static. Each engine renders the paper once for its surface size, theme and palette into a
 * bitmap, keeps only that one, and draws it when the system asks (a new or resized surface). A
 * theme or palette change frees it and renders the new one; nothing animates. It turns offset
 * updates off, ignores zoom and touch, and answers colour requests for any area with the paper's
 * colours, so nothing reads its pixels back. SystemUI declares it only with the Tally flag on
 * (android:featureFlag).
 *
 * In Wallpaper & style, a preview engine shows the colours the picker previews
 * ([COMMAND_PREVIEW_COLOURS]): the paper of that colour's hue and theme, until the picker previews
 * none again. Only the preview changes; it reports no colours for them.
 */
class TallyWallpaper : WallpaperService() {
    private var worker: HandlerThread? = null
    private var handler: Handler? = null

    /** The live engines (one per display, and previews); used on the worker thread only. */
    private val engines = mutableSetOf<PaperEngine>()

    override fun onCreate() {
        super.onCreate()
        worker = HandlerThread(TAG).also { it.start() }
        handler = Handler(worker!!.looper)
    }

    /** Engine messages run on this service's own thread, not SystemUI's main thread. */
    override fun onProvideEngineLooper(): Looper = worker?.looper ?: super.onProvideEngineLooper()

    override fun onCreateEngine(): Engine = PaperEngine()

    /** Dark theme or the palette may have changed: each engine checks its paper. */
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

    private fun currentLook(): PaperLook {
        val night = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return PaperLook.of(resources, dark = night == Configuration.UI_MODE_NIGHT_YES)
    }

    private inner class PaperEngine : Engine() {
        /** The paper drawn and reported, and its colours. */
        @Volatile private var look = currentLook()
        @Volatile private var colors = colorsOf(TallyPaper.average(look))

        /** The rendered paper, for [look] at [paperWidth] by [paperHeight]; the only one kept. */
        private var paper: Bitmap? = null
        private var paperWidth = 0
        private var paperHeight = 0

        /** The areas whose colours the system asked for (lock clock, status bar sampling). */
        private val areas = mutableListOf<RectF>()

        /** The paper of the colours Wallpaper & style previews on this preview engine, or null. */
        private var previewed: PaperLook? = null

        /**
         * Whether this engine has reported its colours yet. The system keeps the last colours it
         * was told across reboots, so an engine that starts in dark theme must report its own
         * once, or Home, the status bar and the lock screen keep the light paper's dark ink.
         */
        private var reported = false

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
            release()
            super.onDestroy()
        }

        override fun onSurfaceRedrawNeeded(holder: SurfaceHolder) {
            val changed = takeLook()
            draw(holder)
            if (changed || !reported) report()
        }

        override fun onComputeColors(): WallpaperColors = colors

        override fun onCommand(
            action: String?,
            x: Int,
            y: Int,
            z: Int,
            extras: Bundle?,
            resultRequested: Boolean,
        ): Bundle? {
            if (action == COMMAND_PREVIEW_COLOURS && isPreview) {
                previewed = extras?.let(::previewLook)
                update()
            }
            return super.onCommand(action, x, y, z, extras, resultRequested)
        }

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

        /** Renders, draws and reports the paper again if the theme or palette changed it. */
        fun update() {
            if (!takeLook()) return
            draw(surfaceHolder)
            report()
        }

        /**
         * Takes the current theme's paper, or the previewed colours'; true if it changed (the old
         * bitmap is freed).
         */
        private fun takeLook(): Boolean {
            val now = previewed ?: currentLook()
            if (now == look) return false
            look = now
            colors = colorsOf(TallyPaper.average(now))
            release()
            return true
        }

        private fun release() {
            paper?.recycle()
            paper = null
        }

        private fun report() {
            // Previewed colours are not the wallpaper's: the picker shows them, nothing reads them.
            if (previewed != null) return
            reported = true
            notifyColorsChanged()
            if (areas.isNotEmpty()) notifyAreas(areas.toList())
        }

        private fun notifyAreas(regions: List<RectF>) {
            val current = colors
            try {
                notifyLocalColorsChanged(regions, regions.map { current })
            } catch (e: RuntimeException) {
                Log.w(TAG, "Could not report the colours of ${regions.size} areas", e)
            }
        }

        /**
         * The paper of the colours in a [COMMAND_PREVIEW_COLOURS] command's [extras]: its seed and
         * style give the palette's accent1_500 as applying them would (ThemeOverlayController, at
         * the system's contrast), whose hue the paper takes, in the previewed theme. Null without a
         * seed.
         */
        private fun previewLook(extras: Bundle): PaperLook? {
            if (!extras.containsKey(EXTRA_SEED_COLOR)) return null
            val dark =
                if (extras.containsKey(EXTRA_DARK_MODE)) extras.getBoolean(EXTRA_DARK_MODE)
                else currentLook().dark
            return try {
                val scheme =
                    ColorScheme(
                        extras.getInt(EXTRA_SEED_COLOR),
                        dark,
                        extras.getInt(EXTRA_THEME_STYLE, ThemeStyle.TONAL_SPOT),
                        getSystemService(UiModeManager::class.java)?.contrast?.toDouble() ?: 0.0,
                    )
                PaperLook.of(resources, dark, PaperColour.hue(scheme.accent1.s500))
            } catch (e: RuntimeException) {
                Log.w(TAG, "Could not preview the colours", e)
                null
            }
        }

        /** The paper at [width] by [height], rendered only when the size or the look changed. */
        private fun paperFor(width: Int, height: Int): Bitmap {
            paper?.let { if (paperWidth == width && paperHeight == height) return it }
            release()
            val software = TallyPaper.render(width, height, look)
            // Kept in graphics memory, not on SystemUI's heap, when it can be
            val kept = software.copy(Bitmap.Config.HARDWARE, false)?.also { software.recycle() }
            paperWidth = width
            paperHeight = height
            return (kept ?: software).also { paper = it }
        }

        private fun draw(holder: SurfaceHolder) {
            val surface = holder.surface
            val frame = holder.surfaceFrame
            if (!surface.isValid || frame.width() <= 0 || frame.height() <= 0) return
            val bitmap = paperFor(frame.width(), frame.height())
            var canvas: Canvas? = null
            try {
                canvas = surface.lockHardwareCanvas()
                canvas.drawBitmap(bitmap, 0f, 0f, null)
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
         * The command Wallpaper & style sends a preview engine with the colours it previews:
         * [EXTRA_SEED_COLOR], [EXTRA_THEME_STYLE] and [EXTRA_DARK_MODE]; without a seed, the paper
         * shows the applied colours again.
         */
        const val COMMAND_PREVIEW_COLOURS = "de.diamaneos.wallpaper.PREVIEW_COLOURS"
        const val EXTRA_SEED_COLOR = "seed_color"
        const val EXTRA_THEME_STYLE = "theme_style"
        const val EXTRA_DARK_MODE = "dark_mode"

        /**
         * The colours of a wallpaper whose average colour is [average], hinting the ink that reads
         * better on it: dark text where black has more contrast than white, otherwise dark theme.
         */
        fun colorsOf(average: Int): WallpaperColors {
            val luminance = Color.luminance(average)
            val darkInk = (luminance + 0.05f) / 0.05f
            val lightInk = 1.05f / (luminance + 0.05f)
            val hints =
                if (darkInk >= lightInk) WallpaperColors.HINT_SUPPORTS_DARK_TEXT
                else WallpaperColors.HINT_SUPPORTS_DARK_THEME
            return WallpaperColors(Color.valueOf(average), null, null, hints)
        }

        /**
         * Whether the lock screen of [userId] (the current user) shows the Paper wallpaper: as its
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
