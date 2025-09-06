// Copyright 2023-2025 Phase Five LLC.  For license terms, see LICENSE.txt in the repository root.

package io.pfive.access.sparse;

import static io.pfive.access.sparse.EgressTimes.MAX_SEC;

/// An ordered collection of opportunity counts, corresponding to an ordered collection of
/// geographic points or cells. Used for opportunity counts within square tiles of pixels.
/// No reference is currently held to the geographic points themselves. This serves mostly to
/// provide strong typing and associated methods for cumulative access calculations.
/// We may want a toPng() method for visualization as tiles, but these don't fit neatly into the
/// web Mercator raster tiles expected by Mapbox GL JS.
public record Opportunities (float[] counts)
      implements ByteSize, MinAvgMax.Operator<Durations, AccessBins> {

    /// Given durations in seconds to the same points referenced by these opportunities, place all
    /// reachable opportunities into bins based on the minute of travel during which they are
    /// reached. Will return null if the supplied durations are null (e.g. when the current tile
    /// was not reached) or if no nonzero opportunity counts were reached in this tile.
    private int[] accessBinsForDurations (int[] durations) {
        if (durations == null) return null;
        int[] histogram = new int[120];
        boolean nonzero = false;
        for (int i = 0; i < counts.length; i++) {
            int seconds = durations[i];
            if (counts[i] > 0 && seconds < MAX_SEC) {
                int minutes = seconds / 60;
                histogram[minutes] += (int)counts[i];
                nonzero = true;
            }
        }
        return nonzero ? histogram : null;
    }

    @Override
    public int byteSize () {
        return ByteSizeUtil.floatArrayBytes(counts);
    }

    // Methods implementing `MinAvgMax.Operator<Durations, AccessBins>` interface

    @Override
    public AccessBins createResult (int[] min, int[] avg, int[] max) {
        return new AccessBins(min, avg, max);
    }

    @Override
    public int[] applyOne (int[] durations) {
        // Given an array of travel times to the same points referenced by this instance,
        // return how many opportunities are encountered during each minute of travel.
        return accessBinsForDurations(durations);
    }

}
