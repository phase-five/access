// Copyright 2023-2025 Phase Five LLC.  For license terms, see LICENSE.txt in the repository root.

package io.pfive.access.util;

import com.conveyal.gtfs.model.Stop;
import org.locationtech.jts.geom.Coordinate;

/// Utility class for sorting points for geographic proximity using space-filling curves.
/// Adapted from Apache Lucene MortonEncoder, GeoEncodingUtils, and BitUtil (under Apache license).
/// Adapted from: http://graphics.stanford.edu/~seander/bithacks.html#InterleaveBMN
public class MortonCode {

    // Magic numbers for interleaving bits.
    private static final long[] MAGIC = {
          0x5555555555555555L, 0x3333333333333333L, 0x0F0F0F0F0F0F0F0FL, 0x00FF00FF00FF00FFL,
          0x0000FFFF0000FFFFL, 0x00000000FFFFFFFFL, 0xAAAAAAAAAAAAAAAAL
    };

    // Bit shift values for interleaving bits.
    private static final short[] SHIFT = {1, 2, 4, 8, 16};

    /// Interleaves the bits of two 32-bit ints producing a 64-bit long.
    private static long interleave(int a, int b) {
        return (expandBits(a) << 1) | expandBits(b);
    }

    /// Expand an int to a long by inserting zero bits in between each bit of the input.
    private static long expandBits (int x) {
        long v = 0x00000000FFFFFFFFL & x;
        v = (v | (v << SHIFT[4])) & MAGIC[4];
        v = (v | (v << SHIFT[3])) & MAGIC[3];
        v = (v | (v << SHIFT[2])) & MAGIC[2];
        v = (v | (v << SHIFT[1])) & MAGIC[1];
        v = (v | (v << SHIFT[0])) & MAGIC[0];
        return v;
    }

    public static long mortonCodeForLonLat (double lon, double lat) {
        // Crude projection to integers. Assume mid-latitudes: lon degrees are physically shorter.
        int x = (int)(lon * 60_000);
        int y = (int)(lat * 100_000);
        return interleave(x, y);
    }

    public static long mortonCodeForStop (Stop s) {
        return mortonCodeForLonLat(s.stop_lon, s.stop_lat);
    }

    public static long mortonCodeForCoordinate (Coordinate c) {
        return mortonCodeForLonLat(c.x, c.y);
    }

}
