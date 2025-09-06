// Copyright 2023-2025 Phase Five LLC.  For license terms, see LICENSE.txt in the repository root.

package io.pfive.access.http.model;

import com.conveyal.gtfs.model.Stop;

import java.io.File;
import java.util.ArrayList;
import java.util.IntSummaryStatistics;
import java.util.List;

/// Group together simple HTTP API response types used only for structuring JSON responsees.
public abstract class ApiModel {

    public record FileList(List<OneFile> files) {
        public static FileList fromFiles (Iterable<File> files) {
            List<OneFile> apiFiles = new ArrayList<>();
            for (File file : files) {
                apiFiles.add(OneFile.fromFile(file));
            }
            return new FileList(apiFiles);
        }
    }

    public record OneFile(String filename) {
        public static OneFile fromFile (File file) {
            return new OneFile(file.getName());
        }
    }

    /// Cursory summary of a GTFS route.
    public record GtfsRouteSummary (String id, String name, String mode) { }

    /// This provides more details about a pattern than GtfsPatternSummary.
    /// TODO Min, max, avg headway. Within and across patterns. Within and across hours. But
    ///   filtering by service dates. Also, process all patterns on route to find segments and
    ///   assign each of them a headway, click on dates to see breakdown for that day. Maybe one
    ///   weekday and one weekend with the most trips active.
    public record GtfsPatternDetail (
            List<GtfsPatternStop> stops,
            IntMinMaxAvg tripDurationSeconds,
            int[] departureTimeCountsByHour
    ) {
        public static GtfsPatternDetail fromStats (
                List<Stop> stops,
                IntSummaryStatistics tripDurationStatsSeconds,
                int[] departureTimeCountsByHour
        ) {
            List<GtfsPatternStop> gtfsPatternStops = new ArrayList<>();
            for (Stop stop : stops) {
                gtfsPatternStops.add(GtfsPatternStop.fromStop(stop));
            }
            IntMinMaxAvg tripDurationSeconds = IntMinMaxAvg.fromStats(tripDurationStatsSeconds);
            return new GtfsPatternDetail(gtfsPatternStops, tripDurationSeconds, departureTimeCountsByHour);
        }
    }

    /// Represents a stop within a pattern in an HTTP API response.
    public record GtfsPatternStop (String id, String name, double lon, double lat) {
        public static GtfsPatternStop fromStop (Stop stop) {
            return new GtfsPatternStop(stop.stop_id, stop.stop_name, stop.stop_lon, stop.stop_lat);
        }
    }

    /// This represents a pattern (collection of trips with the same stop sequence and route) taken
    /// from a GTFSFeed. It should also be resolvable to a unique TripPattern within an R5
    /// TransitLayer using that GTFS feed.
    /// FIXME what happens to IDs when we load multiple GTFS feeds into a TransitLayer?
    public record GtfsPatternSummary (String name, String exemplarTripId, int nTrips, int nStops) { }

    public record IntMinMaxAvg (int min, int max, int avg) {
        public static IntMinMaxAvg fromStats (IntSummaryStatistics stats) {
            return new IntMinMaxAvg(stats.getMin(), stats.getMax(), (int) Math.round(stats.getAverage()));
        }
    }

}
