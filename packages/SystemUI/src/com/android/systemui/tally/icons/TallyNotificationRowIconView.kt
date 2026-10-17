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

package com.android.systemui.tally.icons

import android.app.Notification
import android.content.Context
import android.graphics.ColorFilter
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.RemotableViewMethod
import android.widget.RemoteViews
import com.android.internal.widget.NotificationRowIconView
import de.diamaneos.tally.R as TallyR
import kotlin.math.min

/**
 * Tally: a notification's small icon, where the row shows no app icon (the system's own notices),
 * on a keycap in the raised surface with the glyph in ink. Stock sets it on a circle in the
 * notification's colour, the accent, but in Tally a circle means a state and the accent reads as a
 * lit lamp. App icons are drawn as stock draws them.
 *
 * The view inflates in the posting app's context, which has no SystemUI resources, so the colours
 * come from [systemUi].
 */
@RemoteViews.RemoteView
class TallyNotificationRowIconView(context: Context, attrs: AttributeSet?, systemUi: Context) :
    NotificationRowIconView(context, attrs) {

    private val ink = systemUi.getColor(TallyR.color.tally_ink)
    private val keycap = systemUi.getColor(TallyR.color.tally_surface_high)
    private val keycapRadius =
        systemUi.resources.getFraction(TallyR.fraction.tally_keycap_radius, 1, 1)

    @RemotableViewMethod
    override fun setOriginalIconColor(color: Int) {
        super.setOriginalIconColor(color)
        // An app icon has no background, and keeps its own colours.
        val plate = background ?: return
        if (plate !is KeycapPlate) {
            background = KeycapPlate(keycap, keycapRadius)
        }
        if (color != Notification.COLOR_INVALID) {
            drawable?.mutate()?.colorFilter = PorterDuffColorFilter(ink, PorterDuff.Mode.SRC_ATOP)
        }
    }

    /** The keycap behind the glyph. It keeps its colour when the stock view tints its plate. */
    private class KeycapPlate(color: Int, private val radiusFraction: Float) : GradientDrawable() {
        init {
            setColor(color)
        }

        override fun onBoundsChange(r: Rect) {
            super.onBoundsChange(r)
            cornerRadius = min(r.width(), r.height()) * radiusFraction
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {}
    }
}
