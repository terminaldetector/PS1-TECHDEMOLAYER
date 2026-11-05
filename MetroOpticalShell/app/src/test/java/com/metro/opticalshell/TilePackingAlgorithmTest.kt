package com.metro.opticalshell

import com.metro.opticalshell.metro.PlacedTile
import com.metro.opticalshell.metro.TilePackingAlgorithm
import com.metro.opticalshell.metro.TileSize
import com.metro.opticalshell.metro.TileSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins the packing guarantees of TZ 3.2 and 3.3. */
class TilePackingAlgorithmTest {

    private fun tile(id: String, size: TileSize, order: Int) =
        TileSpec(tileId = id, packageName = "pkg.$id", label = id, size = size, order = order)

    private fun assertNoOverlaps(placed: List<PlacedTile>) {
        for (i in placed.indices) {
            for (j in i + 1 until placed.size) {
                assertFalse(
                    "tiles ${placed[i].spec.tileId} and ${placed[j].spec.tileId} overlap",
                    placed[i].overlaps(placed[j]),
                )
            }
        }
    }

    private fun assertWithinColumns(placed: List<PlacedTile>, columns: Int) {
        placed.forEach {
            assertTrue("${it.spec.tileId} starts off-grid", it.col >= 0)
            assertTrue(
                "${it.spec.tileId} ends at ${it.endCol}, past $columns columns",
                it.endCol <= columns,
            )
        }
    }

    @Test
    fun `empty input packs to nothing`() {
        assertTrue(TilePackingAlgorithm.pack(emptyList(), 6).isEmpty())
    }

    @Test
    fun `all four sizes pack without overlap at every sensible width`() {
        val tiles = listOf(
            tile("a", TileSize.LARGE, 0),
            tile("b", TileSize.WIDE, 1),
            tile("c", TileSize.MEDIUM, 2),
            tile("d", TileSize.SMALL, 3),
            tile("e", TileSize.SMALL, 4),
            tile("f", TileSize.MEDIUM, 5),
            tile("g", TileSize.WIDE, 6),
        )
        for (columns in 2..12) {
            val placed = TilePackingAlgorithm.pack(tiles, columns)
            assertEquals("lost a tile at $columns columns", tiles.size, placed.size)
            assertNoOverlaps(placed)
            assertWithinColumns(placed, columns)
        }
    }

    @Test
    fun `oversized tiles are clamped not clipped`() {
        // A 4-wide tile in a 2-column grid must become 2 wide, keeping height.
        val placed = TilePackingAlgorithm.pack(listOf(tile("wide", TileSize.WIDE, 0)), 2)
        assertEquals(1, placed.size)
        assertEquals(2, placed[0].w)
        assertEquals(TileSize.WIDE.h, placed[0].h)
        assertWithinColumns(placed, 2)
    }

    @Test
    fun `packing is deterministic and idempotent`() {
        val tiles = (0 until 25).map {
            tile("t$it", TileSize.entries[it % TileSize.entries.size], it)
        }
        val first = TilePackingAlgorithm.pack(tiles, 6)
        val second = TilePackingAlgorithm.pack(tiles, 6)
        assertEquals(first, second)
        // And re-packing the same specs in a shuffled list gives the same layout,
        // because order is what matters, not list position.
        val shuffled = tiles.shuffled()
        assertEquals(first, TilePackingAlgorithm.pack(shuffled, 6))
    }

    @Test
    fun `packing is genuinely first-fit`() {
        // The real guarantee is not monotonic rows - a later small tile is
        // SUPPOSED to backfill an earlier gap, which is what makes the WP8 flow
        // feel right. The guarantee is first-fit: no tile could have gone in an
        // earlier slot in reading order.
        //
        // This is checkable on the final grid because occupancy only ever grows:
        // a cell free at the end was free when the tile was placed.
        val tiles = (0 until 24).map {
            tile("t$it", TileSize.entries[it % TileSize.entries.size], it)
        }
        val columns = 6
        val placed = TilePackingAlgorithm.pack(tiles, columns)
        val rows = TilePackingAlgorithm.rowsUsed(placed)

        val occupied = Array(rows) { BooleanArray(columns) }
        placed.forEach { t ->
            for (r in t.row until t.endRow) for (c in t.col until t.endCol) occupied[r][c] = true
        }

        placed.forEach { t ->
            fun freeForThisTile(r: Int, c: Int): Boolean {
                // t's own cells count as free: they were free before it landed.
                val isOwn = r >= t.row && r < t.endRow && c >= t.col && c < t.endCol
                return isOwn || !occupied[r][c]
            }

            fun fitsAt(r: Int, c: Int): Boolean {
                if (c + t.w > columns || r + t.h > rows) return false
                for (rr in r until r + t.h) {
                    for (cc in c until c + t.w) if (!freeForThisTile(rr, cc)) return false
                }
                return true
            }

            for (r in 0 until rows) {
                for (c in 0 until columns) {
                    val earlier = r < t.row || (r == t.row && c < t.col)
                    if (!earlier) continue
                    assertFalse(
                        "${t.spec.tileId} (${t.w}x${t.h}) sits at (${t.row},${t.col}) " +
                            "but would have fitted earlier at ($r,$c)",
                        fitsAt(r, c),
                    )
                }
            }
        }
    }

