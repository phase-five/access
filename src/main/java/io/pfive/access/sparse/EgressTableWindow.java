// Copyright 2023-2025 Phase Five LLC.  For license terms, see LICENSE.txt in the repository root.

package io.pfive.access.sparse;

import java.util.function.IntConsumer;

/// This wraps a SparseEgressTable to extract a rectangular subset of its tiles, evaluate travel
/// times and access indicator values on those tiles, and store them into result arrays or objects.
/// TODO this may be merged with TileRange, which is not just about tiles.
public record EgressTableWindow(SparseEgressTable egressTable, TileRange tileRange) {

    public Durations durationsFrom (StreetVertexCosts vertexCosts) {
        return tileRange.durationsFrom(flatIndex -> {
            StreetConnectors connectors = egressTable.connectors()[flatIndex];
            return (connectors == null) ? null : connectors.eval(vertexCosts);
        });
    }

    /// Though we quickly skip unreached stops within each tile, we can also skip
    /// rendering entire tiles into the output grid. It's not clear whether the checks
    /// are more expensive than writing the empty tiles. Here we signal it with null.
    public Durations durationsFrom (TransitTimes transitTimes) {
        return tileRange.durationsFrom(flatIndex -> {
            EgressTimes egressTimes = egressTable.egressTimes()[flatIndex];
            return (egressTimes == null) ? null : egressTimes.apply(transitTimes);
        });
    }

    public void forEachParentFlatIndex (IntConsumer consumer) {
        tileRange.forEachFlatParentIndex(consumer);
    }

    public GridScheme pixelGrid () {
        return tileRange.pixelGrid();
    }

}
