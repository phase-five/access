// Copyright 2023-2025 Phase Five LLC.  For license terms, see LICENSE.txt in the repository root.

package io.pfive.access.http.handler;

import com.conveyal.gtfs.GTFSFeed;
import com.conveyal.osmlib.OSM;
import com.conveyal.r5.analyst.progress.ProgressListener;
import io.pfive.access.authentication.UserIdentity;
import io.pfive.access.background.BackgroundItem;
import io.pfive.access.background.BackgroundItemTracker;
import io.pfive.access.background.TrackedInputStream;
import io.pfive.access.filepool.FileMetadata;
import io.pfive.access.filepool.FileType;
import io.pfive.access.http.exception.InvalidRequestException;
import io.pfive.access.importer.GeoPackageRasterImporter;
import io.pfive.access.store.JsonStore;
import io.pfive.access.util.JettyUtil;
import io.pfive.access.util.RandomId;
import io.pfive.access.util.Ret;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.geotools.data.DataStore;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.zip.ZipFile;

import static io.pfive.access.util.Ret.err;
import static io.pfive.access.util.Ret.ok;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static org.eclipse.jetty.http.HttpStatus.UNSUPPORTED_MEDIA_TYPE_415;

public class UploadHandler extends Handler.Abstract {

    private final BackgroundItemTracker backgroundItemTracker;

    public UploadHandler (BackgroundItemTracker backgroundItemTracker) {
        this.backgroundItemTracker = backgroundItemTracker;
    }

    // Design requirements here are to avoid buffering the incoming file at all, and avoid handling its contents
    // as anything other than the Jetty native representation as a series of ByteBuffers.

    // Looking at the internal workings of Request.asInputStream() and the ContentSourceInputStream it creates.
    // This is probably the reason for the complicated iteration pattern in org.eclipse.jetty.io.Content.Source:
    // it allows working directly with the underlying ByteBuffer chunks instead of converting each byte into an int one
    // by one. I may be able to wrap that iteration process as an Iterable<ByteBuffer>.
    // See iteration pattern from org.eclipse.jetty.io.Content.Source, and ContentSourceByteBuffer.run().
    // See also org.eclipse.jetty.io.Content.copy() and org.eclipse.jetty.io.internal.ContentCopier.
    // Maybe what we need is to write a FileContentSink. There is also Flow.Subscriber<Chunk> from Jetty.

    // I noticed this when writing a class to track upload progress by wrapping Request.asInputStream().
    // Amazingly Java uses 4-byte words to represent every single byte coming out of a stream, just so it can
    // signal EOF with the number -1 and to compensate for the fact that Java doesn't have signed types.
    // There has to be some more efficient (NIO? Direct system calls?) way to handle this. No wonder old IO was replaced.

    // TODO uploaded files can be provided back to the client using a ResourceHandler behind the authentication.
    //   Or a single large ResourceHandler can be placed behind the authentication, with holes punched in
    //   authentication for login pages. Subdirectories need to be authorized based on per-user information.

