// Copyright 2023-2025 Phase Five LLC.  For license terms, see LICENSE.txt in the repository root.

package io.pfive.access.sparse;

import gnu.trove.map.TIntObjectMap;

import static io.pfive.access.sparse.SparseEgressBuilder.TILE_SPAN_CELLS;

/// This data structure is used for organizing a many instances of the same class, one per tile
/// arranged in a grid. It can be used to maintain tiles of StreetConnectors, EgressTimes, and
/// Opportunities all arranged on the same grid of tiles. Data are expected to be
/// somewhat sparse, with null references standing in for tiles that contain no data.
///
/// Tiles can be quite sparse, with only 10-25 percent of tiles containing any data in some cases.
/// It is tempting to use a primitive int to Object map to hold the non-empty tiles, but this may
/// be no more efficient than an array. Relevant facts are that tile numbers start at zero so do
/// not leave a gap at the beginning of the array; a hashmap typically has a load factor of 0.5
/// (Trove THash#DEFAULT_LOAD_FACTOR) to 0.75 (Java HashMap); a hashmap must store the integer keys
/// while they are implicit in an array; hashmaps must perform additional hash, modulo, and probing
/// calculations.
///
/// Memory consumption for an array is `sizeof(reference) * N` and for a hash map
/// `sizeof((reference, int)) * N * fillRate / loadFactor`. Our heap is typically larger than 4GB
/// so cannot use 32-bit UnscaledNarrowOop references. But if it's less than 32GB, by default the
/// JVM will use 32-bit CompressedOops (at 8-byte granularity).
/// [JDK Reference.](https://wiki.openjdk.org/display/HotSpot/CompressedOops)
///
/// Using 20% fillRate and 50% loadFactor and assuming 32-bit references, `4N` for array vs.
/// `8 * 0.2N / 0.5 = 3.2N` for hashtable. So only 20% less memory for the hashtable. The tiles
/// themselves each contain 256 values at 4 or 8 bytes each, contributing at least
/// `fillRate * N * 256 * 4 ~= 205N` against which the array vs. table difference is a mere 0.4%.
///
/// But total memory consumption is not the issue here. It's compactness (cache and bus efficiency)
/// of the most frequently accessed structures. If only 20% of tile lookups actually result in
/// following a reference out to a tile, with the other 80% returning no tile, then we want the
/// structure used to confirm non-existence of tiles to be as compact as possible. Maybe it should
/// even be a bitset. But also recall that we're frequently traversing small contiguous areas, for
/// which an array of references is effective.
///
/// FIXME use of hashtable was based on incorrect premise. Switch to array, and also use for connectors and egressTimes.
public record TileGrid<T> (GridScheme grid, TIntObjectMap<T> tileForIndex) {

    public int nTiles () {
        return grid.nElements();
    }

    public GridScheme cellGrid () {
        return grid.tilesToCells();
    }

    /// Return the number of cells in all the tiles combined.
    public int nCellsAllTiles () {
        return cellGrid().nElements();
    }

    public T tileForCell (int flatCellIndex) {
        var cellGrid = cellGrid();
        // Global cell index
        int yc = cellGrid.yForFlatIndex(flatCellIndex);
        int xc = cellGrid.xForFlatIndex(flatCellIndex);
        // Tile index within this grid
        int yt = yc / TILE_SPAN_CELLS;
        int xt = xc / TILE_SPAN_CELLS;
        int flatTileIndex = grid.flatIndex(xt, yt);
        return get(flatTileIndex);
    }

    public boolean testCell (int flatCellIndex, TileCellPredicate<T> predicate) {
        var cellGrid = cellGrid();
        // Global cell index
        int yc = cellGrid.yForFlatIndex(flatCellIndex);
        int xc = cellGrid.xForFlatIndex(flatCellIndex);
        // Intra-tile index
        int yi = yc % TILE_SPAN_CELLS;
        int xi = xc % TILE_SPAN_CELLS;
        int intraTileFlatIndex = yi * TILE_SPAN_CELLS + xi;
        // Get the tile and call the supplied function with the tile and intra-tile index.
        T tile = tileForCell(flatCellIndex);
        return predicate.eval(tile, intraTileFlatIndex);
    }

    @FunctionalInterface
    public interface TileCellPredicate<T> {
        boolean eval (T tile, int intraCellIndex);
    }

    public T get (int flatTileIndex) {
        return tileForIndex.get(flatTileIndex);
    }

}
