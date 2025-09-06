// Copyright 2023-2025 Phase Five LLC.  For license terms, see LICENSE.txt in the repository root.

package io.pfive.access.http.routing;

import com.conveyal.r5.kryo.KryoNetworkSerializer;
import com.conveyal.r5.transit.TransportNetwork;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.pfive.access.authentication.UserIdentity;
import io.pfive.access.filepool.FileMetadata;
import io.pfive.access.sparse.Opportunities;
import io.pfive.access.sparse.Serialization;
import io.pfive.access.sparse.SparseEgressTable;
import io.pfive.access.sparse.TileGrid;
import io.pfive.access.store.FileWithMetadata;
import io.pfive.access.store.JsonStore;

import static io.pfive.access.http.handler.EventSourceHandler.statusEvent;

/// RoutingDataCache holds any data that may be heavily reused but are slow to prepare or voluminous
/// such as transport networks, grids of destination data etc. For the moment it's abstract and all
/// methods static, avoiding a more object-oriented approach until there is a clear need for it. If
/// any objects are particularly large and take more than a few seconds to prepare, it might be good
/// to load them as a background task and return some kind of indication to wait and try again. This
/// class must be threadsafe as it can be called from multiple different HTTP handler threads at
/// once. This class is responsible for constraining memory usage and currently does that by
/// retaining only the one or two most recently used items in each category. Simple synchronization
/// cause the UI to lock up because several different items may be fetched at once, so we use caches
/// with more sophisticated per-key locking.
/// ### Implementation notes:
/// Values in LoadingCaches must be loadable based only on the key. If the key is an object ID
/// alone, the organization is not known without a database or filesystem lookup, but it's needed to
/// retrieve the file. Providing feedback about progress to one specific end user also requires a
/// similar approach. Therefore we create instances of Cache instead of LoadingCache, and supply a
/// closure to get() capturing the user. We do have logic to accept null userIdentities and scan
/// through all organizations, but that is quite inefficient and not viable long-term.
public abstract class RoutingDataCache {

    private static final Cache<String, TransportNetwork> networkCache = Caffeine.newBuilder().maximumSize(2).build();
    private static final Cache<String, SparseEgressTable> egressCache = Caffeine.newBuilder().maximumSize(2).build();
    private static final Cache<String, TileGrid<Opportunities>> opportunityCache = Caffeine.newBuilder().maximumSize(4).build();

    public static TransportNetwork getNetwork (String networkId, UserIdentity user) {
        return networkCache.get(networkId, id -> loadNetwork(id, user));
    }

    /// Loading a network from disk relies on knowing the UserIdentity, which is not part of the cache key.
    private static TransportNetwork loadNetwork (String networkId, UserIdentity user) {
        statusEvent(user, "Loading network...");
        FileWithMetadata networkFile = JsonStore.retrieveFile(networkId, user);
        try {
            // Reading rebuilds indexes.
            return KryoNetworkSerializer.read(networkFile.path().toFile());
        } catch (Exception e) {
            return null;
        }
    }

    public static void putOpportunities (String opportunityId, TileGrid<Opportunities> tiles) {
        opportunityCache.put(opportunityId, tiles);
    }

    public static TileGrid<Opportunities> getOpportunities (String opportunityId, UserIdentity user) {
        return opportunityCache.get(opportunityId, id -> loadOpportunities(id, user));
    }

    private static TileGrid<Opportunities> loadOpportunities (String opportunityId, UserIdentity user) {
        FileWithMetadata fileWithMetadata = JsonStore.retrieveFile(opportunityId, user);
        return (TileGrid<Opportunities>) Serialization.read(fileWithMetadata);
    }

    public static void putEgress (String egressId, SparseEgressTable egress) {
        egressCache.put(egressId, egress);
    }

    public static SparseEgressTable getEgress (String egressId, UserIdentity user) {
        return egressCache.get(egressId, id -> loadEgress(id, user));
    }

    private static SparseEgressTable loadEgress (String egressId, UserIdentity user) {
        FileWithMetadata fileWithMetadata = JsonStore.retrieveFile(egressId, user);
        FileMetadata metadata = fileWithMetadata.fileMetadata();
        // TODO check metadata.sources.get(FileType.NETWORK)
        return (SparseEgressTable) Serialization.read(fileWithMetadata);
    }

    /// Illustrating generic approach that could put/get/deserialize multiple kinds of data uniformly.
    public static <T> void put (TileGrid<T> tileGrid, Class<T> klass) {
        throw new UnsupportedOperationException();
    }

}
