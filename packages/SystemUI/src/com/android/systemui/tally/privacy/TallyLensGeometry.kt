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

import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.util.RotationUtils
import android.view.Surface

/**
 * Where the lens ring and the location lamp by the lens are drawn, in the coordinates of the window
 * that holds them ([windowBounds], in display coordinates for the current rotation).
 *
 * The ring follows the outline of the display cutout, and of the lens when a device sets its
 * circle: an edge from 2 to 4 dp outside them, the 3 dp ring in the sensor colour from 4 to 7 dp,
 * and an edge from 7 to 9 dp ([edgeBand] covers both edges and lies under [ringBand]). Around a
 * round cutout around the lens these are circles; around the FP6's rectangular cutout, which
 * reaches the top of the screen and holds the lens, they are rounded bands around its sides and
 * bottom. Nothing is drawn nearer than [CLEARANCE_DP] to the cutout, so the ring stays on active
 * pixels. The lamp sits beside the lens, level with its centre, on the side the prototype puts it
 * (the left, with the phone upright), just outside the ring.
 */
class TallyLensGeometry(
    val windowBounds: Rect,
    val edgeBand: Path?,
    val ringBand: Path?,
    val lampX: Float,
    val lampY: Float,
    val lampRadius: Float,
    val lampEdgeRadius: Float,
    val hasLamp: Boolean,
) {
    companion object {
        /** Nothing is drawn nearer to the display cutout than this. */
        const val CLEARANCE_DP = 2f
        /** The edge on each side of the ring, as the chips' edge. */
        const val EDGE_DP = TallyIndicatorColors.EDGE_WIDTH_DP
        /** The ring in the sensor colour. */
        const val RING_DP = 3f
        /** The location lamp, and the edge around it. */
        const val LAMP_DIAMETER_DP = 8f
        const val LAMP_EDGE_DP = 1.5f

        /**
         * Computes the geometry around [cutoutPath] (display coordinates) and the [lens] circle, if
         * a device sets one (its bounds, from [lensCircle]), on a display of [displayBounds] at
         * [rotation], or null if nothing can be drawn. Without a lens circle, the lens is taken to
         * be at the cutout's end away from the display edge, as a punch hole in a cutout that
         * reaches the edge.
         */
        @JvmStatic
        @JvmOverloads
        fun compute(
            cutoutPath: Path,
            displayBounds: Rect,
            @Surface.Rotation rotation: Int,
            density: Float,
            lens: RectF? = null,
        ): TallyLensGeometry? {
            val cutoutBounds = RectF()
            @Suppress("DEPRECATION") cutoutPath.computeBounds(cutoutBounds, true)
            if (cutoutBounds.isEmpty) return null

            // What the ring keeps clear of: the cutout, and the lens when it is set.
            val cleared = lens?.let { union(cutoutPath, it) } ?: cutoutPath
            val clearedBounds = RectF()
            @Suppress("DEPRECATION") cleared.computeBounds(clearedBounds, true)
            val lensCenter =
                if (lens != null) {
                    PointF(lens.centerX(), lens.centerY())
                } else {
                    inferredLensCenter(cutoutBounds, rotation)
                }

            val innerEdge = CLEARANCE_DP * density
            val ringInner = innerEdge + EDGE_DP * density
            val ringOuter = ringInner + RING_DP * density
            val outerEdge = ringOuter + EDGE_DP * density

            val edgeBand = band(cleared, innerEdge, outerEdge)
            val ringBand = band(cleared, ringInner, ringOuter)

            val lampRadius = LAMP_DIAMETER_DP / 2f * density
            val lampEdgeRadius = lampRadius + LAMP_EDGE_DP * density
            val lamp =
                lampCenter(
                    clearedBounds,
                    lensCenter,
                    displayBounds,
                    rotation,
                    offset = outerEdge + lampEdgeRadius,
                    reach = lampEdgeRadius,
                )

            val drawn = RectF()
            if (edgeBand != null) {
                @Suppress("DEPRECATION") edgeBand.computeBounds(drawn, true)
            }
            if (lamp != null) {
                drawn.union(
                    lamp[0] - lampEdgeRadius,
                    lamp[1] - lampEdgeRadius,
                    lamp[0] + lampEdgeRadius,
                    lamp[1] + lampEdgeRadius,
                )
            }
            val window = Rect()
            drawn.roundOut(window)
            if (!window.intersect(displayBounds)) return null

            val dx = -window.left.toFloat()
            val dy = -window.top.toFloat()
            edgeBand?.offset(dx, dy)
            ringBand?.offset(dx, dy)
            return TallyLensGeometry(
                windowBounds = window,
                edgeBand = edgeBand,
                ringBand = ringBand,
                lampX = (lamp?.get(0) ?: 0f) + dx,
                lampY = (lamp?.get(1) ?: 0f) + dy,
                lampRadius = lampRadius,
                lampEdgeRadius = lampEdgeRadius,
                hasLamp = lamp != null,
            )
        }

        /**
         * A lens circle a device sets in natural-orientation pixels of the display's highest
         * resolution, as the cutout path is ([centerX] from the display's horizontal centre,
         * [centerY] from its top, [radius]), on the display now: scaled by [ratio], the framework's
         * ratio of the display's size now to [physicalWidth] (the highest resolution's width), and
         * rotated to [rotation] on a display of [naturalWidth] x [naturalHeight] in its natural
         * orientation, as the framework maps the cutout path.
         */
        @JvmStatic
        fun lensCircle(
            centerX: Float,
            centerY: Float,
            radius: Float,
            physicalWidth: Int,
            ratio: Float,
            naturalWidth: Int,
            naturalHeight: Int,
            @Surface.Rotation rotation: Int,
        ): RectF {
            val center = floatArrayOf((physicalWidth / 2f + centerX) * ratio, centerY * ratio)
            val toDisplay = Matrix()
            RotationUtils.transformPhysicalToLogicalCoordinates(
                rotation,
                naturalWidth,
                naturalHeight,
                toDisplay,
            )
            toDisplay.mapPoints(center)
            val r = radius * ratio
            return RectF(center[0] - r, center[1] - r, center[0] + r, center[1] + r)
        }

        /** [path] and the circle in [oval] together, or null if that cannot be computed. */
        private fun union(path: Path, oval: RectF): Path? {
            val circle = Path().apply { addOval(oval, Path.Direction.CW) }
            val both = Path()
            return if (both.op(path, circle, Path.Op.UNION)) both else null
        }

        /** The area between [from] and [to] outside [path], or null if it cannot be computed. */
        private fun band(path: Path, from: Float, to: Float): Path? {
            val outer = grow(path, to)
            val inner = grow(path, from)
            val band = Path()
            return if (band.op(outer, inner, Path.Op.DIFFERENCE)) band else null
        }

        /** [path] grown by [distance] on every side, with round corners. */
        private fun grow(path: Path, distance: Float): Path {
            val stroke =
                Paint().apply {
                    style = Paint.Style.STROKE
                    strokeWidth = 2f * distance
                    strokeJoin = Paint.Join.ROUND
                    strokeCap = Paint.Cap.ROUND
                }
            val grown = Path()
            stroke.getFillPath(path, grown)
            grown.op(path, Path.Op.UNION)
            return grown
        }

        /**
         * Where the lens is taken to be when no lens circle is set: at the cutout's end away from
         * the display edge, as a punch hole in a cutout that reaches the edge.
         */
        private fun inferredLensCenter(cutout: RectF, @Surface.Rotation rotation: Int): PointF {
            val lensInset = minOf(cutout.width(), cutout.height()) / 2f
            return when (rotation) {
                Surface.ROTATION_90 -> PointF(cutout.right - lensInset, cutout.centerY())
                Surface.ROTATION_180 -> PointF(cutout.centerX(), cutout.top + lensInset)
                Surface.ROTATION_270 -> PointF(cutout.left + lensInset, cutout.centerY())
                else -> PointF(cutout.centerX(), cutout.bottom - lensInset)
            }
        }

        /**
         * The lamp's centre: beside the [lens], level with its centre, [offset] away from the side
         * of what the ring keeps clear of ([cleared]), on the side that is the left with the phone
         * upright (else the other side, if the lamp and its [reach] would leave the display).
         * Returns null if neither side fits.
         */
        private fun lampCenter(
            cleared: RectF,
            lens: PointF,
            display: Rect,
            @Surface.Rotation rotation: Int,
            offset: Float,
            reach: Float,
        ): FloatArray? {
            val candidates =
                when (rotation) {
                    Surface.ROTATION_90 ->
                        listOf(
                            floatArrayOf(lens.x, cleared.bottom + offset),
                            floatArrayOf(lens.x, cleared.top - offset),
                        )
                    Surface.ROTATION_180 ->
                        listOf(
                            floatArrayOf(cleared.right + offset, lens.y),
                            floatArrayOf(cleared.left - offset, lens.y),
                        )
                    Surface.ROTATION_270 ->
                        listOf(
                            floatArrayOf(lens.x, cleared.top - offset),
                            floatArrayOf(lens.x, cleared.bottom + offset),
                        )
                    else ->
                        listOf(
                            floatArrayOf(cleared.left - offset, lens.y),
                            floatArrayOf(cleared.right + offset, lens.y),
                        )
                }
            return candidates.firstOrNull { (x, y) ->
                x - reach >= display.left &&
                    x + reach <= display.right &&
                    y - reach >= display.top &&
                    y + reach <= display.bottom
            }
        }
    }
}
