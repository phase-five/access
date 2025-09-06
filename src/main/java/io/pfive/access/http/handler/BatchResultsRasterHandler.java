// Copyright 2023-2025 Phase Five LLC.  For license terms, see LICENSE.txt in the repository root.

package io.pfive.access.http.handler;

import io.pfive.access.authentication.UserIdentity;
import io.pfive.access.importer.Wgs84Bounds;
import io.pfive.access.store.FileWithMetadata;
import io.pfive.access.store.JsonStore;
import io.pfive.access.util.JettyUtil;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Fields;

import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/// Fetch a single PNG file representing one percentile and cutoff of many-to-many batch output.
/// This is heavily based on GridPngHandler and BatchResultsRasterHandler. We may want to merge
/// these handlers into a new file handler. It must allow web UIs to both fetch and download files.
public class BatchResultsRasterHandler extends Handler.Abstract {

    @Override
    public boolean handle (Request request, Response response, Callback callback) throws Exception {
        UserIdentity user = JettyUtil.extractUserIdentity(request);
        String subPath = Request.getPathInContext(request);
        if (subPath.isBlank() || !subPath.startsWith("/")) {
            return JettyUtil.respondClientError("URL should end with results ID.", response, callback);
        }
        String resultsId = subPath.substring(1);
        FileWithMetadata batchResults = JsonStore.retrieveFile(resultsId, user);
        Wgs84Bounds wgsBounds = batchResults.fileMetadata().wgsBounds;
        if (wgsBounds == null) {
            return JettyUtil.respondServerError("Results have no bounds metadata.", response, callback);
        }
        Fields fields = Request.extractQueryParameters(request);
        int percentile, cutoff;
        try {
            percentile = Integer.parseInt(fields.get("p").getValue());
            cutoff = Integer.parseInt(fields.get("c").getValue());
        } catch (NumberFormatException e) {
            return JettyUtil.respondClientError("Cannot parse percentile or time cutoff.", response, callback);
        }
        String pngSuffix = "_P%d_C%d.png".formatted(percentile, cutoff);
        Path resultsPngFile = JsonStore.retrieveAuxiliaryFile(resultsId, pngSuffix, user);
        if (Files.notExists(resultsPngFile)) {
            return JettyUtil.respondBadRequest("No such batch results.", response, callback);
        }
        JettyUtil.addGeoPngHeaders(response, wgsBounds);
        // Copy memory-mapped byte buffer to response. MappedByteBuffer is unmapped when garbage collected.
        try (FileChannel pngChannel = FileChannel.open(resultsPngFile, StandardOpenOption.READ)) {
            MappedByteBuffer buf = pngChannel.map(FileChannel.MapMode.READ_ONLY, 0, pngChannel.size());
            response.write(true, buf, callback);
        }
        return true;
    }

}
