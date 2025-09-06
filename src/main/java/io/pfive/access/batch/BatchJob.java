// Copyright 2023-2025 Phase Five LLC.  For license terms, see LICENSE.txt in the repository root.

package io.pfive.access.batch;

import gnu.trove.list.TIntList;
import gnu.trove.list.array.TIntArrayList;
import gnu.trove.map.hash.TIntObjectHashMap;
import io.pfive.access.Configuration;
import io.pfive.access.authentication.UserIdentity;
import io.pfive.access.background.ProgressSink;
import io.pfive.access.http.model.OneToManyOptions;
import io.pfive.access.http.routing.RoutingDataCache;
import io.pfive.access.importer.Wgs84Bounds;
import io.pfive.access.sparse.GridScheme;
import io.pfive.access.sparse.Opportunities;
import io.pfive.access.sparse.SparseEgressBuilder;
import io.pfive.access.sparse.TileGrid;
import io.pfive.access.util.RandomId;

import javax.annotation.Nonnull;
import java.util.Arrays;
import java.util.BitSet;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static io.pfive.access.sparse.SparseEgressBuilder.TILE_SPAN_CELLS;

/// This represents a large batch of requests to be handled asynchronously and in parallel.
/// The use case is to perform single-origin requests at each of many origins and accumulate the
/// results into an output raster. This class tracks the progress of the job as it's being computed.
/// Metadata for a completed job is stored and represented using a different class.
public class BatchJob {

    // TODO Use instances of RandomId ID or case-specific subclasses instead of using Strings? Also for stored files.
    public final String id;
    public final UserIdentity user;
    public final OneToManyOptions options;

    public final int nTasksTotal;

    private final ProgressSink progress;
    private final BitSet completed;
    private final BitSet skipped;
    private int nextTask;
    private int nTasksRemaining;
    private BatchVisualProgress visualProgress;

    // Maybe O/D IDs should be in the settings object and/or OD objects in a BatchContext object.
    // The processor component needs those but not all the progress tracking context.
    public final TileGrid<Opportunities> origins;
    public final TileGrid<Opportunities> destinations;
    public final GridScheme originCells;

    public BatchJob (UserIdentity user, OneToManyOptions options) {
        this.id = RandomId.createRandomStringId();
        this.user = user;
        this.options = options;
        this.progress = new ProgressSink(this.id, user);
        this.completed = new BitSet();
        this.skipped = new BitSet();
        this.nextTask = 0;
        // Maybe BatchJob should be only the public fields here, and we need some other class to track PointSets.
        // Keep origin and destination references in the job object, mainly to inject artificial ones for testing.
        if (Configuration.BATCH_SMALL_AREA_DEBUG) {
            this.origins = originGridForTesting(options.lon, options.lat);
        } else {
            this.origins = RoutingDataCache.getOpportunities(options.originId, user);
        }
        this.destinations = RoutingDataCache.getOpportunities(options.destinationId, user);
        this.originCells = origins.cellGrid();
        this.nTasksTotal = originCells.nElements();
        this.visualProgress = new BatchVisualProgress(user, originCells);
        // FIXME this slow operation should not be done in a constructor
        if (options.originFilter >= 0) {
            skipZeroDensityOrigins(origins, options.originFilter);
        }
        this.nTasksRemaining = nTasksTotal - skipped.cardinality();
        progress.beginTask("Batch Job", nTasksRemaining);
        progress.pushEventAfter(1);
        progress.minTimeBetweenEventsMsec(200);
        // Override default event decimation and throttling for very long-running batch jobs.
        // Over long periods of time, 2-second updates feel less busy visually.
        // progress.pushEventAfter(10);
        // progress.minTimeBetweenEventsMsec(2000);
    }

    /// TODO skip origins not connectable to streets without enqueueing them.
    /// The necessary information may already be in the connectors() tiles of the SparseEgressTable,
    /// but in the general case the destination grid won't necessarily be the same as the origin
    /// grid, and the egress mode won't be the same as the access mode. The logic for iterating over
    /// pixel grids within a tile grid (as seen in SparseEgressBuilder#build) should be generalized.
    /// @param radius number of cells in range 0-4
    private void skipZeroDensityOrigins (TileGrid<Opportunities> origins, int radius) {
        checkArgument(radius >= 0 && radius < 5);
        // All origins are gridded for now, no need to check if they're non-gridded
        int nOrigins = origins.nCellsAllTiles();
        for (int i = 0; i < nOrigins; i++) {
            if (!anyPositive(origins, i, radius))  {
                // Set skipped and completed flags, but do not markComplete.
                // Skipped tasks are not reflected in progress bars for better time estimates.
                skipped.set(i);
                this.completed.set(i);
            }
        }
    }

