// Copyright 2023-2025 Phase Five LLC.  For license terms, see LICENSE.txt in the repository root.

package io.pfive.access.util;

import com.conveyal.gtfs.GTFSFeed;
import com.conveyal.gtfs.model.StopTime;
import org.mapdb.Fun;

/// Utility functions and classes for working with gtfs-lib.
public abstract class GtfsUtil {

    public static int tripDurationSeconds (GTFSFeed feed, String tripId) {
        Fun.Tuple2 lowKey = new Fun.Tuple2(tripId, null);
        Fun.Tuple2 highKey = new Fun.Tuple2(tripId, Fun.HI);
        StopTime first = feed.stop_times.ceilingEntry(lowKey).getValue();
        StopTime last = feed.stop_times.floorEntry(highKey).getValue();
        int durationSeconds = last.arrival_time - first.departure_time;
        return durationSeconds;
    }



}
