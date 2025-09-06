// Copyright 2023-2025 Phase Five LLC.  For license terms, see LICENSE.txt in the repository root.

package io.pfive.access.background;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/// This serves two purposes: it provides a view of active tasks over an HTTP/RPC mechanism to
/// clients. And it also manages the work queues for the submitted tasks. In addition to tasks that
/// will run in the background, it needs to track tasks that will run synchronously in the submitter
/// thread (such as file uploads). It may be better to split the progress reporting and background
/// task management functionality. There are no plans to instantiate this more than once, so it
/// could be static unless needed for unit tests.
public class BackgroundItemTracker {
    private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
    private static final int N_THREADS = 4;

    private final ExecutorService executorService = Executors.newFixedThreadPool(N_THREADS);

    // For now do not index on user or organization, allow filtering on the fly.
    private final List<BackgroundItem> backgroundItems = new LinkedList<>();

    public synchronized void add (BackgroundItem backgroundItem) {
        backgroundItems.add(backgroundItem);
    }

    /// This probably shouldn't be separate from add, but it works for now.
    /// When exceptions occur on an executor task they can lock up the thread, so wrap in try block.
    public void run (Runnable runnable) {
        // TODO named wrapper class instead of anonymous function.
        // TODO ProgressSink parameter to report errors.
        executorService.submit(() -> {
            try {
                runnable.run();
            } catch (Throwable t) {
                LOG.error("Error in background task: " + t);
                t.printStackTrace(System.out);
            }
        });
    }

    public synchronized List<BackgroundItem> getAll () {
        // Return a protective copy made within synchronized block.
        return new ArrayList<>(backgroundItems);
    }

    public synchronized void clearCompleted () {
        backgroundItems.removeIf(b -> {
            final BackgroundItem.State state = b.progressSink.state();
            return state == BackgroundItem.State.COMPLETED || state == BackgroundItem.State.ERRORED;
        });
    }

}
