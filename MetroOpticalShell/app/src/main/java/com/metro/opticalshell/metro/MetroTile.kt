package com.metro.opticalshell.metro

import kotlinx.serialization.Serializable

/**
 * Tile sizes as a LOGICAL grid, never fixed pixel sizes (TZ 3.2).
 *
 * The unit is the WP8 "small" tile. The grid may change how many physical
 * columns it shows and at what scale; the aspect ratios and the meaning of each
 * size stay put.
 */
enum class TileSize(val w: Int, val h: Int) {
    SMALL(1, 1),
    MEDIUM(2, 2),
    WIDE(4, 2),
    LARGE(4, 4),
    ;

    val units: Int get() = w * h

    /** Next size in the WP8 resize cycle. */
    fun next(): TileSize = when (this) {
        SMALL -> MEDIUM
        MEDIUM -> WIDE
        WIDE -> LARGE
        LARGE -> SMALL
    }
}

/** What a live tile is allowed to show in Operation 01 (TZ 3.4). */
enum class LiveTileKind {
    /** A plain launch tile. The default. */
    STATIC,

    /** Wall clock. Data we actually have. */
    CLOCK,

    /** Date. */
    DATE,

    /** Battery level, from BatteryManager. */
    BATTERY,

    /** Window geometry readout, for the diagnostics tile. */
    DISPLAY_INFO,
    ;
}

/**
 * One tile pinned to the start surface.
 *
 * NOTE (TZ 3.3): a tile is a VIEW of an app, not the app. Removing a tile
 * unpins it and never uninstalls anything. [packageName] may be null for
 * shell-owned tiles such as the clock.
 */
@Serializable
data class TileSpec(
    val tileId: String,
    val packageName: String?,
    val activityName: String? = null,
    val label: String,
    val size: TileSize = TileSize.MEDIUM,
    /** Position in the flow. Lower comes first. Drag changes this and nothing else. */
    val order: Int,
    val groupId: String? = null,
    /** ARGB accent. Null means "use the shell accent". */
    val accentArgb: Int? = null,
    val live: LiveTileKind = LiveTileKind.STATIC,
)

/** A tile with a resolved position in the grid. Output of the packer. */
data class PlacedTile(
    val spec: TileSpec,
    val col: Int,
    val row: Int,
    /** Width in grid units AFTER clamping to the column count. */
    val w: Int,
    /** Height in grid units. */
    val h: Int,
) {
    val endCol: Int get() = col + w
    val endRow: Int get() = row + h

    fun overlaps(other: PlacedTile): Boolean =
        col < other.endCol && other.col < endCol &&
            row < other.endRow && other.row < endRow
}

/** A named group / folder of tiles (TZ 3.3). */
@Serializable
data class TileGroup(
    val groupId: String,
    val name: String,
    val order: Int,
)
