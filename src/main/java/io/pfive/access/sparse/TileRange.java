// Copyright 2023-2025 Phase Five LLC.  For license terms, see LICENSE.txt in the repository root.

package io.pfive.access.sparse;

import io.pfive.access.importer.Wgs84Bounds;

import java.util.function.IntConsumer;
import java.util.function.IntFunction;

import static io.pfive.access.sparse.SparseEgressBuilder.TILE_SPAN_CELLS;

public record TileRange (GridScheme parent, int xMin, int yMin, int nTilesWide, int nTilesHigh, Wgs84Bounds wgsBounds) {

    /// Make a grid encompassing all tiles in this TileRange, but whose cells are the cells in the
    /// tiles rather than the tiles themselves. That is, unify the cell grid of all the tiles into
    /// one large grid of cells. Maybe this is a "unifiedCellGrid".
    public GridScheme pixelGrid () {
        return new GridScheme(wgsBounds, nTilesWide * TILE_SPAN_CELLS, nTilesHigh * TILE_SPAN_CELLS);
    }

    /// Read through to the connectors and egressTiles of the wrapped SparseEgressTable.
    /// Use nested iteration over tx, ty, cx, cy to translate flat indexes within the output area.
    /// Then write those values into a bigger flat array corresponding to subGridScheme's pixels.
    /// Note that StreetConnectors and EgressTimes do not know their own GridScheme or envelopes.
    /// So we currently have to rely on constant tile sizes in pixels. This should be configurable.
    /// TODO: There is duplicate code in transit version of this method. Factor out DurationComputer FunctionalInterface.
    /// This is a candidate for SIMD using the Java Vector API, writing by rows into the outputDurations.
    /// It may even be possible to use SIMD types as parameters to pluggable functions.
    /// It is not yet known whether pre-filling with UNREACHED is faster than writing UNREACHED only in skipped tiles.
    /// In brief testing, using intStream.range.parallel doesn't provide much if any speedup here,
    /// probably because this is memory-bound and all tasks are writing into the same target array.
    /// SIMD might be more important here, check whether JIT is outputting SIMD instructions.
    /// @param durationExtractor may return null to signal that a tile should be skipped
    public Durations durationsFrom (IntFunction<Durations> durationExtractor) {
        GridScheme pixelGrid = pixelGrid();
        // Flattened arrays of pixels spanning all the tiles.
        Durations merged = Durations.unreached(pixelGrid.nElements());
        // Outer iteration over a subset of tiles
        for (int ys = 0; ys < nTilesHigh; ys++) {
            for (int xs = 0; xs < nTilesWide; xs++) {
                int xw = xs + xMin;
                int yw = ys + yMin;
                int flatIndex = parent.flatIndex(xw, yw);
                Durations durations = durationExtractor.apply(flatIndex);
                if (durations == null) continue;
                // Inner iteration over pixels in one tile
                for (int yc = 0; yc < TILE_SPAN_CELLS; yc++) {
                    for (int xc = 0; xc < TILE_SPAN_CELLS; xc++) {
                        // This could also be done by creating a GridScheme for each tile.
                        int cellIndexInTile = yc * TILE_SPAN_CELLS + xc;
                        int yp = ys * TILE_SPAN_CELLS + yc;
                        int xp = xs * TILE_SPAN_CELLS + xc;
                        int pixelIndex = pixelGrid.flatIndex(xp, yp);
                        // TODO Factor out method for writing one tile into the merged grid.
                        if (durations.min != null) merged.min[pixelIndex] = durations.min[cellIndexInTile];
                        if (durations.avg != null) merged.avg[pixelIndex] = durations.avg[cellIndexInTile];
                        if (durations.max != null) merged.max[pixelIndex] = durations.max[cellIndexInTile];
                    }
                }
            }
        }
        return merged; // Could include pixel grid in Durations structure.
    }

    public void forEachFlatParentIndex (IntConsumer consumer) {
        for (int ys = 0; ys < nTilesHigh; ys++) {
            for (int xs = 0; xs < nTilesWide; xs++) {
                int xw = xs + xMin;
                int yw = ys + yMin;
                int flatIndex = parent.flatIndex(xw, yw);
                consumer.accept(flatIndex);
            }
        }
    }

    public boolean containsParentIndex (int flatIndex) {
        int x = parent.xForFlatIndex(flatIndex);
        int y = parent.yForFlatIndex(flatIndex);
        return x >= xMin && x < xMin + nTilesWide && y >= yMin && y < yMin + nTilesHigh;
    }

    public void writeParentTile (Durations tile, Durations out, GridScheme pixelGrid, int tileIndex) {
        // Global x y in full tile grid
        int xg = parent.xForFlatIndex(tileIndex);
        int yg = parent.yForFlatIndex(tileIndex);
        // Local x y in window tile grid
        int xw = xg - xMin;
        int yw = yg - yMin;
        // Iteration over cells in one tile
        for (int yc = 0; yc < TILE_SPAN_CELLS; yc++) {
            for (int xc = 0; xc < TILE_SPAN_CELLS; xc++) {
                int cellIndexInTile = yc * TILE_SPAN_CELLS + xc;
                int xp = xw * TILE_SPAN_CELLS + xc;
                int yp = yw * TILE_SPAN_CELLS + yc;
                int pixelIndex = pixelGrid.flatIndex(xp, yp);
                // TODO Factor out method for writing one tile into the merged grid.
                //   Looks like a candidate for a MinAvgMax operator of some kind.
                if (tile.min != null) out.min[pixelIndex] = tile.min[cellIndexInTile];
                if (tile.avg != null) out.avg[pixelIndex] = tile.avg[cellIndexInTile];
                if (tile.max != null) out.max[pixelIndex] = tile.max[cellIndexInTile];
            }
        }
    }
}
