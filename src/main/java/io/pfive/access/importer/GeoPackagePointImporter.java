// Copyright 2023-2025 Phase Five LLC.  For license terms, see LICENSE.txt in the repository root.

package io.pfive.access.importer;

import com.conveyal.r5.analyst.FreeFormPointSet;
import com.esotericsoftware.minlog.Log;
import gnu.trove.list.array.TDoubleArrayList;
import io.pfive.access.authentication.UserIdentity;
import io.pfive.access.store.JsonStore;
import org.geotools.geometry.jts.GeometryCoordinateSequenceTransformer;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.Point;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.zip.GZIPOutputStream;

import static io.pfive.access.util.MortonCode.mortonCodeForCoordinate;

/// Loads points (or polygon centroids) from a GeoPackage into distinct points rather than a raster.
/// These are stored as an R5 FreeformPointset.
/// TODO Spatial sort (Hilbert curve, Morton code, or Z-ordering). QGIS doesn't seem to support this.
public class GeoPackagePointImporter extends GeoPackageImporter {

    // Details of the points will be stored in these parallel arrays/lists.
    // They accumulate in memory before being passed to pre-existing file writing methods.
    private final List<Coordinate> coordinates = new ArrayList<>();
    private final TDoubleArrayList counts = new TDoubleArrayList();

    /// Create and reuse one transformer instance that persists through the whole import process.
    private GeometryCoordinateSequenceTransformer sourceToWgsTransformer;

    public GeoPackagePointImporter (Wgs84Bounds wgsBounds) {
        super(wgsBounds);
    }

    @Override
    protected void prepareCoordinateTransform () {
        sourceToWgsTransformer = new GeometryCoordinateSequenceTransformer();
        sourceToWgsTransformer.setMathTransform(sourceToWgsTransform);
    }

    @Override
    protected void importOneFeature (Geometry srcGeometry, double count) throws Exception {
        // Take centroid before projecting, avoiding projecting every point in every geometry.
        Point srcCentroid = srcGeometry.getCentroid();
        Point wgsCentroid = sourceToWgsTransformer.transformPoint(srcCentroid, srcCentroid.getFactory());
        coordinates.add(wgsCentroid.getCoordinate());
        counts.add(count);
    }

    @Override
    public void storeResults (String fileId, UserIdentity userIdentity) {
        spatialSort();
        // TODO import to new tiled format.
        // Possible goals: represent floats instead of just ints. Compressed serialization.
        File tempFile = JsonStore.makeTempFile(fileId);
        JsonStore.storeFile(tempFile.toPath(), fileId, userIdentity);
        Log.warn("Saving non-gridded points not yet implemented.");
    }

    private static class SortItem implements Comparable<SortItem> {
        Coordinate coordinate;
        long mortonCode;
        double count;
        public SortItem (Coordinate coordinate, double count) {
            this.coordinate = coordinate;
            this.mortonCode = mortonCodeForCoordinate(coordinate);
            this.count = count;
        }
        @Override
        public int compareTo (SortItem that) {
            return Long.compare(this.mortonCode, that.mortonCode);
        }
    }

    /// Propagation of transit travel times can make up 90 percent or more of run time, and spatial
    /// locality of the points makes a big difference, as neighboring destination points will access
    /// neighboring transit stops during propagation. This current implementation performs an extra
    /// copy of all the points that is not strictly necessary, but as this technique is new it will
    /// not be optimized yet. In fact it would probably make sense to Morton-sort the transit stops
    /// themselves in the network model so their array indexes reflect spatial proximity.
    private void spatialSort () {
        List<SortItem> items = new ArrayList<>(coordinates.size());
        for (int i = 0; i < coordinates.size(); i++) {
            items.add(new SortItem(coordinates.get(i), counts.get(i)));
        }
        Collections.sort(items);
        coordinates.clear();
        counts.clear();
        for (SortItem item : items) {
            coordinates.add(item.coordinate);
            counts.add(item.count);
        }
    }

}
