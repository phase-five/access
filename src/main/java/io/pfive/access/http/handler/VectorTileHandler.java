// Copyright 2023-2025 Phase Five LLC.  For license terms, see LICENSE.txt in the repository root.

package io.pfive.access.http.handler;

import com.conveyal.analysis.controllers.NetworkTileController;
import com.conveyal.analysis.util.VectorMapTile;
import com.conveyal.gtfs.GTFSCache;
import com.conveyal.gtfs.GeometryCache;
import com.conveyal.r5.transit.TransportNetwork;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.wdtinc.mapbox_vector_tile.adapt.jts.model.JtsLayer;
import io.pfive.access.authentication.UserIdentity;
import io.pfive.access.filepool.FileMetadata;
import io.pfive.access.filepool.FileStorageAdapter;
import io.pfive.access.filepool.FileType;
import io.pfive.access.http.routing.RoutingDataCache;
import io.pfive.access.store.JsonStore;
import io.pfive.access.util.JettyUtil;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.CoordinateSequence;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.index.strtree.STRtree;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/// Produce Mapbox vector tiles for OSM or GTFS data.
public class VectorTileHandler extends Handler.Abstract {

    private final GTFSCache gtfsCache = new GTFSCache(new FileStorageAdapter());
    private final NetworkTileController networkTileController;
    private final Method privateMethod;

    private static void resizeGeometryCache (GeometryCache<?> gCache, int maxSize) {
        try {
            Field cacheField = GeometryCache.class.getDeclaredField("cache");
            cacheField.setAccessible(true);
            LoadingCache<String, STRtree> loadingCache = (LoadingCache) cacheField.get(gCache);
            loadingCache.policy().eviction().get().setMaximum(maxSize);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public VectorTileHandler () {
        // PACA has 21 GTFS feeds
        resizeGeometryCache(gtfsCache.patternShapes, 30);
        resizeGeometryCache(gtfsCache.stops, 30);
        // This instance is only used as a context for an essentially static method call, so don't inject dependencies.
        this.networkTileController = new NetworkTileController(null);
        try {
            // Via reflection, force this private method to be public and retain a reference to it.
            Method eg = NetworkTileController.class.getDeclaredMethod(
                "getClippedAndProjectedEdgeGeometries", TransportNetwork.class, VectorMapTile.class);
            eg.setAccessible(true);
            this.privateMethod = eg;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public boolean handle (Request request, Response response, Callback callback) throws Exception {
        if (!request.getMethod().equals("GET")) {
            return JettyUtil.respondClientError("Method must be GET.", response, callback);
        }
        final UserIdentity user = JettyUtil.extractUserIdentity(request);
        String subPath = Request.getPathInContext(request);
        String[] parts = subPath.split("/");
        // The string is expected to start with a slash (the one immediately after 'mvt'), so part 0 is empty.
        if (parts.length != 6) {
            return JettyUtil.respondClientError("URL format: /mvt/[gtfs|osm]/id/z/x/y.mvt", response, callback);
        }
        String type = parts[1];
        String id = parts[2];
        int zoom = Integer.parseInt(parts[3]);
        int x = Integer.parseInt(parts[4]);
        String yWithExtension = parts[5];
        if (!yWithExtension.endsWith(".mvt")) {
            return JettyUtil.respondClientError("URL should end with .mvt", response, callback);
        }
        int y = Integer.parseInt(yWithExtension.substring(0, yWithExtension.length() - 4));
        VectorMapTile vectorMapTile = new VectorMapTile(zoom, x, y);
        byte[] tileBytes = null;
        if (type.equals("gtfs")) {
            FileMetadata metadata = JsonStore.retrieveFile(id, user).fileMetadata();
            // Detect whether the supplied ID is a network ID or GTFS ID, collecting all relevant GTFS IDs.
            List<String> gtfsIds = new ArrayList<>();
            if (metadata.fileType == FileType.NETWORK) {
                gtfsIds.addAll(metadata.sources.get(FileType.GTFS));
            } else if (metadata.fileType == FileType.GTFS) {
                gtfsIds.add(id);
            }
            // Collect all LineString geometries from all relevant GTFS feeds for rendering into one tile.
            // TODO tiered spatial index per TransportNetwork (for cases with many GTFS feeds).
            // TODO web Mercator tile-based spatial index fits better with vector and image tile use cases.
            // TODO Simpler zero-copy case for single GTFS feed case? Query already constructs an ArrayList.
            List<LineString> patternLineStrings = new ArrayList<>();
            List<Point> stopPoints = new ArrayList<>();
            for (String gtfsId : gtfsIds) {
                patternLineStrings.addAll(gtfsCache.patternShapes.queryEnvelope(gtfsId, vectorMapTile.envelope));
                stopPoints.addAll(gtfsCache.stops.queryEnvelope(gtfsId, vectorMapTile.envelope));
            }
            // filterPatterns(patternLineStrings);
            JtsLayer gtfsPatternsLayer = vectorMapTile.createLayer(
                    "gtfs-patterns",
                    vectorMapTile.clipAndSimplifyLinesToTile(patternLineStrings)
            );
            JtsLayer gtfsStopsLayer = vectorMapTile.createLayer(
                    "gtfs-stops",
                    vectorMapTile.projectPointsToTile(stopPoints)
            );
            tileBytes = vectorMapTile.encodeLayersToBytes(gtfsPatternsLayer, gtfsStopsLayer);
        } else if (type.equals("osm")) {
            // Note that the ID here is not an OSM file ID but a routable network ID,
            // so maybe type should be gtfs vs. network rather than osm.
            TransportNetwork network = RoutingDataCache.getNetwork(id, user);
            JtsLayer osmEdgesLayer = vectorMapTile.createLayer(
                    "osm-edges", getClippedAndProjectedEdgeGeometries(network, vectorMapTile)
            );
            tileBytes = vectorMapTile.encodeLayersToBytes(osmEdgesLayer);
        } else {
            return JettyUtil.respondClientError("Unrecognized type " + type, response, callback);
        }
        if (tileBytes == null || tileBytes.length == 0) {
            return JettyUtil.respondServerError("No tile data generated.", response, callback);
        }
        JettyUtil.addMapboxVectorHeaders(response);
        response.write(true, ByteBuffer.wrap(tileBytes), callback);
        return true;
    }

    List<Geometry> getClippedAndProjectedEdgeGeometries (TransportNetwork network, VectorMapTile vectorMapTile) {
        try {
            return (List<Geometry>) this.privateMethod.invoke(networkTileController, network, vectorMapTile);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /// Remove all patterns that have very long straight lines in them, to lower visual clutter.
    /// NOTE: Modifies the supplied list in-place.
    /// For some reason this seems to be retaining some very long straight lines.
    private static void filterPatterns (List<LineString> patternLineStrings) {
        final double minCoord = 1.0; // Remove area around null island
        final double maxDelta = 0.01; // ~2.2km at equator
        patternLineStrings.removeIf(ls -> {
            CoordinateSequence cs = ls.getCoordinateSequence();
            for (int c = 0; c < cs.size() - 1; c += 1) {
                Coordinate c0 = cs.getCoordinate(c);
                if (!Double.isFinite(c0.x) || c0.x < minCoord) return true;
                if (!Double.isFinite(c0.y) || c0.y < minCoord) return true;
                Coordinate c1 = cs.getCoordinate(c + 1);
                double dx = c1.x - c0.x;
                double dy = c1.y - c0.y;
                if (dx > maxDelta || dy > maxDelta) {
                    return true;
                }
            }
            return false;
        });
    }

}
