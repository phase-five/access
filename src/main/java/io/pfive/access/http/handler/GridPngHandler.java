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

import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/// This Jetty HTTP handler returns a PNG grid for the specified ID, with response headers providing
/// the bounding box within which the image should be displayed on a map. It might also be possible
/// to do this with a ResourceHandler and a URL path parameter for the group name. ResourceHandler
/// uses HttpContent and ResourceService to handle the GET request and send bytes back. This in turn
/// uses ContentWriterIteratingCallback, not something you'd want to replicate.
public class GridPngHandler extends Handler.Abstract {

    // For simple full-file case in Jetty standard file server see:
    // org.eclipse.jetty.server.ResourceService.writeHttpContent

    @Override
    public boolean handle (Request request, Response response, Callback callback) throws Exception {
        UserIdentity user = JettyUtil.extractUserIdentity(request);
        String subPath = Request.getPathInContext(request);
        if (subPath.isBlank() || !subPath.startsWith("/")) {
            return JettyUtil.respondClientError("URL should end with a grid ID.", response, callback);
        }
        String gridId = subPath.substring(1);
        FileWithMetadata grid = JsonStore.retrieveFile(gridId, user);
        Wgs84Bounds wgsBounds = grid.fileMetadata().wgsBounds;
        if (wgsBounds == null) {
            return JettyUtil.respondServerError("Grid has no bounds metadata.", response, callback);
        }
        Path gridPngFile = JsonStore.retrieveAuxiliaryFile(gridId, ".png", user);
        JettyUtil.addGeoPngHeaders(response, wgsBounds);
        // Copy memory-mapped byte buffer to response. There are apparently no methods to unmap a
        // MappedByteBuffer, it just gets unmapped when it's garbage collected.
        try (FileChannel pngChannel = FileChannel.open(gridPngFile, StandardOpenOption.READ)) {
            MappedByteBuffer buf = pngChannel.map(FileChannel.MapMode.READ_ONLY, 0, pngChannel.size());
            response.write(true, buf, callback);
        }
        // The following stream-based code did not seem like an efficient way to transfer a file. In
        // addition it seems to have a deadlock problem (IOUtils.copy not letting the reading side proceed.)
        // Try-with-resources will close both streams when finished.
        // try (InputStream in = new BufferedInputStream(new FileInputStream(gridPngFile.toFile()));
        //     OutputStream out = Content.Sink.asOutputStream(response)) {
        //     JettyUtil.addGeoPngHeaders(response, wgsBounds);
        //     IOUtils.copy(in, out);
        // }
        return true;
    }

}
