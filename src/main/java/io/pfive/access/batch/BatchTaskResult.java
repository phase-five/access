// Copyright 2023-2025 Phase Five LLC.  For license terms, see LICENSE.txt in the repository root.

package io.pfive.access.batch;

import io.pfive.access.sparse.AccessBins;

import java.io.Serializable;

/// Immutable representation of one result from a single batch job task, for sending as RPC response.
/// Supports only one destination data set for now. Axis order is (percentile, cutoff).
/// Record syntax (long constructor calls, accessor method calls) is often not worth it unless you
/// need hashcode and equals.
/// Should this be migrated to use floats?
/// Do we really need both primal and dual access for a single analysis?
public class BatchTaskResult implements Serializable {
    public final String jobId;
    public final int taskId;
    public final int[][] access;
    public final int[][] dual;
    public final String error;

    /// Construct a result indicating an error has occurred when processing this task.
    public BatchTaskResult (String jobId, int taskId, Throwable throwable) {
        this.jobId = jobId;
        this.taskId = taskId;
        this.access = null;
        this.dual = null;
        this.error = throwable.toString();
    }

    public BatchTaskResult (String jobId, int taskId, AccessBins result) {
        this.jobId = jobId;
        this.taskId = taskId;
        this.access = result.cumulative();
        this.dual = result.dual();
        this.error = null;
    }
}