    // Handles ONE individual uploaded file. The client may upload several in parallel.
    @Override
    public boolean handle (Request request, Response response, Callback callback) throws Exception {
        UserIdentity user = JettyUtil.extractUserIdentity(request);
        String filename = getAttachmentName(request.getHeaders()).getOrThrow(InvalidRequestException::new);
        long contentLength = getContentLength(request);
        // IDs should not be base64 because some filesystems (such as APFS) are not case-sensitive.
        // For now use UUIDs represented as hex digits, which are not case sensitive.
        String fileId = RandomId.createRandomStringId();
        // TODO test file extension (reject unknown formats) before receiving upload, bail out early.
        // TODO use sanitized version of attachmentName as suffix for easier debugging and type detection.
        // The extension of the temp file is important for detecting its type, so use the orignal name as a suffix here.
        // The original name could also be passed to the FileType.detect method.
        Path tempFile = Files.createTempFile("upload", filename);
        BackgroundItem backgroundItem = new BackgroundItem("Upload file " + filename, user);
        backgroundItemTracker.add(backgroundItem);
        try (TrackedInputStream inStream =
             new TrackedInputStream(Request.asInputStream(request), contentLength, backgroundItem.progressSink)
        ) {
            Files.copy(inStream, tempFile, REPLACE_EXISTING);
        }
        // Detect the type of the new file.
        // It might also make more sense to use the Path type instead of file, but we want case-insensitive comparisons.
        FileType fileType = FileType.detect(tempFile.toFile());
        if (fileType == FileType.UNKNOWN) {
            return JettyUtil.respondText(UNSUPPORTED_MEDIA_TYPE_415, "Unrecognized file type.", response, callback);
        }

        // TODO runnable that has a built in progress tracker and catches exceptions, recording them on progress.

        // To return quickly from the upload, create and another background item to process the import operation.
        String title = String.format("Processing uploaded file %s with format %s.", filename, fileType.toString());
        backgroundItem = new BackgroundItem(title, user);
        backgroundItemTracker.add(backgroundItem);
        final ProgressListener progress = backgroundItem.progressSink;
        backgroundItemTracker.run(() -> {
            FileMetadata md = new FileMetadata(fileId, fileType);
            md.name = filename;
            md.originalName = filename;
            if (fileType == FileType.OSMPBF || fileType == FileType.GTFS) {
                // GTFS and OSM files will be loaded into MapDB single-file databases and validated.
                // These round-trip Path-String conversions are ugly, but it seems to work.
                Path dbTemp = Path.of(tempFile.toString() + ".db");
                Path dbpTemp = Path.of(tempFile.toString() + ".db.p");
                String dbId = fileId + ".db";
                String dbpId = fileId + ".db.p";
                if (fileType == FileType.OSMPBF) {
                    progress.beginTask("Loading OSM PBF into MapDB file.", 1);
                    OSM osm = new OSM(dbTemp.toString());
                    try {
                        // We do not need to enable intersectionDetection here, only when re-connecting to OSM later.
                        osm.readFromFile(tempFile.toString());
                    } finally {
                        osm.close();
                    }
                } else if (fileType == FileType.GTFS) {
                    GTFSFeed feed = GTFSFeed.newWritableFile(dbTemp.toFile());
                    // Progress will usually not reach 100% due to continue statements in GTFSFeed.
                    feed.progressListener = progress;
                    try {
                        feed.loadFromFile(new ZipFile(tempFile.toFile()), fileId);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    } finally {
                        // TODO catch feed.errors and prevent from use (unify with validation)
                        // When high-priority errors are present, patterns are not found and the feed is useless.
                        feed.close();
                    }
                }
                // Whether created for OSM or GTFS, store the database files for later use.
                JsonStore.storeFile(dbTemp, dbId, user);
                JsonStore.storeFile(dbpTemp, dbpId, user);
            } else if (fileType == FileType.GEOPACKAGE || fileType == FileType.GEOJSON) {
                // GeoPackage and GeoJSON files will be analyzed to detect their fields and geometry types.
                progress.beginTask("Scanning GIS file for property and geometry types.", 1);
                try {
                    if (fileType == FileType.GEOPACKAGE) {
                        DataStore dataStore = GeoPackageRasterImporter.openGeoPackageDataStore(tempFile);
                        md.layers = FileMetadata.Layer.forDataStore(dataStore);
                    } else if (fileType == FileType.GEOJSON) {
                        // TODO open GeoJson similarly. Or maybe explicitly support only GeoPackage.
                        // DataStore dataStore = GeoPackageRasterImporter.openGeoPackageDataStore(tempFile);
                        // md.layers = FileMetadata.Layer.forDataStore(dataStore);
                    }
                    progress.increment();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
            // TODO validate uploaded files
            progress.beginTask("Storing processed files.", 1);
            JsonStore.storeFile(tempFile, fileId, user);
            JsonStore.storeMetadata(md, user);
            progress.increment();
            // End of file import background task logic.
        });
        return JettyUtil.respondText(HttpStatus.OK_200, title, response, callback);
    }

    // We could also supply/check CONTENT_MD5
    private long getContentLength (Request request) {
        HttpFields headers = request.getHeaders();
        String val = headers.get(HttpHeader.CONTENT_LENGTH);
        return Long.parseLong(val);
    }

    // Note that Request is a Source.
    private static void readSource (Content.Source source) {

    }

    // It may be wiser to reuse something like https://commons.apache.org/proper/commons-fileupload/
    // We also want to track progress while receiving the file. Commons-upload has this capability.
    // Within Jetty we have org.eclipse.jetty.http.MultiPart, but these are both focused on MIME
    // multipart. We don't necessarily need or want broad interoperability with other HTTP tooling or
    // clients, and can actually define our own upload system, so we might want to just add
    // x-content-filename (and maybe content-md5) headers and intentionally make it proprietary while
    // still being clean HTTP.
    // Note: the decoded name will never actually be used as a filename on the server filesystem.
    // It's used only for display purposes so the uploader can recognize the file they uploaded.

    private final String FILENAME_PREFIX="filename*=utf-8''";
    private final String ERR_MESSAGE = "Content-disposition header value should be in the form: attachment; " + FILENAME_PREFIX;

    private Ret<String> getAttachmentName (HttpFields headers) {
        HttpField cdf = headers.getField(HttpHeader.CONTENT_DISPOSITION);
        String[] vals = cdf.getValue().split(";");
        if (vals.length != 2) {
            return err(ERR_MESSAGE);
        }
        // Parsing should really be more sophisticated than trim() or we should just use a different custom header.
        vals[0] = vals[0].toLowerCase(Locale.ROOT).trim();
        if (!"attachment".equals(vals[0])) {
            return err(ERR_MESSAGE);
        }
        vals[1] = vals[1].trim();
        // Notably, we are not accommodating the optional quotes around the value.
        if (!vals[1].toLowerCase(Locale.ROOT).startsWith(FILENAME_PREFIX)) {
            return err(ERR_MESSAGE);
        }
        String encodedFilename = vals[1].substring(FILENAME_PREFIX.length());
        String decodedFilename = URLDecoder.decode(encodedFilename, StandardCharsets.UTF_8);
        return ok(decodedFilename);
    }

}
