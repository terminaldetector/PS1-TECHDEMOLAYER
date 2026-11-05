package com.metro.opticalshell

import com.metro.opticalshell.data.TileRepository
import com.metro.opticalshell.metro.FolderOps
import com.metro.opticalshell.metro.TileSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins Live Folders (TZ §3.4). */
class FolderOpsTest {

    private fun base() = TileRepository.withPinnedApp(
        TileRepository.withPinnedApp(
            TileRepository.withPinnedApp(
                TileRepository.defaultCollection(), "com.a", "A", "Alpha",
            ),
            "com.b", "B", "Bravo",
        ),
        "com.c", "C", "Charlie",
    )

    private fun idOf(pkg: String) = "app:$pkg/${pkg.last().uppercase()}"

    @Test
    fun `dropping a tile on another creates a folder with both`() {
        val c = base()
        val a = c.tiles.first { it.packageName == "com.a" }.tileId
        val b = c.tiles.first { it.packageName == "com.b" }.tileId

        val out = FolderOps.createByDrop(c, a, b, nowMillis = 1)
        assertEquals(1, out.groups.size)
        val groupId = out.groups.first().groupId
        assertEquals(setOf(a, b), FolderOps.tilesIn(out, groupId).map { it.tileId }.toSet())
        // Named after the drop target, as WP did.
        assertEquals("Bravo", out.groups.first().name)
    }

    @Test
    fun `dropping a tile on itself does nothing`() {
        val c = base()
        val a = c.tiles.first { it.packageName == "com.a" }.tileId
        assertSame(c, FolderOps.createByDrop(c, a, a, nowMillis = 1))
    }

    @Test
    fun `dropping onto an existing folder joins it`() {
        var c = base()
        val a = c.tiles.first { it.packageName == "com.a" }.tileId
        val b = c.tiles.first { it.packageName == "com.b" }.tileId
        val d = c.tiles.first { it.packageName == "com.c" }.tileId

        c = FolderOps.createByDrop(c, a, b, nowMillis = 1)
        val groupId = c.groups.first().groupId
        c = FolderOps.createByDrop(c, d, b, nowMillis = 2)

        assertEquals("joining must not create a second folder", 1, c.groups.size)
        assertEquals(3, FolderOps.tilesIn(c, groupId).size)
    }

    @Test
    fun `nesting is refused`() {
        var c = base()
        val a = c.tiles.first { it.packageName == "com.a" }.tileId
        val b = c.tiles.first { it.packageName == "com.b" }.tileId
        val d = c.tiles.first { it.packageName == "com.c" }.tileId

        c = FolderOps.createByDrop(c, a, b, nowMillis = 1)
        val first = c.groups.first().groupId
        // Make a second folder, then try to drop one folder member onto another.
        val clockTile = c.tiles.first { it.tileId == "shell.clock" }.tileId
        c = FolderOps.createByDrop(c, d, clockTile, nowMillis = 2)
        assertEquals(2, c.groups.size)

        val before = c
        // Both tiles already belong to folders: refused.
        val after = FolderOps.createByDrop(c, a, d, nowMillis = 3)
        assertSame("nesting must be refused", before, after)
        assertEquals(1, FolderOps.tilesIn(after, first).count { it.tileId == a })
    }

    @Test
    fun `start surface shows one representative per folder`() {
        var c = base()
        val a = c.tiles.first { it.packageName == "com.a" }.tileId
        val b = c.tiles.first { it.packageName == "com.b" }.tileId
        val looseBefore = FolderOps.startSurfaceTiles(c).size

        c = FolderOps.createByDrop(c, a, b, nowMillis = 1)
        val surface = FolderOps.startSurfaceTiles(c)

        // Two tiles became one folder cell.
        assertEquals(looseBefore - 1, surface.size)
        assertEquals(1, surface.count { FolderOps.isFolderTile(it.tileId) })
    }

