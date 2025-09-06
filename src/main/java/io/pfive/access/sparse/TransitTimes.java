// Copyright 2023-2025 Phase Five LLC.  For license terms, see LICENSE.txt in the repository root.

package io.pfive.access.sparse;

import java.util.Arrays;

import static com.conveyal.r5.profile.FastRaptorWorker.UNREACHED;
import static io.pfive.access.sparse.EgressTimes.MAX_SEC;

/// A more strongly-typed wrapper for results from FastRaptorWorker. Min and max values are computed
/// immediately, as well as a central tendency. Anecdotally, manual vector API usage does not seem
/// to be very effective in this instance. Perhaps JVM auto-vectorization is effective here because
/// of the obvious nested loops. Extracting only one percentile could make interactive one-to-many
/// response times faster, at the expense of being able to compare those percentile results from a
/// single request.
public final class TransitTimes extends MinAvgMax {
    public TransitTimes (int[] min, int[] avg, int[] max) {
        super(min, avg, max);
    }

    public static TransitTimes fromRaptorResult (int[][] timesFromRaptor) {
        int nIter = timesFromRaptor.length;
        int nStops = timesFromRaptor[0].length;
        int[] min = new int[nStops];
        int[] max = new int[nStops];
        int[] avg = new int[nStops];
        int[] timesOneStop = new int[nIter];
        for (int s = 0; s < nStops; s++) {
            for (int i = 0; i < nIter; i++) {
                timesOneStop[i] = timesFromRaptor[i][s];
            }
            Arrays.sort(timesOneStop);
            min[s] = timesOneStop[0];
            max[s] = timesOneStop[timesOneStop.length - 1];
            // NOTE This is incorrect, median must be extracted at destination not at stop.
            // The same is true of the average
            // However, min and max can be found by taking min(min()) and min(max()).
            avg[s] = timesOneStop[timesOneStop.length / 2];
        }
        return new TransitTimes(min, avg, max);
    }

    public static TransitTimes averageFromRaptorResult (int[][] timesFromRaptor) {
        int nStops = timesFromRaptor[0].length;
        int[] min = new int[nStops];
        int[] avg = new int[nStops];
        int[] max = new int[nStops];
        double[] total = new double[nStops];
        int[] count = new int[nStops];
        Arrays.fill(min, Integer.MAX_VALUE);
        Arrays.fill(max, Integer.MIN_VALUE);
        for (int[] times : timesFromRaptor) {
            for (int s = 0; s < nStops; s += 1) {
                if (times[s] < MAX_SEC) {
                    min[s] = Math.min(min[s], times[s]);
                    max[s] = Math.max(min[s], times[s]);
                    total[s] = total[s] + times[s];
                    count[s] += 1;
                }
            }
        }
        for (int s = 0; s < nStops; s += 1) {
            if (count[s] == 0) {
                min[s] = max[s] = avg[s] = UNREACHED;
            } else {
                avg[s] = (int) (total[s] / count[s]);
            }
        }
        return new TransitTimes(min, avg, max);
    }

}
