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

package com.android.systemui.tally.shade

import android.content.Context
import android.widget.TextView
import com.android.systemui.res.R
import org.diamaneos.tally.R as TallyR

/**
 * Tally's live cards (shade.js `buildLive`): the media card and every ongoing notification (a
 * timer, a call, navigation, a download) are neutral cards that carry a live lamp by their words,
 * never a lamp-filled band or a coloured card. The lamp is spent on state: live while the thing is
 * going on, off while media is paused.
 */
object TallyLiveCards {
    /**
     * Shows [form] as a small lamp beside [text]'s words (after them when [atEnd], else before), or
     * takes the lamp away when [form] is null. [context] is SystemUI's, whose resources hold the
     * lamps; a notification's views may carry the posting app's.
     */
    @JvmStatic
    fun bindLamp(context: Context, text: TextView, form: TallyLampForm?, atEnd: Boolean) {
        if (form == null) {
            if (text.getTag(TAG) != null) {
                text.setCompoundDrawablesRelative(null, null, null, null)
                text.setTag(TAG, null)
            }
            return
        }
        if (text.getTag(TAG) == form) return
        val lamp = TallyLamps.drawable(context, form, TallyLamps.SIZE_SMALL)
        text.setCompoundDrawablesRelative(
            if (atEnd) null else lamp,
            null,
            if (atEnd) lamp else null,
            null,
        )
        text.compoundDrawablePadding =
            context.resources.getDimensionPixelSize(TallyR.dimen.tally_space_xs)
        text.setTag(TAG, form)
    }

    /** The lamp of a media card: live while it plays, off while it is paused. */
    @JvmStatic
    fun mediaLamp(playing: Boolean): TallyLampForm =
        if (playing) TallyLampForm.LIVE else TallyLampForm.OFF

    private val TAG = R.id.tally_live_lamp
}