    @Test
    fun `a folder tile id round-trips to its group`() {
        var c = base()
        val a = c.tiles.first { it.packageName == "com.a" }.tileId
        val b = c.tiles.first { it.packageName == "com.b" }.tileId
        c = FolderOps.createByDrop(c, a, b, nowMillis = 1)
        val groupId = c.groups.first().groupId

        val folderTile = FolderOps.startSurfaceTiles(c)
            .first { FolderOps.isFolderTile(it.tileId) }
        assertEquals(groupId, FolderOps.groupIdOf(folderTile.tileId))
        assertNull(FolderOps.groupIdOf(a))
    }

    @Test
    fun `removing a tile dissolves a folder that becomes too small`() {
        var c = base()
        val a = c.tiles.first { it.packageName == "com.a" }.tileId
        val b = c.tiles.first { it.packageName == "com.b" }.tileId
        c = FolderOps.createByDrop(c, a, b, nowMillis = 1)
        assertEquals(1, c.groups.size)

        // Two members; removing one leaves one, which is not a folder.
        c = FolderOps.removeFromFolder(c, a)
        assertTrue("a one-item folder must not survive", c.groups.isEmpty())
        // And both tiles are still there.
        assertTrue(c.tiles.any { it.tileId == a })
        assertTrue(c.tiles.any { it.tileId == b })
        assertNull(c.tiles.first { it.tileId == b }.groupId)
    }

    @Test
    fun `removing from a larger folder keeps it`() {
        var c = base()
        val a = c.tiles.first { it.packageName == "com.a" }.tileId
        val b = c.tiles.first { it.packageName == "com.b" }.tileId
        val d = c.tiles.first { it.packageName == "com.c" }.tileId
        c = FolderOps.createByDrop(c, a, b, nowMillis = 1)
        c = FolderOps.createByDrop(c, d, b, nowMillis = 2)

        c = FolderOps.removeFromFolder(c, a)
        assertEquals(1, c.groups.size)
        assertEquals(2, FolderOps.tilesIn(c, c.groups.first().groupId).size)
    }

    @Test
    fun `dissolving keeps every tile`() {
        var c = base()
        val a = c.tiles.first { it.packageName == "com.a" }.tileId
        val b = c.tiles.first { it.packageName == "com.b" }.tileId
        val countBefore = c.tiles.size
        c = FolderOps.createByDrop(c, a, b, nowMillis = 1)
        c = FolderOps.dissolve(c, c.groups.first().groupId)

        assertTrue(c.groups.isEmpty())
        assertEquals(countBefore, c.tiles.size)
        assertTrue(c.tiles.all { it.groupId == null })
    }

    @Test
    fun `rename trims and refuses blank`() {
        var c = base()
        val a = c.tiles.first { it.packageName == "com.a" }.tileId
        val b = c.tiles.first { it.packageName == "com.b" }.tileId
        c = FolderOps.createByDrop(c, a, b, nowMillis = 1)
        val groupId = c.groups.first().groupId

        c = FolderOps.rename(c, groupId, "  Work  ")
        assertEquals("Work", c.groups.first().name)

        val before = c
        assertSame(before, FolderOps.rename(c, groupId, "   "))
    }

    @Test
    fun `preview is capped`() {
        var c = TileRepository.defaultCollection()
        repeat(8) { i ->
            c = TileRepository.withPinnedApp(c, "com.p$i", "A", "App $i", TileSize.SMALL)
        }
        val ids = c.tiles.filter { it.packageName != null }.map { it.tileId }
        c = FolderOps.createByDrop(c, ids[0], ids[1], nowMillis = 1)
        val groupId = c.groups.first().groupId
        ids.drop(2).forEach { c = FolderOps.createByDrop(c, it, ids[1], nowMillis = 2) }

        assertEquals(8, FolderOps.tilesIn(c, groupId).size)
        assertEquals(4, FolderOps.previewTiles(c, groupId).size)
    }

    @Test
    fun `nesting limit is one`() {
        assertEquals(1, FolderOps.MAX_NESTING)
        assertEquals(2, FolderOps.MIN_TILES)
    }
}
