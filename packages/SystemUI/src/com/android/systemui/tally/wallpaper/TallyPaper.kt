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

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Color
import com.android.systemui.res.R
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sin

/**
 * How the paper looks in one theme: a vertical gradient from [top] to [bottom], a grain of [grain]
 * levels (uniform, the same on red, green and blue) and a vignette of [vignette] (with its alpha)
 * at the edge. The colours already carry the colour preset's hue ([of]).
 */
data class PaperLook(
    val dark: Boolean,
    val top: Int,
    val bottom: Int,
    val vignette: Int,
    val grain: Int,
) {
    companion object {
        /**
         * The paper of the current palette in light or dark theme: the
         * res/values/tally_wallpaper.xml colours (written for Sodium) with their tone and chroma
         * kept and the hue of tally_paper_hue, the colour preset's.
         */
        fun of(resources: Resources, dark: Boolean): PaperLook =
            of(resources, dark, PaperColour.hue(resources.getColor(R.color.tally_paper_hue, null)))

        fun of(resources: Resources, dark: Boolean, hue: Double): PaperLook {
            fun colour(id: Int) = PaperColour.rehue(resources.getColor(id, null), hue)
            return if (dark) {
                PaperLook(
                    dark = true,
                    top = colour(R.color.tally_paper_dark_top),
                    bottom = colour(R.color.tally_paper_dark_bottom),
                    vignette = colour(R.color.tally_paper_dark_vignette),
                    grain = resources.getInteger(R.integer.tally_paper_dark_grain),
                )
            } else {
                PaperLook(
                    dark = false,
                    top = colour(R.color.tally_paper_light_top),
                    bottom = colour(R.color.tally_paper_light_bottom),
                    vignette = colour(R.color.tally_paper_light_vignette),
                    grain = resources.getInteger(R.integer.tally_paper_light_grain),
                )
            }
        }
    }
}

/**
 * Draws the paper: the design prototype's "Paper · fine" (wallpaper ideas, paper() with no
 * options), pixel for pixel at any size. The ground is a vertical gradient; the grain adds to every
 * pixel a uniform random step of -grain/2 to +grain/2 levels on red, green and blue alike, from a
 * fixed seed, so the picture never changes; then a radial vignette, centred at half the width and
 * 0.45 of the height with a radius of 0.72 of the height, clear to its middle and reaching the
 * vignette colour at its edge. The grain is one random step per device pixel.
 */
object TallyPaper {
    /** The seed of the prototype's grain (paper()'s default). */
    const val SEED = 7L

