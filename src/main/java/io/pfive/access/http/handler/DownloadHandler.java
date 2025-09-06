// Copyright 2023-2025 Phase Five LLC.  For license terms, see LICENSE.txt in the repository root.

package io.pfive.access.http.handler;

import io.pfive.access.authentication.UserIdentity;
import io.pfive.access.filepool.FileMetadata;
import io.pfive.access.store.JsonStore;
import io.pfive.access.util.JettyUtil;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;

import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/// Fetch a single file from the user's file pool, giving it a more friendly name. Importantly, the
/// response will have a content-disposition header saying this is an "attachment" and should be
/// downloaded instead of shown in the browser.
/// At this point, this handler is used only for downloading zip archives of geotiffs.
/// Frontend ES code can hit this endpoint with `window.location.assign("/download/fileid.extension");`
/// This is heavily based on GridPngHandler and BatchResultsRasterHandler. It may make sense
/// to merge all of them into a single handler class with a few configuration options.
public class DownloadHandler extends Handler.Abstract {

    @Override
    public boolean handle (Request request, Response response, Callback callback) throws Exception {
        UserIdentity user = JettyUtil.extractUserIdentity(request);
        String subPath = Request.getPathInContext(request);
        if (subPath.isBlank() || !subPath.startsWith("/")) {
            return JettyUtil.respondClientError("URL should end with file name.", response, callback);
        }
        // Split out the object ID before the first dot.
        // NOTE this will not work with files that instead have an underscore or other separator.
        int dotIndex = subPath.indexOf('.');
        String fileId = subPath.substring(1, dotIndex);
        String extension = subPath.substring(dotIndex);
        FileMetadata fileMetadata = JsonStore.retrieveFile(fileId, user).fileMetadata();
        Path filePath = JsonStore.retrieveAuxiliaryFile(fileId, extension, user);
        if (Files.notExists(filePath)) {
            return JettyUtil.respondBadRequest("No such file.", response, callback);
        }
        // Make a less cryptic name for the downloaded file based on its metadata.
        String name = fileMetadata.name.replaceAll("[^A-Za-z0-9]", "-");
        String contentDispo = "attachment; filename=\"%s\"".formatted(name + extension);
        response.getHeaders().add(HttpHeader.CONTENT_DISPOSITION, contentDispo);
        // Copy memory-mapped byte buffer to response. MappedByteBuffer is unmapped when garbage collected.
        try (FileChannel pngChannel = FileChannel.open(filePath, StandardOpenOption.READ)) {
            MappedByteBuffer buf = pngChannel.map(FileChannel.MapMode.READ_ONLY, 0, pngChannel.size());
            response.write(true, buf, callback);
        }
        return true;
    }

}
