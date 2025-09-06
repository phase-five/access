// Copyright 2023-2025 Phase Five LLC.  For license terms, see LICENSE.txt in the repository root.

package io.pfive.access.batch;

import java.util.Objects;

import static com.google.common.base.Preconditions.checkArgument;
import static io.pfive.access.sparse.SparseOneToManyProcessor.STANDARD_CUTOFFS;

/// The chosen approach is to accumulate and retain the entire set of output data in memory, then
/// write it out all at once into image files when the entire analysis is complete.
/// Rather than computing and retaining the access indicators, we could instead retain the densities.
/// This allows computing multiple sets of output data from the same raw internal data. For example,
/// cumulative access indicators with several different decay functions and dual access for multiple
/// numbers of opportunities reached. However, we may want decay function application to occur on
/// compute nodes rather than centralizing it all on the receiving end.
public class BatchJobResults {
    private final String jobId;
    /// Axis order is (origin, percentile, cutoff)
    private final int[][][] accessForOrigin;
    /// TODO consider whether we ever need primal and dual access in the same analysis or can use a single field.
    /// Also, might it be good to store these in seconds for smoother GeoTIFF output?
    private final int[][][] dualForOrigin;

    public BatchJobResults (BatchJob job) {
        this.jobId = job.id;
        this.accessForOrigin = new int[job.nTasksTotal][][];
        this.dualForOrigin = new int[job.nTasksTotal][][];
    }

    public void submitResult (BatchTaskResult result) {
        checkArgument(Objects.equals(this.jobId, result.jobId), "Result does not match job.");
        accessForOrigin[result.taskId] = result.access;
        dualForOrigin[result.taskId] = result.dual;
    }

    /// For a given percentile and cutoff, extract the access values for all origins into an array.
    /// Output axis order and orientation is chosen to facilitate writing them into image formats.
    /// Accessibility figures are internally float64s in R5, but are converted to ints on export.
    /// Previously those were converted to float32s (so double to int to float) to fit GeoTools.
    /// Floats do have the "advantage" of being able to represent missing values with NaN, and at
    /// 32 bits are the same size (uncompressed) as ints. However, 8 bits are consumed by the
    /// exponent so floats can only exactly represent integers up to 2^24 rather than 2^32.
    /// This limits exactly representable values to about 16 million, but it degrades gracefully
    /// beyond that point with the smallest increment simply dropping to 2 instead of 1.
    /// https://stackoverflow.com/a/3793950
    ///
    /// @param ip index into the percentiles array
    /// @param ic index into the travel time cutoffs array (not the cutoff in minutes)
    /// @return access values in (y, x) axis order with y increasing toward the south.
    public int[][] extractAccess (int width, int height, final int ip, final int ic) {
        int[][] out = new int[height][width];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int[][] access = accessForOrigin[y * width + x];
                int pcAccess = (access == null) ? 0 : access[ip][STANDARD_CUTOFFS[ic]];
                // Image formats we're writing into have y axis increasing downward, following
                // computer graphics screen coordinate conventions.
                out[height-y-1][x] = pcAccess;
            }
        }
        return out;
    }
}
