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
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.android.systemui.res.R
import kotlin.math.roundToInt
import org.diamaneos.tally.R as TallyR

/**
 * The always-on display's strip, where the lamp strip is on the lock screen: one row with an icon
 * and words per item, and no lamps. It shows exactly what the stock always-on date line showed
 * besides the date ([TallyAodItem]): the next alarm's time within 12 hours, Do Not Disturb while it
 * is on, and the title and artist of media that is playing, whose words give way at the end when
 * the row is full. It is drawn in the dark theme's ink, as the always-on display is dark.
 */
class TallyAodStripView(context: Context) : LinearLayout(context) {
    private val itemViews = TallyAodItem.Kind.entries.associateWith { ItemView(context, it) }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        itemViews.values.forEach { item ->
            item.visibility = GONE
            addView(item)
        }
        refresh()
    }

    /** Shows [items]; an item not in the list is hidden. */
    fun setItems(items: List<TallyAodItem>) {
        val byKind = items.associateBy { it.kind }
        itemViews.forEach { (kind, view) ->
            val item = byKind[kind]
            view.visibility = if (item != null) VISIBLE else GONE
            if (item != null) view.bind(item)
        }
        applyGaps()
    }

    /** Reads resources again, after a theme, density, font scale or locale change. */
    fun refresh() {
        minimumHeight = px(ROW_MIN_HEIGHT_DP)
        val ink =
            TallyWallTheme.context(context, lightWallpaper = false).getColor(TallyR.color.tally_ink)
        itemViews.values.forEach { it.apply(ink) }
        applyGaps()
    }

    /** The lamp strip's gaps between the items that show. */
    private fun applyGaps() {
        val gap = stripItemGapPx(context)
        var first = true
        itemViews.values.forEach { item ->
            val params = item.layoutParams as LayoutParams
            val start = if (first || item.visibility == GONE) 0 else gap
            if (params.marginStart != start) {
                params.marginStart = start
                item.layoutParams = params
            }
            if (item.visibility != GONE) first = false
        }
    }

    private fun px(dp: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, resources.displayMetrics)
            .roundToInt()

    /** One item: its icon and, for the alarm and the media, its words. */
    private class ItemView(context: Context, val kind: TallyAodItem.Kind) : LinearLayout(context) {
        private val iconView =
            ImageView(context).apply {
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
                scaleType = ImageView.ScaleType.FIT_CENTER
                setImageResource(
                    when (kind) {
                        TallyAodItem.Kind.ALARM -> R.drawable.ic_alarm
                        TallyAodItem.Kind.CALM -> R.drawable.ic_do_not_disturb
                        TallyAodItem.Kind.MEDIA -> R.drawable.ic_music_note
                    }
                )
            }
        private val wordsView =
            TextView(context).apply {
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
                setSingleLine(true)
                ellipsize = TextUtils.TruncateAt.END
                includeFontPadding = false
            }

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
            addView(iconView)
            addView(wordsView)
            // The media's words take the room that is left and give way at their end.
            layoutParams =
                if (kind == TallyAodItem.Kind.MEDIA) LayoutParams(0, WRAP, 1f)
                else LayoutParams(WRAP, WRAP)
        }

        fun bind(item: TallyAodItem) {
            wordsView.text = item.words
            wordsView.visibility = if (item.words == null) GONE else VISIBLE
            contentDescription =
                item.description?.let {
                    if (it.arg == null) context.getString(it.res)
                    else context.getString(it.res, it.arg)
                } ?: item.words
        }

        /** The icon size, the words' type and gap, and [ink] for both. */
        fun apply(ink: Int) {
            val icon = px(ICON_DP)
            iconView.layoutParams = LayoutParams(icon, icon)
            iconView.imageTintList = ColorStateList.valueOf(ink)
            wordsView.setTextAppearance(TallyR.style.TextAppearance_Tally_Label)
            wordsView.fontFeatureSettings = "tnum"
            wordsView.setTextColor(ink)
            wordsView.layoutParams =
                LayoutParams(WRAP, WRAP).apply { marginStart = px(ICON_WORDS_GAP_DP) }
        }

        private fun px(dp: Float): Int =
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, resources.displayMetrics)
                .roundToInt()
    }

    private companion object {
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

        /** As the lamp strip: icon size, the gap before the words, and the row's height, in dp. */
        const val ICON_DP = 18f
        const val ICON_WORDS_GAP_DP = 5f
        const val ROW_MIN_HEIGHT_DP = 40f
    }
}
