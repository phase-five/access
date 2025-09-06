// Copyright 2023-2025 Phase Five LLC.  For license terms, see LICENSE.txt in the repository root.

package io.pfive.access.sparse;

import com.conveyal.r5.profile.StreetMode;
import com.conveyal.r5.streets.StreetLayer;
import com.conveyal.r5.streets.StreetRouter;
import com.conveyal.r5.transit.TransitLayer;
import com.conveyal.r5.transit.TransportNetwork;
import io.pfive.access.background.ProgressSink;
import io.pfive.access.importer.Wgs84Bounds;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.MethodHandles;
import java.util.function.Function;
import java.util.stream.IntStream;

import static com.conveyal.r5.streets.StreetRouter.State.RoutingVariable.DISTANCE_MILLIMETERS;

/// Logic for building sparse grids of egress information in [SparseEgressTable] instances.
/// At this point they are based on only a TransportNetwork. The StreetLayer alone allows finding
/// the StreetConnectors, and the TransitLayer provides the transit stops for building egress tiles.
public abstract class SparseEgressBuilder {
    private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    /// Using original definition of the meter, good enough as average value along the ellipsoid.
    public static final double METERS_PER_DEGREE_LAT = 111111.111111;
    public static final double DEGREES_LAT_PER_METER = 1 / METERS_PER_DEGREE_LAT;
    public static final double TARGET_CELL_SIZE_METERS = 100.0; // 200 to test faster batch preview
    public static final int EGRESS_DISTANCE_METERS = 1000; // FIXME Use value from request

    /// With Span of 8, fill ratio of stop-to-distance matrix in egress tiles is only 10-50 percent.
    /// With Span of 16 this ratio is even lower, implying a lot of wasted calculation.
    /// Packed lists of (stop, distance) may be a better option than grids. Grids may enable SIMD.
    /// Multiples of 8 (usually 8 or 16) will allow SIMD without special cases for extra lanes.
    public static final int TILE_SPAN_CELLS = 16;

    /// Create a new tile grid with standard (100m) grid cells enclosing the given Wgs84Bounds.
    /// This will be the minimum size grid fully enclosing the bounds, and centered on the bounds.
    /// Potentially move to static method on GridScheme
    public static GridScheme tileGridContaining (Wgs84Bounds bounds) {
        double cellSizeLat = TARGET_CELL_SIZE_METERS * DEGREES_LAT_PER_METER;
        double cellSizeLon = cellSizeLat * bounds.xScale();
        double tileSizeLat = cellSizeLat * TILE_SPAN_CELLS;
        double tileSizeLon = cellSizeLon * TILE_SPAN_CELLS;
        // Enlarge bounds as needed to contain an integer number of tiles.
        int nTilesHigh = (int) (Math.ceil(bounds.heightLat() / tileSizeLat));
        int nTilesWide = (int) (Math.ceil(bounds.widthLon() / tileSizeLon));
        int nCellsHigh = nTilesHigh * TILE_SPAN_CELLS;
        int nCellsWide = nTilesWide * TILE_SPAN_CELLS;
        Wgs84Bounds revisedBounds = bounds.resizeCentered(cellSizeLon * nCellsWide, cellSizeLat * nCellsHigh);
        return new GridScheme(revisedBounds, nTilesWide, nTilesHigh);
    }

    /// Convenience method creating a new tile grid encompassing all streets in the given network.
    /// This will be the minimum size grid fully enclosing and centered on their bounds.
    public static GridScheme tileGridContaining (TransportNetwork network) {
        Wgs84Bounds bounds = Wgs84Bounds.fromWgsEnvelope(network.streetLayer.getEnvelope());
        return tileGridContaining(bounds);
    }