    /** A new bitmap of the paper, [width] by [height] device pixels. */
    fun render(width: Int, height: Int, look: PaperLook, grain: Boolean = true): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val row = IntArray(width)
        // The prototype's seeded random (Park-Miller), one draw per pixel in reading order
        var seed = SEED
        val amplitude = if (grain) look.grain.toDouble() else 0.0
        val cx = width * 0.5
        val cy = height * 0.45
        val radius = height * 0.72
        val va = Color.alpha(look.vignette) / 255.0
        val vr = Color.red(look.vignette).toDouble()
        val vg = Color.green(look.vignette).toDouble()
        val vb = Color.blue(look.vignette).toDouble()
        for (y in 0 until height) {
            val t = (y + 0.5) / height
            val r0 = lerp(Color.red(look.top), Color.red(look.bottom), t)
            val g0 = lerp(Color.green(look.top), Color.green(look.bottom), t)
            val b0 = lerp(Color.blue(look.top), Color.blue(look.bottom), t)
            for (x in 0 until width) {
                seed = seed * 16807 % 2147483647
                val n = ((seed - 1) / 2147483646.0 - 0.5) * amplitude
                var r = clamp(round(r0 + n))
                var g = clamp(round(g0 + n))
                var b = clamp(round(b0 + n))
                // The vignette: transparent at half its radius, its colour at the edge, the colour
                // and its alpha rising together (unpremultiplied, as the prototype's canvas)
                val d = hypot(x + 0.5 - cx, y + 0.5 - cy) / radius
                if (d > 0.5) {
                    val k = min(1.0, (d - 0.5) / 0.5)
                    val a = va * k
                    r = r * (1 - a) + vr * k * a
                    g = g * (1 - a) + vg * k * a
                    b = b * (1 - a) + vb * k * a
                }
                row[x] =
                    Color.rgb(
                        clamp(round(r)).toInt(),
                        clamp(round(g)).toInt(),
                        clamp(round(b)).toInt(),
                    )
            }
            bitmap.setPixels(row, 0, width, 0, y, width, 1)
        }
        return bitmap
    }

    /** The paper's average colour (the grain averages out), from a small rendering. */
    fun average(look: PaperLook): Int {
        val small = render(AVERAGE_WIDTH, AVERAGE_HEIGHT, look, grain = false)
        var r = 0L
        var g = 0L
        var b = 0L
        val pixels = IntArray(AVERAGE_WIDTH * AVERAGE_HEIGHT)
        small.getPixels(pixels, 0, AVERAGE_WIDTH, 0, 0, AVERAGE_WIDTH, AVERAGE_HEIGHT)
        small.recycle()
        for (p in pixels) {
            r += Color.red(p)
            g += Color.green(p)
            b += Color.blue(p)
        }
        val n = pixels.size
        return Color.rgb((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
    }

    private const val AVERAGE_WIDTH = 46
    private const val AVERAGE_HEIGHT = 100

    private fun lerp(a: Int, b: Int, t: Double) = a + (b - a) * t

    private fun clamp(v: Double) = min(255.0, max(0.0, v))
}

/**
 * The prototype's colour math (design/prototypes/harness.js: H.hexToOklch, H.tone and H.fromHCT),
 * as the Tally palette style (monet SchemeTally.Oklch) has it: the paper's colours keep their tone
 * (CIELAB L*) and OKLCH chroma and take the colour preset's hue.
 */
object PaperColour {
    /** OKLCH hue of [argb], in degrees. */
    fun hue(argb: Int): Double {
        val lab = oklab(argb)
        return (Math.toDegrees(atan2(lab[2], lab[1])) + 360) % 360
    }

    /** [argb] (alpha kept) at OKLCH hue [hue], with its own tone and chroma. */
    fun rehue(argb: Int, hue: Double): Int {
        val lab = oklab(argb)
        val chroma = hypot(lab[1], lab[2])
        val tone = lstar(luminance(linear(argb)))
        return (argb and 0xff000000.toInt()) or (toArgb(hue, chroma, tone) and 0xffffff)
    }

    /** The colour of OKLCH hue and chroma, reduced to fit sRGB, whose L* is [tone]. */
    fun toArgb(hue: Double, chroma: Double, tone: Double): Int {
        if (tone <= 0) return Color.BLACK
        if (tone >= 100) return Color.WHITE
        val radians = Math.toRadians(hue)
        var c = chroma
        var l = solve(c, radians, tone)
        if (!inGamut(toLinear(l, c, radians))) {
            var low = 0.0
            var high = c
            repeat(20) {
                val mid = (low + high) / 2
                if (inGamut(toLinear(solve(mid, radians, tone), mid, radians))) low = mid
                else high = mid
            }
            c = low
            l = solve(c, radians, tone)
        }
        val rgb = toLinear(l, c, radians)
        return Color.rgb(channel(rgb[0]), channel(rgb[1]), channel(rgb[2]))
    }

    private fun solve(chroma: Double, radians: Double, tone: Double): Double {
        var low = 0.0
        var high = 1.0
        repeat(28) {
            val mid = (low + high) / 2
            if (lstar(max(0.0, luminance(toLinear(mid, chroma, radians)))) < tone) low = mid
            else high = mid
        }
        return (low + high) / 2
    }

    private fun channel(v: Double): Int {
        val c = if (v <= 0.0031308) 12.92 * v else 1.055 * v.pow(1 / 2.4) - 0.055
        return Math.round(min(1.0, max(0.0, c)) * 255).toInt()
    }

    private fun linear(argb: Int): DoubleArray =
        doubleArrayOf(
            linearize(Color.red(argb) / 255.0),
            linearize(Color.green(argb) / 255.0),
            linearize(Color.blue(argb) / 255.0),
        )

    private fun linearize(c: Double) =
        if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)

    private fun luminance(rgb: DoubleArray) = 0.2126 * rgb[0] + 0.7152 * rgb[1] + 0.0722 * rgb[2]

    private fun lstar(y: Double) =
        if (y > 216.0 / 24389.0) 116 * cbrt(y) - 16 else 24389.0 / 27.0 * y

    private fun oklab(argb: Int): DoubleArray {
        val (r, g, b) = linear(argb).toList()
        val l = cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b)
        val m = cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b)
        val s = cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b)
        return doubleArrayOf(
            0.2104542553 * l + 0.793617785 * m - 0.0040720468 * s,
            1.9779984951 * l - 2.428592205 * m + 0.4505937099 * s,
            0.0259040371 * l + 0.7827717662 * m - 0.808675766 * s,
        )
    }

    private fun toLinear(lightness: Double, chroma: Double, radians: Double): DoubleArray {
        val a = chroma * cos(radians)
        val b = chroma * sin(radians)
        val l = (lightness + 0.3963377774 * a + 0.2158037573 * b).pow(3)
        val m = (lightness - 0.1055613458 * a - 0.0638541728 * b).pow(3)
        val s = (lightness - 0.0894841775 * a - 1.291485548 * b).pow(3)
        return doubleArrayOf(
            4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s,
            -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s,
            -0.0041960863 * l - 0.7034186147 * m + 1.707614701 * s,
        )
    }

    private fun inGamut(rgb: DoubleArray) = rgb.all { it >= -1e-4 && it <= 1 + 1e-4 }
}
