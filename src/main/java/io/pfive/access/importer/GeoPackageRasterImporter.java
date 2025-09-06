// Copyright 2023-2025 Phase Five LLC.  For license terms, see LICENSE.txt in the repository root.

package io.pfive.access.importer;

import ar.com.hjg.pngj.FilterType;
import ar.com.hjg.pngj.ImageInfo;
import ar.com.hjg.pngj.ImageLineHelper;
import ar.com.hjg.pngj.ImageLineInt;
import ar.com.hjg.pngj.PngWriter;
import gnu.trove.map.TIntObjectMap;
import gnu.trove.map.hash.TIntObjectHashMap;
import io.pfive.access.authentication.UserIdentity;
import io.pfive.access.http.routing.RoutingDataCache;
import io.pfive.access.sparse.GridScheme;
import io.pfive.access.sparse.Opportunities;
import io.pfive.access.sparse.Serialization;
import io.pfive.access.sparse.TileGrid;
import io.pfive.access.store.JsonStore;
import org.geotools.coverage.grid.GridCoverageFactory;
import org.geotools.geometry.jts.GeometryClipper;
import org.geotools.geometry.jts.GeometryCoordinateSequenceTransformer;
import org.geotools.geometry.jts.ReferencedEnvelope;
import org.geotools.geopkg.FeatureEntry;
import org.geotools.geopkg.GeoPackage;
import org.geotools.referencing.CRS;
import org.geotools.referencing.crs.DefaultGeographicCRS;
import org.geotools.referencing.operation.transform.AffineTransform2D;
import org.geotools.referencing.operation.transform.ConcatenatedTransform;
import org.jaitools.jts.CoordinateSequence2D;
import org.locationtech.jts.geom.CoordinateSequence;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.geom.Polygonal;
import org.locationtech.jts.geom.prep.PreparedPolygon;
import org.opengis.coverage.grid.GridCoverage;
import org.opengis.coverage.grid.GridEnvelope;
import org.opengis.coverage.grid.GridGeometry;
import org.opengis.referencing.crs.CoordinateReferenceSystem;
import org.opengis.referencing.operation.MathTransform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.geom.AffineTransform;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.lang.invoke.MethodHandles;

import static io.pfive.access.sparse.SparseEgressBuilder.TILE_SPAN_CELLS;

/// Process geospatial files managed by this system and turn them into the Grids required by the routing library.
/// This could be refactored to be a GeoTools utility class that will work anywhere GeoTools is available,
/// and the injection of the rasterized data into Grids should be done in a much smaller adapter class.
/// Rasterization is optimized in several ways, primarily relying on PreparedPolygon and GeometryClipper.
/// Add to the README: http://switchfromshapefile.org
///
/// Anecdotal profiling rasterizing Sweden census zones to 1000x2000 grid:
/// - 205 sec calculating intersection area of every cell
/// - 108 sec with contains and !intersects conditions (yielding areas of 1 and 0)
/// - 96 sec reusing RelateOp and IntersectionMatrix
/// - 26 sec using PreparedGeometry preparedPolygon.contains(cell) (with either disjoint or intersects)
/// - 26 sec for nested preparedPolygon.intersects and preparedPolygon.contains.
///   - (54 sec when doubling raster in each dimension: 4x more pixels, but only takes 2x as long)
/// - 10.4 sec using GeometryClipper only (no containment or intersection tests).
/// - 3.5 sec using GeometryClipper nested with PreparedPolygon contains and intersection tests.
///   - (7.3 sec when doubling raster in each dimension: 4x more pixels, but only takes 2x as long)
///   - (20.9 sec when quadrupling raster in each dimension: 16x more pixels, but only takes 6x as long)
/// - 98.3% speedup from basic to optimized version on 1k by 2k grid.
public class GeoPackageRasterImporter extends GeoPackageImporter {
    private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    // TODO Check PNG output is identical between GeometryClipper and general purpose intersection.
    // TODO Definitely use GeometryClipper for Mapbox vector tile output too.
    // TODO next: Set up grids from resolution in meters. WGS Grids. Generalize rasterizer to all GeoTools data sources.
    // public GridDimension forResolution (double meters) { .. }

