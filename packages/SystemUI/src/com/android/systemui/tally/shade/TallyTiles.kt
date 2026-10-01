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
import android.service.quicksettings.Tile
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import com.android.compose.modifiers.thenIf
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.compose.modifiers.sysuiResTag
import com.android.systemui.qs.panels.ui.compose.infinitegrid.CommonTileDefaults
import com.android.systemui.qs.panels.ui.compose.infinitegrid.SmallTileContent
import com.android.systemui.qs.panels.ui.compose.infinitegrid.TileColors
import com.android.systemui.qs.panels.ui.compose.infinitegrid.bounceScale
import com.android.systemui.qs.panels.ui.viewmodel.AccessibilityUiState
import com.android.systemui.qs.panels.ui.viewmodel.TileUiState
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.qs.tileimpl.SubtitleArrayMapping
import com.android.systemui.qs.tiles.CameraToggleTile
import com.android.systemui.qs.tiles.MicrophoneToggleTile
import com.android.systemui.qs.ui.compose.borderOnFocus
import com.android.systemui.res.R
import com.android.systemui.tally.lamp.TallyLamp
import com.android.systemui.tally.lamp.TallyLampDefaults
import com.android.systemui.tally.lamp.TallyLampSize
import com.android.systemui.tally.lamp.TallyLampState
import de.diamaneos.tally.R as TallyR
import kotlin.math.max
import kotlinx.coroutines.delay

/**
 * Tally's Quick Settings tiles, as the prototype draws them (shade.js `tileView`, app.css `.tile`):
 * a tonal key with a lamp that shows the tile's state, lit as a whole in the lamp colour when on. A
 * tile that lights shows its lamp lit, then the lit field wipes across from the lamp's side; a tile
 * that goes off drains back into its lamp. Tiles with words (large tiles) carry their label and
 * state beside the lamp; tiles without words are keycaps with the lamp in their corner, and say
 * their words to screen readers.
 *
 * Only the drawing is Tally's: the tile's clicks, long clicks, toggle target, haptics and launch
 * animations stay the upstream tile's, so what needs the unlock still waits for it.
 */
object TallyTileDefaults {
    /** Tiles with words take the card radius; keycaps take 22 % of their side. */
    @Composable
    fun rememberShape(iconOnly: Boolean): State<RoundedCornerShape> {
        val card = dimensionResource(TallyR.dimen.tally_radius_m)
        val keycap = LocalContext.current.resources.keycapRadiusPercent()
        return rememberUpdatedState(
            if (iconOnly) RoundedCornerShape(CornerSize(keycap)) else RoundedCornerShape(card)
        )
    }

    /**
     * The tile's colours at rest. The Tally content draws its own field and ink; these are for the
     * tile's expandable, the surface a dialog or activity grows from when the tile opens one.
     */
    @Composable
    fun colors(uiState: TileUiState): TileColors {
        val palette = tallyTilePalette()
        return if (uiState.tallyLampState().isLit) {
            TileColors(
                background = palette.lamp,
                iconBackground = Color.Transparent,
                label = palette.onLamp,
                secondaryLabel = palette.onLamp,
                icon = palette.onLamp,
            )
        } else {
            TileColors(
                background = palette.surface,
                iconBackground = palette.innerKey,
                label = palette.ink,
                secondaryLabel = palette.muted,
                icon = palette.ink,
            )
        }
    }
}

/**
 * The lamp a tile shows: unavailable (with the reason in its words), requested while the system is
 * still switching it, on when active, else off. Tiles have no truthful source for live or failed.
 */
fun TileUiState.tallyLampState(): TallyLampState =
    when {
        visualState == Tile.STATE_UNAVAILABLE -> TallyLampState.UNAVAILABLE
        isTransient -> TallyLampState.REQUESTED
        visualState == Tile.STATE_ACTIVE -> TallyLampState.ON
        else -> TallyLampState.OFF
    }

/**
 * A tile's Tally content: its field (tonal, or lit), its lamp, icon and words. It fills the tile
 * and draws under the tile's own press ripple.
 *
 * @param toggleClick the toggle of a tile with words that has one (the upstream dual-target
 *   tile's), drawn as a key of its own at the tile's end.
 * @param bounceScale the upstream tile's press bounce for its icon (keycaps) or words.
 */
