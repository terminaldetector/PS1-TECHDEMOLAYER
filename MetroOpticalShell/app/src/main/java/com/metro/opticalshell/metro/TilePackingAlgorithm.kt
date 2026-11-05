package com.metro.opticalshell.metro

/**
 * Deterministic first-fit tile packer (TZ 3.3: "stable packing with no overlaps
 * and no unpredictable jumps").
 *
 * Why first-fit over stored (col,row) coordinates:
 *
 *  - The same tile collection has to render at 4, 6, 8 or 10 columns depending
 *    on the presentation (TZ 3.1, 2.2). Stored absolute coordinates would be
 *    wrong at every width but one, so the layout is DERIVED from an order,
 *    and only the order is persisted. Rotating the phone therefore cannot
 *    scramble the start screen.
 *  - Order is a single integer per tile, so a drag is one cheap edit and the
 *    result is reproducible: the same list and the same column count always
 *    pack to byte-identical output.
 *
 * Guarantees, pinned by TilePackingAlgorithmTest:
 *  - no two output tiles overlap;
 *  - no tile sticks out past [columns] (oversized tiles are CLAMPED, not
 *    clipped, per TZ 3.2);
 *  - tiles appear in non-decreasing row order relative to their `order`;
 *  - the function is pure and idempotent.
 */
object TilePackingAlgorithm {

    /** Hard ceiling so a corrupt order value cannot spin the scan forever. */
    private const val MAX_ROWS = 4096

    fun pack(tiles: List<TileSpec>, columns: Int): List<PlacedTile> {
        require(columns > 0) { "columns must be positive, was $columns" }
        if (tiles.isEmpty()) return emptyList()

        // Stable sort: equal orders keep their incoming sequence, so packing
        // never depends on map iteration order.
        val ordered = tiles.sortedBy { it.order }

        // Occupancy grid, grown a row at a time.
        val occupied = ArrayList<BooleanArray>()
        fun rowAt(r: Int): BooleanArray {
            while (occupied.size <= r) occupied.add(BooleanArray(columns))
            return occupied[r]
        }

        fun fits(col: Int, row: Int, w: Int, h: Int): Boolean {
            if (col + w > columns) return false
            for (r in row until row + h) {
                val cells = rowAt(r)
                for (c in col until col + w) {
                    if (cells[c]) return false
                }
            }
            return true
        }

        fun occupy(col: Int, row: Int, w: Int, h: Int) {
            for (r in row until row + h) {
                val cells = rowAt(r)
                for (c in col until col + w) cells[c] = true
            }
        }

        val placed = ArrayList<PlacedTile>(ordered.size)

        // The first row that is still worth scanning. Rows below it are full
        // for every remaining tile width, which keeps packing near-linear
        // instead of rescanning the whole board for each tile.
        var scanFrom = 0

        for (spec in ordered) {
            // TZ 3.2: clamp instead of clipping. A 4-wide tile in a 2-column
            // grid becomes 2 wide and keeps its height.
            val w = spec.size.w.coerceAtMost(columns)
            val h = spec.size.h

            var found = false
            var row = scanFrom
            while (row < MAX_ROWS && !found) {
                for (col in 0..(columns - w)) {
                    if (fits(col, row, w, h)) {
                        occupy(col, row, w, h)
                        placed.add(PlacedTile(spec, col, row, w, h))
                        found = true
                        break
                    }
                }
                if (!found) row++
            }

            if (!found) {
                // Unreachable for sane inputs; fail loudly rather than drop a
                // tile silently.
                error("could not place tile ${spec.tileId} within $MAX_ROWS rows")
            }

            // Advance the floor past any fully-occupied leading rows.
            while (scanFrom < occupied.size && occupied[scanFrom].all { it }) scanFrom++
        }

        return placed
    }

    /** Total rows the packed layout needs. Used to size the scroll surface. */
    fun rowsUsed(placed: List<PlacedTile>): Int = placed.maxOfOrNull { it.endRow } ?: 0

    /**
     * Reorder for a drag: move [tileId] so it lands at [targetIndex] in the
     * flow, and renumber everything densely.
     *
     * Dense renumbering is what keeps drags predictable: order values never
     * drift apart, so repeated drags cannot produce surprise gaps.
     */
    fun reorder(tiles: List<TileSpec>, tileId: String, targetIndex: Int): List<TileSpec> {
        val ordered = tiles.sortedBy { it.order }.toMutableList()
        val from = ordered.indexOfFirst { it.tileId == tileId }
        if (from < 0) return tiles
        val to = targetIndex.coerceIn(0, ordered.size - 1)
        if (from == to) return renumber(ordered)
        val moving = ordered.removeAt(from)
        ordered.add(to, moving)
        return renumber(ordered)
    }

    /** Resize one tile, leaving the order alone. */
    fun resize(tiles: List<TileSpec>, tileId: String, size: TileSize): List<TileSpec> =
        tiles.map { if (it.tileId == tileId) it.copy(size = size) else it }

    private fun renumber(ordered: List<TileSpec>): List<TileSpec> =
        ordered.mapIndexed { index, spec -> spec.copy(order = index) }
}
