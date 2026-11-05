package com.metro.opticalshell

import com.metro.opticalshell.metro.JumpList
import com.metro.opticalshell.runtime.InstalledApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins the alphabet jump list (TZ §3.5). */
class JumpListTest {

    private fun app(label: String) = InstalledApp("pkg.$label", "Main", label)

    private val apps = listOf(
        app("Alpha"), app("Argon"), app("Bravo"), app("Zulu"),
        app("1Password"), app("Яндекс"),
    )

    @Test
    fun `buckets use the first letter and collect symbols under hash`() {
        assertEquals('A', JumpList.bucketOf("Alpha"))
        assertEquals('Z', JumpList.bucketOf("zulu"))
        assertEquals('#', JumpList.bucketOf("1Password"))
        assertEquals('#', JumpList.bucketOf("!bang"))
        assertEquals('#', JumpList.bucketOf(""))
        assertEquals('Я', JumpList.bucketOf("Яндекс"))
    }

    @Test
    fun `yo folds into ye`() {
        assertEquals('Е', JumpList.bucketOf("Ёлка"))
    }

    @Test
    fun `hash sorts last as WP did`() {
        val buckets = JumpList.buckets(apps)
        assertEquals('#', buckets.last())
        assertTrue(buckets.contains('A'))
        assertTrue(buckets.contains('Я'))
    }

    @Test
    fun `header indices address the flat list correctly`() {
        // Flat layout: hdrA, Alpha, Argon, hdrB, Bravo, hdrZ, Zulu, hdrЯ, Яндекс, hdr#, 1Password
        val indices = JumpList.headerIndices(apps)
        assertEquals(0, indices['A'])
        assertEquals(3, indices['B'])
        assertEquals(5, indices['Z'])
    }

    @Test
    fun `groups are alphabetical within a bucket`() {
        val grouped = JumpList.groupedInOrder(listOf(app("Argon"), app("Alpha")))
        assertEquals(listOf("Alpha", "Argon"), grouped.first().second.map { it.label })
    }

    @Test
    fun `the jump grid always shows the whole alphabet`() {
        val grid = JumpList.jumpGrid(apps)
        // 26 Latin letters plus '#'.
        assertEquals(27, grid.size)
        // Present letters are enabled and have a target.
        val a = grid.first { it.letter == 'A' }
        assertTrue(a.enabled)
        assertEquals(0, a.scrollIndex)
        // Absent ones are greyed rather than hidden, so the grid never reflows.
        val q = grid.first { it.letter == 'Q' }
        assertFalse(q.enabled)
        assertNull(q.scrollIndex)
    }

    @Test
    fun `an empty app list yields no buckets but still a full grid`() {
        assertTrue(JumpList.buckets(emptyList()).isEmpty())
        assertEquals(27, JumpList.jumpGrid(emptyList()).size)
        assertTrue(JumpList.jumpGrid(emptyList()).none { it.enabled })
    }
}
