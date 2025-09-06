// Copyright 2023-2025 Phase Five LLC.  For license terms, see LICENSE.txt in the repository root.

package io.pfive.access.batch;

import io.pfive.access.sparse.GridScheme;
import io.pfive.access.sparse.SparseOneToManyProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.MethodHandles;

/// TODO Combine with RpcClient.RpcClientTaskRunnable.run so the same runnable can be used locally and on compute nodes.
public class BatchTaskRunnable implements Runnable {

    private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    private final String jobId;
    private final int taskId;
    private final BatchJobManager batchJobManager;

    public BatchTaskRunnable (String jobId, int taskId, BatchJobManager batchJobManager) {
        this.jobId = jobId;
        this.taskId = taskId;
        this.batchJobManager = batchJobManager;
    }

    @Override
    public void run () {
        // Catch any exception or error to prevent crashing pooled threads and report to job manager.
        try {
            // RegionalTask/AnalysisWorkerTask and OneOriginResult should be confined to this method
            // body. All conversion from/to R5 types should happen here.
            // Look up details of the specified job ID.
            // This should be further decoupled from the BatchJobManager to enable additional nodes.
            BatchJob job = batchJobManager.getBatchJob(jobId);
            // We need to go from flat cell index to center lon/lat and tile.
            // Should we retain this value (add a pixel grid to TileGrid)?
            // TODO validate with checkNotNull, checkElementIndex etc.
            GridScheme cellGrid = job.origins.cellGrid();
            var processor = new SparseOneToManyProcessor(job.options, job.user);
            double lat = cellGrid.centerLatForY(cellGrid.yForFlatIndex(taskId));
            double lon = cellGrid.centerLonForX(cellGrid.xForFlatIndex(taskId));
            processor.overrideLatLon(lat, lon);
            processor.process();
            // Convert results to compact format intended for serialization across RPC and submit.
            batchJobManager.submitResult(new BatchTaskResult(jobId, taskId, processor.accessBins));
        } catch (Throwable t) {
            LOG.error("Error in batch job task.", t);
            // Submit result showing that an error occurred, and which error.
            batchJobManager.submitResult(new BatchTaskResult(jobId, taskId, t));
        }
    }

}
