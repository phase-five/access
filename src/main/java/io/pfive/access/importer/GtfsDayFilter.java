// Copyright 2023-2025 Phase Five LLC.  For license terms, see LICENSE.txt in the repository root.

package io.pfive.access.importer;

import com.conveyal.r5.analyst.progress.NoopProgressListener;
import com.conveyal.r5.analyst.progress.ProgressListener;
import io.pfive.access.background.TrackedInputStream;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.CSVRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.lang.invoke.MethodHandles;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static io.pfive.access.importer.GtfsDayFilter.CsvFiles.*;

/// Extract all data relevant to a single operating day directly from GTFS CSV into new GTFS CSV.
/// We start from the calendars and use them to skip trip and stoptime rows that are not relevant.
/// Almost no validation is performed on the input data. It is assumed that the resulting filtered
/// output will go through a more rigorous import process downstream. The output GTFS has a single
/// service ID running on a single day. If a second date is specified on the command line, data
/// will be extracted for the first date but assigned to the second date in the output GTFS.
/// This could be updated to also crop to geographic region, since once stops are filtered the rest
/// of the filtering process is quite similar. Likewise for extracting only certain hours of the day.
/// Testing on http://gtfs.ovapi.nl/nl/ this reduced file size from 198 to 21MB zipped (89% less)
/// or 63MB with shapes retained (68% less). Testing on IDFM (Paris) this reduced file size from
/// 82MB to 27MB retaining shapes (66% reduction).
public class GtfsDayFilter {
    private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
    public static DateTimeFormatter GTFS_DATE = DateTimeFormatter.BASIC_ISO_DATE;

    enum CsvFiles {
        AGENCY, CALENDAR, CALENDAR_DATES, ROUTES, SHAPES, STOPS, STOP_TIMES, TRANSFERS, TRIPS;
        String filename;
        CsvFiles () {
            this.filename = this.name().toLowerCase(Locale.ROOT) + ".txt";
        }
    }

