// Copyright 2023-2025 Phase Five LLC.  For license terms, see LICENSE.txt in the repository root.

package io.pfive.access.importer;

import org.geotools.geometry.jts.ReferencedEnvelope;
import org.geotools.referencing.crs.DefaultGeographicCRS;
import org.locationtech.jts.geom.Envelope;

import static io.pfive.access.sparse.SparseEgressBuilder.DEGREES_LAT_PER_METER;

/// Note that these are serving the same purpose as Envelope or ReferencedEnvelope and GridEnvelope
/// in GridCoverage. Width and height instead of min/max are somewhat easier to validate as long as
/// all values are positive.
public record Wgs84Bounds(double minLon, double minLat, double widthLon, double heightLat) {

    public static int BYTES = Double.BYTES * 4;

    /// Return an approximate minimal bounding box around a circle with the given radius in meters,
    /// centered on the given geographic point.
    public static Wgs84Bounds bufferPoint (double lon, double lat, double radiusMeters) {
        double yRadius = radiusMeters * DEGREES_LAT_PER_METER;
        double xRadius = yRadius * xScale(lat);
        return new Wgs84Bounds(lon - xRadius, lat - yRadius, xRadius * 2, yRadius * 2);
    }

    /// Note that Envelope is the superclass of ReferencedEnvelope so this will work in either role.
    public ReferencedEnvelope toReferencedEnvelope () {
        return new ReferencedEnvelope(minLon, maxLon(), minLat, maxLat(), DefaultGeographicCRS.WGS84);
    }

    public double maxLon () { return minLon + widthLon; }
    public double maxLat () { return minLat + heightLat; }
    public double centerLon () { return minLon + (widthLon / 2.0); }
    public double centerLat () { return minLat + (heightLat / 2.0); }

    public static Wgs84Bounds fromWgsEnvelope (Envelope env) {
        return new Wgs84Bounds(env.getMinX(), env.getMinY(), env.getWidth(), env.getHeight());
    }

    public Wgs84Bounds resizeCentered (double widthLonNew, double heightLatNew) {
        double minLon = centerLon() - (widthLonNew / 2);
        double minLat = centerLat() - (heightLatNew / 2);
        return new Wgs84Bounds(minLon, minLat, widthLonNew, heightLatNew);
    }

    public Wgs84Bounds resizeCenteredMeters (double newWidthMeters, double newHeightMeters) {
        double widthLonNew = newWidthMeters * DEGREES_LAT_PER_METER * xScale();
        double heightLatNew = newHeightMeters * DEGREES_LAT_PER_METER;
        double minLon = centerLon() - (widthLonNew / 2);
        double minLat = centerLat() - (heightLatNew / 2);
        return new Wgs84Bounds(minLon, minLat, widthLonNew, heightLatNew);
    }

    /// Create a new Wgs84Bounds that is a minimal bounding box for this one and the supplied one.
    public Wgs84Bounds encompass (Wgs84Bounds other) {
        double xMin = Math.min(minLon, other.minLon);
        double yMin = Math.min(minLat, other.minLat);
        double xMax = Math.max(maxLon(), other.maxLon());
        double yMax = Math.max(maxLat(), other.maxLat());
        double width = xMax - xMin;
        double height = yMax - yMin;
        return new Wgs84Bounds(xMin, yMin, width, height);
    }

    /// Create a new Wgs84Bounds that is the specified number of meters larger on every side.
    public Wgs84Bounds bufferMeters (double meters) {
        double latExpand = meters * DEGREES_LAT_PER_METER;
        double lonExpand = latExpand * xScale();
        return new Wgs84Bounds(
              minLon - lonExpand, minLat - latExpand,
              widthLon + (lonExpand * 2),
              heightLat + (latExpand * 2)
        );
    }

    /// Return a multiplicative factor for expanding degrees longitude to make them roughly the
    /// same physical length as degrees latitude. This value varies across the height of the
    /// bounding box, so we return the scale factor for the latitude at the center of the bounds.
    public double xScale () {
        return xScale(centerLat());
    }

    public static double xScale (double lat) {
        return 1/Math.cos(Math.toRadians(lat));
    }

    public static class Builder {
        double minLon = Double.POSITIVE_INFINITY;
        double minLat = Double.POSITIVE_INFINITY;
        double maxLon = Double.NEGATIVE_INFINITY;
        double maxLat = Double.NEGATIVE_INFINITY;

        public void includePoint (double lon, double lat) {
            if (lon < minLon) minLon = lon;
            if (lat < minLat) minLat = lat;
            if (lon > maxLon) maxLon = lon;
            if (lat > maxLat) maxLat = lat;
        }

        /// Return a bounding box containing all the supplied points,
        /// or null if no points were supplied.
        public Wgs84Bounds build () {
            if (Double.isInfinite(minLon) || Double.isInfinite(minLat)) return null;
            return new Wgs84Bounds(minLon, minLat, maxLon - minLon, maxLat - minLat);
        }
    }
}
