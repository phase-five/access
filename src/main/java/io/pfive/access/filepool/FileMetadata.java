// Copyright 2023-2025 Phase Five LLC.  For license terms, see LICENSE.txt in the repository root.

package io.pfive.access.filepool;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;
import io.pfive.access.importer.Wgs84Bounds;
import io.pfive.access.store.FileWithMetadata;
import org.geotools.data.DataStore;
import org.geotools.data.FeatureSource;
import org.geotools.data.Query;
import org.geotools.data.simple.SimpleFeatureSource;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.MultiLineString;
import org.locationtech.jts.geom.MultiPoint;
import org.locationtech.jts.geom.MultiPolygon;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;
import org.opengis.feature.type.FeatureType;
import org.opengis.feature.type.GeometryType;
import org.opengis.feature.type.PropertyDescriptor;

import java.util.ArrayList;
import java.util.List;

/// Metadata about a single file in the store. These may be uploaded, dervived from uploaded files,
/// or the result of an analysis.
public class FileMetadata {

    public String fileId;
    public String originalName;
    public String name;
    public String description;
    public FileType fileType;

    /// If layers is not null or empty, the file contains one or more geospatial layers.
    public List<Layer> layers;

    /// Optional bounding box. For example, provides the geographic area for displaying GeoPNG files.
    public Wgs84Bounds wgsBounds;

    /// Optional collection of files this one was built from. This could be made mandatory on a
    /// metadata subclass for derived files (as opposed to freshly uploaded ones).
    public Multimap<FileType, String> sources;

    /// No-arg constructor to allow Jackson deserialization.
    private FileMetadata () { }

    public FileMetadata (String fileId, FileType fileType) {
        this.fileId = fileId;
        this.fileType = fileType;
    }

    /// Add a record to indicate that another file was used as an input to create the file this
    /// metadata describes. This is a base method that is wrapped by several utility methods below.
    /// This base method and the utility methods are not threadsafe: they should be called in a
    /// single thread initializing a new FileMetadata before it's shared.
    public void addSource (FileType fileType, String fileId) {
        if (sources == null) {
            sources = ArrayListMultimap.create();
        }
        sources.put(fileType, fileId);
    }

    public void addSource (FileMetadata fileMetadata) {
        addSource(fileMetadata.fileType, fileMetadata.fileId);
    }

    public void addSources (Iterable<FileWithMetadata> filesWithMetadata) {
        for (FileWithMetadata fileWithMetadata : filesWithMetadata) {
            addSource(fileWithMetadata.fileMetadata());
        }
    }

    public void addSourcesFromMetadata (Iterable<FileMetadata> fileMetadataIterable) {
        for (FileMetadata fileMetadata : fileMetadataIterable) {
            addSource(fileMetadata);
        }
    }

    public boolean hasSource (FileType fileType, String id) {
        if (sources == null) return false;
        // If Multimap is present, result of get() is never null, but may be empty.
        return sources.get(fileType).contains(id);
    }

    /// In our metadata, a Layer is a set of GIS features with the same geometry type, CRS, and
    /// properties or attributes. If we accept only GeoPackage inputs, we can align with the
    /// terminology used by the GeoPackage standard.
    public static class Layer {
        public String name;
        public LayerType layerType; // Should this be renamed to GeomType?
        public String crs;
        public int nFeatures;
        public List<Property> properties;

        /// Create Layer metadata entries for all FeatureSources in the supplied GeoTools/OpenGIS
        /// DataStore. GeoTools/OpenGIS has one FeatureSource per "feature type name" in a DataStore.
        public static List<Layer> forDataStore (DataStore dataStore) throws Exception {
            List<Layer> layers = new ArrayList<>();
            for (String typeName : dataStore.getTypeNames()) {
                SimpleFeatureSource featureSource = dataStore.getFeatureSource(typeName);
                Layer layer = new Layer();
                layer.name = typeName;
                layer.properties = Property.forFeatureSource(featureSource);
                layer.layerType = LayerType.forFeatureSource(featureSource);
                layer.crs = getCoordinateReferenceSystem(featureSource);
                layer.nFeatures = featureSource.getCount(Query.ALL);
                layers.add(layer);
            }
            return layers;
        }
    }

    public static String getCoordinateReferenceSystem(SimpleFeatureSource featureSource) {
        return featureSource.getSchema().getCoordinateReferenceSystem().getName().getCode();
    }

    public static class Property {
        public String name;
        public PropertyType propertyType;

        /// Create a Property metadata entry based on a GeoTools/OpenGIS PropertyDescriptor.
        public static Property forDescriptor (PropertyDescriptor propertyDescriptor) {
            Property property = new Property();
            property.name = propertyDescriptor.getName().getLocalPart();
            property.propertyType = PropertyType.forDescriptor(propertyDescriptor);
            return property;
        }

        public static List<Property> forFeatureSource (FeatureSource featureSource) {
            FeatureType featureType = featureSource.getSchema();
            List<Property> properties = new ArrayList<>();
            for (PropertyDescriptor propertyDescriptor : featureType.getDescriptors()) {
                Property property = Property.forDescriptor(propertyDescriptor);
                if (property != null) {
                    properties.add(property);
                }
            }
            return properties;
        }
    }

    /// Represents the geometry type of one Layer of an uploaded GIS file. Note that some formats
    /// such as geopackage can contain multiple layers of different types, and some formats such as
    /// GeoJSON can contain multiple types within a single layer. To keep things simple, we will
    /// support an extremely limited number of file types and options, perhaps only GeoPackages
    /// containing a single layer. We don't currently use line features for anything, but they could
    /// be useful for defining roads for example.
    public enum LayerType {
        POINT, LINE, POLYGON, RASTER, OTHER;
        public static LayerType forFeatureSource (FeatureSource featureSource) {
            GeometryType gt = featureSource.getSchema().getGeometryDescriptor().getType();
            // TODO gt.getCoordinateReferenceSystem();
            Class c = gt.getBinding();
            return LayerType.forClass(c);
        }
        public static LayerType forClass (Class c) {
            if (c == Polygon.class || c == MultiPolygon.class) { return POLYGON; }
            else if (c == LineString.class || c == MultiLineString.class) { return LINE; }
            else if (c == Point.class || c == MultiPoint.class) { return POINT; }
            // Some GeoPackages (exported from DuckDB) just return Geometry as the binding class.
            else { return OTHER; }
        }
    }

    public enum PropertyType {
        INTEGER, REAL, STRING, GEOMETRY, OTHER;
        public static PropertyType forDescriptor (PropertyDescriptor propertyDescriptor) {
            Class c = propertyDescriptor.getType().getBinding();
            if (c == Integer.class) { return INTEGER; }
            else if (c == String.class) { return STRING; }
            else if (c == Float.class || c == Double.class) { return REAL; }
            else if (c == Geometry.class) { return GEOMETRY; }
            else { return OTHER; }
        }
        public boolean isNumeric () {
            return this == INTEGER || this == REAL;
        }
    }
}
