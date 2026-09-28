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

package com.android.systemui.tally.volume

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewOutlineProvider
import android.view.ViewTreeObserver
import android.widget.ImageButton
import com.android.app.tracing.coroutines.launchTraced
import com.android.systemui.res.R
import com.android.systemui.tally.TallyShell
import com.android.systemui.volume.dialog.dagger.scope.VolumeDialogScope
import com.android.systemui.volume.dialog.domain.interactor.ExpandedAudioTileDetailsFeatureInteractor
import com.android.systemui.volume.dialog.ui.binder.ViewBinder
import de.diamaneos.tally.R as TallyR
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation

/**
 * Draws the vertical volume dialog as Tally's volume panel (the prototype's `.vol`): one surface
 * with a hairline in the outline variant, the card radius on every corner and the shell's one
 * shadow, around the ringer drawer, the slider and the keys under it, as the drawer opens and
 * closes. The plain keys (settings) take the key shape and ink.
 *
 * Stock draws the dialog as two overlapping pills (the drawer's background and the dialog's). With
 * Tally both are transparent (`volume_dialog_panel`) and the dialog's root draws their outline as
 * one shape, so the hairline runs around the whole panel and never across it. The shape follows the
 * two backgrounds every frame, through the drawer's animation and the slider's overscroll. Only
 * drawing changes: the touchable region, the keys and what they do stay stock.
 */
@VolumeDialogScope
class TallyVolumePanelViewBinder
@Inject
constructor(
    private val expandedAudioTileDetailsFeatureInteractor: ExpandedAudioTileDetailsFeatureInteractor
) : ViewBinder {

    override fun CoroutineScope.bind(view: View) {
        if (TallyShell.isUnexpectedlyInLegacyMode()) return
        // The horizontal dialog (Quick Settings' audio details) keeps its own look.
        if (expandedAudioTileDetailsFeatureInteractor.isEnabled()) return

        val panel = TallyVolumePanel(view)
        panel.attach()
        launchTraced("TVPVB#panel") {
            try {
                awaitCancellation()
            } finally {
                panel.detach()
            }
        }
    }
}

/** The panel's shape on the dialog's [root], kept up to date before each frame. */
private class TallyVolumePanel(private val root: View) : ViewTreeObserver.OnPreDrawListener {
    private val background: View = root.requireViewById(R.id.volume_dialog_background)
    private val drawer: View = root.requireViewById(R.id.ringer_buttons_background)
    private val drawable = PanelDrawable(root.context)
    private val backgroundBounds = RectF()
    private val drawerBounds = RectF()
    private val lastBackgroundBounds = RectF()
    private val lastDrawerBounds = RectF()
    private var lastDrawerShown = false
    private var observer: ViewTreeObserver? = null

    fun attach() {
        root.background = drawable
        root.outlineProvider =
            object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    if (drawable.outline.isEmpty) {
                        outline.setEmpty()
                    } else {
                        outline.setPath(drawable.outline)
                    }
                }
            }
        root.elevation = root.resources.getDimension(R.dimen.tally_volume_panel_elevation)

        // The settings key: a plain key, ink on the panel. Its binder only sets its icon.
        root.requireViewById<ImageButton>(R.id.volume_dialog_settings).apply {
            setBackgroundResource(R.drawable.tally_volume_key_background)
            imageTintList = ColorStateList.valueOf(context.getColor(TallyR.color.tally_ink))
        }

        observer = root.viewTreeObserver.also { it.addOnPreDrawListener(this) }
        update()
    }

    fun detach() {
        observer?.let { if (it.isAlive) it.removeOnPreDrawListener(this) }
        observer = null
    }

    override fun onPreDraw(): Boolean {
        if (update()) {
            drawable.invalidateSelf()
            root.invalidateOutline()
        }
        return true
    }

    /** Measures the two backgrounds in [root]'s coordinates; returns whether the shape changed. */
    private fun update(): Boolean {
        // Not laid out yet: no panel (an empty rectangle keeps the shape empty).
        if (!background.boundsIn(root, backgroundBounds)) backgroundBounds.setEmpty()
        val drawerShown = drawer.isShownIn(root) && drawer.boundsIn(root, drawerBounds)
        if (
            backgroundBounds == lastBackgroundBounds &&
                drawerShown == lastDrawerShown &&
                (!drawerShown || drawerBounds == lastDrawerBounds)
        ) {
            return false
        }
        lastBackgroundBounds.set(backgroundBounds)
        lastDrawerBounds.set(drawerBounds)
        lastDrawerShown = drawerShown
        drawable.setShape(backgroundBounds, if (drawerShown) drawerBounds else null)
        return true
    }
}

/**
 * The panel: the union of the dialog's background and the open or closed drawer's, each a rectangle
 * with the card radius, filled with the surface colour and edged inside with a hairline, as a CSS
 * border.
 */
private class PanelDrawable(context: Context) : Drawable() {
    private val radius = context.resources.getDimension(TallyR.dimen.tally_radius_m)
    private val edgeWidth = context.resources.getDimension(TallyR.dimen.tally_stroke_hairline)
    private val fillPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(TallyR.color.tally_surface) }
    private val edgePaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = edgeWidth
            color = context.getColor(TallyR.color.tally_outline_variant)
        }

    /** The panel's outline in the host's coordinates; empty before the first shape. */
    val outline = Path()
    private val edge = Path()
    private val part = Path()
    private val inset = RectF()

    /** Sets the panel to [first] and [second] together; an empty [first] clears it. */
    fun setShape(first: RectF, second: RectF?) {
        union(outline, first, second, 0f)
        // The hairline's centre runs half its width inside the outline.
        union(edge, first, second, edgeWidth / 2f)
    }

    private fun union(into: Path, first: RectF, second: RectF?, by: Float) {
        into.reset()
        if (first.isEmpty) return
        inset.set(first)
        inset.inset(by, by)
        into.addRoundRect(inset, radius - by, radius - by, Path.Direction.CW)
        if (second != null) {
            inset.set(second)
            inset.inset(by, by)
            part.reset()
            part.addRoundRect(inset, radius - by, radius - by, Path.Direction.CW)
            into.op(part, Path.Op.UNION)
        }
    }

    override fun draw(canvas: Canvas) {
        if (outline.isEmpty) return
        canvas.drawPath(outline, fillPaint)
        canvas.drawPath(edge, edgePaint)
    }

    override fun setAlpha(alpha: Int) {
        fillPaint.alpha = alpha
        edgePaint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        fillPaint.colorFilter = colorFilter
        edgePaint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in android.graphics.drawable.Drawable")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/** Sets [out] to this view's bounds in [root]'s coordinates, translations included. */
private fun View.boundsIn(root: View, out: RectF): Boolean {
    var x = 0f
    var y = 0f
    var view: View = this
    while (view !== root) {
        x += view.x
        y += view.y
        val parent = view.parent as? View ?: return false
        x -= parent.scrollX
        y -= parent.scrollY
        view = parent
    }
    out.set(x, y, x + width, y + height)
    return true
}

/** Whether this view and every parent up to [root] is visible. */
private fun View.isShownIn(root: View): Boolean {
    var view: View = this
    while (view !== root) {
        if (view.visibility != View.VISIBLE) return false
        view = view.parent as? View ?: return false
    }
    return true
}
