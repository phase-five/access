// Copyright 2023-2025 Phase Five LLC.  For license terms, see LICENSE.txt in the repository root.

package io.pfive.access.sparse;

public class AccessBins extends MinAvgMax {
    public AccessBins (int[] min, int[] avg, int[] max) {
        super(min, avg, max);
    }

    /// Create an instance initialized with all zeroes, as a target for additive accumulation.
    public static AccessBins zero () {
        return new AccessBins(new int[120], new int[120], new int[120]);
    }

    /// Return the raw opportunity density numbers in the array format consumed by the client.
    /// This is a 2D array and the client currently expects 3D (axis zero being destination set).
    public int[][] density () {
        return new int[][] {min, avg, max};
    }

    /// Return  cumulative access to opportunities in the array format consumed by the client.
    /// This is a 2D array and the client currently expects 3D (axis zero being destination set).
    /// This is returned separately from the density to eventually allow applying decay functions.
    public int[][] cumulative () {
        return new int[][] {cumulative(min), cumulative(avg), cumulative(max)};
    }

    /// Return dual access (time in minutes to nearest N opportunities).
    public int[][] dual () {
        return new int[][] {dual(min), dual(avg), dual(max)};
    }

    /// Compute cumulative access to opportunities from per-minute densities.
    /// This is returned separately from the density to eventually allow applying decay functions.
    private static int[] cumulative (int[] source) {
        int[] ret = new int[120];
        int sum = 0;
        for (int i = 0; i < 120; i++) {
            sum += source[i];
            ret[i] = sum;
        }
        return ret;
    }

    /// Return an array of travel times in minutes to reach at least N opportunities, where the
    /// array index plus one is the number of opportunities.
    private static int[] dual (int[] source) {
        final int N_MAX = 6;
        int[] ret = new int[N_MAX];
        int minute = 0;
        int sum = 0;
        for (int n = 0; n < N_MAX; n++) {
            while (sum <= n && minute < 120) {
                sum += source[minute];
                minute += 1;
            }
            ret[n] = (sum > n) ? minute : -1;
        }
        return ret;
    }

}
