// Copyright 2023-2025 Phase Five LLC.  For license terms, see LICENSE.txt in the repository root.

package io.pfive.access.importer;

import com.conveyal.r5.analyst.progress.NoopProgressListener;
import com.conveyal.r5.analyst.progress.ProgressListener;
import com.google.common.collect.Sets;
import io.pfive.access.authentication.UserIdentity;
import org.geotools.data.DataStore;
import org.geotools.data.DataStoreFinder;
import org.geotools.data.simple.SimpleFeatureSource;
import org.geotools.feature.FeatureIterator;
import org.geotools.geometry.jts.JTS;
import org.geotools.referencing.CRS;
import org.geotools.referencing.crs.DefaultGeographicCRS;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygonal;
import org.locationtech.jts.geom.Puntal;
import org.opengis.feature.simple.SimpleFeature;
import org.opengis.feature.type.FeatureType;
import org.opengis.feature.type.PropertyDescriptor;
import org.opengis.referencing.crs.CoordinateReferenceSystem;
import org.opengis.referencing.operation.MathTransform;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

/// Base class with shared functionality for point and raster import from GeoPackage GIS files.
/// This could in principle be generalized to all GeoTools supported formats but we've decided to
/// support only GeoPackage.
/// Documentation here https://docs.geotools.org/latest/userguide/library/data/geopackage.html
/// says that low-level functionality is provided by the GeoPackage class, with high level wrappers
/// (?) using the GeoPackage JDBC DataStore (vector) or GeoPackageReader (raster).
/// The DataStoreFinder seems to hit the GeoPkgDataStoreFactory which extends JDBCDataStoreFactory
/// and seems to use a an SQLite JDBC driver.
public abstract class GeoPackageImporter {

    protected final Wgs84Bounds wgsBounds;
    protected ProgressListener progress = new NoopProgressListener();
    protected MathTransform sourceToWgsTransform;
    protected MathTransform wgsToSourceTransform;

    public GeoPackageImporter (Wgs84Bounds wgsBounds) {
        this.wgsBounds = wgsBounds;
    }

    public void setProgressListener (ProgressListener progress) {
        this.progress = progress;
    }

    /// Open a GeoPackage for use in GeoTools, using the DataStore API as suggested by the GeoTools
    /// docs. This enterprisey "service discovery" using stringly-typed maps is ugly, but it's the
    /// GeoTools way. This throws IOException rather than handling it, because it's generally a
    /// first step in some other process that also throws IOExceptions.
    public static DataStore openGeoPackageDataStore (Path geoPackageFile) throws Exception {
        Map<String, Object> params = Map.of(
              "dbtype", "geopkg",
              "database", geoPackageFile.toString(),
              "read_only", "TRUE"
              // "memory map size", "20" // for SQLite in MB
        );
        return DataStoreFinder.getDataStore(params);
    }

    /// Opens the GeoPackage file as a GeoTools DataStore, selects one layer out of that file
    /// as a GeoTools FeatureSource, and runs a data import processing operation on the features
    /// in that one layer. TODO allow rasterizing many layers at once, like LODES data?
    public void importFeatures (Path geoPackageFile, String layer, String attribute) {
        DataStore dataStore = null;
        try {
            dataStore = openGeoPackageDataStore(geoPackageFile);
            SimpleFeatureSource featureSource = dataStore.getFeatureSource(layer);
            importFeatureSource(featureSource, attribute);
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            if (dataStore != null) dataStore.dispose();
        }
    }

    /// This method will be called before a layer is imported, allowing the implementation class to
    /// prepare a GeometryCoordinateSequenceTransformer that will be reused for the entire operation.
    /// Coordinate transforms could in principle be prepared in the constructor, but that implies we
    /// will only import one layer, or that every layer is known to have the same projection.
    /// Do not use JTS.transform() in a tight loop. Create and reuse one transformer instance that
    /// persists through the whole import process. Every time JTS.transform() is called it creates a
    /// new GeometryCoordinateSequenceTransformer with a new DefaultCoordinateSequenceTransformer.
    protected abstract void prepareCoordinateTransform ();