    // Instance Variables

    private final GridScheme tileGrid;
    private final GridScheme cellGrid;
    private final int widthCells;
    private final int heightCells;

    /// After rasterization, the raw data (to be written to several different file formats) will be held here.
    /// The axis order is (x, y) and y indexes increase with increasing latitude (unlike most image formats).
    /// The fact that this is a single field will not cooperate well with processing input files that have multiple
    /// "TypeNames" in the same file. It might make more sense if this was represented as a GeoTools GridCoverage.
    private final double[][] gridData;

    /// Create and reuse one transformer instance that persists through the whole import process.
    private GeometryCoordinateSequenceTransformer sourceToGridTransformer;

    public GeoPackageRasterImporter (GridScheme tileGridScheme) {
        super(tileGridScheme.wgsBounds());
        tileGrid = tileGridScheme;
        cellGrid = tileGridScheme.subdivide(TILE_SPAN_CELLS);
        widthCells = cellGrid.nCellsWide();
        heightCells = cellGrid.nCellsHigh();
        gridData = new double[widthCells][heightCells];
    }

    @Override
    protected void prepareCoordinateTransform () {
        // Compose two affine transforms to shift the corner of the grid to the origin, then scale
        // positions to use grid cells as units.
        // If the grid cells have been chosen to be roughly square in linear units (meters) this has the
        // advantage of making all area computations more accurate and in convenient units of grid cells.
        // Note: result of AffineTransform.concatenate performs the supplied operation BEFORE the existing.
        AffineTransform wgsToGrid = AffineTransform.getScaleInstance(1 / cellGrid.lonStep(), 1 / cellGrid.latStep());
        wgsToGrid.concatenate(AffineTransform.getTranslateInstance(-wgsBounds.minLon(), -wgsBounds.minLat()));
        MathTransform wgsToGridTransform = new AffineTransform2D(wgsToGrid);
        MathTransform sourceToGrid = ConcatenatedTransform.create(sourceToWgsTransform, wgsToGridTransform);
        sourceToGridTransformer = new GeometryCoordinateSequenceTransformer();
        sourceToGridTransformer.setMathTransform(sourceToGrid);
    }

    @Override
    protected void importOneFeature (Geometry sourceGeometry, double count) throws Exception {
        // Transform the incoming geometry into the CRS needed by the concrete importer.
        // Transforming Point coordinates yields a Point, Polygon yields Polygon.
        Geometry gridGeometry = sourceToGridTransformer.transform(sourceGeometry);
        // Handle the simple case of binning Point features into grid cells.
        if (gridGeometry instanceof Point gridPoint) {
            final int x = (int) Math.floor(gridPoint.getX());
            final int y = (int) Math.floor(gridPoint.getY());
            gridData[x][y] += count;
            // Point feature is fully handled, don't fall through to the polygon-handling code.
            return;
        }
        // Find the range of grid cells that could potentially intersect this geometry,
        // based on its minimum bounding box in the grid coordinate system.
        Envelope env = gridGeometry.getEnvelopeInternal();
        int minX = (int) Math.floor(env.getMinX());
        int minY = (int) Math.floor(env.getMinY());
        int maxX = (int) Math.ceil(env.getMaxX());
        int maxY = (int) Math.ceil(env.getMaxY());
        GeometryFactory gf = gridGeometry.getFactory();
        double densityPerPixel = count / gridGeometry.getArea();
        // Ignore parts of the polygon outside the output grid area.
        // Clamp cell iteration range to grid size.
        // Do not spend time evaluating cells outside the output grid.
        if (minX < 0) minX = 0;
        if (maxX > widthCells) maxX = widthCells;
        if (minY < 0) minY = 0;
        if (maxY > heightCells) maxY = heightCells;
        // Preparing the polygon once per feature does not seem to contribute significantly to runtime.
        PreparedPolygon preparedPolygon = new PreparedPolygon((Polygonal) gridGeometry);
        for (int x = minX; x < maxX; x += 1) {
            for (int y = minY; y < maxY; y++) {
                CoordinateSequence cellCoordinates = new CoordinateSequence2D(
                    x, y, x, y+1, x+1, y+1, x+1, y, x, y
                );
                Polygon cell = gf.createPolygon(gf.createLinearRing(cellCoordinates));
                double area;
                if (preparedPolygon.contains(cell)) {
                    area = 1;
                } else if (preparedPolygon.intersects(cell)) {
                    // Only compute the intersection geometry for cells straddling the polygon border.
                    // Rather than using the general-purpose intersection method:
                    // area = gridGeometry.intersection(cell).getArea();
                    // instead use GeometryClipper which is optimized for clipping to axis-aligned rectangles.
                    Envelope clipEnv = new Envelope(x, x+1, y, y+1);
                    GeometryClipper clipper = new GeometryClipper(clipEnv);
                    Geometry clipped = clipper.clip(gridGeometry, false);
                    if (clipped == null) continue;
                    area = clipped.getArea();
                } else {
                    continue;
                }
                gridData[x][y] += area * densityPerPixel;
            }
        }
    }