    /// Reports progress as it builds the connectors, street search trees, and egress time tiles.
    public static SparseEgressTable build (TransportNetwork network, ProgressSink progress) {
        // Determine top-level grid dimensions.
        // Note: R5 WebMercatorExtents constructor will not allow more than 5M points.
        // Avoid using network.fullExtentGridPointSet. Disable creating unevictable linkage when building network.
        GridScheme tileGridScheme = tileGridContaining(network);
        // Create connectors for every tile. For each tile iterate over all the pixels it contains.
        // Find a street network connector from each pixel to the supplied street network. If no
        // pixel in a tile has a connector, the tile cannot be reached and will be left out of the
        // final data structure (its slot will be null).
        StreetConnectors[] connectorTiles = new StreetConnectors[tileGridScheme.nElements()];
        progress.beginTask("Connecting tiles to street network", connectorTiles.length);
        // The number of tiles can be huge, set custom progress update frequency and throttling.
        // TODO better logic to automatically adjust throttling dynamically.
        if (connectorTiles.length > 10_000) {
            progress.pushEventAfter(50); // 20
            progress.minTimeBetweenEventsMsec(100);
        }
        connectorTiles = IntStream.range(0, tileGridScheme.nCellsHigh()).parallel()
              .mapToObj(ty -> IntStream.range(0, tileGridScheme.nCellsWide()).parallel()
                    .mapToObj(tx -> {
                        Wgs84Bounds tileBounds = tileGridScheme.boundsForCell(tx, ty);
                        GridScheme cellGrid = new GridScheme(tileBounds, TILE_SPAN_CELLS, TILE_SPAN_CELLS);
                        var connectorBuilder = new StreetConnectors.Builder(network.streetLayer);
                        // TODO GridScheme.forEachCellCenter((lat, lon)->void) Functions.LonLatConsumer interface.
                        for (int cy = 0; cy < cellGrid.nCellsHigh(); cy++) {
                          for (int cx = 0; cx < cellGrid.nCellsWide(); cx++) {
                              double cellCenterLon = cellGrid.centerLonForX(cx);
                              double cellCenterLat = cellGrid.centerLatForY(cy);
                              connectorBuilder.findConnector(cellCenterLon, cellCenterLat);
                          }
                        }
                        StreetConnectors connectors = connectorBuilder.build();
                        progress.increment();
                        return connectors;
                    })
              ).flatMap(Function.identity()).toArray(StreetConnectors[]::new);
        final TransitLayer transitLayer = network.transitLayer;
        final StreetLayer streetLayer = network.streetLayer;
        final int nStops = transitLayer.getStopCount();
        progress.beginTask("Finding egress times from transit stops", nStops);
        // Pre-fill the result array with egress builders in only those cells with StreetConnectors.
        EgressTimes.Builder[] egressBuilders = new EgressTimes.Builder[connectorTiles.length];
        for (int i = 0; i < connectorTiles.length; i++) {
            StreetConnectors connectors = connectorTiles[i];
            egressBuilders[i] = (connectors == null) ? null : new EgressTimes.Builder(connectors);
        }
        IntStream.range(0, nStops).parallel().forEach(s -> {
            int v = transitLayer.streetVertexForStop.get(s);
            if (v >= 0) {
                var stopVertex = streetLayer.vertexStore.getCursor(v);
                StreetRouter router = new StreetRouter(streetLayer);
                router.streetMode = StreetMode.WALK;
                // router.setOrigin(v) will not work correctly with non-walk modes. Although link
                // edges allowAllModes, they are often connected to pedestrian walkways in and
                // around stations. Setting to latlon will connect car or bike to the closest road.
                router.setOrigin(stopVertex.getLat(), stopVertex.getLon());
                router.quantityToMinimize = DISTANCE_MILLIMETERS;
                router.distanceLimitMeters = EGRESS_DISTANCE_METERS;
                router.route();
                var streetVertexCosts = StreetVertexCosts.fromRouter(router);
                Wgs84Bounds searchBounds = streetVertexCosts.wgsBoundsForReached(streetLayer.vertexStore);
                // Clamp to grid, yielding zero iterations over ranges entirely outside grid.
                int xMin = Math.max(tileGridScheme.xForLon(searchBounds.minLon()), 0);
                int xMax = Math.min(tileGridScheme.xForLon(searchBounds.maxLon()), tileGridScheme.nCellsWide() - 1);
                int yMin = Math.max(tileGridScheme.yForLat(searchBounds.minLat()), 0);
                int yMax = Math.min(tileGridScheme.yForLat(searchBounds.maxLat()), tileGridScheme.nCellsHigh() - 1);
                // Note that iteration ranges are inclusive of max.
                for (int y = yMin; y <= yMax; y++) {
                    for (int x = xMin; x <= xMax; x++) {
                        int flatTileIndex = tileGridScheme.flatIndex(x, y);
                        EgressTimes.Builder egressBuilder = egressBuilders[flatTileIndex];
                        if (egressBuilder != null) {
                            // There are connected streets in this tile. Record distances from stop.
                            // Eventually push/pull logic around between methods on these classes.
                            egressBuilder.addStop(s, streetVertexCosts);
                        }
                    }
                }
            }
            progress.increment();
        });

        EgressTimes[] egressTimes = new EgressTimes[egressBuilders.length];
        for (int i = 0; i < egressTimes.length; i++) {
            EgressTimes.Builder builder = egressBuilders[i];
            egressTimes[i] = (builder == null) ? null : builder.build();
            egressBuilders[i] = null; // Garbage collect builder arrays.
        }
        // Construct immutable record from grid scheme, connectors, and egress durations.
        return new SparseEgressTable(tileGridScheme, egressTimes, connectorTiles);
    }

}
