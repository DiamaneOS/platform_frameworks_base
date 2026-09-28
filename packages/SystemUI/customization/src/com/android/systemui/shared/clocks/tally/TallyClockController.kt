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

package com.android.systemui.shared.clocks.tally

import android.content.Context
import android.icu.text.DateFormat
import android.icu.util.TimeZone
import android.text.TextPaint
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.graphics.ColorUtils
import com.android.systemui.customization.R as CustomizationR
import com.android.systemui.customization.clocks.DigitalTimeFormatter
import com.android.systemui.customization.clocks.TimeKeeper
import com.android.systemui.customization.clocks.TimeKeeperImpl
import com.android.systemui.customization.clocks.view.DefaultClockFaceLayout
import com.android.systemui.plugins.keyguard.VRect
import com.android.systemui.plugins.keyguard.data.model.AlarmData
import com.android.systemui.plugins.keyguard.data.model.WeatherData
import com.android.systemui.plugins.keyguard.data.model.ZenData
import com.android.systemui.plugins.keyguard.ui.clocks.ClockAnimations
import com.android.systemui.plugins.keyguard.ui.clocks.ClockAxisStyle
import com.android.systemui.plugins.keyguard.ui.clocks.ClockConfig
import com.android.systemui.plugins.keyguard.ui.clocks.ClockController
import com.android.systemui.plugins.keyguard.ui.clocks.ClockEventListeners
import com.android.systemui.plugins.keyguard.ui.clocks.ClockEvents
import com.android.systemui.plugins.keyguard.ui.clocks.ClockFaceConfig
import com.android.systemui.plugins.keyguard.ui.clocks.ClockFaceController
import com.android.systemui.plugins.keyguard.ui.clocks.ClockFaceEvents
import com.android.systemui.plugins.keyguard.ui.clocks.ClockPositionAnimationArgs
import com.android.systemui.plugins.keyguard.ui.clocks.ClockPreviewConfig
import com.android.systemui.plugins.keyguard.ui.clocks.ClockSettings
import com.android.systemui.plugins.keyguard.ui.clocks.ClockViewIds
import com.android.systemui.plugins.keyguard.ui.clocks.ThemeConfig
import com.android.systemui.plugins.keyguard.ui.clocks.TimeFormatKind
import java.io.PrintWriter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import org.diamaneos.tally.R as TallyR

/**
 * The Tally lock clock: the date (LockDate) above the time (Clock: Sofia Sans at weight 280,
 * tabular figures, -0.025 em), at the top start of the lock screen. The time is a display size, 118
 * dp at density 480, scaled by 480 / densityDpi so it keeps its size on the glass at any display
 * size.
 *
 * Both faces look the same and sit in the same place, so the clock does not move when notifications
 * come and go; the large face only takes the small face's place (see [TallyClockFaceLayout]).
 */
class TallyClockController(ctx: Context, settings: ClockSettings) : ClockController {
    private val timeKeeper: TimeKeeper = TimeKeeperImpl()

    override val smallClock = TallyClockFaceController(ctx, settings, timeKeeper, isLarge = false)

    override val largeClock = TallyClockFaceController(ctx, settings, timeKeeper, isLarge = true)

    override val config =
        ClockConfig(
            TallyClocks.TALLY_CLOCK_ID,
            ctx.resources.getString(CustomizationR.string.tally_clock_name),
            ctx.resources.getString(CustomizationR.string.tally_clock_name),
        )

    override val eventListeners = ClockEventListeners()

    override val events =
        object : ClockEvents {
            override fun onTimeZoneChanged(timeZone: TimeZone) {
                timeKeeper.timeZone = timeZone
                forEachFace { it.onTimeZoneChanged(timeZone) }
            }

            override fun onTimeFormatChanged(formatKind: TimeFormatKind) {
                forEachFace { it.onTimeFormatChanged(formatKind) }
            }

            override fun onLocaleChanged(locale: Locale) {
                forEachFace { it.onLocaleChanged(locale) }
            }

            override fun onWeatherDataChanged(data: WeatherData) {}

            override fun onAlarmDataChanged(data: AlarmData) {}

            override fun onZenDataChanged(data: ZenData) {}
        }

    override fun initialize(isDarkTheme: Boolean, dozeFraction: Float, foldFraction: Float) {
        forEachFace { face ->
            face.events.onThemeChanged(face.theme.copy(isDarkTheme = isDarkTheme))
            face.animations.doze(dozeFraction)
            face.animations.fold(foldFraction)
            face.events.onTimeTick()
        }
    }

    override fun dump(pw: PrintWriter) {
        pw.println("TallyClockController: time=${smallClock.timeText}")
    }

    private inline fun forEachFace(action: (TallyClockFaceController) -> Unit) {
        action(smallClock)
        action(largeClock)
    }
}