    /// Call after rasterizing to split giant unified 2D array into small sparse tiles.
    public TileGrid<Opportunities> intoTiles () {
        TIntObjectMap<Opportunities> tileMap = new TIntObjectHashMap<>();
        for (int ty = 0; ty < tileGrid.nCellsHigh(); ty++) {
            for (int tx = 0; tx < tileGrid.nCellsWide(); tx++) {
                // Copy sub-ranges of unified 2D array into small flat array for this single tile.
                float[] counts = new float[TILE_SPAN_CELLS * TILE_SPAN_CELLS];
                int nNonZero = 0;
                for (int cy = 0; cy < TILE_SPAN_CELLS; cy++) {
                    for (int cx = 0; cx < TILE_SPAN_CELLS; cx++) {
                        int xSource = tx * TILE_SPAN_CELLS + cx;
                        int ySource = ty * TILE_SPAN_CELLS + cy;
                        float val = (float)gridData[xSource][ySource];
                        if (val != 0) {
                            int outFlatIndex = cy * TILE_SPAN_CELLS + cx;
                            counts[outFlatIndex] = val;
                            nNonZero += 1;
                        }
                    }
                }
                // Only store tiles that have some nonzero data in them. Others are implicitly null.
                if (nNonZero > 0) {
                    int flatTileIndex = tileGrid.flatIndex(tx, ty);
                    tileMap.put(flatTileIndex, new Opportunities(counts));
                }
            }
        }
        return new TileGrid<>(tileGrid, tileMap);
    }

