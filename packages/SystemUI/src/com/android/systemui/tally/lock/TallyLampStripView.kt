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

package com.android.systemui.tally.lock

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import com.android.systemui.privacy.PrivacyType
import com.android.systemui.res.R
import com.android.systemui.tally.lamp.TallyLampColors
import com.android.systemui.tally.lamp.TallyLampSize
import com.android.systemui.tally.lamp.TallyLampState
import com.android.systemui.tally.lamp.TallyLampView
import de.diamaneos.tally.R as TallyR
import java.text.NumberFormat
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The lock screen's lamp strip: one quiet row under the clock, with no boxes, a lamp (the shared
 * animated Tally lamp) and an icon per item and words only for the alarm time and the battery. A
 * screen reader hears each item's words from its description.
 *
 * When the row does not fit (both sensors in use, large text) it gives way in steps, measured on
 * every layout, as the prototype's declutter rule has it: first the alarm's time goes, then Wi-Fi,
 * Bluetooth and Calm fold away while they are off (their words stay in the strip's description),
 * and only then does the row wrap. The camera and microphone come first, so a sensor in use is
 * never the item that runs out of room. From 150 % text, as the prototype, the alarm's time always
 * goes, the battery readout stops growing and the gaps narrow.
 *
 * Text and lamps are drawn in the theme the lock wallpaper calls for ([setLightWallpaper]), as
 * stock SystemUI does for text on the lock screen.
 */
class TallyLampStripView(context: Context) : ViewGroup(context) {
    private val itemViews =
        TallyStripItem.Kind.entries.associateWith { kind -> ItemView(context, kind) }
    private var items: List<TallyStripItem> = emptyList()
    private var lightWallpaper = false
    private var wall: Context = TallyWallTheme.context(context, lightWallpaper)
    private val rows = mutableListOf<List<ItemView>>()

    init {
        clipChildren = false
        clipToPadding = false
        itemViews.values.forEach { item ->
            item.visibility = GONE
            addView(item)
        }
    }

    /** Shows [newItems], in the strip's order; an item not in the list is hidden. */
    fun setItems(newItems: List<TallyStripItem>) {
        items = newItems
        val byKind = newItems.associateBy { it.kind }
        itemViews.forEach { (kind, view) ->
            val item = byKind[kind]
            view.visibility = if (item != null) VISIBLE else GONE
            if (item != null) view.bind(item, wall)
        }
        requestLayout()
    }

    /**
     * Draws the strip in the theme the lock wallpaper calls for: dark text on a light one. Takes
     * effect on the next [refresh].
     */
    fun setLightWallpaper(light: Boolean) {
        lightWallpaper = light
    }

    /** Reads resources again, after a theme, density, font scale or locale change. */
    fun refresh() {
        wall = TallyWallTheme.context(context, lightWallpaper)
        itemViews.values.forEach { it.applyTextAppearance() }
        setItems(items)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val available = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        val shown = itemViews.values.filter { it.visibility != GONE }
        val alarm = itemViews.getValue(TallyStripItem.Kind.ALARM)
        val gap = stripItemGapPx(context)

        // Everything first, then give way step by step until the row fits.
        shown.forEach {
            it.folded = false
            it.showWords = true
        }
        measureItems(shown)
        var visible = shown
        val unbounded = MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED
        if (!unbounded && rowWidth(visible, gap) > available && alarm in visible) {
            alarm.showWords = false
            measureItems(listOf(alarm))
        }
        if (!unbounded && rowWidth(visible, gap) > available) {
            visible.forEach {
                if (it.kind.foldsWhenOff && it.lamp == TallyLampState.OFF) it.folded = true
            }
            visible = visible.filter { !it.folded }
        }

        rows.clear()
        if (unbounded || rowWidth(visible, gap) <= available) {
            // Never an empty row: with nothing to show (before the first items arrive, or when
            // every item has folded away) the strip has no rows and no height.
            if (visible.isNotEmpty()) rows += visible
        } else {
            var row = mutableListOf<ItemView>()
            var width = 0
            visible.forEach { item ->
                val needed = item.measuredWidth + if (row.isEmpty()) 0 else gap
                if (row.isNotEmpty() && width + needed > available) {
                    rows += row
                    row = mutableListOf()
                    width = 0
                }
                width += item.measuredWidth + if (row.isEmpty()) 0 else gap
                row += item
            }
            if (row.isNotEmpty()) rows += row
        }

        val minRow = px(ROW_MIN_HEIGHT_DP)
        val height =
            if (rows.isEmpty()) 0
            else rows.sumOf { row -> max(minRow, row.maxOfOrNull { it.measuredHeight } ?: 0) }
        val width =
            if (unbounded) rows.maxOfOrNull { rowWidth(it, gap) } ?: 0
            else MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(
            resolveSize(width + paddingLeft + paddingRight, widthMeasureSpec),
            resolveSize(height + paddingTop + paddingBottom, heightMeasureSpec),
        )
        updateDescription(shown.filter { it.folded })
        // A folded item's words are in the strip's description; screen readers skip the item.
        shown.forEach {
            it.importantForAccessibility =
                if (it.folded) IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                else IMPORTANT_FOR_ACCESSIBILITY_YES
        }
    }

    // A folded item takes no room and is not drawn: nothing clips its lamp and icon, which would
    // otherwise draw at the strip's start.
    override fun drawChild(canvas: Canvas, child: View, drawingTime: Long): Boolean =
        if ((child as? ItemView)?.folded == true) false
        else super.drawChild(canvas, child, drawingTime)

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val gap = stripItemGapPx(context)
        val minRow = px(ROW_MIN_HEIGHT_DP)
        val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
        val inner = r - l - paddingLeft - paddingRight
        var top = paddingTop
        val placed = mutableSetOf<ItemView>()
        rows.forEach { row ->
            val rowHeight = max(minRow, row.maxOfOrNull { it.measuredHeight } ?: 0)
            var x = 0
            row.forEach { item ->
                // The battery readout sits at the end of its row.
                val start =
                    if (item.kind == TallyStripItem.Kind.BATTERY) {
                        max(x, inner - item.measuredWidth)
                    } else {
                        x
                    }
                val left =
                    if (rtl) paddingLeft + inner - start - item.measuredWidth
                    else paddingLeft + start
                val itemTop = top + (rowHeight - item.measuredHeight) / 2
                item.layout(left, itemTop, left + item.measuredWidth, itemTop + item.measuredHeight)
                placed += item
                x = start + item.measuredWidth + gap
            }
            top += rowHeight
        }
        // Folded and hidden items take no room and draw nothing.
        itemViews.values.forEach { if (it !in placed) it.layout(0, 0, 0, 0) }
    }

    private fun measureItems(views: List<ItemView>) {
        val spec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        views.forEach {
            // The same spec as last time would reuse the cached size: showWords may have changed.
            it.forceLayout()
            it.measure(spec, spec)
        }
    }

    private fun rowWidth(views: List<ItemView>, gap: Int): Int =
        if (views.isEmpty()) 0 else views.sumOf { it.measuredWidth } + gap * (views.size - 1)

    /** Folded items keep their words in the strip's own description. */
    private fun updateDescription(folded: List<ItemView>) {
        val words = folded.joinToString(DESCRIPTION_SEPARATOR) { it.contentDescription ?: "" }
        val description = words.ifEmpty { null }
        if (contentDescription?.toString() != description) contentDescription = description
    }

    private fun px(dp: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, resources.displayMetrics)
            .roundToInt()

    /** One item: its lamp, its icon and, for the alarm and the battery, its words. */
    private class ItemView(context: Context, val kind: TallyStripItem.Kind) : ViewGroup(context) {
        private val lampView =
            TallyLampView(context).apply {
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
                setLampSize(TallyLampSize.DEFAULT)
            }
        private val iconView =
            ImageView(context).apply {
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
        private val wordsView =
            TextView(context).apply {
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
                setSingleLine(true)
                includeFontPadding = false
            }
        private var battery: TallyBatteryDrawable? = null

        /** Set by the strip before it measures this item; not a layout request of its own. */
        var showWords = true
        var folded = false
        var lamp: TallyLampState? = null
            private set

        init {
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
            // The lamp reaches a little past the item, for the live lamp's ring of light.
            clipChildren = false
            clipToPadding = false
            addView(lampView)
            addView(iconView)
            addView(wordsView)
            applyTextAppearance()
        }

        fun applyTextAppearance() {
            wordsView.setTextAppearance(TallyR.style.TextAppearance_Tally_Label)
            wordsView.fontFeatureSettings = "tnum"
            if (kind == TallyStripItem.Kind.BATTERY && isLargeText(context)) {
                // As the prototype: from 150 % text the battery readout, a display value like the
                // clock, stops growing, at its size there (15 x 1.5), so the row keeps one line.
                wordsView.setTextSize(
                    TypedValue.COMPLEX_UNIT_DIP,
                    BATTERY_WORDS_LARGE_TEXT_DP * LARGE_TEXT_SCALE,
                )
            }
        }

        fun bind(item: TallyStripItem, wall: Context) {
            lamp = item.lamp
            val ink = wall.getColor(TallyR.color.tally_ink)
            contentDescription =
                item.description.joinToString(DESCRIPTION_SEPARATOR) {
                    if (it.arg == null) context.getString(it.res)
                    else context.getString(it.res, it.arg)
                }

            val lampState = item.lamp
            lampView.visibility = if (lampState == null) GONE else VISIBLE
            if (lampState != null) {
                val sensor =
                    kind == TallyStripItem.Kind.CAMERA || kind == TallyStripItem.Kind.MICROPHONE
                // The shared animated lamp: a sensor lamp in the sensor colour lights at once;
                // the others take the lamp colours of the wallpaper's theme, with the strip's
                // muted off ring, and ignite or turn as their state changes.
                lampView.colors =
                    if (sensor) TallyLampColors.sensor(wall)
                    else
                        TallyLampColors.theme(wall)
                            .copy(off = wall.getColor(TallyR.color.tally_ink_muted))
                lampView.setState(lampState)
            }

            iconView.imageTintList = ColorStateList.valueOf(ink)
            when (kind) {
                TallyStripItem.Kind.BATTERY -> {
                    // The Tally battery glyph, with the level and charging state the words and
                    // the description also give.
                    val drawable = battery ?: TallyBatteryDrawable().also { battery = it }
                    drawable.color = ink
                    drawable.chargeLevel = item.batteryLevel
                    drawable.charging = item.isCharging
                    iconView.imageTintList = null
                    iconView.setImageDrawable(drawable)
                }
                else -> iconView.setImageResource(iconFor(kind))
            }

            val words =
                when (kind) {
                    // As the prototype: from 150 % text the alarm's time goes; its words keep it.
                    TallyStripItem.Kind.ALARM -> item.alarmTime.takeUnless { isLargeText(context) }
                    TallyStripItem.Kind.BATTERY ->
                        if (!item.showBatteryPercent) null
                        else NumberFormat.getPercentInstance().format(item.batteryLevel / 100.0)
                    else -> null
                }
            wordsView.text = words
            wordsView.visibility = if (words == null) GONE else VISIBLE
            wordsView.setTextColor(ink)
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val icon = px(ICON_DP)
            val exact = { size: Int -> MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY) }
            val unspecified = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            // The lamp and its ring of light, at least the token box, on whole pixels.
            lampView.measure(unspecified, unspecified)
            iconView.measure(exact(icon), exact(icon))
            wordsView.measure(unspecified, unspecified)

            var width = 0
            if (lampView.visibility != GONE) width += lampView.lampSizePx + px(LAMP_ICON_GAP_DP)
            width += icon
            if (hasWords()) width += px(ICON_WORDS_GAP_DP) + wordsView.measuredWidth
            val height = max(px(ROW_MIN_HEIGHT_DP), max(icon, wordsView.measuredHeight))
            setMeasuredDimension(width, height)
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
            val width = r - l
            val height = b - t
            var x = 0
            fun place(view: View, start: Int, viewWidth: Int, viewHeight: Int) {
                val left = if (rtl) width - start - viewWidth else start
                val top = (height - viewHeight) / 2
                view.layout(left, top, left + viewWidth, top + viewHeight)
            }
            if (lampView.visibility != GONE) {
                // The view reaches haloReachPx past the lamp on each side: the lamp sits at x.
                val reach = lampView.haloReachPx
                place(lampView, x - reach, lampView.measuredWidth, lampView.measuredHeight)
                x += lampView.lampSizePx + px(LAMP_ICON_GAP_DP)
            }
            place(iconView, x, iconView.measuredWidth, iconView.measuredHeight)
            x += iconView.measuredWidth
            if (hasWords()) {
                x += px(ICON_WORDS_GAP_DP)
                place(wordsView, x, wordsView.measuredWidth, wordsView.measuredHeight)
            } else {
                wordsView.layout(0, 0, 0, 0)
            }
        }

        private fun hasWords() = showWords && wordsView.visibility != GONE

        private fun px(dp: Float): Int =
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, resources.displayMetrics)
                .roundToInt()

        private companion object {
            fun iconFor(kind: TallyStripItem.Kind): Int =
                when (kind) {
                    TallyStripItem.Kind.CAMERA -> PrivacyType.TYPE_CAMERA.iconId
                    TallyStripItem.Kind.MICROPHONE -> PrivacyType.TYPE_MICROPHONE.iconId
                    TallyStripItem.Kind.WIFI -> R.drawable.vd_wifi
                    TallyStripItem.Kind.BLUETOOTH -> R.drawable.vd_bluetooth
                    TallyStripItem.Kind.ALARM -> R.drawable.ic_alarm
                    TallyStripItem.Kind.CALM -> R.drawable.ic_do_not_disturb
                    TallyStripItem.Kind.BATTERY -> 0
                }
        }
    }

    private companion object {
        /** The prototype's strip (shell.js, app.css): gaps, lamp, icon and row sizes in dp. */
        const val LAMP_ICON_GAP_DP = 4f
        const val ICON_DP = 18f
        const val ICON_WORDS_GAP_DP = 5f
        const val ROW_MIN_HEIGHT_DP = 40f
        const val DESCRIPTION_SEPARATOR = ", "
    }
}

/**
 * The gap between the items of a lock screen strip: 18 dp, and 14 dp from 150 % text, as the
 * prototype's strip (app.css, .tally.ts-150 .lk-strip).
 */
internal fun stripItemGapPx(context: Context): Int {
    val dp = if (isLargeText(context)) ITEM_GAP_LARGE_TEXT_DP else ITEM_GAP_DP
    return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp,
            context.resources.displayMetrics,
        )
        .roundToInt()
}

/** Whether the text size is 150 % or more, where the prototype's strip changes its rules. */
private fun isLargeText(context: Context): Boolean =
    context.resources.configuration.fontScale >= LARGE_TEXT_SCALE

private const val ITEM_GAP_DP = 18f
private const val ITEM_GAP_LARGE_TEXT_DP = 14f
private const val LARGE_TEXT_SCALE = 1.5f

/** The prototype's battery readout at large text: 15 dp times the text size, up to 150 %. */
private const val BATTERY_WORDS_LARGE_TEXT_DP = 15f
