package com.metro.opticalshell.metro

import kotlinx.serialization.Serializable

/**
 * The five tile background modes of TZ §3.2.
 *
 * The distinction that matters, and the one the TZ calls out as the common
 * mistake: [WP81_IMAGE_MASK] is **not** a translucent coloured square laid over
 * a wallpaper. In Windows Phone 8.1 there is ONE Start Background image, and
 * each tile whose asset is transparent reveals the part of that image which
 * sits behind it. Adjacent tiles therefore show a continuous picture, while the
 * gaps between them stay black.
 *
 * Primary sources (quoted in docs/OP02_UPSTREAM_RESEARCH.md):
 *  - "a background image displayed behind the tiles [...] the app's tile needs
 *    to have a transparent background" — Windows Developer Blog, 19 May 2014.
 *  - the image has ONE user transform: "you'll then be asked to crop it [...]
 *    pinch to zoom in or out and move the image around" — Windows Blog,
 *    29 Apr 2014.
 *
 * [TRANSLUCENT_ACCENT] is kept as a separate, honestly-labelled mode precisely
 * so that it is not mistaken for the historical effect.
 */
enum class TileBackgroundMode {
    /** Opaque Metro tiles. The WP8 classic look. */
    SOLID_ACCENT,

    /** One shared Start Background, revealed inside transparent tiles. */
    WP81_IMAGE_MASK,

    /** A user-chosen semi-transparent accent fill. Not a historical imitation. */
    TRANSLUCENT_ACCENT,

    /** A per-tile image. Distinct from the shared Start Background. */
    PHOTO_TILE,

    /** Maximum contrast for a lens or AMOLED: pure black, no imagery. */
    PURE_BLACK_OPTICAL,
}

/** Visual presets of TZ §3.2. Chronology is not blurred (TZ §3.7). */
enum class VisualPreset {
    /** WP8.0: opaque accent tiles, no Start Background. */
    WP8_CLASSIC,

    /** WP8.1: transparent tiles over a shared Start Background. */
    WP81_TRANSPARENT,

    /** Our own extension for the Expanded environment. Not a WP release. */
    METRO_EXPANDED,
    ;

    val defaultBackgroundMode: TileBackgroundMode
        get() = when (this) {
            WP8_CLASSIC -> TileBackgroundMode.SOLID_ACCENT
            WP81_TRANSPARENT -> TileBackgroundMode.WP81_IMAGE_MASK
            METRO_EXPANDED -> TileBackgroundMode.SOLID_ACCENT
        }
}

/**
 * Whether the shared image is anchored to the scrolling content or to the
 * viewport (TZ §3.2: "отдельная пользовательская настройка фоновой привязки").
 */
enum class BackgroundAnchor {
    /** The image scrolls with the tiles. Picture stays with its tiles. */
    CONTENT,

    /** The image stays put while tiles scroll over it, parallax-free. */
    VIEWPORT,
}

/**
 * The single Start Background transform (TZ §3.2: "один wallpaper transform").
 *
 * All transparent tiles sample through this one transform, which is what makes
 * the picture continuous across them.
 *
 * @param scale user zoom, 1.0 meaning "fit the canvas"
 * @param offsetXFraction pan, as a fraction of the canvas width
 * @param offsetYFraction pan, as a fraction of the canvas height
 */
@Serializable
data class StartBackground(
    /** Content URI or null when no wallpaper is chosen. */
    val imageUri: String? = null,
    val scale: Float = 1.0f,
    val offsetXFraction: Float = 0f,
    val offsetYFraction: Float = 0f,
    val anchor: BackgroundAnchor = BackgroundAnchor.CONTENT,
    /** Dim the image so white tile labels stay legible (TZ §3.2). */
    val dimFraction: Float = 0.15f,
    /** A contrast scrim behind tile labels, for busy photographs. */
    val labelScrim: Boolean = true,
    /** Per-presentation transforms, since portrait and landscape crop differently. */
    val perPresentation: Map<String, StartBackgroundTransform> = emptyMap(),
) {
    fun clamped(): StartBackground = copy(
        scale = scale.coerceIn(MIN_SCALE, MAX_SCALE),
        offsetXFraction = offsetXFraction.coerceIn(-1f, 1f),
        offsetYFraction = offsetYFraction.coerceIn(-1f, 1f),
        dimFraction = dimFraction.coerceIn(0f, 0.8f),
    )

    /** The transform for a presentation, falling back to the shared one. */
    fun transformFor(presentationName: String): StartBackgroundTransform =
        perPresentation[presentationName] ?: StartBackgroundTransform(
            scale = scale,
            offsetXFraction = offsetXFraction,
            offsetYFraction = offsetYFraction,
        )

    companion object {
        const val MIN_SCALE = 1.0f
        const val MAX_SCALE = 3.0f
    }
}

@Serializable
data class StartBackgroundTransform(
    val scale: Float = 1.0f,
    val offsetXFraction: Float = 0f,
    val offsetYFraction: Float = 0f,
) {
    fun clamped() = copy(
        scale = scale.coerceIn(StartBackground.MIN_SCALE, StartBackground.MAX_SCALE),
        offsetXFraction = offsetXFraction.coerceIn(-1f, 1f),
        offsetYFraction = offsetYFraction.coerceIn(-1f, 1f),
    )
}

