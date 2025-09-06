// Copyright 2023-2025 Phase Five LLC.  For license terms, see LICENSE.txt in the repository root.

package io.pfive.access.batch;

import io.pfive.access.background.BackgroundItemTracker;
import io.pfive.access.batch.BatchJob.TaskBlock;
import io.pfive.access.http.model.OneToManyOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.MethodHandles;
import java.util.ArrayDeque;
import java.util.Objects;
import java.util.Queue;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;

/// Part of the native batch job system, not for tracking job processing managed by libraries.
/// This will process jobs sequentially one at a time. It should support pre-filtering the tasks
/// to process sparse grids of origins. Tasks should be pulled from this job manager rather than
/// pushed, as this allows the slowest link in the chain to apply backpressure. This class should
/// be threadsafe to support submitting and requesting tasks from multiple HTTP handler threads.
public class BatchJobManager {

    private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
    private final BackgroundItemTracker background;
    private final Queue<BatchJob> queuedJobs = new ArrayDeque<>();
    private BatchJob currentJob = null;
    private BatchJobResults currentResults = null;

    public BatchJobManager (BackgroundItemTracker background) {
        this.background = background;
    }

    public synchronized void addJob (BatchJob job) {
        queuedJobs.add(job);
        if (currentJob == null) {
            proceedToNextJobIfAny();
        }
    }

    private synchronized void proceedToNextJobIfAny () {
        checkState(currentJob == null, "Expected no currently active job.");
        checkState(currentResults == null, "Expected no active job results.");
        currentJob = queuedJobs.poll(); // returns null if queue is empty.
        if (currentJob != null) {
            LOG.info("Started batch job.");
            // TODO supply BatchJob to give results matching size.
            currentResults = new BatchJobResults(currentJob);
        }
    }

    /// Retain single-task results in the job results.
    public synchronized void submitResult (BatchTaskResult result) {
        checkArgument(Objects.equals(result.jobId, currentJob.id), "Can only submit results for currently active job.");
        currentResults.submitResult(result);
        currentJob.markComplete(result.taskId);
        if (currentJob.noTasksRemaining()) {
            // Job is complete. Create output files.
            background.run(new BatchFinalizeRunnable(currentJob, currentResults));
            currentJob = null;
            currentResults = null;
            proceedToNextJobIfAny();
        }
    }

    public synchronized void submitResults (Iterable<BatchTaskResult> results) {
        for (BatchTaskResult result : results) {
            submitResult(result);
        }
    }

    /// Get the next block of tasks for the current job, containing at most maxTasks tasks.
    /// The taskBlock may be empty if the current job has no tasks left to distribute.
    /// If there is a currently active job, a TaskBlock instance with a valid jobId will always be
    /// returned, though the TaskBlock may be empty. If no job is currently active, returns null.
    /// @return a possibly empty TaskBlock object with a valid jobId, or null if no job is active.
    public synchronized TaskBlock getTasks (int maxTasks) {
        if (currentJob == null) return null;
        return currentJob.nextTaskBlock(maxTasks);
    }

    /// The caller is responsible for verifying that the user has permission to take this action.
    /// Or maybe the user identity or group ID should be supplied to facilitate that check.
    public synchronized void cancelCurrentJob (String organizationId) {
        if (currentJob == null) return;
        if (currentJob.user.organization.equals(organizationId)) {
            // currentJob.abandon();
            currentJob = null;
            currentResults = null;
            proceedToNextJobIfAny();
        }
    }

    /// This is a stopgap for local task processing. Presumably additional machines would need to
    /// look up job details through another mechanism (such as a memoized API endpoint).
    /// Only the public details (id, user identity, routing options) should be used by the caller.
    public synchronized BatchJob getBatchJob (String jobId) {
        checkArgument(jobId.equals(currentJob.id), "Can only fetch currently active job.");
        return currentJob;
    }

    public synchronized OneToManyOptions getBatchJobOptions (String jobId) {
        checkArgument(jobId.equals(currentJob.id), "Can only fetch options for currently active job.");
        return currentJob.options;
    }

    /// Maybe return these stats per queued job, with the user's jobs highlighted.
    /// But we can also just sidestep queueing complexities for now and process one job at a time.
    public record JobQueueStats (int totalOrigins, int totalOriginDestinationPairs) {}

}
