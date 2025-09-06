// Copyright 2023-2025 Phase Five LLC.  For license terms, see LICENSE.txt in the repository root.

package io.pfive.access.http.handler;

import com.google.common.base.Preconditions;
import io.pfive.access.authentication.UserIdentity;
import io.pfive.access.filepool.FileType;
import io.pfive.access.store.JsonStore;
import io.pfive.access.util.JettyUtil;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/// List stored files or delete a stored file and associated metadata.
/// This Jetty HTTP Handler returns a list of stored files, optionally filtered down to one or more
/// file types. The list consists only of one file metadata record per file ID. In addition to or
/// instead of one file with that ID alone as a name, there may actually be several files whose
/// names begin with the same ID but have different suffixes or extensions.
public class StoredFileHandler extends Handler.Abstract {

    @Override
    public boolean handle (Request request, Response response, Callback callback) throws Exception {
        UserIdentity user = JettyUtil.extractUserIdentity(request);
        String subPath = Request.getPathInContext(request);
        if (request.getMethod().equalsIgnoreCase("DELETE")) {
            Preconditions.checkArgument(subPath.startsWith("/"), "Expected path separator.");
            String fileId = subPath.substring(1, subPath.length());
            int nDeleted = JsonStore.deleteAllFiles(fileId, user);
            String message = String.format("Deleted %d files.", nDeleted);
            return JettyUtil.respondText(HttpStatus.OK_200, message, response, callback);
        }
        // For requests with method other than DELETE, return JSON list of file metadata.
        // By default, do not filter results.
        Set<FileType> fileTypes = null;
        if (subPath.startsWith("/") && subPath.length() > 1) {
            fileTypes = new HashSet<>();
            // Interpret any subpath as a comma-separated list of FileType enum values.
            String[] fts = subPath.substring(1).toUpperCase(Locale.ROOT).split(",");
            for (String ft : fts) fileTypes.add(FileType.valueOf(ft));
        }
        return JettyUtil.respondJson(JsonStore.getFileMetadata(user, fileTypes), response, callback);
    }

}