@Composable
fun TallyTileContent(
    uiState: TileUiState,
    spec: TileSpec,
    iconOnly: Boolean,
    shape: RoundedCornerShape,
    iconProvider: Context.() -> Icon,
    toggleClick: (() -> Unit)?,
    onLongClick: (() -> Unit)?,
    isVisible: () -> Boolean,
    modifier: Modifier = Modifier,
    bounceScale: () -> Float = { 1f },
) {
    val state = uiState.tallyLampState()
    val instantLamp = spec.isSensorAccess()
    val palette = tallyTilePalette()
    val wipe = rememberWipe(state.isLit)
    // The content takes the lit colours once the field has lit. Until then, and while it drains,
    // it keeps the unlit colours and the lit part of the field shows it in the colour for text on
    // the lamp, as the prototype's two layers do. The wipe carries the change, so the colours
    // switch at once; an icon easing into the lit colour would flash its unlit one first.
    val fieldLit by remember(wipe) { derivedStateOf { wipe.value >= 1f } }
    val lit = state.isLit && fieldLit
    val edgeWidth = dimensionResource(TallyR.dimen.tally_stroke_lit_edge)
    val keyPlate = remember { TileKeyPlate() }
    val keyRadius = dimensionResource(TallyR.dimen.tally_radius_s)
    Box(
        modifier
            .fillMaxSize()
            .onPlaced(keyPlate::onTilePlaced)
            .litField({ wipe.value }, lit, shape, palette, edgeWidth, keyPlate, keyRadius)
    ) {
        if (iconOnly) {
            KeycapContent(state, lit, instantLamp, iconProvider, palette, bounceScale)
        } else {
            LargeContent(
                uiState,
                spec,
                state,
                lit,
                instantLamp,
                iconProvider,
                toggleClick,
                onLongClick,
                isVisible,
                palette,
                keyPlate,
                bounceScale,
            )
        }
    }
}

/**
 * Where a tile's toggle key sits in the tile. The tile's field draws the key's plate, tonal where
 * the field is unlit and outlined where it is lit, so the plate wipes with the field. Drawn by the
 * key, the tonal plate showed on the lit part as a solid block in the colour for text on the lamp,
 * with the icon lost in it, until the field had lit.
 */
private class TileKeyPlate {
    private var tile: LayoutCoordinates? = null
    private var key: LayoutCoordinates? = null

    /** The key's bounds in the tile; empty when the tile has no key. */
    var bounds by mutableStateOf(Rect.Zero)
        private set

    fun onTilePlaced(coordinates: LayoutCoordinates) {
        tile = coordinates
        update()
    }

    fun onKeyPlaced(coordinates: LayoutCoordinates) {
        key = coordinates
        update()
    }

    fun onKeyGone() {
        key = null
        bounds = Rect.Zero
    }

    private fun update() {
        val tile = tile ?: return
        val key = key ?: return
        if (tile.isAttached && key.isAttached) {
            bounds = tile.localBoundingBoxOf(key, clipBounds = false)
        }
    }
}

/**
 * A tile's lamp, the shell's animated lamp: 14 dp, 16 dp from 200 % text, in the colour for text on
 * the lamp once the tile's field has lit. A requested tile's dashes turn three times and then rest,
 * while the tile's own words ("Turning on…") still say that it is switching.
 *
 * @param instantAppear the lamp lights at once instead of igniting, as sensor lamps do. It is part
 *   of the lamp's parameters, so it is in place before any state the lamp shows.
 */
@Composable
private fun TileLamp(
    state: TallyLampState,
    lit: Boolean,
    instantAppear: Boolean,
    modifier: Modifier = Modifier,
) {
    TallyLamp(
        state = state,
        modifier = modifier,
        size = TallyLampDefaults.size(TallyLampSize.LARGE, growsWithText = true),
        colors = if (lit) TallyLampDefaults.onLitFieldColors() else TallyLampDefaults.colors(),
        instantAppear = instantAppear,
    )
}

/**
 * Whether the tile controls a sensor's access (camera or microphone), whose lamp lights at once, as
 * a sensor lamp does. It keeps the tile's colours: lit means that access is allowed, not that the
 * sensor is in use, which only the privacy indicators say, in the sensor colour.
 */
private fun TileSpec.isSensorAccess(): Boolean =
    spec == CameraToggleTile.TILE_SPEC || spec == MicrophoneToggleTile.TILE_SPEC

