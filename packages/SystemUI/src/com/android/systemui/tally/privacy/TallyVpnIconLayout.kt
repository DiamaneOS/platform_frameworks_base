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

package com.android.systemui.tally.privacy

/**
 * Tally keeps the VPN icon out of the status bar's icon overflow. The status icons are laid out
 * from the end, and when they do not all fit, those nearest the start go into the overflow dot.
 * Tally's privacy dot takes room at the end of the status bar, so the VPN icon, a security
 * indicator, could go into the overflow where stock shows it. When it would, the icons after it
 * give way instead, the nearest first, until it fits: it then shows whenever stock shows it (and
 * also when stock would not). Otherwise the layout is stock's.
 */
object TallyVpnIconLayout {
    /**
     * Where the icons that stay shown start, when the VPN icon must stay too.
     *
     * The icons are laid out from the end: [x] holds each one's start edge, in order from the
     * start, and stock puts the icons up to [cut] into the overflow, the VPN icon among them. The
     * icons after the cut give way from the start of their block until the VPN icon ([vpnWidth]
     * wide, [spacing] before the block, or before [endEdge] if none is left) fits after the
     * overflow dot (from [contentStart], [underflowWidth] wide), and, when icons are [restricted],
     * until at most [maxVisible] show. The VPN icon goes just before the returned index. Returns -1
     * if even the VPN icon alone does not fit: stock's layout then stays.
     */
    @JvmStatic
    fun keptStart(
        x: FloatArray,
        cut: Int,
        vpnWidth: Float,
        spacing: Float,
        endEdge: Float,
        contentStart: Float,
        underflowWidth: Float,
        maxVisible: Int,
        restricted: Boolean,
    ): Int {
        val count = x.size
        var first = cut + 1
        while (true) {
            val vpnEnd = if (first < count) x[first] - spacing else endEdge
            val shown = count - first + 1
            val fits = vpnEnd - vpnWidth >= contentStart + underflowWidth
            if (fits && (!restricted || shown <= maxVisible)) return first
            if (first >= count) return -1
            first++
        }
    }
}
