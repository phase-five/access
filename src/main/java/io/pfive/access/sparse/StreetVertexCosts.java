// Copyright 2023-2025 Phase Five LLC.  For license terms, see LICENSE.txt in the repository root.

package io.pfive.access.sparse;

import com.conveyal.r5.streets.StreetRouter;
import com.conveyal.r5.streets.StreetRouter.State.RoutingVariable;
import com.conveyal.r5.streets.VertexStore;
import gnu.trove.map.TIntIntMap;
import io.pfive.access.importer.Wgs84Bounds;

/// A sparse set of reached street vertices associated with the duration or distance to reach them.
/// The R5 StreetRouter already returns maps of this kind but they are not strongly typed.
public class StreetVertexCosts {

    public final RoutingVariable costUnits;
    public final TIntIntMap vertexCosts;

    private StreetVertexCosts (RoutingVariable costUnits, TIntIntMap vertexCosts) {
        this.costUnits = costUnits;
        this.vertexCosts = vertexCosts;
    }

    /// @return the cost to reach the given vertex, or Integer.MAX_VALUE if it was not reached.
    public int costAtVertex (int vertexIndex) {
        return vertexCosts.get(vertexIndex);
    }

    public static StreetVertexCosts fromRouter (StreetRouter router) {
        return new StreetVertexCosts(router.quantityToMinimize, router.getReachedVertices());
    }

    /// Return a minimal bounding box around all vertices that were reached. StreetVertexCosts
    /// doesn't keep the StreetLayer whose vertices it references, so pass in the VertexStore.
    /// This is not ideal for selecting destination tiles, because tiles outside the minimal
    /// bounding box may be reached via road segments crossing tile boundaries. We should instead
    /// use bounds based on the max straight line distance based on walk speed. See [x].
    public Wgs84Bounds wgsBoundsForReached (VertexStore vertexStore) {
        final var builder = new Wgs84Bounds.Builder();
        final var vertex = vertexStore.getCursor();
        vertexCosts.forEachKey(v -> {
            vertex.seek(v);
            builder.includePoint(vertex.getLon(), vertex.getLat());
            return true;
        });
        return builder.build();
    }

}
