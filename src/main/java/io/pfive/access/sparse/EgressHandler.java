// Copyright 2023-2025 Phase Five LLC.  For license terms, see LICENSE.txt in the repository root.

package io.pfive.access.sparse;

import com.conveyal.r5.profile.StreetMode;
import com.conveyal.r5.transit.TransportNetwork;
import io.pfive.access.authentication.UserIdentity;
import io.pfive.access.background.BackgroundItem;
import io.pfive.access.background.BackgroundItemTracker;
import io.pfive.access.filepool.FileMetadata;
import io.pfive.access.filepool.FileType;
import io.pfive.access.http.routing.RoutingDataCache;
import io.pfive.access.store.FileWithMetadata;
import io.pfive.access.store.JsonStore;
import io.pfive.access.util.JettyUtil;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Fields;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.MethodHandles;
import java.util.Locale;

import static com.conveyal.analysis.util.HttpStatus.ACCEPTED_202;
import static io.pfive.access.http.handler.EventSourceHandler.statusEvent;
import static io.pfive.access.sparse.SparseEgressBuilder.EGRESS_DISTANCE_METERS;
import static io.pfive.access.sparse.StreetConnectors.MAX_CONNECTOR_LENGTH_METERS;

/// Handles HTTP requests asking to build sparse grids of connectors and egress information.
/// These are serialized to disk, allowing later rapid reuse of such tables for different
/// resolutions, modes of travel, and egress distances.
public class EgressHandler extends Handler.Abstract {

    private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    private final BackgroundItemTracker backgroundItemTracker;

    public EgressHandler (BackgroundItemTracker backgroundItemTracker) {
        this.backgroundItemTracker = backgroundItemTracker;
    }

    @Override
    public boolean handle (Request request, Response response, Callback callback) throws Exception {
        final UserIdentity user = JettyUtil.extractUserIdentity(request);
        statusEvent(user, "Loading network to build egress, please wait...");
        Params params = new Params(request, user);
        TransportNetwork network = RoutingDataCache.getNetwork(params.networkId, user);
        BackgroundItem backgroundItem = new BackgroundItem("Build Egress", user);
        backgroundItemTracker.add(backgroundItem);
        statusEvent(user, "Building egress information.");
        backgroundItemTracker.run(() -> {
            try {
                //// Egress Table
                SparseEgressTable table = SparseEgressBuilder.build(network, backgroundItem.progressSink);
                LOG.info("Resulting table: {}", table);
                backgroundItem.progressSink.beginTask("Saving egress information to files", 1);
                String egressId = Serialization.writeToStore(table, user);
                //// Table Metadata
                FileMetadata metadata = new FileMetadata(egressId, FileType.EGRESS);
                metadata.name = params.name;
                metadata.description = null;
                metadata.wgsBounds = table.gridScheme().wgsBounds();
                metadata.addSource(FileType.NETWORK, params.networkId);
                JsonStore.storeMetadata(metadata, user);
                // LOG.info("Wrote egress table with ID {}", egressId);
                backgroundItem.progressSink.increment();
                // Place in cache for immediate reuse in routing. Currently disabled to test save/load.
                // RoutingDataCache.putEgress(egressId, table);
            } catch (Throwable t) {
                // TODO move error reporting logic into BackgroundItemTracker.run()
                backgroundItem.progressSink.error(t.getMessage());
            }
        });
        return JettyUtil.respondText(ACCEPTED_202, "Building egress information.", response, callback);
    }

    private static class Params {
        final String networkId;
        final String name;
        final int connectorLengthMeters = MAX_CONNECTOR_LENGTH_METERS;
        final int egressDistanceMeters = EGRESS_DISTANCE_METERS;
        final StreetMode streetMode = StreetMode.WALK;
        public Params (Request request, UserIdentity user) {
            Fields fields = Request.extractQueryParameters(request);
            networkId = fields.get("network").getValue();
            FileWithMetadata networkFile = JsonStore.retrieveFile(networkId, user);
            name = String.format("%s (%s %d meters)", networkFile.fileMetadata().name,
                  streetMode.toString().toLowerCase(Locale.ROOT), egressDistanceMeters);
        }
    }

}
