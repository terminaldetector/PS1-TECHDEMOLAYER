package com.metro.opticalshell.metro

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.metro.opticalshell.ui.MetroMetrics
import kotlin.math.roundToInt

/**
 * The one tile surface, used by MOBILE_PORTRAIT and EXPANDED_PORTRAIT alike
 * (TZ 0, 2.2, A05).
 *
 * The SAME composable renders both portrait presentations. They differ only in
 * the [columns] they are given. That is what makes "Expanded portrait is still
 * Metro tiles" structurally true rather than a promise.
 *
 * Layout comes from [TilePackingAlgorithm], so what is persisted is an order,
 * never a pixel position, and a rotation cannot scramble the start screen.
 */
@Composable
fun MetroTileGrid(
    tiles: List<TileSpec>,
    columns: Int,
    editMode: Boolean,
    modifier: Modifier = Modifier,
    tileScale: Float = 1f,
    onTileClick: (TileSpec) -> Unit = {},
    onTileLongPress: (TileSpec) -> Unit = {},
    onReorder: (tileId: String, targetIndex: Int) -> Unit = { _, _ -> },
    onResize: (tileId: String, size: TileSize) -> Unit = { _, _ -> },
    onUnpin: (tileId: String) -> Unit = {},
    tileContent: @Composable (PlacedTile, Dp) -> Unit,
) {
    val density = LocalDensity.current
    val gap = MetroMetrics.TileGapDp
    val pad = MetroMetrics.SurfacePaddingDp

    // Packing is pure and cheap, but it is still memoised on its real inputs so
    // that scrolling does not repack every frame (TZ 12).
    val placed = remember(tiles, columns) { TilePackingAlgorithm.pack(tiles, columns) }
    val rows = remember(placed) { TilePackingAlgorithm.rowsUsed(placed) }

    BoxWithConstraints(modifier = modifier.verticalScroll(rememberScrollState())) {
        val availableWidth = maxWidth - pad * 2
        // One grid unit, derived from the window. Never a hard pixel size (TZ 3.2).
        val unit: Dp = ((availableWidth - gap * (columns - 1)) / columns)
            .coerceAtLeast(16.dp)

        fun spanDp(span: Int): Dp = unit * span + gap * (span - 1)

        val surfaceHeight = spanDp(rows.coerceAtLeast(1)) + pad * 2

        Box(
            modifier = Modifier
                .size(width = maxWidth, height = surfaceHeight),
        ) {
            // Which tile is being dragged, and by how much.
            var draggingId by remember { mutableStateOf<String?>(null) }
            var dragOffset by remember { mutableStateOf(IntOffset.Zero) }

            val unitPx = with(density) { unit.toPx() }
            val gapPx = with(density) { gap.toPx() }
            val padPx = with(density) { pad.toPx() }

            placed.forEachIndexed { index, tile ->
                val isDragging = draggingId == tile.spec.tileId
                val baseX = padPx + tile.col * (unitPx + gapPx)
                val baseY = padPx + tile.row * (unitPx + gapPx)

                Box(
                    modifier = Modifier
                        .zIndex(if (isDragging) 1f else 0f)
                        .offset {
                            IntOffset(
                                x = baseX.roundToInt() + if (isDragging) dragOffset.x else 0,
                                y = baseY.roundToInt() + if (isDragging) dragOffset.y else 0,
                            )
                        }
                        .size(width = spanDp(tile.w), height = spanDp(tile.h))
                        .graphicsLayer {
                            // WP8 lifted the dragged tile slightly. No blur (TZ 3.1).
                            if (isDragging) {
                                scaleX = 1.06f
                                scaleY = 1.06f
                                alpha = 0.9f
                            }
                        }
                        .pointerInput(tile.spec.tileId, editMode, columns) {
                            detectTapGestures(
                                onTap = { onTileClick(tile.spec) },
                                onLongPress = { onTileLongPress(tile.spec) },
                            )
                        }
                        .then(
                            if (!editMode) Modifier else Modifier.pointerInput(
                                tile.spec.tileId,
                                columns,
                                placed,
                            ) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = {
                                        draggingId = tile.spec.tileId
                                        dragOffset = IntOffset.Zero
                                    },
                                    onDrag = { _, delta ->
                                        dragOffset = IntOffset(
                                            dragOffset.x + delta.x.roundToInt(),
                                            dragOffset.y + delta.y.roundToInt(),
                                        )
                                    },
                                    onDragCancel = {
                                        draggingId = null
                                        dragOffset = IntOffset.Zero
                                    },
                                    onDragEnd = {
                                        // Translate the drop point into a flow index.
                                        val centreX = baseX + dragOffset.x + spanPx(tile.w, unitPx, gapPx) / 2
                                        val centreY = baseY + dragOffset.y + spanPx(tile.h, unitPx, gapPx) / 2
                                        val target = dropIndexFor(
                                            placed = placed,
                                            xPx = centreX,
                                            yPx = centreY,
                                            padPx = padPx,
                                            unitPx = unitPx,
                                            gapPx = gapPx,
                                            columns = columns,
                                            fallbackIndex = index,
                                        )
                                        draggingId = null
                                        dragOffset = IntOffset.Zero
                                        if (target != index) onReorder(tile.spec.tileId, target)
                                    },
                                )
                            },
                        ),
                ) {
                    tileContent(tile, unit)

                    if (editMode) {
                        TileEditOverlay(
                            tile = tile,
                            onResize = { onResize(tile.spec.tileId, it) },
                            onUnpin = { onUnpin(tile.spec.tileId) },
                        )
                    }
                }
            }
        }
    }
}

private fun spanPx(span: Int, unitPx: Float, gapPx: Float): Float =
    unitPx * span + gapPx * (span - 1)

/**
 * Maps a drop position to an index in the tile flow.
 *
 * Rule: find the tile whose packed cell block contains the drop point and take
 * its index; if the drop lands on empty space, use the last tile that starts
 * before the drop point in reading order. Deterministic, so a drag always lands
 * where the user aimed (TZ 3.3 "no unpredictable jumps").
 */
internal fun dropIndexFor(
    placed: List<PlacedTile>,
    xPx: Float,
    yPx: Float,
    padPx: Float,
    unitPx: Float,
    gapPx: Float,
    columns: Int,
    fallbackIndex: Int,
): Int {
    if (placed.isEmpty()) return 0
    val pitch = unitPx + gapPx
    if (pitch <= 0f) return fallbackIndex

    val col = (((xPx - padPx) / pitch).toInt()).coerceIn(0, columns - 1)
    val row = (((yPx - padPx) / pitch).toInt()).coerceAtLeast(0)

    placed.forEachIndexed { i, t ->
        if (col >= t.col && col < t.endCol && row >= t.row && row < t.endRow) return i
    }

    // Empty space: the last tile in reading order that begins before the point.
    var best = -1
    placed.forEachIndexed { i, t ->
        if (t.row < row || (t.row == row && t.col <= col)) best = i
    }
    return if (best >= 0) best else fallbackIndex
}
