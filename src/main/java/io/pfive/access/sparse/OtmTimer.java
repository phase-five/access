// Copyright 2023-2025 Phase Five LLC.  For license terms, see LICENSE.txt in the repository root.

package io.pfive.access.sparse;

import com.conveyal.r5.profile.ExecutionTimer;

/// Groups together a hierarchical set of timers for different steps in One-To-Many routing.
/// TODO Class to group timers. timer.enter
public class OtmTimer {

    private final ExecutionTimer full = new ExecutionTimer("Full one-to-many processing");
    final ExecutionTimer street = new ExecutionTimer(full, "Street routing");
    final ExecutionTimer transit = new ExecutionTimer(full, "Transit routing");
    final ExecutionTimer propagation = new ExecutionTimer(full, "Propagation and access");
    final ExecutionTimer png = new ExecutionTimer(full, "PNG rendering");

    private ExecutionTimer current = null;

    /// Auto-starts when created.
    public OtmTimer () {
        full.start();
    }

    /// Do not call start() or stop() directly on subtimers. Use this method to transition.
    /// It might make sense to use enum values instead of fields for sub-timers. But then you can't
    /// have multiple instances of the timer group for different threads.
    public void start(ExecutionTimer subtimer) {
        if (current != null) current.stop();
        subtimer.start();
        current = subtimer;
    }

    /// Stop the top level timer and log the times.
    public void done () {
        if (current != null) current.stop();
        current = null;
        full.stop();
        full.logWithChildren();
    }

}