@Immutable
internal class TallyTilePalette(
    val surface: Color,
    val innerKey: Color,
    val lamp: Color,
    val litEdge: Color,
    val ink: Color,
    val muted: Color,
    val onLamp: Color,
    val focus: Color,
)

@Composable
internal fun tallyTilePalette() =
    TallyTilePalette(
        surface = colorResource(TallyR.color.tally_surface_high),
        innerKey = colorResource(TallyR.color.tally_surface),
        lamp = colorResource(TallyR.color.tally_lamp),
        litEdge = colorResource(TallyR.color.tally_lamp_outline),
        ink = colorResource(TallyR.color.tally_ink),
        muted = colorResource(TallyR.color.tally_ink_muted),
        onLamp = colorResource(TallyR.color.tally_on_lamp),
        focus = colorResource(TallyR.color.tally_accent),
    )

/**
 * The lit field's extent, 0 to 1: it lights 50 ms after the lamp and drains at once, on the fill
 * spring (no overshoot). A tile shown for the first time is drawn as it is.
 */
@Composable
private fun rememberWipe(lit: Boolean): Animatable<Float, AnimationVector1D> {
    val resources = LocalContext.current.resources
    val spec =
        remember(resources) {
            spring(
                dampingRatio = resources.getFloat(TallyR.dimen.tally_spring_fill_damping_ratio),
                stiffness = resources.getFloat(TallyR.dimen.tally_spring_fill_stiffness),
                visibilityThreshold = WIPE_THRESHOLD,
            )
        }
    val wipe = remember { Animatable(if (lit) 1f else 0f) }
    LaunchedEffect(lit) {
        if (lit) {
            if (wipe.value < 1f) {
                delay(LIGHT_DELAY_MILLIS)
                wipe.animateTo(1f, spec)
            }
        } else {
            wipe.animateTo(0f, spec)
        }
    }
    return wipe
}

/**
 * Draws the tile's field: tonal where it is not lit, the lamp colour (with the lamp outline as an
 * inner edge, which only shows in light theme) where it is, and the plate of the tile's toggle key:
 * tonal on the unlit part, outlined in the colour for text on the lamp on the lit part. While the
 * field wipes, the content is in its unlit colours and the lit part shows it in the colour for text
 * on the lamp.
 *
 * @param contentLit the content is in its lit colours, which it is only once the field is lit.
 */
private fun Modifier.litField(
    wipe: () -> Float,
    contentLit: Boolean,
    shape: RoundedCornerShape,
    palette: TallyTilePalette,
    edgeWidth: Dp,
    keyPlate: TileKeyPlate,
    keyRadius: Dp,
): Modifier = drawWithCache {
    val tint = Paint().apply { colorFilter = ColorFilter.tint(palette.onLamp, BlendMode.SrcIn) }
    val edge = edgeWidth.toPx()
    val radius = shape.topStart.toPx(size, this)
    val keyCorner = keyRadius.toPx()
    onDrawWithContent {
        val content = this
        val width = size.width
        val height = size.height
        val extent = (width * wipe()).coerceIn(0f, width)
        val key = keyPlate.bounds
        // The field lights from the lamp's side, the start.
        val rtl = layoutDirection == LayoutDirection.Rtl
        val litLeft = if (rtl) width - extent else 0f
        val litRight = if (rtl) width else extent
        val unlitLeft = if (rtl) 0f else extent
        val unlitRight = if (rtl) width - extent else width
        if (unlitRight > unlitLeft) {
            drawRect(palette.surface, Offset(unlitLeft, 0f), Size(unlitRight - unlitLeft, height))
            if (!key.isEmpty) {
                clipRect(unlitLeft, 0f, unlitRight, height) {
                    drawRoundRect(palette.innerKey, key.topLeft, key.size, CornerRadius(keyCorner))
                }
            }
        }
        if (litRight > litLeft) {
            clipRect(litLeft, 0f, litRight, height) {
                drawRect(palette.lamp)
                drawRoundRect(
                    palette.litEdge,
                    topLeft = Offset(edge / 2f, edge / 2f),
                    size = Size(width - edge, height - edge),
                    cornerRadius = CornerRadius(max(0f, radius - edge / 2f)),
                    style = Stroke(edge),
                )
                if (!key.isEmpty) {
                    drawRoundRect(
                        palette.onLamp,
                        topLeft = key.topLeft + Offset(edge / 2f, edge / 2f),
                        size = Size(key.width - edge, key.height - edge),
                        cornerRadius = CornerRadius(max(0f, keyCorner - edge / 2f)),
                        style = Stroke(edge),
                    )
                }
            }
        }
        if (contentLit) {
            content.drawContent()
            return@onDrawWithContent
        }
        if (unlitRight > unlitLeft) {
            clipRect(unlitLeft, 0f, unlitRight, height) { content.drawContent() }
        }
        if (litRight > litLeft) {
            clipRect(litLeft, 0f, litRight, height) {
                drawIntoCanvas { canvas ->
                    canvas.saveLayer(Rect(litLeft, 0f, litRight, height), tint)
                    content.drawContent()
                    canvas.restore()
                }
            }
        }
    }
}

