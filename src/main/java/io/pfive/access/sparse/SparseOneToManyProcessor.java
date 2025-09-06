// Copyright 2023-2025 Phase Five LLC.  For license terms, see LICENSE.txt in the repository root.

package io.pfive.access.sparse;

import com.conveyal.r5.analyst.cluster.AnalysisWorkerTask;
import com.conveyal.r5.profile.FastRaptorWorker;
import com.conveyal.r5.profile.StreetMode;
import com.conveyal.r5.streets.StreetRouter;
import com.conveyal.r5.transit.TransportNetwork;
import gnu.trove.map.TIntIntMap;
import io.pfive.access.authentication.UserIdentity;
import io.pfive.access.http.model.OneToManyOptions;
import io.pfive.access.http.routing.RoutingDataCache;
import io.pfive.access.importer.Wgs84Bounds;
import io.pfive.access.util.JettyUtil;

import static com.conveyal.r5.streets.StreetRouter.State.RoutingVariable.DURATION_SECONDS;
import static io.pfive.access.sparse.EgressTimes.MAX_SEC;

/// This handles calculating the one-to-many travel times and access for sparse destination grids.
/// It keeps the implementations separate from the HTTP handler and batch tasks that call it.
/// TODO Work with a "request context" rctx that can hold request-scoped things besides options.
///      This can be done on the request itself with setAttribute.
public class SparseOneToManyProcessor {

    public static final int[] STANDARD_CUTOFFS = new int[] {30, 45, 60};
    public static final int[] STANDARD_PERCENTILES = new int[] {1, 50, 99};

    private final OneToManyOptions options;
    private final UserIdentity user;

    private double overrideLat = Double.NaN;
    private double overrideLon = Double.NaN;
    // TODO bundle GridScheme with durations into a single return type.
    // Travel time display does not need to use the Web Mercator raster tiles in MapboxGLJS.
    // Instead we just dynamically create a single rectangular envelope around the reached area.
    public boolean writePng = true;
    private GridScheme outputPixelGrid;
    private Durations outputDurations;
    public AccessBins accessBins;
    public byte[] pngBytes;

    public SparseOneToManyProcessor (OneToManyOptions options, UserIdentity user) {
        this.options = options;
        this.user = user;
    }

    /// Supply new lon and lat values that will override those in the OneToManyOptions. Fields on
    /// that class are all final including lat and lon, so we can't just modify a protective copy.
    public void overrideLatLon (double lat, double lon) {
        overrideLat = lat;
        overrideLon = lon;
    }

