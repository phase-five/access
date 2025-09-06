// Copyright 2023-2025 Phase Five LLC.  For license terms, see LICENSE.txt in the repository root.

package io.pfive.access.batch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.MethodHandles;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static io.pfive.access.util.JettyUtil.msecSleep;

/// Fetch batch job tasks, compute results for them in parallel, and return the results.
/// In a fully single-node system, we would not need a busy-polling loop and could just wait on
/// a blocking queue. The busy-polling approach can be generalized to multiple compute nodes.
public class BatchJobProcessor implements Runnable {

    private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
    private final BatchJobManager batchJobManager;
    private final BlockingQueue<Runnable> queue;
    private final ThreadPoolExecutor executor;

    public BatchJobProcessor (BatchJobManager batchJobManager) {
        this.batchJobManager = batchJobManager;
        // We get somewhat higher throughput using all availableProcessors, but on the primary
        // backend instance we want to leave some spare capacity for smooth interactive use.
        // Do not use the "hyperthreading" cores for batch processing. This should also leave some
        // capacity available for garbage collection and user interaction.
        final int threads = Runtime.getRuntime().availableProcessors() / 2;
        final int queueLength = threads * 100;
        this.queue = new LinkedBlockingQueue<>(queueLength);
        this.executor = new ThreadPoolExecutor(threads, threads, 60, TimeUnit.SECONDS, queue);
    }

    @Override
    public void run () {
        boolean shouldPause = false;
        while (true) {
            if (shouldPause) {
                // This does not fill the buffer enough for large empty areas.
                // They should be pre-filtered, or we should not pause until we get zero tasks.
                if (msecSleep(1000)) return;
            }
            int nToRequest = queue.remainingCapacity();
            if (nToRequest == 0) {
                shouldPause = true;
                continue;
            }
            // TODO switchable local / RPC logic for fetching tasks.
            BatchJob.TaskBlock taskBlock = batchJobManager.getTasks(nToRequest);
            if (taskBlock == null || taskBlock.isEmpty()) {
                shouldPause = true;
                continue;
            }
            // This variable-pause approach may be unnecessary as the native job management does
            // not impose artificial limits on the number of tasks distributed.
            // TODO consider concurrency primitives that make BatchTask as a function producing a result into a pipeline.
            shouldPause = (taskBlock.taskIds().length == nToRequest);
            for (int taskId : taskBlock.taskIds()) {
                executor.execute(new BatchTaskRunnable(taskBlock.jobId(), taskId, batchJobManager));
            }
        }
    }

}
