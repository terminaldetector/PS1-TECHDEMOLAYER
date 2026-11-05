package com.metro.opticalshell

import com.metro.opticalshell.data.ShellJson
import com.metro.opticalshell.expanded.DesktopShortcut
import com.metro.opticalshell.expanded.ExpandedWorkspace
import com.metro.opticalshell.expanded.ExpandedWorkspaceState
import com.metro.opticalshell.expanded.TaskbarConfiguration
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins TZ 2.2/2.3/5.1: ONE Expanded workspace behind both of its
 * presentations, and TZ 5.2: recents are shell launches, not Android tasks.
 */
class ExpandedWorkspaceTest {

    @Test
    fun `pinning is idempotent and densely ordered`() {
        var s = ExpandedWorkspaceState()
        s = ExpandedWorkspace.withPinned(s, "com.a", "A", "A")
        s = ExpandedWorkspace.withPinned(s, "com.b", "B", "B")
        val again = ExpandedWorkspace.withPinned(s, "com.a", "A", "A")
        assertSame(s, again)
        assertEquals(listOf(0, 1), s.pinnedApps.map { it.order })
    }

    @Test
    fun `unpinning renumbers the rest`() {
        var s = ExpandedWorkspaceState()
        s = ExpandedWorkspace.withPinned(s, "com.a", null, "A")
        s = ExpandedWorkspace.withPinned(s, "com.b", null, "B")
        s = ExpandedWorkspace.withPinned(s, "com.c", null, "C")
        s = ExpandedWorkspace.withoutPinned(s, "com.b")
        assertEquals(listOf("com.a", "com.c"), s.pinnedApps.sortedBy { it.order }.map { it.packageName })
        assertEquals(listOf(0, 1), s.pinnedApps.map { it.order }.sorted())
    }

    @Test
    fun `unpinning an absent app is a no-op`() {
        val s = ExpandedWorkspace.withPinned(ExpandedWorkspaceState(), "com.a", null, "A")
        assertSame(s, ExpandedWorkspace.withoutPinned(s, "com.zz"))
    }

    @Test
    fun `shortcuts can be added moved and removed`() {
        var s = ExpandedWorkspace.withShortcut(
            ExpandedWorkspaceState(),
            DesktopShortcut("com.a", null, "A", 1, 2),
        )
        assertEquals(1, s.desktopShortcuts.size)

        s = ExpandedWorkspace.withShortcutMoved(s, "com.a", 4, 5)
        assertEquals(4, s.desktopShortcuts.first().cellX)
        assertEquals(5, s.desktopShortcuts.first().cellY)

        s = ExpandedWorkspace.withoutShortcut(s, "com.a")
        assertTrue(s.desktopShortcuts.isEmpty())
    }

    @Test
    fun `adding the same shortcut replaces rather than duplicates`() {
        var s = ExpandedWorkspace.withShortcut(
            ExpandedWorkspaceState(),
            DesktopShortcut("com.a", null, "A", 1, 1),
        )
        s = ExpandedWorkspace.withShortcut(s, DesktopShortcut("com.a", null, "A", 7, 7))
        assertEquals(1, s.desktopShortcuts.size)
        assertEquals(7, s.desktopShortcuts.first().cellX)
    }

    @Test
    fun `shell launches are most-recent-first deduplicated and bounded`() {
        var s = ExpandedWorkspaceState()
        repeat(20) { s = ExpandedWorkspace.withShellLaunch(s, "com.app$it") }
        assertEquals(12, s.recentShellLaunches.size)
        assertEquals("com.app19", s.recentShellLaunches.first())

        s = ExpandedWorkspace.withShellLaunch(s, "com.app15")
        assertEquals("com.app15", s.recentShellLaunches.first())
        assertEquals(1, s.recentShellLaunches.count { it == "com.app15" })
    }

    @Test
    fun `taskbar height is clamped to the usable range`() {
        val tall = ExpandedWorkspace.withTaskbar(
            ExpandedWorkspaceState(),
            TaskbarConfiguration(heightDp = 500),
        )
        assertEquals(TaskbarConfiguration.MAX_HEIGHT_DP, tall.taskbar.heightDp)

        val short = ExpandedWorkspace.withTaskbar(
            ExpandedWorkspaceState(),
            TaskbarConfiguration(heightDp = 1),
        )
        assertEquals(TaskbarConfiguration.MIN_HEIGHT_DP, short.taskbar.heightDp)
    }

    @Test
    fun `reconcile prunes pinned shortcuts and recents together`() {
        var s = ExpandedWorkspaceState()
        s = ExpandedWorkspace.withPinned(s, "com.keep", null, "Keep")
        s = ExpandedWorkspace.withPinned(s, "com.gone", null, "Gone")
        s = ExpandedWorkspace.withShortcut(s, DesktopShortcut("com.gone", null, "Gone", 0, 0))
        s = ExpandedWorkspace.withShellLaunch(s, "com.gone")
        s = ExpandedWorkspace.withShellLaunch(s, "com.keep")

        val r = ExpandedWorkspace.reconciled(s, setOf("com.keep"))
        assertEquals(listOf("com.keep"), r.pinnedApps.map { it.packageName })
        assertTrue(r.desktopShortcuts.isEmpty())
        assertEquals(listOf("com.keep"), r.recentShellLaunches)
    }

    @Test
    fun `workspace json round-trips`() {
        var s = ExpandedWorkspaceState()
        s = ExpandedWorkspace.withPinned(s, "com.a", "A", "A")
        s = ExpandedWorkspace.withShortcut(s, DesktopShortcut("com.a", "A", "A", 2, 3))
        s = ExpandedWorkspace.withTaskbar(s, TaskbarConfiguration(atTop = true, heightDp = 56))
        s = ExpandedWorkspace.withShellLaunch(s, "com.a")

        val back = ShellJson.decodeFromString<ExpandedWorkspaceState>(ShellJson.encodeToString(s))
        assertEquals(s, back)
    }
}
