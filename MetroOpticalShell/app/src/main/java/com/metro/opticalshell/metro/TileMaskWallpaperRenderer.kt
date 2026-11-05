package com.metro.opticalshell.metro

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The ONE decoded Start Background, plus its placement in the shared canvas
 * space (TZ §3.2).
 *
 * One instance is created per start surface and read by every tile. There is
 * deliberately no per-tile bitmap, no per-tile shader and no per-tile
 * allocation: tiles differ only by the translation they draw it at.
 */
class SharedStartBackground(
    val bitmap: ImageBitmap?,
    val placement: ImageRect?,
    val dimFraction: Float,
    val mode: TileBackgroundMode,
) {
    val isActive: Boolean
        get() = bitmap != null && placement != null &&
            TileBackgroundMath.usesSharedImage(mode)

    companion object {
        val Inactive = SharedStartBackground(
            bitmap = null,
            placement = null,
            dimFraction = 0f,
            mode = TileBackgroundMode.SOLID_ACCENT,
        )
    }
}

val LocalStartBackground = staticCompositionLocalOf { SharedStartBackground.Inactive }

/**
 * Decodes the wallpaper once and computes its placement for the given canvas.
 *
 * Decoding happens off the main thread and is keyed on the URI alone, so
 * scrolling, re-packing or changing the zoom never re-decodes the file. Only
 * the cheap [ImageRect] placement is recomputed when the canvas or transform
 * changes.
 */
@Composable
fun rememberSharedStartBackground(
    background: StartBackground,
    mode: TileBackgroundMode,
    presentationName: String,
    canvasWidthPx: Float,
    canvasHeightPx: Float,
): SharedStartBackground {
    val context = LocalContext.current
    var decoded by remember(background.imageUri) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(background.imageUri) {
        decoded = background.imageUri?.let { uri ->
            withContext(Dispatchers.IO) { decodeWallpaper(context, uri) }
        }
    }

    val bmp = decoded
    if (bmp == null || !TileBackgroundMath.usesSharedImage(mode) ||
        canvasWidthPx <= 0f || canvasHeightPx <= 0f
    ) {
        return SharedStartBackground(null, null, 0f, mode)
    }

    val placement = remember(
        bmp,
        canvasWidthPx,
        canvasHeightPx,
        presentationName,
        background,
    ) {
        TileBackgroundMath.imagePlacement(
            canvasWidthPx = canvasWidthPx,
            canvasHeightPx = canvasHeightPx,
            imageWidthPx = bmp.width,
            imageHeightPx = bmp.height,
            transform = background.transformFor(presentationName),
        )
    }

    return SharedStartBackground(bmp, placement, background.clamped().dimFraction, mode)
}

/**
 * Decodes at a bounded size. A 108MP wallpaper would otherwise be decoded at
 * full resolution for a surface a few hundred pixels wide.
 */
private fun decodeWallpaper(context: Context, uriString: String): ImageBitmap? = runCatching {
    val uri = Uri.parse(uriString)

    // Pass 1: read the bounds only.
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, bounds)
    }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null

    // Pass 2: decode subsampled.
    var sample = 1
    while (
        bounds.outWidth / (sample * 2) >= MAX_WALLPAPER_EDGE_PX ||
        bounds.outHeight / (sample * 2) >= MAX_WALLPAPER_EDGE_PX
    ) {
        sample *= 2
    }
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    context.contentResolver.openInputStream(uri)?.use { stream ->
        BitmapFactory.decodeStream(stream, null, opts)?.asImageBitmap()
    }
}.getOrNull()

private const val MAX_WALLPAPER_EDGE_PX = 2048

/**
 * Draws the shared Start Background inside one tile (TZ §3.2).
 *
 * [tileLeftInCanvasPx] / [tileTopInCanvasPx] are the tile's position in the
 * SHARED canvas space, which is what makes the picture continuous across
 * neighbouring tiles. Drawing the wallpaper independently per tile — the
 * mistake the TZ calls out — would repeat or stretch it in every cell.
 *
 * The draw is clipped to the tile, so the gaps between tiles stay black.
 */
fun Modifier.wp81TileMask(
    shared: SharedStartBackground,
    tileLeftInCanvasPx: Float,
    tileTopInCanvasPx: Float,
): Modifier {
    if (!shared.isActive) return this
    val bitmap = shared.bitmap ?: return this
    val placement = shared.placement ?: return this

    return this.drawBehind {
        val offset = TileBackgroundMath.tileDrawOffset(
            placement = placement,
            tileLeftInCanvasPx = tileLeftInCanvasPx,
            tileTopInCanvasPx = tileTopInCanvasPx,
        )
        // clipRect confines the draw to this tile; the inter-tile gaps are never
        // painted, which is what keeps them black.
        clipRect(left = 0f, top = 0f, right = size.width, bottom = size.height) {
            translate(left = offset.x, top = offset.y) {
                drawSharedImage(bitmap, placement)
            }
        }
        if (shared.dimFraction > 0f) {
            drawRect(
                color = Color.Black.copy(alpha = shared.dimFraction),
                size = Size(size.width, size.height),
            )
        }
    }
}

private fun DrawScope.drawSharedImage(bitmap: ImageBitmap, placement: ImageRect) {
    drawImage(
        image = bitmap,
        dstOffset = androidx.compose.ui.unit.IntOffset.Zero,
        dstSize = androidx.compose.ui.unit.IntSize(
            placement.width.toInt().coerceAtLeast(1),
            placement.height.toInt().coerceAtLeast(1),
        ),
    )
}

/**
 * The scrim behind a tile label, for busy photographs (TZ §3.2).
 *
 * Only drawn in the image mask mode, and only when the user left it on: over a
 * solid accent it would be pointless, and over a calm photograph it is noise.
 */
fun Modifier.tileLabelScrim(active: Boolean, heightFraction: Float = 0.38f): Modifier {
    if (!active) return this
    return this.drawBehind {
        val h = size.height * heightFraction.coerceIn(0.1f, 1f)
        drawRect(
            color = Color.Black.copy(alpha = 0.45f),
            topLeft = Offset(0f, size.height - h),
            size = Size(size.width, h),
        )
    }
}
