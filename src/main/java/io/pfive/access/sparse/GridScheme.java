// Copyright 2023-2025 Phase Five LLC.  For license terms, see LICENSE.txt in the repository root.

package io.pfive.access.sparse;

import io.pfive.access.importer.Wgs84Bounds;

import static io.pfive.access.sparse.SparseEgressBuilder.TILE_SPAN_CELLS;

/// Converts geographic locations to (x, y) cell coordinates used to look up tiles or pixels.
/// All cells are the same size, with a single latitude and longitude step value across the whole
/// grid. At large scales, cells will vary in area depending on latitude. But for countries up to
/// the size of France, this is a reasonable approach and has the advantage of simplicity.
/// France extends from 42 to 51 degrees, so the longitude scale factor ranges from 0.74 to 0.63,
/// or about 7% on either side of the average scale factor. Web Mercator has the same problem, and
/// only a custom local projection would solve it. We want both uniform step sizes (in meters) and
/// integer numbers of pixels. The factory methods attempt to balance these factors.
public record GridScheme (Wgs84Bounds wgsBounds, int nCellsWide, int nCellsHigh) {

    public static int BYTES = Wgs84Bounds.BYTES + Integer.BYTES * 2;

    public double lonStep () {
        return wgsBounds.widthLon() / nCellsWide;
    }

    public double latStep () {
        return wgsBounds.heightLat() / nCellsHigh;
    }

    public int nElements () {
        return nCellsHigh * nCellsWide;
    }

    public int xForLon (double lon) {
        return (int)((lon - wgsBounds.minLon()) / lonStep());
    }

    public int yForLat (double lat) {
        return (int)((lat - wgsBounds.minLat()) / latStep());
    }

    public double minLonForX (int x) {
        return wgsBounds.minLon() + (x * lonStep());
    }

    public double minLatForY (int y) {
        return wgsBounds.minLat() + (y * latStep());
    }

    public double centerLonForX (int x) {
        return wgsBounds.minLon() + ((x + 0.5) * lonStep());
    }

    public double centerLatForY (int y) {
        return wgsBounds.minLat() + ((y + 0.5) * latStep());
    }

    /// Does not perform range checks, for use in constrained iteration over provably safe ranges.
    public int flatIndex (int x, int y) {
        return y * nCellsWide + x;
    }

    /// Performs range checks on the inputs and returns -1 for points that are not binned into the
    /// cells of this grid. This is easier to reason about than checking the latitude and longitude
    /// directly against the bounds of the grid, as points on or near the boundaries might be binned
    /// into cells outside the grid range. Simple inequalities may be more efficient but would
    /// require a more thorough analysis of those boundary effects.
    public int flatIndexForLonLat (double lon, double lat) {
        int x = xForLon(lon);
        if (x < 0 || x >= nCellsWide) return -1;
        int y = yForLat(lat);
        if (y < 0 || y >= nCellsHigh) return -1;
        return flatIndex(x, y);
    }

    public Wgs84Bounds boundsForCell (int x, int y) {
        return new Wgs84Bounds(minLonForX(x), minLatForY(y), lonStep(), latStep());
    }

    public Wgs84Bounds boundsForCellIndex (int flatIndex) {
        int x = xForFlatIndex(flatIndex);
        int y = yForFlatIndex(flatIndex);
        return boundsForCell(x, y);
    }

    /// Interpreting the cells of this grid as tiles, return the smallest range of those tiles that
    /// contains the given WGS94 bounding box. TODO it actually doesn't matter if they are tiles. Just cells.
    public TileRange tileRange (Wgs84Bounds subBounds) {
        int xMin = Math.max(xForLon(subBounds.minLon()), 0);
        int xMax = Math.min(xForLon(subBounds.maxLon()), nCellsWide - 1);
        int yMin = Math.max(yForLat(subBounds.minLat()), 0);
        int yMax = Math.min(yForLat(subBounds.maxLat()), nCellsHigh - 1);
        if (xMin > xMax || yMin > yMax) return null;
        int nCellsWide = xMax - xMin + 1;
        int nCellsHigh = yMax - yMin + 1;
        double minLon = minLonForX(xMin);
        double minLat = minLatForY(yMin);
        double maxLon = minLonForX(xMax + 1);
        double maxLat = minLatForY(yMax + 1);
        double widthLon = maxLon - minLon;
        double heightLat = maxLat - minLat;
        Wgs84Bounds revisedSubBounds = new Wgs84Bounds(minLon, minLat, widthLon, heightLat);
        return new TileRange(this, xMin, yMin, nCellsWide, nCellsHigh, revisedSubBounds);
    }

    /// Return a TileRange covering the entire GridScheme, mainly so we can apply the range
    /// iteration methods over the entire grid.
    public TileRange fullTileRange (Wgs84Bounds subBounds) {
        return new TileRange(this, 0, 0, nCellsWide, nCellsHigh, wgsBounds);
    }

    /// Returns a new grid like this one, but with the number of cells in each dimension increased
    /// by the given factor.
    public GridScheme subdivide (int factor) {
        return new GridScheme(
              wgsBounds,
              nCellsWide * factor,
              nCellsHigh * factor
        );
    }

    public GridScheme tilesToCells () {
        return this.subdivide(TILE_SPAN_CELLS);
    }

    public int xForFlatIndex (int flatIndex) {
        return flatIndex % nCellsWide;
    }

    public int yForFlatIndex (int flatIndex) {
        return flatIndex / nCellsWide;
    }

}
