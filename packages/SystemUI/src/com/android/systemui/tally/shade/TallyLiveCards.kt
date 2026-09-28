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
import com.android.systemui.tally.lamp.TallyLampColors
import com.android.systemui.tally.lamp.TallyLampDrawable
import com.android.systemui.tally.lamp.TallyLampSize
import com.android.systemui.tally.lamp.TallyLampState
import org.diamaneos.tally.R as TallyR

/**
 * Tally's live cards (shade.js `buildLive`): the media card and every ongoing notification (a
 * timer, a call, navigation, a download) are neutral cards that carry a live lamp by their words,
 * never a lamp-filled band or a coloured card. The lamp is spent on state: live while the thing is
 * going on, off while media is paused.
 */
object TallyLiveCards {
    /**
     * Shows [state] as a small lamp beside [text]'s words (after them when [atEnd], else before),
     * or takes the lamp away when [state] is null. The text view keeps one lamp, the shell's
     * animated lamp, in its tag, and a new state moves that lamp, so a card that starts or stops
     * playing animates its lamp. [context] is SystemUI's, whose resources and theme the lamp takes;
     * a notification's views may carry the posting app's.
     */
    @JvmStatic
    fun bindLamp(context: Context, text: TextView, state: TallyLampState?, atEnd: Boolean) {
        val kept = text.getTag(TAG) as? TallyLampDrawable
        if (state == null) {
            if (kept != null) {
                text.setCompoundDrawablesRelative(null, null, null, null)
                text.setTag(TAG, null)
            }
            return
        }
        val lamp = kept ?: TallyLampDrawable(context).apply { setLampSize(TallyLampSize.SMALL) }
        // Cards bind again after a light or dark theme change, so the colours are read each time.
        lamp.colors = TallyLampColors.theme(context)
        lamp.setState(state)
        val side = if (atEnd) END else START
        if (text.compoundDrawablesRelative[side] !== lamp) {
            text.setCompoundDrawablesRelativeWithIntrinsicBounds(
                if (atEnd) null else lamp,
                null,
                if (atEnd) lamp else null,
                null,
            )
            text.compoundDrawablePadding =
                context.resources.getDimensionPixelSize(TallyR.dimen.tally_space_xs)
            text.setTag(TAG, lamp)
        }
    }

    /** The lamp of a media card: live while it plays, off while it is paused. */
    @JvmStatic
    fun mediaLamp(playing: Boolean): TallyLampState =
        if (playing) TallyLampState.LIVE else TallyLampState.OFF

    private val TAG = R.id.tally_live_lamp
    private const val START = 0
    private const val END = 2
}