    /// Concrete subclasses implement this method to provide logic that imports one feature.
    /// The implementation must perform any necessary coordinate transforms. They are not performed
    /// by the shared logic that calls the method.
    protected abstract void importOneFeature (Geometry sourceGeometry, double count) throws Exception;

    /// Concrete subclasses implement this method to save the imported data in one or more files.
    /// Ideally this would return a collection of newly created files and the caller would put them into
    /// storage, such that the importers don't need to know anything about user identity, file storage
    /// components, file IDs, or security. But for the time being the importer implementations need to
    /// handle those details themselves.
    public abstract void storeResults (String fileId, UserIdentity userIdentity);

    /// Performs requested processing on a single featureSource representing the contents of a
    /// single layer drawn from the input data.
    private void importFeatureSource (SimpleFeatureSource featureSource, String attribute) throws Exception {
        FeatureType featureType = featureSource.getSchema();
        CoordinateReferenceSystem srcCrs = featureType.getCoordinateReferenceSystem();
        sourceToWgsTransform = CRS.findMathTransform(srcCrs, DefaultGeographicCRS.WGS84, false);
        wgsToSourceTransform = sourceToWgsTransform.inverse();

        // Allows rejecting features that can't intersect any cells before even projecting them.
        Envelope sourceCrsClipArea = JTS.transform(wgsBounds.toReferencedEnvelope(), wgsToSourceTransform);

        // Do not retain the attribute descriptor, we're just validating that it exists and is numeric.
        PropertyDescriptor attributeDescriptor = featureType.getDescriptor(attribute);
        if (attributeDescriptor == null) {
            throw new RuntimeException("Attribute " + attribute + " not present.");
        }
        if (!Number.class.isAssignableFrom(attributeDescriptor.getType().getBinding())) {
            throw new RuntimeException("Attribute " + attribute + " is non-numeric.");
        }

        long startTime = System.currentTimeMillis();
        int nFeatures = featureSource.getFeatures().size();
        progress.beginTask("Importing features from GeoPackage", nFeatures);

        // Track suspicious characteristics and report them after import.
        Set<Class<?>> featureTypes = Sets.newHashSet();
        int nNullGeometries = 0;

        // Prepare coordinate projection for reuse throughout the import process.
        prepareCoordinateTransform();

        // Geotools FeatureIterator does not implement Iterator<Feature>.
        // This is probably because it's Closeable (extending AutoCloseable) for use in try-with-resources.
        // Only SimpleFeatures have a defaultGeometry, the more general Feature does not.
        try (FeatureIterator<SimpleFeature> fi = featureSource.getFeatures().features()) {
            while (fi.hasNext()) {
                SimpleFeature feature = fi.next();
                progress.increment();
                Geometry geom = (Geometry) feature.getDefaultGeometry();
                if (geom == null) {
                    nNullGeometries += 1;
                    continue;
                }
                featureTypes.add(geom.getClass());
                // Currently only supports Polygonal and Point.
                // TODO handle Multipoint (Puntal).
                if (!(geom instanceof Point || geom instanceof Polygonal)) {
                    System.out.println("Cannot rasterize geometry type: " + geom.getClass());
                    continue;
                }
                if (geom.getEnvelopeInternal().disjoint(sourceCrsClipArea)) {
                    continue;
                }
                // Cast to Number because Integer (boxed) can't be cast to Double.
                final double count = ((Number)feature.getAttribute(attribute)).doubleValue();

                // Avoid invalid geometries by zero-width buffering every geometry.
                // Anecdotally this does not significantly impact run time.
                // Buffering a Point (even by 0) yields a Polygon, so only buffer non-Points.
                if (!(geom instanceof Puntal)) {
                    geom = geom.buffer(0);
                }
                // Call the feature import code for the concrete importer implementation.
                importOneFeature(geom, count);
            }
        }
        // TODO Background error reporting, task cancellation, and timing via progress listener.
        if (nNullGeometries > 0) {
            System.out.println("Number of null geometries: " + nNullGeometries);
        }
        if (featureTypes.size() > 1) {
            System.out.println("More than one feature type present: " + featureTypes);
        }
        System.out.println("Elapsed time: " + (System.currentTimeMillis() - startTime) / 1000.0 + " seconds");
    }

}
