// Copyright 2023-2025 Phase Five LLC.  For license terms, see LICENSE.txt in the repository root.

package io.pfive.access.http.handler;

import com.conveyal.gtfs.GTFSFeed;
import com.conveyal.osmlib.OSM;
import com.conveyal.r5.kryo.KryoNetworkSerializer;
import com.conveyal.r5.streets.StreetLayer;
import com.conveyal.r5.transit.TransferFinder;
import com.conveyal.r5.transit.TransitLayer;
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
import io.pfive.access.importer.Wgs84Bounds;
import io.pfive.access.store.FileWithMetadata;
import io.pfive.access.store.JsonStore;
import io.pfive.access.util.JettyUtil;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static com.conveyal.analysis.util.HttpStatus.ACCEPTED_202;
import static io.pfive.access.util.JettyUtil.jsonObjectFromRequestBody;
import static io.pfive.access.util.RandomId.createRandomStringId;

public class NetworkBuildHandler extends Handler.Abstract {

    private final BackgroundItemTracker backgroundItemTracker;

    public NetworkBuildHandler (BackgroundItemTracker backgroundItemTracker) {
        this.backgroundItemTracker = backgroundItemTracker;
    }

    private static class Parameters {
        UserIdentity user;
        String name;
        String description;
        List<FileWithMetadata> gtfsFiles;
        List<FileWithMetadata> osmFiles;

        public static Parameters fromRequest (Request req) {
            UserIdentity user = JettyUtil.extractUserIdentity(req);
            ObjectNode obj = jsonObjectFromRequestBody(req);
            JsonNode fileIds = obj.get("files");
            if (fileIds == null || !fileIds.isArray() || fileIds.isEmpty()) {
                throw new InvalidRequestException("Body should contain an array of file IDs.");
            }
            List<FileWithMetadata> gtfsFiles = new ArrayList<>();
            List<FileWithMetadata> osmFiles = new ArrayList<>();
            for (JsonNode idNode : fileIds) {
                String fileId = idNode.asText();
                FileWithMetadata fm = JsonStore.retrieveFile(fileId, user);
                switch (fm.fileMetadata().fileType) {
                    case GTFS -> gtfsFiles.add(fm);
                    case OSMPBF -> osmFiles.add(fm);
                    case null, default -> throw new InvalidRequestException("Files should be OSM or GTFS.");
                }
            }
            Parameters ret = new Parameters();
            ret.user = user;
            // Name and description fields are common to all file metadata and could be factored out somehow.
            ret.name = obj.get("name").asText();
            ret.description = obj.get("description").asText();
            ret.gtfsFiles = gtfsFiles;
            ret.osmFiles = osmFiles;
            return ret;
        }
    }

    // TODO factor out method to extract derive request params and reuse in multiple handlers (with acceptable file types)

    @Override
    public boolean handle (Request request, Response response, Callback callback) throws Exception {
        Parameters params = Parameters.fromRequest(request);
        // TODO parameters could even be merged with the NetworkBuildTask runnable / background task itself.
        // To handle multiple kinds of file building actions in a single handler class:
        // if (parameters.thingToDerive == ThingToDerive.NETWORK) { ... }
        // TODO check that there's only one OSM and at least one GTFS file. Or is no GTFS acceptable?
        // To return quickly from the upload, create and another background item to process the import operation.
        // This progress item will be reused for each step. Although it doesn't currently hurt anything, avoid
        // incrementing progress to the full number of steps so the task doesn't reach "completed" status.
        String title = String.format("Creating TransportNetwork '%s'", params.name);
        BackgroundItem backgroundItem = new BackgroundItem(title, params.user);
        backgroundItemTracker.add(backgroundItem);
        backgroundItemTracker.run(new NetworkBuildTask(params, backgroundItem.progressSink));
        // TODO method to do response.setStatus() in JSON responses; also, respond with full metadata.
        String responseMessage = String.format("Received request to build network '%s' (task ID %s)",
                params.name, backgroundItem.id);
        return JettyUtil.respondText(ACCEPTED_202, responseMessage, response, callback);
    }