/** A keycap: the lamp in its corner and the icon, set a little off centre away from the lamp. */
@Composable
private fun BoxScope.KeycapContent(
    state: TallyLampState,
    lit: Boolean,
    instantLamp: Boolean,
    iconProvider: Context.() -> Icon,
    palette: TallyTilePalette,
    bounceScale: () -> Float,
) {
    TileLamp(
        state,
        lit,
        instantLamp,
        Modifier.align(Alignment.TopStart)
            .padding(start = KEYCAP_LAMP_INSET, top = KEYCAP_LAMP_INSET),
    )
    val iconSize = dimensionResource(TallyR.dimen.tally_icon_size)
    SmallTileContent(
        iconProvider = iconProvider,
        color = iconColor(state, lit, palette),
        size = { iconSize },
        animateColor = false,
        modifier =
            Modifier.align(Alignment.Center)
                .offset(KEYCAP_ICON_SHIFT, KEYCAP_ICON_SHIFT)
                .bounceScale(scale = bounceScale),
    )
}

/** A tile with words: the lamp and label with the icon at the end, and the state under them. */
@Composable
private fun LargeContent(
    uiState: TileUiState,
    spec: TileSpec,
    state: TallyLampState,
    lit: Boolean,
    instantLamp: Boolean,
    iconProvider: Context.() -> Icon,
    toggleClick: (() -> Unit)?,
    onLongClick: (() -> Unit)?,
    isVisible: () -> Boolean,
    palette: TallyTilePalette,
    keyPlate: TileKeyPlate,
    bounceScale: () -> Float,
) {
    val ink = if (lit) palette.onLamp else palette.ink
    val secondaryInk = if (lit) palette.onLamp else palette.muted
    val gap = dimensionResource(TallyR.dimen.tally_space_s)
    val lines = rememberLargeTileLines()
    Row(
        modifier =
            Modifier.fillMaxSize()
                .padding(
                    start = dimensionResource(TallyR.dimen.tally_space_m),
                    end = if (toggleClick != null) gap else LARGE_END_PADDING,
                ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            Modifier.weight(1f).bounceScale(TransformOrigin(0f, 0.5f), bounceScale),
            verticalArrangement = Arrangement.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TileLamp(state, lit, instantLamp)
                Spacer(Modifier.width(gap))
                TileText(
                    text = uiState.label,
                    style = lines.label,
                    condensedStyle = lines.labelCondensed,
                    color = ink,
                    isVisible = isVisible,
                    modifier = Modifier.weight(1f),
                )
                if (toggleClick == null) {
                    Spacer(Modifier.width(gap))
                    SmallTileContent(
                        iconProvider = iconProvider,
                        color = iconColor(state, lit, palette),
                        size = { LARGE_ICON_SIZE },
                        animateColor = false,
                    )
                }
            }
            val secondary = secondaryText(uiState, spec)
            if (secondary.isNotEmpty()) {
                TileText(
                    text = secondary,
                    style = lines.caption,
                    condensedStyle = lines.captionCondensed,
                    color = secondaryInk,
                    isVisible = isVisible,
                    modifier =
                        Modifier.padding(top = LINE_GAP).thenIf(
                            uiState.accessibilityUiState.stateDescription.contains(secondary)
                        ) {
                            // Screen readers already hear it as the tile's state.
                            Modifier.clearAndSetSemantics {}
                        },
                )
            }
        }
        if (toggleClick != null) {
            ToggleKey(
                iconProvider = iconProvider,
                iconColor = iconColor(state, lit, palette),
                palette = palette,
                plate = keyPlate,
                toggleClick = toggleClick,
                onLongClick = onLongClick,
                accessibilityUiState = uiState.accessibilityUiState,
            )
        }
    }
}

