package com.metro.opticalshell

import com.metro.opticalshell.data.ShellJson
import com.metro.opticalshell.data.TileCollection
import com.metro.opticalshell.data.TileRepository
import com.metro.opticalshell.metro.LiveTileKind
import com.metro.opticalshell.metro.TileGroup
import com.metro.opticalshell.metro.TileSize
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the tile-collection rules of TZ 3.3 and 8:
 * one shared collection, unpinning is not uninstalling, and a user's layout
 * survives catalog churn.
 */
class TileCollectionTest {

    private fun base() = TileRepository.defaultCollection()

    @Test
    fun `default collection has only shell-owned live tiles`() {
        val c = base()
        assertTrue(c.tiles.isNotEmpty())
        // No fabricated third-party entries on first run.
        assertTrue(c.tiles.all { it.packageName == null })
        // And only data we genuinely have (TZ 3.4).
        val kinds = c.tiles.map { it.live }.toSet()
        assertTrue(
            kinds.all {
                it in setOf(
                    LiveTileKind.CLOCK,
                    LiveTileKind.DATE,
                    LiveTileKind.BATTERY,
                    LiveTileKind.DISPLAY_INFO,
                    LiveTileKind.STATIC,
                )
            },
        )
    }

    @Test
    fun `default order values are dense and unique`() {
        val orders = base().tiles.map { it.order }.sorted()
        assertEquals((0 until orders.size).toList(), orders)
    }

    @Test
    fun `pinning appends and is idempotent`() {
        val once = TileRepository.withPinnedApp(base(), "com.x", "com.x.Main", "X")
        assertEquals(base().tiles.size + 1, once.tiles.size)
        val twice = TileRepository.withPinnedApp(once, "com.x", "com.x.Main", "X")
        assertSame("pinning the same app twice must not duplicate", once, twice)
    }

    @Test
    fun `unpinning removes the tile and renumbers densely`() {
        val pinned = TileRepository.withPinnedApp(base(), "com.x", "com.x.Main", "X")
        val tileId = pinned.tiles.first { it.packageName == "com.x" }.tileId
        val after = TileRepository.withoutTile(pinned, tileId)
        assertEquals(base().tiles.size, after.tiles.size)
        assertTrue(after.tiles.none { it.packageName == "com.x" })
        assertEquals((0 until after.tiles.size).toList(), after.tiles.map { it.order }.sorted())
    }

    @Test
    fun `unpinning an unknown tile is a no-op`() {
        val c = base()
        assertSame(c, TileRepository.withoutTile(c, "nope"))
    }

    @Test
    fun `reconcile drops missing packages but keeps shell tiles and user order`() {
        var c = base()
        c = TileRepository.withPinnedApp(c, "com.keep", "A", "Keep")
        c = TileRepository.withPinnedApp(c, "com.gone", "B", "Gone")
        c = TileRepository.withResize(c, c.tiles.first { it.packageName == "com.keep" }.tileId, TileSize.LARGE)

        val reconciled = TileRepository.reconciled(c, setOf("com.keep"))

        assertTrue(reconciled.tiles.none { it.packageName == "com.gone" })
        // Shell-owned tiles (null package) always survive.
        assertEquals(
            base().tiles.size,
            reconciled.tiles.count { it.packageName == null },
        )
        // The user's deliberate resize is preserved.
        assertEquals(
            TileSize.LARGE,
            reconciled.tiles.first { it.packageName == "com.keep" }.size,
        )
        assertEquals(
            (0 until reconciled.tiles.size).toList(),
            reconciled.tiles.map { it.order }.sorted(),
        )
    }

    @Test
    fun `reconcile with nothing missing returns the same instance`() {
        val c = TileRepository.withPinnedApp(base(), "com.keep", "A", "Keep")
        assertSame(c, TileRepository.reconciled(c, setOf("com.keep")))
    }

    @Test
    fun `groups can be created renamed and removed without losing tiles`() {
        var c = TileRepository.withPinnedApp(base(), "com.x", "A", "X")
        val tileId = c.tiles.first { it.packageName == "com.x" }.tileId

        c = TileRepository.withGroup(c, TileGroup("g1", "Work", 0))
        c = TileRepository.withTileMovedToGroup(c, tileId, "g1")
        assertEquals("g1", c.tiles.first { it.tileId == tileId }.groupId)

        c = TileRepository.withGroupRenamed(c, "g1", "Office")
        assertEquals("Office", c.groups.first { it.groupId == "g1" }.name)

        val tileCount = c.tiles.size
        c = TileRepository.withoutGroup(c, "g1")
        // The group is gone, the tiles are not.
        assertTrue(c.groups.none { it.groupId == "g1" })
        assertEquals(tileCount, c.tiles.size)
        assertNull(c.tiles.first { it.tileId == tileId }.groupId)
    }

    @Test
    fun `accent can be set and cleared`() {
        var c = base()
        val id = c.tiles.first().tileId
        c = TileRepository.withAccent(c, id, 0xFF112233.toInt())
        assertEquals(0xFF112233.toInt(), c.tiles.first { it.tileId == id }.accentArgb)
        c = TileRepository.withAccent(c, id, null)
        assertNull(c.tiles.first { it.tileId == id }.accentArgb)
    }

    @Test
    fun `json round-trips the whole collection`() {
        var c = TileRepository.withPinnedApp(base(), "com.x", "com.x.Main", "X", TileSize.WIDE)
        c = TileRepository.withGroup(c, TileGroup("g1", "Work", 0))
        c = c.copy(
            presentationSizeOverrides = mapOf(
                "EXPANDED_PORTRAIT" to mapOf("app:com.x/com.x.Main" to TileSize.LARGE),
            ),
        )

        val json = ShellJson.encodeToString(c)
        val back = ShellJson.decodeFromString<TileCollection>(json)
        assertEquals(c, back)
    }

    @Test
    fun `malformed json import returns null rather than throwing`() {
        // Pure-function behaviour of the codec; the repository wraps the same call.
        val parsed = runCatching {
            ShellJson.decodeFromString<TileCollection>("{not json")
        }.getOrNull()
        assertNull(parsed)
    }

    @Test
    fun `unknown fields in stored json are ignored`() {
        val json = """{"tiles":[],"groups":[],"schemaVersion":1,"somethingNew":42}"""
        val back = ShellJson.decodeFromString<TileCollection>(json)
        assertNotNull(back)
        assertTrue(back.tiles.isEmpty())
    }
}