    private record NetworkBuildTask (Parameters params, ProgressSink progress) implements Runnable {
        @Override
        public void run() {
            // OSM and GTFS should already be pre-processed into MapDBs at time of upload.
            // TODO process different background task phases in parallel and sequentially.
            //      Parallel phases just sum total and completed counts.
            TransportNetwork network = new TransportNetwork();
            network.streetLayer = new StreetLayer();
            network.transitLayer = new TransitLayer();
            network.streetLayer.parentNetwork = network;
            network.transitLayer.parentNetwork = network;
            for (FileWithMetadata osmFile : params.osmFiles) {
                // It is expected that the PBF was already loaded into the MapDB file when it was uploaded.
                // For intersection detection to work correctly below, the OSM data must already be loaded,
                // the MapDB filename must end in .db, and the (unused) name of the file to read must end in .pbf.
                progress.beginTask("Loading OSM into StreetLayer: " + osmFile.fileMetadata().name, 2);
                OSM osm = new OSM(osmFile.path().toString() + ".db");
                // Setting this boolean field and calling readFromFile() are crucial to detect intersections.
                osm.intersectionDetection = true;
                osm.readFromFile("DUMMY_FILE_NAME.pbf");
                progress.increment();
                // Parameter to disable island pruning is in a protected method, but only takes 3 minutes for all France.
                // About 10 minutes to load France OSM, but fails on checkWgsEnvelopeSize MAX_BOUNDING_BOX_AREA_SQ_KM.
                // This check is the final step in loading OSM, so we can just ignore the exception.
                try {
                    network.streetLayer.loadFromOsm(osm);
                } catch (IllegalArgumentException exception) {
                    if (!exception.getMessage().contains("Geographic extent")) throw exception;
                }
                progress.increment();
                osm.close();
            }
            // TODO find and retain active dates of feeds when uploading/validating them.
            //  And here, maybe select only one or a few dates to import per network to simplify routing.
            for (FileWithMetadata gtfsFile : params.gtfsFiles) {
                GTFSFeed feed = GTFSFeed.reopenReadOnly(new File(gtfsFile.path().toString() + ".db"));
                progress.beginTask("Loading GTFS into TransitLayer: " + gtfsFile.fileMetadata().name, 1);
                network.transitLayer.loadFromGtfs(feed);
                feed.close();
                progress.increment();
            }
            // TODO Better progress tracking within individual steps.
            {
                String networkId = createRandomStringId();
                // ProgressSink is an implementation of listener, you can hit its methods manually.
                progress.beginTask("Building indexes and storing.", 3);
                network.streetLayer.associateStops(network.transitLayer);
                network.streetLayer.buildEdgeLists();
                network.rebuildTransientIndexes();
                progress.increment();
                new TransferFinder(network).findTransfers();
                network.scenarioId = networkId;
                progress.increment();
                // SKIPPING distance tables and linked pointsets.
                // TESTING sparse grid connectors, egress, and destinations.
                // The built-in R5 methods will fail on large networks like all of France.

                // Serialize network to a temp file and move into place.
                try {
                    File networkFile = File.createTempFile(networkId, ".network");
                    KryoNetworkSerializer.write(network, networkFile);
                    JsonStore.storeFile(networkFile.toPath(), networkId, params.user);
                } catch (IOException e) {
                    // It's not a good idea for the task to let an exception leak out.
                    // TODO real progress error handling.
                    progress.error(e.getMessage());
                }
                FileMetadata metadata = new FileMetadata(networkId, FileType.NETWORK);
                metadata.name = params.name;
                metadata.description = params.description;
                // WebMercatorExtents constructor will not allow more than 5M points.
                // Get the bounds directly from the StreetLayer.
                metadata.wgsBounds = Wgs84Bounds.fromWgsEnvelope(network.streetLayer.envelope);
                // By recording the files this network was built from, we can more easily visualize
                // those inputs later, or allow someone to remember what they built the network from.
                metadata.addSources(params.gtfsFiles);
                metadata.addSources(params.osmFiles);
                JsonStore.storeMetadata(metadata, params.user);
                progress.increment();
            }
        }
    }

}
