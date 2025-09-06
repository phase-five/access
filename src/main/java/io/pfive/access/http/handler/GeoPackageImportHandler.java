// Copyright 2023-2025 Phase Five LLC.  For license terms, see LICENSE.txt in the repository root.

package io.pfive.access.http.handler;

import com.conveyal.r5.transit.TransportNetwork;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.pfive.access.authentication.UserIdentity;
import io.pfive.access.background.BackgroundItem;
import io.pfive.access.background.BackgroundItemTracker;
import io.pfive.access.background.ProgressSink;
import io.pfive.access.filepool.FileMetadata;
import io.pfive.access.filepool.FileType;
import io.pfive.access.http.exception.InvalidRequestException;
import io.pfive.access.http.routing.RoutingDataCache;
import io.pfive.access.importer.GeoPackageImporter;
import io.pfive.access.importer.GeoPackagePointImporter;
import io.pfive.access.importer.GeoPackageRasterImporter;
import io.pfive.access.importer.Wgs84Bounds;
import io.pfive.access.sparse.GridScheme;
import io.pfive.access.sparse.SparseEgressBuilder;
import io.pfive.access.store.FileWithMetadata;
import io.pfive.access.store.JsonStore;
import io.pfive.access.util.JettyUtil;
import io.pfive.access.util.RandomId;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;

import static com.conveyal.analysis.util.HttpStatus.ACCEPTED_202;
import static io.pfive.access.util.JettyUtil.jsonObjectFromRequestBody;

/// HTTP handler that creates rasters and sets of non-gridded points for use as access destinations.
public class GeoPackageImportHandler extends Handler.Abstract {

    private final BackgroundItemTracker backgroundItemTracker;

    private static class Parameters {
        UserIdentity user;
        String name;
        String description;
        FileWithMetadata sourceFile;
        String layer;
        String numericProperty;
        Wgs84Bounds wgsBounds;
        boolean points;

        // Possible fields for a request body usable for building things other than networks:
        // Set<FileType> fileTypes;
        // MultiMap<FileType, FileWithMetadata> filesByType;

        public static Parameters fromRequest (Request request) {
            UserIdentity user = JettyUtil.extractUserIdentity(request);
            ObjectNode json = jsonObjectFromRequestBody(request);

            // Validate inputs in the request body.
            JsonNode sourceId = json.get("source");
            JsonNode networkId = json.get("network");

            // Don't check isEmpty, that refers to the number of child nodes.
            if (networkId.asText().isBlank()) {
                throw new InvalidRequestException("Body should specify a source file ID.");
            }
            if (networkId.asText().isBlank()) {
                throw new InvalidRequestException("Body should specify a network ID.");
            }
            FileWithMetadata fm = JsonStore.retrieveFile(sourceId.asText(), user);
            if (fm.fileMetadata().fileType != FileType.GEOPACKAGE) {
                throw new InvalidRequestException("Source file should be a GeoPackage.");
            }

            // All we need form the network is its extents.
            // There is probably some better way to do this with metadata, or by user manually choosing extents in UI.
            // TODO Get the extents out of the metadata without opening the network.
            TransportNetwork network = RoutingDataCache.getNetwork(networkId.asText(), user);
            // Use the StreetLayer envelope to mimic the GridScheme construction prcess in SparseEgressBuilder.java:65
            // Ideally, rather than repeating the process here we'd save the GridScheme with the network or connectors.
            // TODO expand by the max StreetConnector length.
            Wgs84Bounds wgsBounds = Wgs84Bounds.fromWgsEnvelope(network.streetLayer.getEnvelope());

            // Construct the parameters object from validated inputs and return it.
            Parameters params = new Parameters();
            params.user = user;
            // TODO find or create methods to handle missing value case and defaults.
            params.name = json.get("name").asText();
            params.description = json.get("description").asText();
            params.sourceFile = fm;
            params.layer = json.get("layer").asText();
            params.numericProperty = json.get("property").asText();
            params.wgsBounds = wgsBounds;
            params.points = json.get("points").asBoolean(false);
            // Infer missing values
            if (params.name.isBlank()) {
                params.name = params.numericProperty + " from " + params.layer;
            }
            return params;
        }
    }

    public GeoPackageImportHandler (BackgroundItemTracker backgroundItemTracker) {
        this.backgroundItemTracker = backgroundItemTracker;
    }

    @Override
    public boolean handle (Request request, Response response, Callback callback) throws Exception {
        Parameters params = Parameters.fromRequest(request);
        // NOTE that the following is extremely similar to the network building handler and could be factored out.
        String title = String.format("Rasterizing features '%s'", params.name);
        BackgroundItem backgroundItem = new BackgroundItem(title, params.user);
        backgroundItemTracker.add(backgroundItem);
        backgroundItemTracker.run(new GeoPackageImportTask(params, backgroundItem.progressSink));
        // TODO method to do response.setStatus() in JSON responses; also, respond with full metadata.
        String responseMessage = String.format("Received request to rasterize '%s' (task ID %s)",
                params.name, backgroundItem.id);
        return JettyUtil.respondText(ACCEPTED_202, responseMessage, response, callback);
    }

    private record GeoPackageImportTask(Parameters params, ProgressSink progress) implements Runnable {
        @Override
        public void run () {
            String fileId = RandomId.createRandomStringId();
            GeoPackageImporter geoPackageImporter;
            if (params.points) {
                geoPackageImporter = new GeoPackagePointImporter(params.wgsBounds);
            } else {
                GridScheme tileGridScheme = SparseEgressBuilder.tileGridContaining(params.wgsBounds);
                geoPackageImporter = new GeoPackageRasterImporter(tileGridScheme);
            }
            try {
                geoPackageImporter.setProgressListener(progress);
                geoPackageImporter.importFeatures(params.sourceFile.path(), params.layer, params.numericProperty);
                geoPackageImporter.storeResults(fileId, params.user);
                // TODO save georeferencing metadata, include with/in PNG or derive PNG file and headers from native.
                FileMetadata fm = new FileMetadata(fileId, params.points ? FileType.POINT : FileType.GRID);
                fm.name = params.name;
                fm.description = params.description;
                fm.originalName = params.sourceFile.fileMetadata().originalName;
                // Retain geographic bounds in metadata, though we should also include these in a text chunk in the PNG.
                // FIXME in raster import, these bounds are superseded when converted into a GridScheme!
                fm.wgsBounds = params.wgsBounds;
                // Only store the GeoPackage file as a source.
                // The network is not really a source file, we're just using it to get a bounding rectangle.
                fm.addSource(params.sourceFile.fileMetadata());
                JsonStore.storeMetadata(fm, params.user);
                progress.increment();
                // progress.completed(); // TODO fix completed() to display correctly in UI.
                // TODO Add to transport data cache so we don't need to reload from disk to use the new file.
            } catch (Exception e) {
                String message = "Failed to import GeoPackage: " + e.toString();
                System.out.println(message);
                progress.error(message);
            }
        }
    }
}
