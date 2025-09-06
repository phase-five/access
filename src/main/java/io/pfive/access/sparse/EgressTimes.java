// Copyright 2023-2025 Phase Five LLC.  For license terms, see LICENSE.txt in the repository root.

package io.pfive.access.sparse;

import com.google.common.base.MoreObjects;
import gnu.trove.list.TIntList;
import gnu.trove.list.array.TIntArrayList;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static com.conveyal.r5.profile.FastRaptorWorker.UNREACHED;
import static io.pfive.access.sparse.ByteSizeUtil.OBJECT_BYTES;
import static io.pfive.access.sparse.ByteSizeUtil.intArrayFieldBytes;
import static io.pfive.access.sparse.ByteSizeUtil.nNonMax;
import static io.pfive.access.sparse.ByteSizeUtil.objectArrayFieldBytes;

/// Given travel times to transit stops, efficiently produces travel times to geographic points.
/// One of these is created per tile in a SparseEgressTable.
public record EgressTimes(int[] stopIds, int[][] egressDurationsSeconds)
      implements ByteSize, MinAvgMax.Operator<TransitTimes, Durations> {

    /// Skips travel times too long to be displayed. This includes UNREACHED values that will
    /// overflow upon addition, but is more selective and eliminates more points that won't
    /// contribute to displayed results. Could be configurable to allow longer total travel times.
    public static final int MAX_SEC = 120 * 60;

    /// Given TransitTimes to all stops, return times to all geographic points in this EgressTimes.
    /// Returns null if none of the points are reached, allowing steps to be skipped in the caller.
    /// This could also convert to float if we want to make use of POSITIVE_INFINITY and SIMD.
    /// TODO Check whether this is faster with axes swapped.
    private int[] toCellDurations (int[] stopDurations) {
        int[] durationsToPoints = new int[egressDurationsSeconds[0].length];
        Arrays.fill(durationsToPoints, UNREACHED);
        boolean reached = false;
        for (int sidx = 0; sidx < stopIds.length; sidx++) {
            int ts = stopDurations[stopIds[sidx]];
            if (ts >= MAX_SEC) continue;
            for (int didx = 0; didx < durationsToPoints.length; didx++) {
                int te = egressDurationsSeconds[sidx][didx];
                if (te >= MAX_SEC) continue;
                int tt = ts + te;
                if (tt < MAX_SEC && tt < durationsToPoints[didx]) {
                    durationsToPoints[didx] = tt;
                    reached = true;
                }
            }
        }
        return reached ? durationsToPoints : null;
    }

    /// Initial check to bail out without any allocation or initialization if no stops reached.
    /// Anecdotally, using this to bail out early seems to make the process slower, not faster.
    private boolean anyStopReached (int[] stopDurations) {
        for (int sid : stopIds) {
            if (stopDurations[sid] < MAX_SEC) {
                return true;
            }
        }
        return false;
    }

    /// Add and build methods are synchronized because multiple street searches for different stops
    /// may try to update the same tile from different threads.
    public static class Builder {
        final StreetConnectors connectors;
        final TIntList stopIds = new TIntArrayList();
        final List<int[]> durationsFromStopsToPoints = new ArrayList<>();

        public Builder (StreetConnectors connectors) {
            this.connectors = connectors;
        }

        /// Given StreetVertexCosts from a street search outward from a single transit stop, use
        /// the StreetConnectors for this tile to infer the durations at the tile's GeoPoints.
        /// Accumulate those times in the builder for later conversion to efficient immutable form.
        public synchronized void addStop (int egressStopId, StreetVertexCosts vertexCosts) {
            Durations pointsDurations = connectors.eval(vertexCosts);
            if (pointsDurations != null && pointsDurations.anyReached()) {
                stopIds.add(egressStopId);
                durationsFromStopsToPoints.add(pointsDurations.min);
            }
        }

        /// Create an immutable EgressTimes record from the accumulated values.
        /// Returns null if no stops and times were added to this builder.
        public synchronized EgressTimes build () {
            if (stopIds.isEmpty()) return null;
            int[][] egressDurationsSeconds = durationsFromStopsToPoints.toArray(int[][]::new);
            return new EgressTimes(stopIds.toArray(), egressDurationsSeconds);
        }
    }

    /// Transform anything over the maximum displayable number of seconds to UNREACHED.
    public static int filterMaxSeconds (int seconds) {
        if (seconds >= MAX_SEC) return UNREACHED;
        else return seconds;
    }

    public int fillPercent () {
        double total = 0;
        for (var durations : egressDurationsSeconds) total += nNonMax(durations);
        return (int) (total * 100.0) / (stopIds.length * egressDurationsSeconds[0].length);
    }

    /// Interface implementations

    @Override
    public int byteSize () {
        return OBJECT_BYTES + intArrayFieldBytes(stopIds) +
              objectArrayFieldBytes(egressDurationsSeconds, ByteSizeUtil::intArrayBytes);
    }

    @Override
    public Durations createResult (int[] min, int[] avg, int[] max) {
        return new Durations(min, avg, max);
    }

    @Override
    public int[] applyOne (int[] stopDurations) {
        // Array of travel times to stops, into an array of travel times to geographic points.
        return toCellDurations(stopDurations);
    }

    @Override
    public String toString () {
        return MoreObjects.toStringHelper(this)
              .add("nStops", stopIds.length)
              .add("nCells", egressDurationsSeconds[0].length)
              .add("fillPercent", fillPercent())
              .add("memBytes", humanByteSize())
              .toString();
    }
}
