// Copyright 2023-2025 Phase Five LLC.  For license terms, see LICENSE.txt in the repository root.

package io.pfive.access.filepool;

import com.conveyal.file.FileStorage;
import com.conveyal.file.FileStorageKey;
import com.conveyal.r5.analyst.PersistenceBuffer;
import io.pfive.access.store.JsonStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.lang.invoke.MethodHandles;

/// The FileStorage methods don't receive any indication of what user is calling them, so they
/// cannot know which directory to look in when adapted to this system's file storage. We could try
/// all directories in turn, which will be inefficient and cannot handle identically-named files
/// existing in multiple directories. Or we can create a separate instance per user. The problem
/// with multiple instances is that other components depending on FileStorage also need to be
/// replicated. Some components such as the TaskScheduler shouldn't be replicated and don't strictly
/// need to be replicated if they don't depend on FileStorage. But this leads to a complicated mix
/// of replicated and non replicated components which may not behave correctly.
/// Although it now seems feasible to just create one FileStorageAdapter per organization, possibly
/// on the fly while handling each tile request, the GTFSCache can only take one FSA at a time.
public class FileStorageAdapter implements FileStorage {

    private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    /// Extract file ID, removing any extension that will interfere with our ID-only filename scheme.
    private static String convertKey(FileStorageKey key) {
        String fileId = key.path;
        if (fileId.endsWith(".dat")) {
            // For network .dat files, chop off the extension but also the network version (_nvX.dat).
            int lastUnderscoreIndex = fileId.lastIndexOf('_');
            fileId = fileId.substring(0, lastUnderscoreIndex);
        }
        return fileId;
    }

    @Override
    public void moveIntoStorage (FileStorageKey fileStorageKey, File file) {
        // User is not known here, so we can't know which directory to store the file in.
        LOG.warn("User unknown in moveIntoStorage call. Taking no action.");
    }

    @Override
    public void moveIntoStorage (FileStorageKey fileStorageKey, PersistenceBuffer persistenceBuffer) {
        // User is not known here, so we can't know which directory to store the file in.
        LOG.warn("User unknown in moveIntoStorage call. Taking no action.");
    }

    @Override
    public File getFile (FileStorageKey fileStorageKey) {
        return JsonStore.retrieveAuxiliaryFile(convertKey(fileStorageKey), null).toFile();
    }

    @Override
    public String getURL (FileStorageKey fileStorageKey) {
        throw new UnsupportedOperationException("Method not implemented in adapter.");
    }

    @Override
    public void delete (FileStorageKey fileStorageKey) {
        JsonStore.deleteFile(convertKey(fileStorageKey), null);
    }

    @Override
    public boolean exists (FileStorageKey fileStorageKey) {
        return JsonStore.fileExists(convertKey(fileStorageKey), null);
    }

}
