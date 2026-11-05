package com.metro.opticalshell.metro

import com.metro.opticalshell.runtime.InstalledApp

/**
 * The WP alphabet jump list (TZ §3.5).
 *
 * Pure index arithmetic: given the app list, produce the letter buckets, the
 * flat row index of each bucket header, and the jump-grid letters. Tested
 * without a device.
 */
object JumpList {

    /** The bucket a label falls in. Non-letters collect under '#'. */
    fun bucketOf(label: String): Char {
        val c = label.trim().firstOrNull()?.uppercaseChar() ?: return '#'
        return when {
            c in 'A'..'Z' -> c
            c in 'А'..'Я' -> c
            c == 'Ё' -> 'Е'
            else -> '#'
        }
    }

    /**
     * Buckets in display order: '#' last, as WP did — digits and symbols after
     * the alphabet rather than before it.
     */
    fun buckets(apps: List<InstalledApp>): List<Char> {
        val present = apps.map { bucketOf(it.label) }.toSortedSet()
        val letters = present.filter { it != '#' }
        return if (present.contains('#')) letters + '#' else letters
    }

    /**
     * The flat index of each bucket header in a list rendered as
     * header, items…, header, items… — which is what a LazyColumn needs to
     * scroll to a letter.
     */
    fun headerIndices(apps: List<InstalledApp>): Map<Char, Int> {
        val grouped = groupedInOrder(apps)
        val out = LinkedHashMap<Char, Int>()
        var index = 0
        grouped.forEach { (letter, items) ->
            out[letter] = index
            index += 1 + items.size
        }
        return out
    }

    /** Apps grouped by bucket, in display order, each group alphabetical. */
    fun groupedInOrder(apps: List<InstalledApp>): List<Pair<Char, List<InstalledApp>>> {
        val byBucket = apps.groupBy { bucketOf(it.label) }
        return buckets(apps).mapNotNull { letter ->
            byBucket[letter]?.sortedBy { it.label.lowercase() }?.let { letter to it }
        }
    }

    /**
     * The jump grid: every letter of the alphabet plus '#', with a flag for
     * whether anything is actually there.
     *
     * WP showed the full alphabet and greyed out the empty letters rather than
     * hiding them, so the grid never reflows as apps are installed.
     */
    fun jumpGrid(apps: List<InstalledApp>, alphabet: String = LATIN): List<JumpTarget> {
        val available = apps.map { bucketOf(it.label) }.toSet()
        val headers = headerIndices(apps)
        return (alphabet.toList() + '#').map { letter ->
            JumpTarget(
                letter = letter,
                enabled = letter in available,
                scrollIndex = headers[letter],
            )
        }
    }

    const val LATIN = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
    const val CYRILLIC = "АБВГДЕЖЗИЙКЛМНОПРСТУФХЦЧШЩЭЮЯ"
}

data class JumpTarget(
    val letter: Char,
    val enabled: Boolean,
    /** Null when the letter has no apps. */
    val scrollIndex: Int?,
)
