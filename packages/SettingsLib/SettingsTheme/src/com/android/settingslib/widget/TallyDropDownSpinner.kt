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

package com.android.settingslib.widget

import android.content.Context
import android.content.res.Resources
import android.graphics.Rect
import android.text.TextUtils
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.ListAdapter
import android.widget.Spinner
import android.widget.SpinnerAdapter
import android.widget.TextView
import android.widget.ThemedSpinnerAdapter
import com.android.settingslib.widget.theme.R
import kotlin.math.max
import kotlin.math.min

/**
 * The spinner behind a dropdown row (DropDownPreference), whose popup is the Tally menu: as wide as
 * its widest entry, check column included, kept [R.dimen.settingslib_tally_menu_margin] from both
 * sides of the window, and an entry too long for that width wraps onto more lines instead of being
 * cut.
 *
 * Stock measures the entries before they are attached, when a start drawable (the check column) is
 * not yet placed, so the popup came out narrower than its entries; it also let the popup reach the
 * screen's edge. Selection, clicks, accessibility and the popup's position under the row stay
 * [Spinner]'s.
 */
class TallyDropDownSpinner
@JvmOverloads
constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.spinnerStyle,
) : Spinner(context, attrs, defStyleAttr) {

    private val margin = resources.getDimensionPixelSize(R.dimen.settingslib_tally_menu_margin)
    private val entryMinHeight =
        resources.getDimensionPixelSize(R.dimen.settingslib_tally_menu_entry_min_height)
    private val entryPaddingVertical =
        resources.getDimensionPixelSize(R.dimen.settingslib_tally_menu_entry_padding_vertical)
    private val frame = Rect()
    private val backgroundPadding = Rect()
    private val location = IntArray(2)

    override fun setAdapter(adapter: SpinnerAdapter?) {
        super.setAdapter(adapter?.let { if (it is EntryAdapter) it else EntryAdapter(it, this) })
    }

    override fun performClick(): Boolean {
        fitDropDown()
        return super.performClick()
    }

    /**
     * Sizes the popup to its widest entry, at most the window less the margin on each side, and
     * moves the (invisible, zero-width) spinner the popup hangs from so that the popup keeps the
     * margin on both sides. [Spinner] places the popup from the spinner's place on screen, and the
     * popup window would otherwise push a popup that runs past the edge flush against it.
     */
    private fun fitDropDown() {
        val adapter = adapter ?: return
        translationX = 0f
        rootView.getWindowVisibleDisplayFrame(frame)
        if (frame.isEmpty) return
        val background = popupBackground
        if (background == null || !background.getPadding(backgroundPadding)) {
            backgroundPadding.setEmpty()
        }
        val maxContent =
            frame.width() - 2 * margin - backgroundPadding.left - backgroundPadding.right
        if (maxContent <= 0) return
        val content = min(widestEntry(adapter), maxContent)
        if (content <= 0) return
        dropDownWidth = content

        // Where Spinner's popup would start, and where it must start to keep the margins.
        val popupWidth = content + backgroundPadding.left + backgroundPadding.right
        getLocationOnScreen(location)
        val naturalLeft =
            if (isRtl) {
                location[0] + backgroundPadding.right + width - paddingRight - popupWidth
            } else {
                location[0] - backgroundPadding.left + paddingLeft
            }
        val left = naturalLeft.coerceIn(frame.left + margin, frame.right - margin - popupWidth)
        translationX = (left - naturalLeft).toFloat()
    }

    /** The widest entry as the popup draws it, check column included, on one line. */
    private fun widestEntry(adapter: SpinnerAdapter): Int {
        val unspecified = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        val types = max(adapter.viewTypeCount, 1)
        val scrap = arrayOfNulls<View>(types)
        var widest = 0
        for (position in 0 until min(adapter.count, MAX_ENTRIES_MEASURED)) {
            val type = adapter.getItemViewType(position)
            val slot = if (type in 0 until types) type else 0
            val entry = adapter.getDropDownView(position, scrap[slot], this) ?: continue
            scrap[slot] = entry
            entry.measure(unspecified, unspecified)
            widest = max(widest, entry.measuredWidth)
        }
        return widest
    }

    /** Lets a popup entry wrap and places its check column before it is attached. */
    internal fun fitEntry(entry: View) {
        // A start drawable is placed only once the layout direction is known, which an entry the
        // popup has not yet attached cannot inherit: give it the spinner's own.
        entry.layoutDirection = layoutDirection
        val text = entry as? TextView ?: return
        val params = text.layoutParams
        if (params != null && params.height != ViewGroup.LayoutParams.WRAP_CONTENT) {
            // The Tally menu's entry height becomes the least height, so a wrapped entry grows.
            text.minHeight = entryMinHeight
            params.height = ViewGroup.LayoutParams.WRAP_CONTENT
            text.layoutParams = params
        }
        if (text.maxLines == 1) {
            text.isSingleLine = false
            text.ellipsize = TextUtils.TruncateAt.END
            text.setPaddingRelative(
                text.paddingStart,
                entryPaddingVertical,
                text.paddingEnd,
                entryPaddingVertical,
            )
        }
    }

    private val isRtl: Boolean
        get() = layoutDirection == View.LAYOUT_DIRECTION_RTL

    /** The row's adapter, with its popup entries fitted by [fitEntry]. */
    private class EntryAdapter(
        private val adapter: SpinnerAdapter,
        private val spinner: TallyDropDownSpinner,
    ) : SpinnerAdapter by adapter, ListAdapter, ThemedSpinnerAdapter {

        override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup?): View =
            adapter.getDropDownView(position, convertView, parent).also { spinner.fitEntry(it) }

        override fun areAllItemsEnabled(): Boolean =
            (adapter as? ListAdapter)?.areAllItemsEnabled() ?: true

        override fun isEnabled(position: Int): Boolean =
            (adapter as? ListAdapter)?.isEnabled(position) ?: true

        override fun setDropDownViewTheme(theme: Resources.Theme?) {
            (adapter as? ThemedSpinnerAdapter)?.dropDownViewTheme = theme
        }

        override fun getDropDownViewTheme(): Resources.Theme? =
            (adapter as? ThemedSpinnerAdapter)?.dropDownViewTheme
    }

    private companion object {
        /** As many entries as Spinner itself measures for its popup. */
        const val MAX_ENTRIES_MEASURED = 15
    }
}
