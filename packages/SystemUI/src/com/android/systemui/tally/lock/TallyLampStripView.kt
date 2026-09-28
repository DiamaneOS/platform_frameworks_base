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
import com.android.settingslib.graph.ThemedBatteryDrawable
import com.android.systemui.privacy.PrivacyType
import com.android.systemui.res.R
import java.text.NumberFormat
import kotlin.math.max
import kotlin.math.roundToInt
import org.diamaneos.tally.R as TallyR

/**
 * The lock screen's lamp strip: one quiet row under the clock, with no boxes, a lamp and an icon
 * per item and words only for the alarm time and the battery. A screen reader hears each item's
 * words from its description.
 *
 * When the row does not fit (both sensors in use, large text) it gives way in steps, measured on
 * every layout, as the prototype's declutter rule has it: first the alarm's time goes, then Wi-Fi,
 * Bluetooth and Calm fold away while they are off (their words stay in the strip's description),
 * and only then does the row wrap. The camera and microphone come first, so a sensor in use is
 * never the item that runs out of room.
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
        val gap = px(ITEM_GAP_DP)

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
                if (it.kind.foldsWhenOff && it.lamp == TallyLamp.OFF) it.folded = true
            }
            visible = visible.filter { !it.folded }
        }

        rows.clear()
        if (unbounded || rowWidth(visible, gap) <= available) {
            rows += visible
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
            else rows.sumOf { row -> max(minRow, row.maxOf { it.measuredHeight }) }
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
        val gap = px(ITEM_GAP_DP)
        val minRow = px(ROW_MIN_HEIGHT_DP)
        val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
        val inner = r - l - paddingLeft - paddingRight
        var top = paddingTop
        val placed = mutableSetOf<ItemView>()
        rows.forEach { row ->
            val rowHeight = max(minRow, row.maxOf { it.measuredHeight })
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
            ImageView(context).apply { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
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
        private var battery: ThemedBatteryDrawable? = null

        /** Set by the strip before it measures this item; not a layout request of its own. */
        var showWords = true
        var folded = false
        var lamp: TallyLamp? = null
            private set

        init {
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
            // The lamp's drawable reaches a little past the item, for the live lamp's halo.
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
        }

        fun bind(item: TallyStripItem, wall: Context) {
            lamp = item.lamp
            val ink = wall.getColor(TallyR.color.tally_ink)
            contentDescription =
                item.description.joinToString(DESCRIPTION_SEPARATOR) {
                    if (it.arg == null) context.getString(it.res)
                    else context.getString(it.res, it.arg)
                }

            val lampForm = item.lamp
            lampView.visibility = if (lampForm == null) GONE else VISIBLE
            if (lampForm != null) {
                val sensor =
                    kind == TallyStripItem.Kind.CAMERA || kind == TallyStripItem.Kind.MICROPHONE
                val (drawable, tint) =
                    when {
                        sensor ->
                            TallyR.drawable.tally_lamp_live_plain_12 to TallyR.color.tally_sensor
                        lampForm == TallyLamp.OFF ->
                            TallyR.drawable.tally_lamp_off_12 to TallyR.color.tally_ink_muted
                        lampForm == TallyLamp.REQUESTED ->
                            TallyR.drawable.tally_lamp_requested_12 to null
                        lampForm == TallyLamp.LIVE -> TallyR.drawable.tally_lamp_live_12 to null
                        else -> TallyR.drawable.tally_lamp_on_12 to null
                    }
                lampView.setImageDrawable(wall.getDrawable(drawable))
                lampView.imageTintList = tint?.let { ColorStateList.valueOf(wall.getColor(it)) }
            }

            iconView.imageTintList = ColorStateList.valueOf(ink)
            when (kind) {
                TallyStripItem.Kind.BATTERY -> {
                    val drawable =
                        battery ?: ThemedBatteryDrawable(context, ink).also { battery = it }
                    drawable.setColors(ink, wall.getColor(TallyR.color.tally_ink_muted), ink)
                    drawable.setBatteryLevel(item.batteryLevel)
                    drawable.charging = item.isCharging
                    iconView.imageTintList = null
                    iconView.setImageDrawable(drawable)
                }
                else -> iconView.setImageResource(iconFor(kind))
            }

            val words =
                when (kind) {
                    TallyStripItem.Kind.ALARM -> item.alarmTime
                    TallyStripItem.Kind.BATTERY ->
                        NumberFormat.getPercentInstance().format(item.batteryLevel / 100.0)
                    else -> null
                }
            wordsView.text = words
            wordsView.visibility = if (words == null) GONE else VISIBLE
            wordsView.setTextColor(ink)
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val lampBox = px(LAMP_BOX_DP)
            val icon = px(ICON_DP)
            val exact = { size: Int -> MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY) }
            lampView.measure(exact(lampBox), exact(lampBox))
            iconView.measure(exact(icon), exact(icon))
            val unspecified = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            wordsView.measure(unspecified, unspecified)

            var width = 0
            if (lampView.visibility != GONE) width += px(LAMP_DP) + px(LAMP_ICON_GAP_DP)
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
                // The lamp's drawable is its box, the lamp plus its halo's reach, centred on the
                // lamp's own size.
                val lampBox = lampView.measuredWidth
                place(lampView, x - (lampBox - px(LAMP_DP)) / 2, lampBox, lampBox)
                x += px(LAMP_DP) + px(LAMP_ICON_GAP_DP)
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
        const val ITEM_GAP_DP = 18f
        const val LAMP_DP = 12f
        const val LAMP_BOX_DP = 17f
        const val LAMP_ICON_GAP_DP = 4f
        const val ICON_DP = 18f
        const val ICON_WORDS_GAP_DP = 5f
        const val ROW_MIN_HEIGHT_DP = 40f
        const val DESCRIPTION_SEPARATOR = ", "
    }
}