    public static void main (String[] args) {
        File file = new File(args[0]);
        LocalDate inDate = LocalDate.parse(args[1]);
        LocalDate outDate = (args.length == 2) ? inDate : LocalDate.parse(args[2]);
        try {
            importDay(file, inDate, outDate);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static void importDay (File gtfsFile, LocalDate inDate, LocalDate outDate) throws IOException {
        final int dateInt = dateAsInt(inDate.format(GTFS_DATE));
        final ZipFile zipFile = new ZipFile(gtfsFile);

        final Set<String> activeServiceIds = new HashSet<>();
        final String dayField = inDate.getDayOfWeek()
              .getDisplayName(TextStyle.FULL, Locale.US).toLowerCase(Locale.US);
        process(zipFile, CALENDAR, false, record -> {
            int startDateInt = dateAsInt(record.get("start_date"));
            int endDateInt = dateAsInt(record.get("end_date"));
            if (dateInt < startDateInt || dateInt > endDateInt) return;
            if (record.get(dayField).equals("1")) {
                activeServiceIds.add(record.get("service_id"));
            }
        });
        LOG.info("{} active service IDs from calendar alone.", activeServiceIds.size());
        process(zipFile, CALENDAR_DATES, false, record -> {
            int exceptionDateInt = dateAsInt(record.get("date"));
            if (exceptionDateInt == dateInt) {
                String serviceId = record.get("service_id");
                switch (record.get("exception_type")) {
                    case "1" -> activeServiceIds.add(serviceId);
                    case "2" -> activeServiceIds.remove(serviceId);
                    default -> throw new IllegalArgumentException("Unrecognized exception type.");
                }
            }
        });
        LOG.info("{} active service IDs considering calendar_dates.", activeServiceIds.size());

        ZipOutputStream zipOut = new ZipOutputStream(new FileOutputStream(gtfsFile.getName() + ".filtered.zip"));
        writeServiceAll(zipOut, outDate);

        Set<String> activeTripIds = new HashSet<>();
        Set<String> activeShapeIds = new HashSet<>();
        Set<String> activeRouteIds = new HashSet<>();
        processAndWrite(zipFile, zipOut, TRIPS, true, record -> {
            if (activeServiceIds.contains(record.get("service_id"))) {
                activeTripIds.add(record.get("trip_id"));
                // TODO Skip if shape header is not present.
                activeShapeIds.add(record.get("shape_id"));
                activeRouteIds.add(record.get("route_id"));
                return true;
            }
            return false;
        });

        Set<String> activeStopIds = new HashSet<>();
        processAndWrite(zipFile, zipOut, STOP_TIMES, true, record -> {
            if (activeTripIds.contains(record.get("trip_id"))) {
                activeStopIds.add(record.get("stop_id"));
                return true;
            }
            return false;
        });

        processAndWrite(zipFile, zipOut, STOPS, true, record -> {
            String stopId = record.get("stop_id");
            return activeStopIds.contains(stopId);
        });

        Set<String> activeAgencyIds = new HashSet<>();
        processAndWrite(zipFile, zipOut, ROUTES, true, record -> {
            String routeId = record.get("route_id");
            if (activeRouteIds.contains(routeId)) {
                activeAgencyIds.add(record.get("agency_id"));
                return true;
            }
            return false;
        });

        processAndWrite(zipFile, zipOut, AGENCY, true, record -> {
            return activeAgencyIds.contains(record.get("agency_id"));
        });

        // Transfers can contain optional fields like fromRouteId and fromTripId.
        // Filtering on these optional fields makes the code more complex.
        // R5 does not currently load transfers or use parent stations, so we leave them out.
        // TODO UI can be changed to use only the single day selected out of a GTFS input.

        processAndWrite(zipFile, zipOut, SHAPES, false, record -> {
            String shapeId = record.get("shape_id");
            return activeShapeIds.contains(shapeId);
        });

        zipFile.close();
        zipOut.flush();
        zipOut.close();
        LOG.info("Done.");
    }

    private static void writeServiceAll (ZipOutputStream zipOut, LocalDate outDate) {
        try {
            CSVPrinter printer = csvPrinter(zipOut, CALENDAR_DATES);
            printer.printRecord("service_id", "exception_type", "date");
            printer.printRecord("ALL", "1", outDate.format(DateTimeFormatter.BASIC_ISO_DATE));
            printer.flush();
            zipOut.closeEntry();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static final CSVFormat GTFS_CSV_FORMAT =
          CSVFormat.RFC4180.builder().setHeader().setSkipHeaderRecord(true).build();

    private static boolean process (
          ZipFile zipFile,
          CsvFiles csvFile,
          boolean required,
          Consumer<CSVRecord> handler
    ) {
        ZipEntry entry = zipFile.getEntry(csvFile.filename);
        if (entry == null) {
            if (required) throw new IllegalArgumentException("File is required: " + csvFile.filename);
            else return false;
        }
        try (Reader reader = trackedReader(zipFile, entry, new NoopProgressListener())) {
            for (CSVRecord record : GTFS_CSV_FORMAT.parse(reader)) {
                handler.accept(record);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return true;
    }

    private static boolean processAndWrite (
          ZipFile inZip,
          ZipOutputStream outZip,
          CsvFiles csvFile,
          boolean required,
          Predicate<CSVRecord> handler
    ) {
        ZipEntry inEntry = inZip.getEntry(csvFile.filename);
        if (inEntry == null) {
            if (required) throw new IllegalArgumentException("File is required: " + csvFile.filename);
            else return false;
        }
        CSVPrinter printer = csvPrinter(outZip, csvFile);
        try (Reader reader = trackedReader(inZip, inEntry, new NoopProgressListener())) {
            CSVParser parser = GTFS_CSV_FORMAT.parse(reader);
            printer.printRecord(parser.getHeaderNames());
            Integer serviceIdColumn = parser.getHeaderMap().get("service_id");
            for (CSVRecord record : parser) {
                if (handler.test(record)) {
                    String[] values = record.values();
                    // Could be a generic replacer function, but we currently have only one use case.
                    if (serviceIdColumn != null) values[serviceIdColumn] = "ALL";
                    printer.printRecord((Object[])values);
                }
            }
            printer.flush();
            outZip.closeEntry();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return true;
    }

    private static Reader trackedReader (ZipFile file, ZipEntry entry, ProgressListener progress) {
        return new InputStreamReader(trackedInputStream(file, entry, progress));
    }

    private static InputStream trackedInputStream (ZipFile file, ZipEntry entry, ProgressListener progress) {
        try {
            return new TrackedInputStream(file.getInputStream(entry), entry.getSize(), progress);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static CSVPrinter csvPrinter (ZipOutputStream zout, CsvFiles csvFile) {
        try {
            ZipEntry entry = new ZipEntry(csvFile.filename);
            zout.putNextEntry(entry);
            Writer writer = new OutputStreamWriter(zout);
            return GTFS_CSV_FORMAT.print(writer);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static int dateAsInt (String value) {
        int result = Integer.parseInt(value);
        if (result < 20200000 || result > 20500000) {
            throw new IllegalArgumentException("Date out of range: " + value);
        }
        return result;
    }

}
