// Copyright 2023-2025 Phase Five LLC.  For license terms, see LICENSE.txt in the repository root.

package io.pfive.access.sparse;

import com.conveyal.r5.profile.StreetMode;
import com.conveyal.r5.streets.Split;
import com.conveyal.r5.streets.StreetLayer;
import com.google.common.base.MoreObjects;
import gnu.trove.list.TIntList;
import gnu.trove.list.array.TIntArrayList;

import static com.conveyal.r5.profile.FastRaptorWorker.UNREACHED;
import static io.pfive.access.sparse.ByteSizeUtil.OBJECT_BYTES;
import static io.pfive.access.sparse.ByteSizeUtil.intArrayFieldBytes;
import static io.pfive.access.sparse.EgressTimes.filterMaxSeconds;

/// An ordered list of connectors to the street network corresponding to an ordered list of
/// geographic points. Parallel iteration over arrays may encourage use of SIMD instructions in
/// object code, but given the hashtable lookups in the StreetVertexCosts this doesn't seem likely.
public record StreetConnectors(int[] vertexA, int[] distMmA, int[] vertexB, int[] distMmB)
      implements ByteSize, MinAvgMax.Producer<StreetVertexCosts, Durations> {

    public static final int NO_VERTEX = -1;
    public static final int NO_DISTANCE = -1;
    public static final int MAX_CONNECTOR_LENGTH_METERS = 500;
    public static final int SPEED_MM_PER_SEC = 1300;

    /// Given costs to reach each street vertex, return the cost to reach each geographic point.
    @Override
    public Durations eval (StreetVertexCosts vertexCosts) {
        int n = vertexA.length;
        int[] durationsSeconds = new int[n];
        for (int i = 0; i < n; i++) {
            // Unlinked points are indicated by a vertex index and distance of -1. Looking
            // up the cost of this nonexistent vertex -1 or any unreached vertex will yield
            // Integer.MAX_VALUE. Vertex costs and post-vertex distances are all in millimeters.
            int costA = (vertexCosts.costAtVertex(vertexA[i]));
            int costB = (vertexCosts.costAtVertex(vertexB[i]));
            int timeA = (costA == UNREACHED) ? UNREACHED : switch (vertexCosts.costUnits) {
                case DISTANCE_MILLIMETERS -> (costA + distMmA[i]) / SPEED_MM_PER_SEC;
                case DURATION_SECONDS -> costA + (distMmA[i] / SPEED_MM_PER_SEC);
            };
            int timeB = (costB == UNREACHED) ? UNREACHED : switch (vertexCosts.costUnits) {
                case DISTANCE_MILLIMETERS -> (costB + distMmB[i]) / SPEED_MM_PER_SEC;
                case DURATION_SECONDS -> costB + (distMmB[i] / SPEED_MM_PER_SEC);
            };
            // TODO return null when nothing was reached
            durationsSeconds[i] = filterMaxSeconds(Math.min(timeA, timeB));
        }
        return new Durations(durationsSeconds, durationsSeconds, durationsSeconds);
    }

    @Override
    public int byteSize () {
        return OBJECT_BYTES + intArrayFieldBytes(vertexA) * 4;
    }

    @Override
    public String toString () {
        return MoreObjects.toStringHelper(this)
              .add("nPoints", vertexA.length)
              .add("memBytes", humanByteSize())
              .toString();
    }

    /// Used to build up immutable StreetConnectors records from a series of searches around points.
    public static class Builder {
        private final StreetLayer streetLayer;
        private final TIntList vertexA = new TIntArrayList();
        private final TIntList distMmA = new TIntArrayList();
        private final TIntList vertexB = new TIntArrayList();
        private final TIntList distMmB = new TIntArrayList();
        private int nConnectorsFound = 0;

        public Builder (StreetLayer streetLayer) {
            this.streetLayer = streetLayer;
        }

        // We can't assume every tile will have roads in it. Many regions are half water.
        // The hash grid spatial index in the StreetLayer should in principle work quite well even
        // for selecting a single pixel out of an entire country. Note that the connector radius
        // is the highest radius that will be tried only if a hard-wired 300m radius fails.
        // An instance method reference to this method conforms to LonLatConsumer functional interface.
        public void findConnector (double lon, double lat) {
            addSplit(streetLayer.findSplit(lat, lon, MAX_CONNECTOR_LENGTH_METERS, StreetMode.WALK));
        }

        /// Accumulate street connectors one by one, with each corresponding to a point. It should
        /// be possible to define a method here that takes a street network and an iterable over
        /// points and does this more cleanly, with the GridScheme implementing
        /// `Iterable<GeoPoint>`.
        /// @param split may be null if no roads are nearby.
        private void addSplit (Split split) {
            if (split == null) {
                vertexA.add(NO_VERTEX);
                vertexB.add(NO_VERTEX);
                distMmA.add(NO_DISTANCE);
                distMmB.add(NO_DISTANCE);
            } else {
                vertexA.add(split.vertex0);
                vertexB.add(split.vertex1);
                distMmA.add(split.distance0_mm);
                distMmB.add(split.distance1_mm);
                nConnectorsFound += 1;
            }
        }

        public boolean foundAnyConnectors () {
            return nConnectorsFound > 0;
        }

        /// Returns null if no connectors were added to this builder (if all splits were null).
        public StreetConnectors build () {
            if (foundAnyConnectors()) {
                return new StreetConnectors(vertexA.toArray(), distMmA.toArray(), vertexB.toArray(), distMmB.toArray());
            } else {
                return null;
            }
        }
    }

}
