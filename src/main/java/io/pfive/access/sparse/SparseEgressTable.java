// Copyright 2023-2025 Phase Five LLC.  For license terms, see LICENSE.txt in the repository root.

package io.pfive.access.sparse;

import com.google.common.base.MoreObjects;
import io.pfive.access.importer.Wgs84Bounds;

import static io.pfive.access.sparse.ByteSizeUtil.OBJECT_BYTES;
import static io.pfive.access.sparse.ByteSizeUtil.objectArrayFieldBytes;
import static io.pfive.access.sparse.ByteSizeUtil.percentNonNull;

/// This is the top-level object that holds egress data (street connectors and egress times from
/// transit stops) in sparse grid data structures, enabling searches from any origin point in very
/// large areas, where the resulting reachable area may be much smaller than the total area or may
/// include gaps and large empty spaces. The indexes into the arrays are 1D flat indexes of tiles
/// as described by the GridScheme.
/// It makes sense to group connectors with egress times as they both depend on the egress mode,
/// but if the egress distance is varied, the connectors remain valid while egress times don't.
public record SparseEgressTable(
      GridScheme gridScheme,
      EgressTimes[] egressTimes,
      StreetConnectors[] connectors
) implements ByteSize {

    /// The incoming WGS84Bounds will be expanded as needed to enclose an integer number of tiles.
    /// Therefore we do not retain a reference to those bounds, and use the ones in the TileRange.
    public EgressTableWindow subWindow (Wgs84Bounds wgsBounds) {
        return new EgressTableWindow(this, gridScheme.tileRange(wgsBounds));
    }

    /// The incoming WGS84Bounds will be expanded as needed to enclose an integer number of tiles.
    /// Therefore we do not retain a reference to those bounds, and use the ones in the TileRange.
    public TileRange tileRange (Wgs84Bounds wgsBounds) {
        return gridScheme.tileRange(wgsBounds);
    }

    @Override
    public int byteSize () {
        return OBJECT_BYTES + GridScheme.BYTES
              + objectArrayFieldBytes(egressTimes)
              + objectArrayFieldBytes(connectors);
    }

    @Override
    public String toString () {
        return MoreObjects.toStringHelper(this)
              .add("nTiles", gridScheme.nElements())
              .add("connectorFillPct", percentNonNull(connectors))
              .add("egressFillPct", percentNonNull(egressTimes))
              .add("memBytes", humanByteSize())
              .toString();
    }
}