/**
 * The toggle of a tile with a toggle of its own (Internet, Bluetooth): a 48 dp key at the tile's
 * end, tonal on an unlit tile and outlined on a lit one, with the upstream toggle's click, long
 * click and switch semantics. The tile's field draws its plate ([TileKeyPlate]).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ToggleKey(
    iconProvider: Context.() -> Icon,
    iconColor: Color,
    palette: TallyTilePalette,
    plate: TileKeyPlate,
    toggleClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    accessibilityUiState: AccessibilityUiState,
) {
    val shape = RoundedCornerShape(dimensionResource(TallyR.dimen.tally_radius_s))
    val longPressLabel = CommonTileDefaults.longPressLabelSettings().takeIf { onLongClick != null }
    Box(
        modifier =
            Modifier.size(dimensionResource(TallyR.dimen.tally_key_height))
                .onPlaced(plate::onKeyPlaced)
                .borderOnFocus(color = palette.focus, cornerSize = shape.topEnd)
                .clip(shape)
                .combinedClickable(
                    onClick = toggleClick,
                    onLongClick = onLongClick,
                    onLongClickLabel = longPressLabel,
                    hapticFeedbackEnabled = false, // Haptics are handled by the tile.
                )
                .semantics {
                    contentDescription = accessibilityUiState.contentDescription
                    stateDescription = accessibilityUiState.stateDescription
                    accessibilityUiState.toggleableState?.let { toggleableState = it }
                    role = Role.Switch
                }
                .sysuiResTag(TOGGLE_TAG),
        contentAlignment = Alignment.Center,
    ) {
        DisposableEffect(plate) { onDispose { plate.onKeyGone() } }
        SmallTileContent(
            iconProvider = iconProvider,
            color = iconColor,
            size = { LARGE_ICON_SIZE },
            animateColor = false,
        )
    }
}

/**
 * One line of a tile's words. Fit before truncate: a label that does not fit is set in Semi
 * Condensed; one that still does not fit fades at its end and scrolls once, as stock's labels do.
 */
@Composable
private fun TileText(
    text: String,
    style: TextStyle,
    condensedStyle: TextStyle,
    color: Color,
    isVisible: () -> Boolean,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier) {
        val measurer = rememberTextMeasurer()
        val maxWidth = constraints.maxWidth
        fun width(textStyle: TextStyle) =
            measurer
                .measure(AnnotatedString(text), textStyle, softWrap = false, maxLines = 1)
                .size
                .width
        val fits = remember(text, style, maxWidth) { width(style) <= maxWidth }
        val overflows =
            remember(text, condensedStyle, maxWidth, fits) {
                !fits && width(condensedStyle) > maxWidth
            }
        BasicText(
            text = text,
            color = { color },
            style = if (fits) style else condensedStyle,
            maxLines = 1,
            modifier =
                Modifier.fillMaxWidth()
                    .thenIf(overflows) { Modifier.fadedEnd() }
                    .basicMarquee(
                        iterations = if (isVisible()) MARQUEE_ITERATIONS else 0,
                        initialDelayMillis = MARQUEE_DELAY_MILLIS,
                    ),
        )
    }
}

/** Fades a line's last 32 dp, as stock's tile labels do when they are cut off. */
private fun Modifier.fadedEnd(): Modifier =
    graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            val edge = CommonTileDefaults.TileLabelBlurWidth.toPx()
            val rtl = layoutDirection == LayoutDirection.Rtl
            val start = if (rtl) 0f else size.width - edge
            drawRect(
                brush =
                    Brush.horizontalGradient(
                        colors =
                            if (rtl) listOf(Color.Transparent, Color.Black)
                            else listOf(Color.Black, Color.Transparent),
                        startX = start,
                        endX = start + edge,
                    ),
                topLeft = Offset(start, 0f),
                size = Size(edge, size.height),
                blendMode = BlendMode.DstIn,
            )
        }

