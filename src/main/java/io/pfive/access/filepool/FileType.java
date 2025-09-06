// Copyright 2023-2025 Phase Five LLC.  For license terms, see LICENSE.txt in the repository root.

package io.pfive.access.filepool;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/// We intentionally don't support shapefile. Please export them to Geopackage in QGIS. Note that
/// some formats, notably geopackage and geotiff, can have multiple layers per file. Geopackage can
/// even have multiple layers of different data types, such as polygons and rasters. Shapefile dates
/// from the late 1980s, as does GeoTIFF. Some things from the late 80s are simple yet good. But
/// these are layer upon layer of hacks and have been past their best-by date for decades. To begin
/// with we allow ONLY WGS84. And maybe only Geopackage uploads. Maybe we don't want to allow CSV or
/// other formats. Require everything to be pre-validated in QGIS by only accepting geopkg. So only
/// OSMPBF, GTFS, GEOPACKAGE are supported at first. GeoJson is irritating in that a single
/// collection can have features of multiple types. Maybe what we really want is a FileFormat type
/// hierarchy with detect() and validate() methods, and the latter can summarize the file with a
/// number of features, size etc. (in FileMetadata?)
public enum FileType {

    // Input file types
    OSMPBF,     // OpenStreetMap binary format for streets
    GTFS,       // General Transit Feed Specification (public transport schedule data)
    GEOPACKAGE, // The only geospatial format we accept. Replaces Shapefile, CSV, GeoJSON etc.
    GEOJSON,    // TO BE REMOVED (interface only with GeoPackage, convert with QGIS as needed)
    CSV,        // TO BE REMOVED (interface only with GeoPackage, convert with QGIS as needed)

    // Internal file formats for imported data
    NETWORK,    // Serialized transportation network with streets and transit
    GRID,       // Opportunities on a regular geographic grid
    POINT,      // Opportunities at arbitrary geographic points, can be sparse and not on a grid
    EGRESS,     // Sparse tables of street connectors and transit egress distances

    // Results and image file types for visualization or raster export
    BATCH,      // Results for all origins of a many-to-many batch job
    GEOTIFF,    // Suitable for loading into QGIS
    PNG,        // Suitable for layering on web maps

    // Files storing elements of scenarios
    MODIFICATION, // Transit modification TODO different type for street modifications?
    SCENARIO,     // Selection of modifications applicable to networks with one or more GTFS and OSM

    // Everything else that can't be recognized
    UNKNOWN;

    /// Detect the type of an input file (does not recognize output and already-imported file types).
    public static FileType detect (File file) {
        if (isOsmPbf(file)) {
            return OSMPBF;
        }
        if (isGtfs(file)) {
            return GTFS;
        }
        if (isGeoJson(file)) {
            return GEOJSON;
        }
        if (isGeoPackage(file)) {
            return GEOPACKAGE;
        }
        return UNKNOWN;
    }

    private static boolean isGeoJson (File file) {
        if (!(fileNameEndsWith(file, ".json", ".geojson"))) {
            return false;
        }
        try {
            JsonFactory jsonFactory = new JsonFactory();
            JsonParser jsonParser = jsonFactory.createParser(file);
            while (jsonParser.nextToken() != JsonToken.END_OBJECT) {
                if ("type".equals(jsonParser.getCurrentName())) {
                    jsonParser.nextToken();
                    String type = jsonParser.getText();
                    if ("FeatureCollection".equals(type)) {
                        return true;
                    }
                    if ("Feature".equals(type)) {
                        return true;
                    }
                }
            }
            return false;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static boolean isGtfs (File file) {
        // We often name these with .gtfs.zip. This catches both .gtfs.zip and .zip.
        if (!isZip(file)) {
            return false;
        }
        // Try to open as a zip file and check presence of several required table files in the root.
        try {
            ZipFile zipFile = new ZipFile(file);
            final List<String> requiredTables = List.of("agency.txt", "stops.txt", "routes.txt", "trips.txt");
            for (String table : requiredTables) {
                ZipEntry entry = zipFile.getEntry(table);
                if (entry == null) {
                    return false;
                }
            }
            return true;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static boolean isOsmPbf (File file) {
        return fileNameEndsWith(file, ".pbf", ".osmpbf") && hasPbfHeader(file);
    }


    private static boolean fileNameEndsWith (File file, String... extensions) {
        for (String extension : extensions) {
            if (fileNameEndsWith(file, extension)) {
                return true;
            }
        }
        return false;
    }

    private static boolean fileNameEndsWith (File file, String extension) {
        String name = file.getName();
        int extLen = extension.length();
        return name.regionMatches(true, name.length() - extLen, extension, 0, extLen);
    }

    private static final byte[] PBF_SIGNATURE = "OSMHeader".getBytes(StandardCharsets.UTF_8);

    private static boolean hasPbfHeader (File file) {
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            // Full message length is variable from one file to another, so discard it.
            // However, readInt method is big-endian (network order), matching file format. See formula in its javadoc.
            int messageLength = raf.readInt();
            int unknown = raf.readByte();
            int stringLength = raf.readByte();
            if (stringLength != 9) {
                return false;
            }
            byte[] buffer = new byte[PBF_SIGNATURE.length];
            raf.read(buffer);
            return Arrays.equals(PBF_SIGNATURE, buffer);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }


    private static boolean isZip (File file) {
        // Many file formats like JAR and DOCX are actually zip files.
        // Here we want to reject those and accept only files that are overtly zips.
        if (!fileNameEndsWith(file, ".zip")) {
            return false;
        }
        // Though technically 0x504B0506 (empty zip file) and 0x504B0708 (spanned zip file) are also zips,
        // we don't want to accept them here.
        final int ZIP_SIGNATURE = 0x504B0304;
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            int magicNumber = raf.readInt();
            if (magicNumber == ZIP_SIGNATURE) {
                return true;
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return false;
    }


    // A common java geopackage library is https://github.com/ngageoint/geopackage-java
    // This introduces dependencies on ormlite-jdbc and sqlite-jdbc, of specific versions of course.
    // So you might as well be using sqlite as your database if you're going to be working with geopackages.
    // Should we be using GDAL and GeoTools and JTS these days, or some other library, or just code up our simplest
    // cases to avoid needing to use their overly general API?
    // http://www.geopackage.org/guidance/getting-started.html
    // http://www.geopackage.org/spec120/#r1
    // The first 16 bytes of a GeoPackage SHALL be the null-terminated ASCII string "SQLite format 3".
    // A GeoPackage SHALL contain a value of 0x47504B47 ("GPKG" in ASCII) in the "application_id" field of the
    // SQLite database header to indicate that it is a GeoPackage. A GeoPackage SHALL contain an appropriate value
    // in "user_version" field of the SQLite database header to indicate its version. The value SHALL be in integer
    // with a major version, two-digit minor version, and two-digit bug-fix. For GeoPackage Version 1.2 this value
    // is 0x000027D8 (the hexadecimal value for 10200).
    // A GeoPackage is an SQLite Database file with a .gpkg extension.

    private static final byte[] GPKG_SIGNATURE = "SQLite format 3".getBytes(StandardCharsets.UTF_8);

    private static boolean isGeoPackage (File file) {
        if (!fileNameEndsWith(file, ".gpkg")) {
            return false;
        }
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            byte[] buffer = new byte[GPKG_SIGNATURE.length];
            raf.read(buffer);
            return Arrays.equals(GPKG_SIGNATURE, buffer);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