/** One face of the Tally lock clock: a vertical pair of the date and the time. */
class TallyClockFaceController(
    private val ctx: Context,
    settings: ClockSettings,
    private val timeKeeper: TimeKeeper,
    private val isLarge: Boolean,
) : ClockFaceController {
    private val timeFormatter =
        DigitalTimeFormatter("h:mm", timeKeeper, enableContentDescription = true)
    private var dateFormat: DateFormat = createDateFormat(Locale.getDefault())
    private var dozeFraction = 0f
    private val fitPaint = TextPaint()

    private val dateView =
        TextView(ctx).apply {
            includeFontPadding = false
            setSingleLine(true)
        }

    private val timeView =
        TextView(ctx).apply {
            includeFontPadding = false
            setSingleLine(true)
            // The time is digits and a separator, which are directionally weak: keep it LTR.
            textDirection = View.TEXT_DIRECTION_LTR
        }

    override val view: LinearLayout =
        FaceView(ctx).apply {
            id =
                if (isLarge) ClockViewIds.LOCKSCREEN_CLOCK_VIEW_LARGE
                else ClockViewIds.LOCKSCREEN_CLOCK_VIEW_SMALL
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            addView(dateView, LinearLayout.LayoutParams(WRAP, WRAP))
            addView(timeView, LinearLayout.LayoutParams(WRAP, WRAP))
        }

    init {
        applySizes()
    }

    override val layout = TallyClockFaceLayout(view, isLarge, CLOCK_START_DP)

    override val config = ClockFaceConfig()

    override var theme = ThemeConfig(isDarkTheme = true, settings.seedColor)

    /** The rendered time, for dumps. */
    val timeText: CharSequence
        get() = timeView.text

    override val events =
        object : ClockFaceEvents {
            override fun onTimeTick() {
                timeKeeper.updateTime()
                timeView.text = timeFormatter.getText()
                timeView.contentDescription = timeFormatter.getContentDescription()
                dateView.text = dateFormat.format(timeKeeper.time)
            }

            override fun onThemeChanged(theme: ThemeConfig) {
                this@TallyClockFaceController.theme = theme
                applyColors()
            }

            // Called on density and font scale changes. The time keeps its size on the glass
            // whatever the target size; the date is in sp and follows the text size.
            override fun onFontSettingChanged(fontSizePx: Float) {
                applySizes()
            }

            override fun onTargetRegionChanged(targetRegion: VRect) {}

            override fun onSecondaryDisplayChanged(onSecondaryDisplay: Boolean) {}
        }

    override val animations =
        object : ClockAnimations {
            override fun enter() {}

            override fun doze(fraction: Float) {
                dozeFraction = fraction
                applyColors()
            }

            override fun fold(fraction: Float) {}

            override fun charge() {}

            override fun onPositionAnimated(anim: ClockPositionAnimationArgs) {}

            override fun onPickerCarouselSwiping(swipingFraction: Float) {}

            override fun onFidgetTap(x: Float, y: Float) {}

            override fun onFontAxesChanged(style: ClockAxisStyle) {}
        }

    fun onTimeZoneChanged(timeZone: TimeZone) {
        dateFormat.timeZone = timeZone
        events.onTimeTick()
    }

    fun onTimeFormatChanged(formatKind: TimeFormatKind) {
        timeFormatter.formatKind = formatKind
        events.onTimeTick()
    }

    fun onLocaleChanged(locale: Locale) {
        timeFormatter.locale = locale
        dateFormat = createDateFormat(locale)
        events.onTimeTick()
    }

    /**
     * Ink of the theme the wallpaper calls for (ThemeConfig.isDarkTheme means light text), toward
     * the dark theme's ink on AOD. A seed colour picked for this clock wins, as in stock clocks.
     */
    private fun applyColors() {
        val darkInk = ctx.getColor(android.R.color.system_on_surface_dark)
        val ink =
            theme.seedColor
                ?: if (theme.isDarkTheme) darkInk
                else ctx.getColor(android.R.color.system_on_surface_light)
        val color = ColorUtils.blendARGB(ink, darkInk, dozeFraction)
        timeView.setTextColor(color)
        dateView.setTextColor(color)
    }

    private fun createDateFormat(locale: Locale): DateFormat =
        TallyClocks.dateFormat(locale).apply { timeZone = timeKeeper.timeZone }

    /**
     * Text appearances are read again on each call, so the date follows font scale changes; the
     * time takes its size on the glass, which [fitTime] may reduce when it measures.
     */
    private fun applySizes() {
        dateView.setTextAppearance(TallyR.style.TextAppearance_Tally_LockDate)
        dateView.setPaddingRelative(dp(DATE_INSET_DP), 0, 0, 0)
        timeView.setTextAppearance(TallyR.style.TextAppearance_Tally_Clock)
        setTimeSize(glassSizePx(ctx, CLOCK_SIZE_DP))
    }

    /**
     * The time's size and box. The box is the prototype's line box, one text size tall: the font's
     * own ascent and descent are taller, so its margins take the difference back. The box starts a
     * little above the date's bottom, as in the prototype.
     */
    private fun setTimeSize(sizePx: Float) {
        timeView.setTextSize(TypedValue.COMPLEX_UNIT_PX, sizePx)
        val metrics = timeView.paint.fontMetrics
        val halfLeading = (timeView.textSize - (metrics.descent - metrics.ascent)) / 2f
        timeView.layoutParams =
            LinearLayout.LayoutParams(WRAP, WRAP).apply {
                topMargin = (halfLeading - dp(DATE_OVERLAP_DP)).roundToInt()
                bottomMargin = halfLeading.roundToInt()
            }
    }

    /**
     * Shrinks the time, before the face measures, when at its size on the glass it would be wider
     * than the room the face has: a long time (another numbering system, a wider fallback font)
     * never runs off the screen. The room is the width the face is measured with, never more than
     * the screen less the clock's start inset, less the lock screen's side margin.
     */
    private fun fitTime(widthMeasureSpec: Int) {
        val full = glassSizePx(ctx, CLOCK_SIZE_DP)
        val screen = ctx.resources.displayMetrics.widthPixels - dp(CLOCK_START_DP)
        val given =
            if (View.MeasureSpec.getMode(widthMeasureSpec) == View.MeasureSpec.UNSPECIFIED) screen
            else min(View.MeasureSpec.getSize(widthMeasureSpec), screen)
        val room = given - dp(SIDE_MARGIN_DP)
        fitPaint.set(timeView.paint)
        fitPaint.textSize = full
        val text = timeView.text
        val natural = fitPaint.measureText(text, 0, text.length)
        val size = if (room <= 0 || natural <= room) full else full * room / natural
        if (abs(timeView.textSize - size) >= SIZE_STEP_PX) setTimeSize(size)
    }

    /** The face's view: it fits the time to its room each time before it measures. */
    private inner class FaceView(context: Context) : LinearLayout(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            fitTime(widthMeasureSpec)
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }

    private fun dp(value: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, ctx.resources.displayMetrics)
            .roundToInt()

    companion object {
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

        /** The clock's size on the glass: 118 dp at the owner's density, 480. */
        private const val CLOCK_SIZE_DP = 118f
        private const val GLASS_DENSITY_DPI = DisplayMetrics.DENSITY_XXHIGH
        private const val GLASS_STEP_DP = 2f

        /** The prototype's positions: the clock at 18 dp, the date at 24 dp, 2 dp overlap. */
        private const val CLOCK_START_DP = 18f
        private const val DATE_INSET_DP = 6f
        private const val DATE_OVERLAP_DP = 2f

        /** The lock screen's side margin, which the time keeps clear of at its end. */
        private const val SIDE_MARGIN_DP = 24f

        /** Size changes smaller than this are left alone, so that measuring never loops. */
        private const val SIZE_STEP_PX = 0.5f

        /** [dp] at density 480, scaled by 480 / densityDpi and rounded to 2 dp, in pixels. */
        fun glassSizePx(ctx: Context, dp: Float): Float {
            val densityDpi = ctx.resources.configuration.densityDpi
            val scaledDp =
                (dp * GLASS_DENSITY_DPI / densityDpi / GLASS_STEP_DP).roundToInt() * GLASS_STEP_DP
            return TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                scaledDp,
                ctx.resources.displayMetrics,
            )
        }
    }
}

