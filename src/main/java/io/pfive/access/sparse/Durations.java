// Copyright 2023-2025 Phase Five LLC.  For license terms, see LICENSE.txt in the repository root.

package io.pfive.access.sparse;

import java.util.Arrays;

import static com.conveyal.r5.profile.FastRaptorWorker.UNREACHED;
import static io.pfive.access.sparse.EgressTimes.MAX_SEC;

/// Durations (in seconds) of travel to reach a specific ordered sequence of geographic points.
/// Holding references to related instances such as GeoPoints may be problematic for serialization
/// and caching. This can hold separate mix/avg/max values, but for street search results we just
/// store three references to the same array of walk or bike times. That approach should work as
/// expected for min-merging with transit duration tiles.
public class Durations extends MinAvgMax {

    public Durations (int[] min, int[] avg, int[] max) {
        super(min, avg, max);
    }

    public static Durations unreached (int n) {
        return new Durations(unreachedArray(n), unreachedArray(n), unreachedArray(n));
    }

    /// For visual demonstration of tile-based rendering. Initializing values to zero instead of
    /// UNREACHED will make areas of unrendered tiles obvious in PNG output.
    public static Durations allZero (int n) {
        return new Durations(new int[n], new int[n], new int[n]);
    }

    private static int[] unreachedArray (int n) {
        int[] durations = new int[n];
        Arrays.fill(durations, UNREACHED);
        return durations;
    }

    public boolean anyReached () {
        // We only need to check the minimum durations. If one is set, we can immediately return
        // true. If none of them are set, then the averages and maxima must also not be set.
        for (int duration : min) if (duration < MAX_SEC) return true;
        return false;
    }

    /// Inefficient stopgap until MAX_SEC is configurable. Also treats values as mutable.
    public void imposeMaxMinutes (int minutes) {
        int seconds = minutes * 60;
        imposeMaxSeconds(min, seconds);
        imposeMaxSeconds(avg, seconds);
        imposeMaxSeconds(max, seconds);
    }

    private static void imposeMaxSeconds (int[] times, int maxSeconds) {
        if (times != null) {
            for (int i = 0; i < times.length; i++) {
                if (times[i] > maxSeconds) times[i] = UNREACHED;
            }
        }
    }

    public static Durations mergeMin (Durations a, Durations b) {
        if (a == null) return b;
        if (b == null) return a;
        return new Durations(mergeMin(a.min, b.min), mergeMin(a.avg, b.avg), mergeMin(a.max, b.max));
    }

    private static int[] mergeMin (int[] a, int[] b) {
        if (a == null) return b;
        if (b == null) return a;
        int[] c = new int[a.length];
        for (int i = 0; i < c.length; i++) {
            c[i] = Math.min(a[i], b[i]);
        }
        return c;
    }
}
