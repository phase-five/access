// Copyright 2023-2025 Phase Five LLC.  For license terms, see LICENSE.txt in the repository root.

package io.pfive.access.batch;

import io.pfive.access.authentication.UserIdentity;
import io.pfive.access.http.handler.EventSourceHandler;
import io.pfive.access.importer.Wgs84Bounds;
import io.pfive.access.sparse.GridScheme;

/// Retains only enough information for visualizing batch job progress on a map. Server-sent events
/// are generated very frequently and state is discarded after events are sent. Primitive int
/// collections reflect only changes since the last messages were sent. JSON is generated to
/// communicate the geographic extents of all cells that have been distributed for calculation but
/// are not yet completed, as well as those that have just been completed. These could be
/// differentiated by which compute node received them, and/or by whether they are queued or
/// actively computing. The collection of queued cells is a bit too large for frequent server-sent
/// events, so we let the client build up its own approximation of the queued cells based on the
/// sent and finished messages.
public class BatchVisualProgress {

    private final UserIdentity user;
    private final GridScheme cells;

    public BatchVisualProgress (UserIdentity user, GridScheme cells) {
        this.user = user;
        this.cells = cells;
    }

    private final String TEMPLATE = """
                {"x0":%6.5f,"y0":%6.5f,"dx":%6.5f,"dy":%6.5f}""";

    private String oneOriginJson (int origin) {
        Wgs84Bounds bounds = cells.boundsForCellIndex(origin);
        return String.format(TEMPLATE, bounds.minLon(), bounds.minLat(), bounds.widthLon(), bounds.heightLat());
    }

    public synchronized void sent (int[] origins) {
        StringBuilder sb = new StringBuilder();
        sb.append("[");
        for (int i = 0; i < origins.length; i++) {
            int origin = origins[i];
            if (i > 0) sb.append(",");
            sb.append(oneOriginJson(origin));
        }
        sb.append("]");
        EventSourceHandler.sendEvent(user, "batch-sent", sb.toString());
    }

    public synchronized void finished (int origin) {
        EventSourceHandler.sendEvent(user, "batch-finished", oneOriginJson(origin));
    }

}