/** A tile with words' two lines: its label and its state, each also in the condensed face. */
@Immutable
private class LargeTileLines(
    val label: TextStyle,
    val labelCondensed: TextStyle,
    val caption: TextStyle,
    val captionCondensed: TextStyle,
)

/**
 * The lines of a tile with words at the type's line heights where both fit the tile. Under
 * Android's non-linear font scaling a line height keeps its proportion to its text, so at 200 %
 * text the two lines would need 71 dp of the tile's 64 and the state would be cut at its foot.
 * Tiles do not grow, so there the lines take their type's own height: the words keep their size and
 * only the space between the lines tightens.
 */
@Composable
private fun rememberLargeTileLines(): LargeTileLines {
    val label = tallyTextStyle(TallyR.style.TextAppearance_Tally_Label, LABEL_LINE)
    val labelCondensed =
        tallyTextStyle(
            TallyR.style.TextAppearance_Tally_Label,
            LABEL_LINE,
            TallyR.string.tally_font_family_condensed,
        )
    val caption = tallyTextStyle(TallyR.style.TextAppearance_Tally_Caption, CAPTION_LINE)
    val captionCondensed =
        tallyTextStyle(
            TallyR.style.TextAppearance_Tally_Caption,
            CAPTION_LINE,
            TallyR.string.tally_font_family_condensed,
        )
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    // The tile's height, which CommonTileDefaults.TileHeight reads too.
    val tileHeight =
        with(density) { dimensionResource(R.dimen.common_tile_default_tile_height).roundToPx() }
    val gap = with(density) { LINE_GAP.roundToPx() }
    return remember(label, labelCondensed, caption, captionCondensed, measurer, tileHeight, gap) {
        fun lineHeight(style: TextStyle) =
            measurer.measure(LINE_SAMPLE, style, softWrap = false, maxLines = 1).size.height
        if (lineHeight(label) + gap + lineHeight(caption) <= tileHeight) {
            LargeTileLines(label, labelCondensed, caption, captionCondensed)
        } else {
            LargeTileLines(
                label.withTypeLineHeight(),
                labelCondensed.withTypeLineHeight(),
                caption.withTypeLineHeight(),
                captionCondensed.withTypeLineHeight(),
            )
        }
    }
}

private fun TextStyle.withTypeLineHeight(): TextStyle =
    copy(lineHeight = TextUnit.Unspecified, lineHeightStyle = null)

/**
 * The tile's state in words: its own secondary label, else stock's translated word for its state
 * ("Off", "On", "Unavailable"), as lamps in Tally always carry words.
 */
@Composable
private fun secondaryText(uiState: TileUiState, spec: TileSpec): String {
    if (uiState.secondaryLabel.isNotEmpty()) return uiState.secondaryLabel
    val resources = LocalContext.current.resources
    val configuration = LocalConfiguration.current
    return remember(resources, configuration, spec, uiState.visualState) {
        resources
            .getStringArray(SubtitleArrayMapping.getSubtitleId(spec.spec))
            .getOrNull(uiState.visualState) ?: ""
    }
}

private fun iconColor(state: TallyLampState, lit: Boolean, palette: TallyTilePalette): Color =
    when {
        lit -> palette.onLamp
        state == TallyLampState.UNAVAILABLE -> palette.muted
        else -> palette.ink
    }

private fun android.content.res.Resources.keycapRadiusPercent(): Float =
    getFraction(TallyR.fraction.tally_keycap_radius, PERCENT, PERCENT)

/** The prototype's tile geometry (app.css `.tile-s`, `.tile-l`), in dp. */
private val KEYCAP_LAMP_INSET = 7.dp
private val KEYCAP_ICON_SHIFT = 3.dp
private val LARGE_END_PADDING = 10.dp
private val LARGE_ICON_SIZE = 20.dp
private val LINE_GAP = 2.dp
private val LABEL_LINE = TallyR.dimen.tally_type_label_line_height
private val CAPTION_LINE = TallyR.dimen.tally_type_caption_line_height
private const val LINE_SAMPLE = "Hg"
private const val LIGHT_DELAY_MILLIS = 50L
private const val WIPE_THRESHOLD = 0.002f
private const val PERCENT = 100
private const val MARQUEE_ITERATIONS = 1
private const val MARQUEE_DELAY_MILLIS = 2000
private const val TOGGLE_TAG = "qs_tile_toggle_target"