    @Override
    public void storeResults (String fileId, UserIdentity userIdentity) {
        progress.beginTask("Saving raster to files", 4);
        try {
            // Write the lower 3 bytes of integer opportunity counts into the RGB channels of a PNG.
            // TODO use the same method to write opportunity counts and cumulative access results as PNGs.
            File pngTemp = JsonStore.makeTempFile(fileId);
            this.writePng(pngTemp);
            JsonStore.storeFile(pngTemp.toPath(), fileId + ".png", userIdentity);
            progress.increment();
            // The main file (with no filename extension) is our native floating-point-capable format.
            // Serialized tiled float representation is about 110MB which is acceptable but could perhaps be compressed.
            File gridTemp = JsonStore.makeTempFile(fileId);
            // Convert giant unified 2D array to tiles.
            TileGrid<Opportunities> tiles = intoTiles();
            progress.increment();
            Serialization.write(gridTemp, tiles);
            progress.increment();
            LOG.info("Wrote grid {}. {} tiles out of {} contained opportunities.", fileId, tiles.tileForIndex().size(), tileGrid.nElements());
            JsonStore.storeFile(gridTemp.toPath(), fileId, userIdentity);
            progress.increment();
            // Push the resulting data structure into the cache so we can use it immediately.
            RoutingDataCache.putOpportunities(fileId, tiles);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // We may not want to have writePng and writeGrid methods here because that introduces new dependencies for
    // this class. It's probably better for it to return purely JTS/GeoTools types and those are saved elsewhere.
    // TODO inline this back into its only caller, as long as we're not performing the refactor mentioned above.
    public void writePng (File pngOutputFile) {
        System.out.println("Writing PNG " + pngOutputFile);
        try {
            streamPng(gridData, new BufferedOutputStream(new FileOutputStream(pngOutputFile)));
        } catch (FileNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    /// Based on internals of setPixelRGB8, channels are packed into 4 bytes as 0x__RRGGBB.
    /// The lower 3 bytes of our opportunity count will be interpreted as the RGB channels.
    /// This is truncating any fractional opportunities created by rasterization of polygons,
    /// but these PNG files are only for display and floating point will be used in computations.
    /// Since counts of over 65k per 100m cell are not expected, we could also use a fixed
    /// point representation with the blue channel carrying fractions.
    /// TODO Combine with travel time PNG output function. Pixel lookup can be supplied as a functional interface.
    /// TODO Combine with BatchFinalizeRunnable#writePng but note that we have int and double versions.
    /// Dimensions and geographic referencing supplied in a clean, reusable way.
    /// Note that BatchFinalizeRunnable#writePng() uses Java built-in `ImageIO.write(img, "png", outputStream);`
    public void streamPng (double[][] grid, OutputStream outputStream) {
        // Image will be 8 bits per channel with no alpha.
        ImageInfo imi = new ImageInfo(widthCells, heightCells, 8, false);
        PngWriter png = new PngWriter(outputStream, imi);
        png.setFilterType(FilterType.FILTER_ADAPTIVE_FAST);
        // Image line object can be reused for successive rows.
        ImageLineInt iline = new ImageLineInt(imi);
        // Write lines from top to bottom of image, from high to low latitude (iterate backward).
        for (int row = heightCells - 1; row >= 0; row -= 1) {
            for (int col = 0; col < widthCells; col++) {
                // TODO record error if 24-bit range is ever exceeded?
                double value = grid[col][row];
                int rgb = (int) Math.clamp(value, 0, 0x00FFFFFF);
                ImageLineHelper.setPixelRGB8(iline, col, rgb);
            }
            png.writeRow(iline);
        }
        // This call closes the OutputStream wrapped by the PngWriter.
        png.end();
    }

    /// This code fragment is looking into how to produce all needed transforms with a GridCoverage,
    /// and maybe store the rasterized data in the GridCoverage instance.
    private void gridCoverageExample () throws Exception {
        CoordinateReferenceSystem originalCrs = CRS.decode("EPSG:3006");
        MathTransform originalCrsToWgs = CRS.findMathTransform(originalCrs, DefaultGeographicCRS.WGS84, true);
        GridCoverageFactory gcf = new GridCoverageFactory();
        ReferencedEnvelope referencedEnvelope = wgsBounds.toReferencedEnvelope();
        float[][] data = new float[heightCells][widthCells]; // Data array axis order is height, width
        GridCoverage gc = gcf.create("name", data, referencedEnvelope);
        // GridGeometry represents the pixel grid and its relationship to the CRS (the GridEnvelope and MathTransform).
        GridGeometry gridGeometry = gc.getGridGeometry();
        // GridEnvelope is the size and shape of the grid itself, in integer units.
        GridEnvelope gridEnvelope = gridGeometry.getGridRange();
        // This creates a MathTransform that could be composed with the one for original CRS to WGS84.
        MathTransform wgsToGrid = gridGeometry.getGridToCRS().inverse();
        // This should project objects into grid cell coordinates.
        MathTransform originalCrsToGrid = ConcatenatedTransform.create(originalCrsToWgs, wgsToGrid);
        // JTS.transform(geom, originalCrsToGrid); ...
    }

    private static void example () throws Exception {
        // Open GeoPackage file in read-only mode with no username or password.
        // TODO is this a different way of accessing and loading GeoPackages? Is there a reason we aren't using this
        // instead of old generic GeoTools feature interation? This gt-geopkg GeoPackage class seems to load all the
        // features into a List in memory before returning them. How does FeatureIterator do it?
        File geoPackageFile = new File("geoPackageFilePath");
        GeoPackage geoPackage = new GeoPackage(geoPackageFile, null, null, true);
        for (FeatureEntry entry : geoPackage.features()) {
            System.out.println("entry = " + entry);
        }
    }



}
