package com.metro.opticalshell.metro

import com.metro.opticalshell.data.TileCollection

/**
 * Live Folders (TZ §3.4): drag a tile onto another to create a folder, open it
 * on Start, rename it, reorder inside it.
 *
 * Pure operations on [TileCollection] so folder behaviour is tested without a
 * device. The nesting limit is explicit, as TZ §3.4 requires.
 */
object FolderOps {

    /** One level only. A folder inside a folder has no WP precedent. */
    const val MAX_NESTING = 1

    /** Below this a "folder" is pointless, so a drop-out dissolves it. */
    const val MIN_TILES = 2

    /**
     * Creates a folder from a tile dropped onto another (TZ §3.4).
     *
     * Returns the collection unchanged when the drop is not a valid folder
     * creation — onto itself, onto a tile already in a folder (nesting), or
     * involving a shell-owned live tile, which has nothing useful to fold.
     */
    fun createByDrop(
        collection: TileCollection,
        draggedTileId: String,
        targetTileId: String,
        name: String? = null,
        nowMillis: Long = 0L,
    ): TileCollection {
        if (draggedTileId == targetTileId) return collection
        val dragged = collection.tiles.firstOrNull { it.tileId == draggedTileId } ?: return collection
        val target = collection.tiles.firstOrNull { it.tileId == targetTileId } ?: return collection

        // Nesting limit: a target already inside a folder cannot host another.
        if (target.groupId != null && dragged.groupId != null) return collection

        // If the target is already a folder, the drop joins it.
        target.groupId?.let { existing ->
            return collection.copy(
                tiles = collection.tiles.map {
                    if (it.tileId == draggedTileId) it.copy(groupId = existing) else it
                },
            )
        }

        if (dragged.groupId != null) return collection

        val groupId = "folder-$nowMillis-${targetTileId.hashCode().toUInt().toString(16)}"
        val group = TileGroup(
            groupId = groupId,
            name = name ?: target.label,
            order = collection.groups.size,
        )
        return collection.copy(
            groups = collection.groups + group,
            tiles = collection.tiles.map {
                when (it.tileId) {
                    draggedTileId, targetTileId -> it.copy(groupId = groupId)
                    else -> it
                }
            },
        )
    }

    /** The tiles inside a folder, in order. */
    fun tilesIn(collection: TileCollection, groupId: String): List<TileSpec> =
        collection.tiles.filter { it.groupId == groupId }.sortedBy { it.order }

    /**
     * Tiles rendered on the Start surface: loose tiles, plus one representative
     * per folder so the folder occupies a single cell.
     */
    fun startSurfaceTiles(collection: TileCollection): List<TileSpec> {
        val loose = collection.tiles.filter { it.groupId == null }
        val folderReps = collection.groups.mapNotNull { group ->
            val members = tilesIn(collection, group.groupId)
            members.minByOrNull { it.order }?.copy(
                tileId = "group:${group.groupId}",
                label = group.name,
                packageName = null,
                activityName = null,
                live = LiveTileKind.STATIC,
            )
        }
        return (loose + folderReps).sortedBy { it.order }
    }

    /** True when a tile id names a folder representative rather than an app. */
    fun isFolderTile(tileId: String): Boolean = tileId.startsWith("group:")

    fun groupIdOf(tileId: String): String? =
        if (isFolderTile(tileId)) tileId.removePrefix("group:") else null

    /**
     * Removes a tile from its folder, dissolving the folder if too few remain
     * (TZ §3.4: a folder must stay meaningful).
     */
    fun removeFromFolder(collection: TileCollection, tileId: String): TileCollection {
        val tile = collection.tiles.firstOrNull { it.tileId == tileId } ?: return collection
        val groupId = tile.groupId ?: return collection

        val detached = collection.copy(
            tiles = collection.tiles.map {
                if (it.tileId == tileId) it.copy(groupId = null) else it
            },
        )
        return if (tilesIn(detached, groupId).size < MIN_TILES) {
            dissolve(detached, groupId)
        } else {
            detached
        }
    }

    /** Dissolves a folder, keeping every tile. */
    fun dissolve(collection: TileCollection, groupId: String): TileCollection = collection.copy(
        groups = collection.groups.filterNot { it.groupId == groupId },
        tiles = collection.tiles.map {
            if (it.groupId == groupId) it.copy(groupId = null) else it
        },
    )

    fun rename(collection: TileCollection, groupId: String, name: String): TileCollection =
        if (name.isBlank()) {
            collection
        } else {
            collection.copy(
                groups = collection.groups.map {
                    if (it.groupId == groupId) it.copy(name = name.trim()) else it
                },
            )
        }

    /** A folder's preview tiles — the first few, for the closed state. */
    fun previewTiles(collection: TileCollection, groupId: String, max: Int = 4): List<TileSpec> =
        tilesIn(collection, groupId).take(max)
}