    /// Do not fire any server-sent messages to the user from here, as it is used in batch jobs.
    public void process () {
        OtmTimer timer = new OtmTimer();
        TransportNetwork network = RoutingDataCache.getNetwork(options.networkId, user);
        timer.start(timer.street);
        StreetRouter router = new StreetRouter(network.streetLayer);
        // Generally we always try to minimize travel time. But downstream we currently expect distance tables.
        router.quantityToMinimize = DURATION_SECONDS;
        // router.quantityToMinimize = DISTANCE_MILLIMETERS;
        router.streetMode = StreetMode.WALK; // WALK or BICYCLE
        // RaptorState will fail if ReachedStops are more than 7200 sec (120 minutes) away.
        // router.distanceLimitMeters = 1000;
        // Finding transit stops farther than the max routing duration will confuse the transit router.
        router.timeLimitSeconds = 60 * Math.min(options.duration, 60); // What is determining the walk speed?
        boolean foundOrigin = (Double.isFinite(overrideLat))
              ? router.setOrigin(overrideLat, overrideLon)
              : router.setOrigin(options.lat, options.lon);
        if (!foundOrigin) {
            // Leave AccessBins null to signal failure. We may want to return an empty image.
            accessBins = AccessBins.zero();
            return;
        }
        router.route();
        StreetVertexCosts vertexCosts = StreetVertexCosts.fromRouter(router);
        SparseEgressTable egressTable = RoutingDataCache.getEgress(options.egressId, user);
        // Iterate over tiles intersecting street search bounding box and evaluate travel times.
        // Use bounds based on the straight line distance and walk speed, as tiles outside those
        // containing the reached vertices may be reachable via street segments that exit the tile.
        // Vertices can affect tiles other than the one that contains them, so we don't want tight
        // bounds around the reached vertices. Instead, use maximum radius based on walk speed.
        final double streetSpeed = StreetConnectors.SPEED_MM_PER_SEC / 1000.0;
        // final double streetSpeed = 4.0;
        final double streetRadiusMeters = router.timeLimitSeconds * streetSpeed;
        Wgs84Bounds streetBounds = Wgs84Bounds.bufferPoint(options.lon, options.lat, streetRadiusMeters);
        EgressTableWindow streetWindow = egressTable.subWindow(streetBounds);

        // There is always a street search (though it may yield an empty envelope.)
        // There is not always a transit search. After running the street and transit searches,
        // there is a single propagation pipeline, processing one tile at a time, with the
        // transit and street search sections of the pipeline conditional on location and settings.

        // Must be final and definitively initialized for use in closures below.
        final TransitTimes transitTimes = switch (options.transitModes.isEmpty()) {
            case true -> null;
            default -> {
                timer.start(timer.transit);
                // If no transit stops were reached, avoid performing transit search.
                // The raptor search takes at least 0.1 sec even when no starting points are supplied.
                TIntIntMap reachedStops = router.getReachedStops();
                if (reachedStops.isEmpty()) yield null;
                // This R5-specific type is needed to construct and configure a FastRaptorWorker.
                // It appears that we can use a TravelTimeSurfaceTask for both interactive and batch.
                // Maximum rides and duration affect raptor search time, but making TransitTimes is also slow.
                // Make a SparseTransitTimes using TIntIntMap or TIntFloatMap?
                AnalysisWorkerTask task = options.toTravelTimeSurfaceTask();
                task.maxRides = 3;
                var raptor = new FastRaptorWorker(network.transitLayer, task, reachedStops);
                raptor.retainPaths = false;
                yield TransitTimes.fromRaptorResult(raptor.route());
            }
        };

        timer.start(timer.propagation);
        // Record full PNG output envelope covering all relevant tiles and prepare output buffers.
        // Note transitBounds are discarded here, as they are expanded to encompass entire tiles.
        final TileRange outputWindow = egressTable.tileRange(
              transitBounds(transitTimes, network, streetBounds, streetRadiusMeters));
        outputPixelGrid = outputWindow.pixelGrid();
        if (writePng) outputDurations = Durations.unreached(outputPixelGrid.nElements());
        // Initializing to zero instead of UNREACHED makes tile-based rendering visisble in PNG.
        // outputDurations = Durations.allZero(outputPixelGrid.nElements());
        accessBins = AccessBins.zero();

        // Is pipelining all this is actually better than just keeping the tiles and intermediate results
        // in memory and performing one operation at a time? It might be better for the instruction cache.
        TileGrid<Opportunities> oppGrid = RoutingDataCache.getOpportunities(options.destinationId, user);
        // TODO iteration method supplying both parentIndex and local relative x, y inside window.
        outputWindow.forEachFlatParentIndex(flatIndex -> {
            Opportunities opportunities = oppGrid.get(flatIndex);
            // In contexts with no travel time visualization (batch runs) we can skip tiles with no opportunities.
            if (!writePng && opportunities == null) return;
            Durations streetDurations = null;
            if (streetWindow.tileRange().containsParentIndex(flatIndex)) {
                // Runs only when the tile is within the smaller street search bounds.
                StreetConnectors connectors = egressTable.connectors()[flatIndex];
                if (connectors != null) streetDurations = connectors.eval(vertexCosts);
            }
            EgressTimes egressTimes = egressTable.egressTimes()[flatIndex];
            Durations transitDurations = null;
            // TODO make apply() static and fold null checks into it?
            if (egressTimes != null) transitDurations = egressTimes.apply(transitTimes);
            if (transitDurations != null) {
                transitDurations.imposeMaxMinutes(options.duration);
            }
            Durations mergedDurations = Durations.mergeMin(streetDurations, transitDurations);
            if (mergedDurations == null) return;
            AccessBins tileAccess = (opportunities == null) ? null : opportunities.apply(mergedDurations);
            if (tileAccess != null) accessBins.accumulate(tileAccess);
            // Finally, write pixels to output grid
            // TODO combine outputDurations, outputPixelGrid, and write methods into new class for this purpose
            if (writePng) {
                outputWindow.writeParentTile(mergedDurations, outputDurations, outputPixelGrid, flatIndex);
            }
        });

        // TODO at request scope, combine references to the three separate arrays (connectors, egress, opportunities)
        //   into a single compound object, supplied with methods that combine them, plus subgrid slicing.

        if (writePng) {
            // Width and height here should are integer numbers of pixels/cells in the image.
            timer.start(timer.png);
            var geoPngWriter = new SparseGeoPngWriter(
                  outputPixelGrid.nCellsWide(), outputPixelGrid.nCellsHigh(), outputDurations, accessBins);
            this.pngBytes = geoPngWriter.writeBytes();
        }
        timer.done();
    }

    /// Find a minimal bounding box around all transit stops reached by a raptor search in less
    /// than 120 minutes. The bounds will be expanded to include the area reached in the supplied
    /// streetBounds, and to account for neighboring areas reachable from the stops. If the
    /// TransitTimes are null or reach nothing, the streetBounds will be returned unchanged.
    private static Wgs84Bounds transitBounds (
          TransitTimes transitTimes,
          TransportNetwork transportNetwork,
          Wgs84Bounds streetBounds,
          double streetRadiusMeters
    ) {
        if (transitTimes == null) return streetBounds;
        int nStops = transportNetwork.transitLayer.getStopCount();
        var vertex = transportNetwork.streetLayer.vertexStore.getCursor();
        var boundsBuilder = new Wgs84Bounds.Builder();
        for (int s = 0; s < nStops; s++) {
            int t = transitTimes.min[s];
            if (t < MAX_SEC) {
                int stopVertex = transportNetwork.transitLayer.streetVertexForStop.get(s);
                if (stopVertex == -1) continue; // Vertex not connected to streets.
                vertex.seek(stopVertex);
                boundsBuilder.includePoint(vertex.getLon(), vertex.getLat());
            }
        }
        Wgs84Bounds transitBounds = boundsBuilder.build();
        if (transitBounds == null) return streetBounds;
        // Transit stops may affect tiles outside the one that contains them.
        // We are also going to work on both transit and street output in the next step.
        return transitBounds.bufferMeters(streetRadiusMeters).encompass(streetBounds);
    }

    public String getBboxHeaderValue () {
        return JettyUtil.getBboxHeaderValue(outputPixelGrid.wgsBounds());
    }

}
