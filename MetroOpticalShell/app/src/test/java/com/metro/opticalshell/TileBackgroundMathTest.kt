package com.metro.opticalshell

import com.metro.opticalshell.metro.StartBackground
import com.metro.opticalshell.metro.StartBackgroundTransform
import com.metro.opticalshell.metro.TileBackgroundMath
import com.metro.opticalshell.metro.TileBackgroundMode
import com.metro.opticalshell.metro.VisualPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the WP8.1 Start Background geometry (TZ §3.2).
 *
 * The headline invariant is CONTINUITY: two adjacent tiles must reveal exactly
 * adjacent parts of one image, with no seam, no repeat and no stretch. That is
 * the whole difference between the historical effect and a translucent square.
 */
class TileBackgroundMathTest {

    private val identity = StartBackgroundTransform()

    @Test
    fun `image covers the canvas so no gap shows through a tile`() {
        // A 4:3 image on a tall phone canvas must be scaled to cover, which
        // means overflowing horizontally rather than letterboxing.
        val p = TileBackgroundMath.imagePlacement(
            canvasWidthPx = 1080f,
            canvasHeightPx = 2400f,
            imageWidthPx = 4000,
            imageHeightPx = 3000,
            transform = identity,
        )
        assertTrue("must cover width", p.width >= 1080f)
        assertTrue("must cover height", p.height >= 2400f)
        // Cover, not stretch: aspect ratio preserved.
        assertEquals(4000f / 3000f, p.width / p.height, 0.001f)
    }

    @Test
    fun `cover placement is centred`() {
        val p = TileBackgroundMath.imagePlacement(1000f, 1000f, 2000, 1000, identity)
        // Square canvas, 2:1 image -> scaled to height 1000, width 2000,
        // overflowing 500 each side.
        assertEquals(2000f, p.width, 0.1f)
        assertEquals(1000f, p.height, 0.1f)
        assertEquals(-500f, p.left, 0.1f)
        assertEquals(0f, p.top, 0.1f)
    }

    @Test
    fun `adjacent tiles reveal adjacent image regions with no seam`() {
        val canvasW = 1080f
        val canvasH = 2400f
        val placement = TileBackgroundMath.imagePlacement(
            canvasW, canvasH, 2000, 3000, identity,
        )

        // Two tiles side by side: 200px wide each, touching at x=200.
        val left = TileBackgroundMath.sourceRectForTile(
            placement, 2000, 3000,
            tileLeftInCanvasPx = 0f, tileTopInCanvasPx = 300f,
            tileWidthPx = 200f, tileHeightPx = 200f,
        )
        val right = TileBackgroundMath.sourceRectForTile(
            placement, 2000, 3000,
            tileLeftInCanvasPx = 200f, tileTopInCanvasPx = 300f,
            tileWidthPx = 200f, tileHeightPx = 200f,
        )

        // The right tile's source must begin exactly where the left one's ends.
        assertEquals(
            "a seam would appear between adjacent tiles",
            left.right, right.left, 0.01f,
        )
        // Same row -> same vertical slice.
        assertEquals(left.top, right.top, 0.01f)
        assertEquals(left.height, right.height, 0.01f)
    }

    @Test
    fun `vertically adjacent tiles are also continuous`() {
        val placement = TileBackgroundMath.imagePlacement(1080f, 2400f, 2000, 3000, identity)
        val top = TileBackgroundMath.sourceRectForTile(
            placement, 2000, 3000, 100f, 0f, 200f, 200f,
        )
        val bottom = TileBackgroundMath.sourceRectForTile(
            placement, 2000, 3000, 100f, 200f, 200f, 200f,
        )
        assertEquals(top.bottom, bottom.top, 0.01f)
        assertEquals(top.left, bottom.left, 0.01f)
    }

    @Test
    fun `draw offset is the same for every tile up to its own position`() {
        // This is what proves ONE bitmap is reused: the only per-tile difference
        // is a translation.
        val placement = TileBackgroundMath.imagePlacement(1080f, 2400f, 2000, 3000, identity)
        val a = TileBackgroundMath.tileDrawOffset(placement, 0f, 0f)
        val b = TileBackgroundMath.tileDrawOffset(placement, 240f, 480f)
        assertEquals(a.x - 240f, b.x, 0.01f)
        assertEquals(a.y - 480f, b.y, 0.01f)
    }

    @Test
    fun `zoom enlarges the image without changing its aspect`() {
        val one = TileBackgroundMath.imagePlacement(1000f, 1000f, 1000, 1000, identity)
        val two = TileBackgroundMath.imagePlacement(
            1000f, 1000f, 1000, 1000, StartBackgroundTransform(scale = 2f),
        )
        assertEquals(one.width * 2f, two.width, 0.1f)
        assertEquals(one.height * 2f, two.height, 0.1f)
        assertEquals(two.width / two.height, one.width / one.height, 0.001f)
    }