    @Test
    fun `the first tile in the flow takes the top-left corner`() {
        val tiles = (0 until 8).map {
            tile("t$it", TileSize.entries[it % TileSize.entries.size], it)
        }
        val placed = TilePackingAlgorithm.pack(tiles, 6)
        val first = placed.first { it.spec.order == 0 }
        assertEquals(0, first.row)
        assertEquals(0, first.col)
    }

    @Test
    fun `first row is filled before the second where geometry allows`() {
        // Six 1x1 tiles in a 6-column grid must all land on row 0.
        val tiles = (0 until 6).map { tile("s$it", TileSize.SMALL, it) }
        val placed = TilePackingAlgorithm.pack(tiles, 6)
        assertTrue(placed.all { it.row == 0 })
        assertEquals(setOf(0, 1, 2, 3, 4, 5), placed.map { it.col }.toSet())
    }

    @Test
    fun `small tiles backfill the gap beside a wide tile`() {
        // WIDE is 4x2. In a 6-column grid that leaves a 2x2 hole on its right,
        // which the following small tiles should occupy rather than skipping.
        val tiles = listOf(
            tile("wide", TileSize.WIDE, 0),
            tile("s1", TileSize.SMALL, 1),
            tile("s2", TileSize.SMALL, 2),
        )
        val placed = TilePackingAlgorithm.pack(tiles, 6)
        val s1 = placed.first { it.spec.tileId == "s1" }
        val s2 = placed.first { it.spec.tileId == "s2" }
        assertEquals(0, s1.row)
        assertEquals(4, s1.col)
        assertEquals(0, s2.row)
        assertEquals(5, s2.col)
        assertNoOverlaps(placed)
    }

    @Test
    fun `rowsUsed reports the real height`() {
        val tiles = listOf(tile("a", TileSize.LARGE, 0))
        val placed = TilePackingAlgorithm.pack(tiles, 4)
        assertEquals(4, TilePackingAlgorithm.rowsUsed(placed))
        assertEquals(0, TilePackingAlgorithm.rowsUsed(emptyList()))
    }

    @Test
    fun `reorder moves a tile and densifies order values`() {
        val tiles = listOf(
            tile("a", TileSize.MEDIUM, 0),
            tile("b", TileSize.MEDIUM, 5),
            tile("c", TileSize.MEDIUM, 9),
        )
        val reordered = TilePackingAlgorithm.reorder(tiles, "c", 0)
        assertEquals(listOf("c", "a", "b"), reordered.sortedBy { it.order }.map { it.tileId })
        assertEquals(listOf(0, 1, 2), reordered.sortedBy { it.order }.map { it.order })
    }

    @Test
    fun `reorder clamps an out-of-range target`() {
        val tiles = listOf(tile("a", TileSize.SMALL, 0), tile("b", TileSize.SMALL, 1))
        val high = TilePackingAlgorithm.reorder(tiles, "a", 99)
        assertEquals(listOf("b", "a"), high.sortedBy { it.order }.map { it.tileId })
        val low = TilePackingAlgorithm.reorder(tiles, "b", -5)
        assertEquals(listOf("b", "a"), low.sortedBy { it.order }.map { it.tileId })
    }

    @Test
    fun `reorder of an unknown id changes nothing`() {
        val tiles = listOf(tile("a", TileSize.SMALL, 0))
        assertEquals(tiles, TilePackingAlgorithm.reorder(tiles, "missing", 0))
    }

    @Test
    fun `resize changes only the one tile and keeps order`() {
        val tiles = listOf(tile("a", TileSize.SMALL, 0), tile("b", TileSize.SMALL, 1))
        val resized = TilePackingAlgorithm.resize(tiles, "a", TileSize.LARGE)
        assertEquals(TileSize.LARGE, resized.first { it.tileId == "a" }.size)
        assertEquals(TileSize.SMALL, resized.first { it.tileId == "b" }.size)
        assertEquals(0, resized.first { it.tileId == "a" }.order)
    }

    @Test
    fun `resize cycle visits all four sizes and returns`() {
        var size = TileSize.SMALL
        val seen = mutableListOf(size)
        repeat(4) {
            size = size.next()
            seen.add(size)
        }
        assertEquals(
            listOf(
                TileSize.SMALL,
                TileSize.MEDIUM,
                TileSize.WIDE,
                TileSize.LARGE,
                TileSize.SMALL,
            ),
            seen,
        )
    }

    @Test
    fun `equal order values do not drop tiles`() {
        val tiles = (0 until 5).map { tile("t$it", TileSize.MEDIUM, 0) }
        val placed = TilePackingAlgorithm.pack(tiles, 4)
        assertEquals(5, placed.size)
        assertNoOverlaps(placed)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `zero columns is rejected`() {
        TilePackingAlgorithm.pack(listOf(tile("a", TileSize.SMALL, 0)), 0)
    }
}
