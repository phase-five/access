// Copyright 2023-2025 Phase Five LLC.  For license terms, see LICENSE.txt in the repository root.

package io.pfive.access.http.handler;

import com.conveyal.gtfs.GTFSFeed;
import com.conveyal.gtfs.model.Pattern;
import com.conveyal.gtfs.model.Route;
import com.conveyal.gtfs.model.Stop;
import com.conveyal.gtfs.model.StopTime;
import com.conveyal.gtfs.model.Trip;
import com.conveyal.r5.api.util.TransitModes;
import com.conveyal.r5.transit.TransitLayer;
import io.pfive.access.authentication.UserIdentity;
import io.pfive.access.http.model.ApiModel.GtfsPatternDetail;
import io.pfive.access.http.model.ApiModel.GtfsPatternSummary;
import io.pfive.access.http.model.ApiModel.GtfsRouteSummary;
import io.pfive.access.store.FileWithMetadata;
import io.pfive.access.store.JsonStore;
import io.pfive.access.util.GtfsUtil;
import io.pfive.access.util.JettyUtil;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IntSummaryStatistics;
import java.util.List;
import java.util.Locale;

/// Return details from within a GTFS MapDB, such as routes or patterns on a route.
public class GtfsHandler extends Handler.Abstract {

    private static final String USAGE =  "/gtfs/feedId/routes or /gtfs/feedId/patterns/routeId";

    @Override
    public boolean handle (Request request, Response response, Callback callback) throws Exception {
        UserIdentity user = JettyUtil.extractUserIdentity(request);
        String subPath = Request.getPathInContext(request);
        String[] parts = subPath.split("/");
        if (!(parts.length == 3 || parts.length == 4)) {
            return JettyUtil.respondClientError(USAGE, response, callback);
        }
        String gtfsId = parts[1];
        String entityType = parts[2];
        FileWithMetadata gtfsFileWithMetadata = JsonStore.retrieveFile(gtfsId, user);
        File gtfsDbFile = new File(gtfsFileWithMetadata.path().toString() + ".db");
        try (GTFSFeed feed = GTFSFeed.reopenReadOnly(gtfsDbFile)) {
            if (entityType.equals("routes")) {
                List<GtfsRouteSummary> routeSummaries = allRoutes(feed);
                // Note that records have hidden fields with accessor methods.
                routeSummaries.sort(Comparator.comparing(GtfsRouteSummary::name));
                return JettyUtil.respondJson(routeSummaries, response, callback);
            } else if (entityType.equals("patterns")) {
                String routeId = parts[3];
                List<GtfsPatternSummary> patternSummaries = patternsForRoute(feed, routeId);
                patternSummaries.sort(Comparator.comparingInt(GtfsPatternSummary::nTrips)
                        .thenComparingInt(GtfsPatternSummary::nStops).reversed());
                return JettyUtil.respondJson(patternSummaries, response, callback);
            } else if (entityType.equals("patternDetail")) {
                String exemplarTripId = parts[3];
                GtfsPatternDetail patternDetail = onePatternDetail(feed, exemplarTripId);
                return JettyUtil.respondJson(patternDetail, response, callback);
            } else {
                return JettyUtil.respondClientError("Did not recognize: " + entityType, response, callback);
            }
        }
    }

    private static List<GtfsPatternSummary> patternsForRoute (GTFSFeed feed, String routeId) {
        List<GtfsPatternSummary> ret = new ArrayList<>();
        for (Pattern pattern : feed.patterns.values()) {
            if (!pattern.route_id.equals(routeId)) {
                continue;
            }
            if (pattern.associatedTrips.isEmpty()) {
                continue;
            }
            String patternName = String.format(
                    "%d trips / %d stops / %s",
                    pattern.associatedTrips.size(),
                    pattern.orderedStops.size(),
                    getDirectionString(feed, pattern)
            );
            String exemplarTripId = pattern.associatedTrips.getFirst();
            GtfsPatternSummary patternSummary = new GtfsPatternSummary(
                    patternName,
                    exemplarTripId,
                    pattern.associatedTrips.size(),
                    pattern.orderedStops.size()
            );
            ret.add(patternSummary);
        }
        return ret;
    }

    /// Patterns have internal IDs but we always refer to them by the ID of any trip on the pattern.
    /// For now don't reveal the gtfs-lib pattern IDs.
    private static GtfsPatternDetail onePatternDetail (GTFSFeed feed, String exemplarTripId) {
        String patternId = feed.patternForTrip.get(exemplarTripId);
        Pattern pattern = feed.patterns.get(patternId);
        List<Stop> patternStops = new ArrayList<>();
        for (String stopId : pattern.orderedStops) {
            Stop stop = feed.stops.get(stopId);
            patternStops.add(stop);
        }
        IntSummaryStatistics tripDurationStatsSeconds = new IntSummaryStatistics();
        int[] hourBinnedDepartures = new int[24];
        // NOTE this part iterating over stop times is a little slow, maybe do in a per-pattern fetch.
        // It's not that slow on a per-route basis though, only when you fetch all patterns in the feed.
        for (String tripId : pattern.associatedTrips) {
            int tripDurationSeconds = GtfsUtil.tripDurationSeconds(feed, tripId);
            tripDurationStatsSeconds.accept(tripDurationSeconds);
            for (StopTime st : feed.getOrderedStopTimesForTrip(tripId)) {
                int hour = (st.departure_time / 60 / 60) % 24;
                hourBinnedDepartures[hour] += 1;
            }
        }
        return GtfsPatternDetail.fromStats(patternStops, tripDurationStatsSeconds, hourBinnedDepartures);
    }

    /// TODO conditionally include mode, as well as agency when there are multiple agencies
    /// TODO group by mode (sub-lists of options, mode in summary object) rather than adding mode to name
    private static List<GtfsRouteSummary> allRoutes (GTFSFeed feed) {
        List<GtfsRouteSummary> ret = new ArrayList<>(feed.routes.size());
        for (Route route : feed.routes.values()) {
            StringBuilder nameBuilder = new StringBuilder();
            if (route.route_short_name != null) {
                nameBuilder.append(route.route_short_name);
            }
            if (route.route_long_name != null) {
                if (!nameBuilder.isEmpty()) {
                    nameBuilder.append(" ");
                }
                nameBuilder.append(route.route_long_name);
            }
            nameBuilder.append(" (");
            // nameBuilder.append(route.route_id);
            TransitModes tmode = TransitLayer.getTransitModes(route.route_type);
            String modeString = tmode.toString().toLowerCase(Locale.ROOT);
            nameBuilder.append(modeString);
            nameBuilder.append(")");
            GtfsRouteSummary routeSummary = new GtfsRouteSummary(route.route_id, nameBuilder.toString(), modeString);
            ret.add(routeSummary);
        }
        return ret;
    }

    /// Return a string representing a direction of travel on a pattern.
    /// Falls back on the final stop name if the examined trips have no headsign information.
    private static String getDirectionString (GTFSFeed feed, Pattern pattern) {
        String exemplarTripId = pattern.associatedTrips.getFirst();
        Trip exemplarTrip = feed.trips.get(exemplarTripId);
        String headsign = exemplarTrip.trip_headsign;
        if (headsign != null && !headsign.isBlank()) {
            return "headsign " + headsign;
        }
        String finalStopId = pattern.orderedStops.getLast();
        Stop finalStop = feed.stops.get(finalStopId);
        return "to " + finalStop.stop_name;
    }
}