    @Test
    fun `pan moves the image by a fraction of the canvas`() {
        val base = TileBackgroundMath.imagePlacement(1000f, 2000f, 1000, 2000, identity)
        val panned = TileBackgroundMath.imagePlacement(
            1000f, 2000f, 1000, 2000,
            StartBackgroundTransform(offsetXFraction = 0.25f, offsetYFraction = -0.1f),
        )
        assertEquals(base.left + 250f, panned.left, 0.1f)
        assertEquals(base.top - 200f, panned.top, 0.1f)
    }

    @Test
    fun `transform is clamped to the documented range`() {
        val p = TileBackgroundMath.imagePlacement(
            1000f, 1000f, 1000, 1000,
            StartBackgroundTransform(scale = 99f, offsetXFraction = 50f),
        )
        // Scale clamps to MAX_SCALE, pan to 1.0 of the canvas.
        assertEquals(1000f * StartBackground.MAX_SCALE, p.width, 0.1f)
        assertTrue(p.left <= 1000f)
    }

    @Test
    fun `mask mode clears the tile body and translucent mode does not`() {
        // This is the distinction TZ 3.2 says is most often got wrong.
        assertEquals(0f, TileBackgroundMath.accentAlpha(TileBackgroundMode.WP81_IMAGE_MASK), 0f)
        assertEquals(1f, TileBackgroundMath.accentAlpha(TileBackgroundMode.SOLID_ACCENT), 0f)
        assertEquals(
            0.45f,
            TileBackgroundMath.accentAlpha(TileBackgroundMode.TRANSLUCENT_ACCENT, 0.45f),
            0.001f,
        )
        assertEquals(0f, TileBackgroundMath.accentAlpha(TileBackgroundMode.PURE_BLACK_OPTICAL), 0f)
    }

    @Test
    fun `only the mask mode uses the shared image`() {
        assertTrue(TileBackgroundMath.usesSharedImage(TileBackgroundMode.WP81_IMAGE_MASK))
        TileBackgroundMode.entries
            .filterNot { it == TileBackgroundMode.WP81_IMAGE_MASK }
            .forEach { assertFalse(it.name, TileBackgroundMath.usesSharedImage(it)) }
    }

    @Test
    fun `presets map to the historically correct mode`() {
        // TZ 3.7: do not blur the chronology. WP8.0 had no Start Background.
        assertEquals(
            TileBackgroundMode.SOLID_ACCENT,
            VisualPreset.WP8_CLASSIC.defaultBackgroundMode,
        )
        assertEquals(
            TileBackgroundMode.WP81_IMAGE_MASK,
            VisualPreset.WP81_TRANSPARENT.defaultBackgroundMode,
        )
    }

    @Test
    fun `per-presentation transforms fall back to the shared one`() {
        val bg = StartBackground(scale = 1.5f, offsetXFraction = 0.2f)
        val fallback = bg.transformFor("MOBILE_PORTRAIT")
        assertEquals(1.5f, fallback.scale, 0.001f)
        assertEquals(0.2f, fallback.offsetXFraction, 0.001f)

        val withOverride = bg.copy(
            perPresentation = mapOf("EXPANDED_LANDSCAPE" to StartBackgroundTransform(scale = 2.5f)),
        )
        assertEquals(2.5f, withOverride.transformFor("EXPANDED_LANDSCAPE").scale, 0.001f)
        assertEquals(1.5f, withOverride.transformFor("MOBILE_PORTRAIT").scale, 0.001f)
    }

    @Test
    fun `background clamping keeps dim and scale sane`() {
        val c = StartBackground(scale = 99f, dimFraction = 5f, offsetXFraction = -9f).clamped()
        assertEquals(StartBackground.MAX_SCALE, c.scale, 0.001f)
        assertTrue(c.dimFraction in 0f..0.8f)
        assertEquals(-1f, c.offsetXFraction, 0.001f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `zero canvas is rejected`() {
        TileBackgroundMath.imagePlacement(0f, 100f, 100, 100, identity)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `zero image is rejected`() {
        TileBackgroundMath.imagePlacement(100f, 100f, 0, 100, identity)
    }

    @Test
    fun `a wide canvas also covers correctly`() {
        // Landscape canvas, portrait image: must overflow vertically.
        val p = TileBackgroundMath.imagePlacement(2400f, 1080f, 1000, 2000, identity)
        assertNotNull(p)
        assertTrue(p.width >= 2400f)
        assertTrue(p.height >= 1080f)
    }
}
