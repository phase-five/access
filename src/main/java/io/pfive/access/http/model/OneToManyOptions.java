// Copyright 2023-2025 Phase Five LLC.  For license terms, see LICENSE.txt in the repository root.

package io.pfive.access.http.model;

import com.conveyal.r5.analyst.cluster.AnalysisWorkerTask;
import com.conveyal.r5.analyst.cluster.RegionalTask;
import com.conveyal.r5.analyst.cluster.TravelTimeSurfaceTask;
import com.conveyal.r5.analyst.decay.StepDecayFunction;
import com.conveyal.r5.api.util.LegMode;
import com.conveyal.r5.api.util.TransitModes;
import io.pfive.access.http.exception.InvalidRequestException;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.util.Fields;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.EnumSet;

import static io.pfive.access.sparse.SparseOneToManyProcessor.STANDARD_CUTOFFS;
import static io.pfive.access.sparse.SparseOneToManyProcessor.STANDARD_PERCENTILES;
import static io.pfive.access.util.JettyUtil.enumSetFromCommaSeparatedString;

/// The basic options for a one-to-many routing request intended to provide a travel time profile
/// for use in access to opportunities analysis. Some subset of these options can also be applied to
/// each element of a many-to-many batch, so could be factored out into a shared options.
/// These options should be primitive types or short Strings such as IDs, rather than references to
/// larger nested objects. This is because these options may be used as a key in maps and caches,
/// and may be logged or stored.
/// TODO clarify if this is linked to one particular network (and can therefore include scenarios
///   etc.) or if it's meant to be reused across different networks. Maybe separate routing options
///   from analysis configuration that includes network, scenario, etc.
public class OneToManyOptions implements Serializable {

    public final double lat;
    public final double lon;
    public final String networkId; // redundant (network can be derived from egress) - at least validate consistency
    public final String egressId;
    public final LocalDate date;
    public final LocalTime time;
    public final int window; // departure time window length in minutes
    public final int duration; // max travel duration in minutes
    public final EnumSet<TransitModes> transitModes;
    public final EnumSet<LegMode> streetModes;
    public final String destinationId; // may be gridded or non-gridded
    public final boolean batch; // a little odd on a class whose name implies "one origin only"
    public final String originId;
    public final int originFilter;

    /// Constructor that is reusable for both single-origin and many-to-many batch requests.
    public OneToManyOptions (Request request) {
        if (!request.getMethod().equals("GET")) {
            throw new InvalidRequestException("Method must be GET.");
        }
        Fields fields = Request.extractQueryParameters(request);
        try {
            this.lat = Double.parseDouble(fields.get("lat").getValue());
            this.lon = Double.parseDouble(fields.get("lon").getValue());
        } catch (NumberFormatException e) {
            throw new InvalidRequestException("Cannot parse origin coordinates.");
        }
        this.networkId = fields.get("network").getValue();
        this.egressId = fields.get("egress").getValue();
        this.streetModes = enumSetFromCommaSeparatedString(fields.get("streetModes").getValue(), LegMode.class);
        this.transitModes = enumSetFromCommaSeparatedString(fields.get("transitModes").getValue(), TransitModes.class);
        this.date = LocalDate.parse(fields.get("date").getValue());
        this.time = LocalTime.parse(fields.get("time").getValue());
        this.window = fields.get("window").getValueAsInt();
        this.duration = fields.get("duration").getValueAsInt();
        this.destinationId = fields.get("destinationId").getValue();
        // Stopgap approach to launching batch analysis.
        this.batch = Boolean.parseBoolean(fields.get("launchBatch").getValue());
        // TODO set origin set and origin filtering from request.
        this.originId = this.batch ? fields.get("originId").getValue() : null;
        this.originFilter = this.batch ? fields.get("originFilter").getValueAsInt() : -1;
    }

    /// Initialize an R5 TravelTimeSurfaceTask (single-origin) from this native OneToManyOptions.
    /// Note: R5 expects single origin analyses to be used for rendering rectangular images, and
    /// therefore checks TravelTimeSurfaceTask.nTargetsPerOrigin which is locked to the dimensions
    /// of the image grid. Calculating accessibility from freeform points in addition to or instead
    /// of a grid is not supported. New routing code needs to support propagating through multiple
    /// linkages for multiple destination set shapes.
    public TravelTimeSurfaceTask toTravelTimeSurfaceTask () {
        TravelTimeSurfaceTask task = new TravelTimeSurfaceTask();
        task.graphId = this.networkId;
        task.scenario = null;
        task.date = this.date;
        task.fromTime = this.time.toSecondOfDay();
        // NOTE the time window strongly affects the propagation speed and search speed
        task.toTime = task.fromTime + (this.window * 60);
        task.directModes = this.streetModes;
        task.accessModes = this.streetModes;
        task.egressModes = this.streetModes;
        task.transitModes = this.transitModes;
        task.percentiles = STANDARD_PERCENTILES;
        // Single origin tasks should have a prepared decay function and exactly 121 cutoffs.
        // Outside the adapter approach, tasks are no longer validated so be careful.
        // TODO Confirm whether this has any effect or serves only to pass validation in R5.
        task.cutoffsMinutes = new int[121];
        for (int i = 0; i < task.cutoffsMinutes.length; i++) {
            task.cutoffsMinutes[i] = i;
        }
        task.maxTripDurationMinutes = this.duration;
        // Enable temporal density and travel times only for single-origin requests. Temporal
        // density result allows displaying a 2D histogram of when opportunities are reached.
        // This is essentially the derivative of cumulative opportunities.
        task.includeTemporalDensity = true;
        task.dualAccessThresholds = new int[0];
        task.includePathResults = false;
        // These are particularly relevant for single-origin requests.
        task.fromLat = this.lat;
        task.fromLon = this.lon;
        // Add and prepare decay function after all other parameters are set.
        task.decayFunction = new StepDecayFunction();
        task.decayFunction.prepare();
        return task;
    }

}