    /// Given a Grid, return whether any cell in a box extending `radius` cells in any direction
    /// around the given point index has a nonzero opportunity count.
    /// FIXME TileGrid<Opportunities> is no good, switch to arrays and extend that with a class that has a get method.
    private boolean anyPositive (TileGrid<Opportunities> origins, int i, int radius) {
        checkArgument(radius >= 0);
        // FIXME this involves so many repeated calls to cellGrid().
        // TODO push this cell interation logic down into TileGrid or GridScheme?
        if (radius == 0) {
            // Fixme only detects cells > 0, maybe cellPredicate instead of int?
            return origins.testCell(i, (opp, ii) -> (opp != null && opp.counts()[ii] > 0.0));
        }
        int xCenter = origins.cellGrid().xForFlatIndex(i);
        int yCenter = origins.cellGrid().yForFlatIndex(i);
        // Constrain iteration to cells inside the Grid instance.
        int xMin = Math.max(xCenter - radius, 0);
        int yMin = Math.max(yCenter - radius, 0);
        int xMax = Math.min(xCenter + radius, origins.cellGrid().nCellsWide() - 1);
        int yMax = Math.min(yCenter + radius, origins.cellGrid().nCellsHigh() - 1);
        for (int y = yMin; y <= yMax; y++) {
            for (int x = xMin; x <= xMax; x++) {
                // Note that Grid density[][] axis order is (x, y),
                // but in the point indexes of Grids x changes faster than y.
                int j = origins.cellGrid().flatIndex(x, y);
                boolean positive = origins.testCell(j, (opp, ii) ->
                      (opp != null && opp.counts()[ii] > 0.0));
                if (positive) return true;
            }
        }
        return false;
    }

    /// Create a small grid of origin points centered around the given geographic coordinate.
    /// Height and width are different to catch axis order problems: the result should be taller
    /// than it is wide.
    private static TileGrid<Opportunities> originGridForTesting (double lon, double lat) {
        // For origin points we don't need a grid scheme that exactly fits 100m tiles. It could
        // have arbitrary points. But using tiles does better match with the usual use cases.
        var smallBounds = Wgs84Bounds.bufferPoint(lon, lat, 100).resizeCenteredMeters(1000, 6000);
        GridScheme smallTileGrid = SparseEgressBuilder.tileGridContaining(smallBounds);
        int nTiles = smallTileGrid.nElements();
        // Fill 2/3 of origin grid with density values so most origins are not skipped.
        // Due to grid axis order, the bottom 1/3 of tiles should remain null and be interpreted as zero.
        int nTilesFilled = nTiles * 2 / 3;
        var tileMap = new TIntObjectHashMap<Opportunities>(nTiles);
        for (int i = 0; i < nTilesFilled; i++) {
            float[] cellValues = new float[TILE_SPAN_CELLS * TILE_SPAN_CELLS];
            Arrays.fill(cellValues, 10.0f);
            tileMap.put(i, new Opportunities(cellValues));
        }
        return new TileGrid<>(smallTileGrid, tileMap);
    }

    public synchronized void markComplete (int taskId) {
        checkArgument(taskId < nTasksTotal, "Task ID is too high.");
        checkState(!this.completed.get(taskId), "Task result was already submitted.");
        completed.set(taskId);
        visualProgress.finished(taskId);
        nTasksRemaining -= 1;
        progress.increment();
    }

    public boolean noTasksRemaining () {
        // This should be faster on average than checking the cardinality of BitSet completed.
        return this.nTasksRemaining <= 0;
    }

    /// Tasks are identified by the integer index of an origin in the origins set of the job.
    /// It is tempting to work on contiguous blocks, specified by offset and length. However, we
    /// want to be able to mask off or skip large numbers of origins scattered through the set
    /// based on some predicate. And we may need to retry individual tasks that fail or go missing,
    /// though in a system without ephemeral workers failures are not expected in normal operation.
    /// The size overhead of just serializing sets or lists of integer IDs is almost trivial.
    public record TaskBlock (String jobId, @Nonnull int[] taskIds) {
        public boolean isEmpty () {
            return this.taskIds.length == 0;
        }
        public int size () {
            return this.taskIds.length;
        }
        public static TaskBlock empty() {
            return new TaskBlock(null, new int[0]);
        }
    }

    /// Get the next block of tasks for the currently active job.
    /// NOTE this is going to leave workers idle until all tasks are completed for the job.
    /// @return a TaskBlock from the current job, whose array of taskIds may be empty but never null.
    public synchronized @Nonnull TaskBlock nextTaskBlock (int maxTasks) {
        TIntList taskIds = new TIntArrayList();
        while (nextTask < nTasksTotal && taskIds.size() < maxTasks) {
            if (!skipped.get(nextTask)) {
                checkState(!completed.get(nextTask), "Next task already completed. This is a bug.");
                taskIds.add(nextTask);
            }
            nextTask += 1;
        }
        int[] idArray = taskIds.toArray();
        visualProgress.sent(idArray);
        return new TaskBlock(id, idArray);
    }

}
