// Copyright 2023-2025 Phase Five LLC.  For license terms, see LICENSE.txt in the repository root.

package io.pfive.access.background;

import com.conveyal.r5.analyst.progress.ProgressListener;
import io.pfive.access.authentication.UserIdentity;
import io.pfive.access.http.handler.EventSourceHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.MethodHandles;
import java.util.Map;

/// Stores progress information, occasionally pushing updates to the client.
public class ProgressSink implements ProgressListener {
    private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    // Parameters affecting the maximum number and frequency of push messages for a single task.
    private static final int DEFAULT_MAX_EVENTS = 100;
    private static final int DEFAULT_MIN_MSEC = 100;

    // Permanent characteristics of the background item.
    private final String id;
    private final UserIdentity user;

    // Tracking current state of the background item.
    private String title = "UNKNOWN";
    private int totalSteps = 1000;
    private int stepsCompleted = 0;
    private BackgroundItem.State state = BackgroundItem.State.WAITING;
    private long startTime;
    private long endTime;
    // private int activeSeconds;
    // private int remainingSeconds;

    // Variables used in throttling push messages.
    private int prevPushStep = 0;
    private int pushAfter = 0;
    private long lastPushTime = 0;
    private int msecBetweenEvents = DEFAULT_MIN_MSEC;

    public ProgressSink (String id, UserIdentity user) {
        this.id = id;
        this.user = user;
    }

    public void pushEventAfter (int n) {
        this.pushAfter = n;
    }

    public void minTimeBetweenEventsMsec (int msec) {
        this.msecBetweenEvents = msec;
    }

    private int estimateRemainingSeconds (long currentTime) {
        double activeTimeSeconds = (currentTime - this.startTime) / 1000.0;
        double stepsRemaining = totalSteps - stepsCompleted;
        return (int)(activeTimeSeconds * stepsRemaining / stepsCompleted);
    }

    //// Implementation of ProgressListener for compatibility with R5 ////

    @Override
    public void beginTask (String title, int totalSteps) {
        this.title = title;
        this.totalSteps = totalSteps;
        stepsCompleted = 0;
        if (state == BackgroundItem.State.WAITING) {
            this.startTime = System.currentTimeMillis();
        }
        state = BackgroundItem.State.IN_PROGRESS;
        // Throttling will still function if totalSteps <= MAX_EVENTS and pushEvery is zero.
        pushAfter = totalSteps / DEFAULT_MAX_EVENTS;
        prevPushStep = 0;
        EventSourceHandler.sendMapAsEvent(user, "progress", Map.of(
              "id", this.id,
              "type", "begin",
              "title", this.title,
              "total", this.totalSteps
        ));
    }

    // TODO show (n/m) when number of items is less than 100, show integer percentages when over 500 items.

    /// Threadsafe: This may be called by many threads at once, for example in parallel streams.
    @Override
    public synchronized void increment (int i) {
        // LOG.info("Increment {}", i);
        if (state == BackgroundItem.State.COMPLETED) return;
        stepsCompleted += i;
        if (stepsCompleted >= totalSteps) {
            this.state = BackgroundItem.State.COMPLETED;
            this.endTime = System.currentTimeMillis();
            EventSourceHandler.sendMapAsEvent(this.user, "progress", Map.of(
                  "id", this.id,
                  "type", "done",
                  "durationSec", (endTime - startTime)/1000
            ));
        } else if (stepsCompleted >= prevPushStep + pushAfter) {
            long currTime = System.currentTimeMillis();
            if (currTime - lastPushTime < msecBetweenEvents) return;
            // FIXME this seems to assume that tab was open when the background task began so the receiver already
            //  knows how many total steps there are (which is not necessarily true)
            EventSourceHandler.sendMapAsEvent(this.user, "progress", Map.of(
                  "id", this.id,
                  "type", "step",
                  "step", this.stepsCompleted,
                  "total", this.totalSteps,
                  "secRemain", estimateRemainingSeconds(currTime)
            ));
            prevPushStep = stepsCompleted;
            lastPushTime = currTime;
        }
    }

    //// Additional convenience methods not implementing ProgressListener ////

    // TODO complete this validation logic
    private void stateTransition (BackgroundItem.State fromState, BackgroundItem.State toState) {
        if (state != fromState) {
            String message = String.format("State may only transition to %s from %s.", fromState, toState);
            throw new IllegalStateException(message);
        }
        state = toState;
    }

    public void start (int totalSteps) {
        this.totalSteps = totalSteps;
        stateTransition(BackgroundItem.State.WAITING, BackgroundItem.State.IN_PROGRESS);
    }

    public void completed () {
        stateTransition(BackgroundItem.State.IN_PROGRESS, BackgroundItem.State.COMPLETED);
    }

    public void error (String error) {
        this.state = BackgroundItem.State.ERRORED;
        this.title = error;
        // stateTransition(BackgroundItem.State.IN_PROGRESS, BackgroundItem.State.ERRORED);
    }

    public BackgroundItem.State state () {
        return state;
    }

}