/**
 * Places both faces of the Tally clock where SystemUI places the small clock, at the prototype's
 * start inset and at their own height: the large face does not move to the middle of the screen.
 * The large face takes the small face's top, which SystemUI keeps in place while the small face is
 * gone.
 */
class TallyClockFaceLayout(view: View, private val isLarge: Boolean, private val startDp: Float) :
    DefaultClockFaceLayout(view) {

    override fun applyConstraints(constraints: ConstraintSet): ConstraintSet {
        super.applyConstraints(constraints)
        return constraints.apply {
            place(
                this,
                topAnchor = ClockViewIds.LOCKSCREEN_CLOCK_VIEW_SMALL,
                topSide = ConstraintSet.TOP,
            )
        }
    }

    override fun applyPreviewConstraints(
        clockPreviewConfig: ClockPreviewConfig,
        constraints: ConstraintSet,
    ): ConstraintSet {
        super.applyPreviewConstraints(clockPreviewConfig, constraints)
        // The Tally clock's own top margin, as on the lock screen (KeyguardClockViewModel).
        val topMargin = TallyClocks.topMarginPx(view.resources)
        return constraints.apply {
            place(
                this,
                topAnchor = ConstraintSet.PARENT_ID,
                topSide = ConstraintSet.TOP,
                topMargin =
                    clockPreviewConfig.copy(clockTopMargin = topMargin).getSmallClockTopPadding(),
            )
        }
    }

    private fun place(
        constraints: ConstraintSet,
        topAnchor: Int,
        topSide: Int,
        topMargin: Int = 0,
    ) {
        val id = view.id
        val startPx =
            TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP,
                    startDp,
                    view.resources.displayMetrics,
                )
                .roundToInt()
        constraints.apply {
            if (isLarge) {
                clear(id, ConstraintSet.BOTTOM)
                clear(id, ConstraintSet.END)
                constrainMaxHeight(id, 0)
                connect(id, ConstraintSet.TOP, topAnchor, topSide, topMargin)
            }
            constrainWidth(id, ConstraintSet.WRAP_CONTENT)
            constrainHeight(id, ConstraintSet.WRAP_CONTENT)
            connect(id, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START, startPx)
        }
    }
}