/**
 * The geometry of the WP8.1 mask effect, as pure arithmetic (TZ §3.2).
 *
 * Why this is a separate, pure object: the one thing that makes the effect read
 * correctly is that every tile samples ONE image in ONE coordinate space. That
 * is a property of the arithmetic, not of the drawing code, so it is computed
 * here and pinned by TileBackgroundMathTest — including the invariant that two
 * adjacent tiles produce exactly adjacent source rectangles with no seam.
 */
object TileBackgroundMath {

    /**
     * Where the shared image is placed, in the shared canvas coordinate space.
     *
     * The image is scaled to COVER the canvas (so no letterboxing shows through
     * a tile), then the user's zoom and pan are applied on top.
     *
     * @return the image's destination rectangle in canvas coordinates
     */
    fun imagePlacement(
        canvasWidthPx: Float,
        canvasHeightPx: Float,
        imageWidthPx: Int,
        imageHeightPx: Int,
        transform: StartBackgroundTransform,
    ): ImageRect {
        require(canvasWidthPx > 0f && canvasHeightPx > 0f) { "canvas must be positive" }
        require(imageWidthPx > 0 && imageHeightPx > 0) { "image must be positive" }

        val t = transform.clamped()

        // Cover: the larger of the two ratios, so neither axis leaves a gap.
        val coverScale = maxOf(
            canvasWidthPx / imageWidthPx.toFloat(),
            canvasHeightPx / imageHeightPx.toFloat(),
        )
        val effective = coverScale * t.scale

        val drawW = imageWidthPx * effective
        val drawH = imageHeightPx * effective

        // Centre it, then pan. Pan is a fraction of the canvas, not the image,
        // so the control feels the same whatever picture is loaded.
        val left = (canvasWidthPx - drawW) / 2f + t.offsetXFraction * canvasWidthPx
        val top = (canvasHeightPx - drawH) / 2f + t.offsetYFraction * canvasHeightPx

        return ImageRect(left = left, top = top, width = drawW, height = drawH)
    }

    /**
     * The translation to apply when drawing the shared image *inside one tile*.
     *
     * The tile's canvas acts as its own coordinate origin, so to make the image
     * line up with its neighbours we shift it by minus the tile's position in
     * the shared space, plus the image's own placement.
     *
     * This is the whole trick, and the reason no per-tile bitmap is allocated:
     * every tile draws the same bitmap, only translated differently
     * (TZ §3.2: "не допускать GPU-аллоцирования bitmap на каждую плитку").
     */
    fun tileDrawOffset(
        placement: ImageRect,
        tileLeftInCanvasPx: Float,
        tileTopInCanvasPx: Float,
    ): Offset2 = Offset2(
        x = placement.left - tileLeftInCanvasPx,
        y = placement.top - tileTopInCanvasPx,
    )

    /**
     * The region of the image a tile reveals, in image pixel coordinates.
     *
     * Used by tests to prove continuity, and by the crop preview to show what
     * will actually be visible.
     */
    fun sourceRectForTile(
        placement: ImageRect,
        imageWidthPx: Int,
        imageHeightPx: Int,
        tileLeftInCanvasPx: Float,
        tileTopInCanvasPx: Float,
        tileWidthPx: Float,
        tileHeightPx: Float,
    ): ImageRect {
        val sx = imageWidthPx / placement.width
        val sy = imageHeightPx / placement.height
        return ImageRect(
            left = (tileLeftInCanvasPx - placement.left) * sx,
            top = (tileTopInCanvasPx - placement.top) * sy,
            width = tileWidthPx * sx,
            height = tileHeightPx * sy,
        )
    }

    /** Whether a mode draws the shared image at all. */
    fun usesSharedImage(mode: TileBackgroundMode): Boolean =
        mode == TileBackgroundMode.WP81_IMAGE_MASK

    /** Whether a mode paints an opaque accent fill. */
    fun usesOpaqueAccent(mode: TileBackgroundMode): Boolean =
        mode == TileBackgroundMode.SOLID_ACCENT

    /**
     * The alpha for a tile's accent fill in the given mode.
     *
     * In WP81_IMAGE_MASK the tile body must be fully clear so the image shows,
     * which is exactly what distinguishes it from TRANSLUCENT_ACCENT.
     */
    fun accentAlpha(mode: TileBackgroundMode, translucentAlpha: Float = 0.45f): Float =
        when (mode) {
            TileBackgroundMode.SOLID_ACCENT -> 1f
            TileBackgroundMode.WP81_IMAGE_MASK -> 0f
            TileBackgroundMode.TRANSLUCENT_ACCENT -> translucentAlpha.coerceIn(0f, 1f)
            TileBackgroundMode.PHOTO_TILE -> 0f
            TileBackgroundMode.PURE_BLACK_OPTICAL -> 0f
        }
}

/** A rectangle in float pixels. Kept framework-free so it is unit-testable. */
data class ImageRect(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
) {
    val right: Float get() = left + width
    val bottom: Float get() = top + height
}

/** A framework-free 2D offset. */
data class Offset2(val x: Float, val y: Float)
