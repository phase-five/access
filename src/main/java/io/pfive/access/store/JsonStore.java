// Copyright 2023-2025 Phase Five LLC.  For license terms, see LICENSE.txt in the repository root.

package io.pfive.access.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.base.Preconditions;
import io.pfive.access.authentication.UserIdentity;
import io.pfive.access.filepool.FileMetadata;
import io.pfive.access.filepool.FileType;
import io.pfive.access.util.JettyUtil;
import io.pfive.access.util.RandomId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

/// Maybe go full procedural and have no singleton component instances, just classes with static
/// methods? Is there any reason we'd ever want more than one JsonStore? Maybe for testing.
/// Note that this is currently abstract and all methods are static. And it's not necessarily bad
/// that way.
public abstract class JsonStore {

    private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
    private static final String BASE_PATH = "library"; // maybe should be "store"
    private static final String META_JSON_SUFFIX = ".meta.json";

    // Special case: accommodate R5 components that are not aware of users and per-organization directories.
    private static final Set<String> knownOrganizations = new HashSet<>();

    // Object could be restricted to be a supertype that always has an ID, an associated user and org, and a getDirs.
    // ID should be a specific StoreId type, not String.
    public static void storeJson (Object object, String objectName, UserIdentity user) {
        try {
            File tempJsonFile = File.createTempFile("store", ".json");
            JettyUtil.objectMapper.writeValue(tempJsonFile, object);
            storeFile(tempJsonFile.toPath(), objectName, user);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static void createOrganizationDirectoryAsNeeded (UserIdentity user) {
        try {
            // Creates parent directories, no exception if directory already exists.
            Files.createDirectories(Path.of("library", user.organization));
            knownOrganizations.add(user.organization);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    // TODO make overwrite behavior optional
    // TODO reduce race conditions by synchronizing some methods or whole class
    public static void storeFile (Path sourcePath, String objectName, UserIdentity user) {
        try {
            Files.move(sourcePath, orgPath(user, objectName), REPLACE_EXISTING); // Somehow signal success?
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /// Get the path of a file that is not the main, extensionless file (with just the ID for a
    /// name) but some other auxiliary file with the same ID but an extension added.
    ///
    /// @param extension should include the leading dot or other separator character
    public static Path retrieveAuxiliaryFile (String objectId, String extension, UserIdentity user) {
        return orgPath(user, objectId + extension);
    }

    public static Path retrieveAuxiliaryFile (String nameWithAnyExtension, UserIdentity user) {
        return orgPath(user, nameWithAnyExtension);
    }

    /// TODO allow retrieving auxiliary file (with specified extension) with metadata for base name.

    public static FileWithMetadata retrieveFile (String objectName, UserIdentity user) {
        Path path = orgPath(user, objectName);
        Path metadataPath = orgPath(user, objectName + META_JSON_SUFFIX);
        FileMetadata fileMetadata = readJsonPath(metadataPath, FileMetadata.class);
        return new FileWithMetadata(path, fileMetadata);
    }

    public static boolean fileExists (String objectName, UserIdentity user) {
        Path path = orgPath(user, objectName);
        return Files.exists(path);
        // FIXME altered this for networkId_nv3.dat and MapDB db/dbp files.
        //  These have no metadata because they are themselves sidecar derived files for a particular ID.
        // Path metadataPath = orgPath(user, objectName + META_JSON_SUFFIX);
        // return Files.exists(path) && Files.exists(metadataPath);
    }

    /// Return a list of metadata for all the files uploaded or created by a particular user's
    /// organization. If fileTypes is non-null, only metadata for files of the types in the Set will
    /// be returned. If fileType is null, metadata will be returned for all files.
    /// TODO both include and exclude sets, either here or in caller, with utils to build and
    ///   validate from strings. Plus/minus prefixes?
    public static List<FileMetadata> getFileMetadata (UserIdentity user, Set<FileType> fileTypes) {
        List<FileMetadata> metaData = new ArrayList<>();
        for (File file : orgPath(user).toFile().listFiles()) {
            if (!file.getName().endsWith(".meta.json")) continue;
            FileMetadata fileMetadata = readJsonFile(file, FileMetadata.class);
            if (fileTypes == null || fileTypes.contains(fileMetadata.fileType)) {
                metaData.add(fileMetadata);
            }
        }
        return metaData;
    }

    public static void deleteFile (String objectName, UserIdentity user) {
        Path path = orgPath(user, objectName);
        Path metadataPath = orgPath(user, objectName + META_JSON_SUFFIX);
        try {
            Files.delete(path);
            Files.delete(metadataPath);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /// Delete every file that starts with the given random string ID.
    public static int deleteAllFiles (String id, UserIdentity user) {
        Preconditions.checkArgument(RandomId.validRandomStringId(id), "Invalid ID.");
        // Use filter, create File objects only for Strings that meet criteria.
        FilenameFilter filter = (dir, name) -> name.startsWith(id);
        int n = 0;
        try {
            for (File file : orgPath(user).toFile().listFiles(filter)) {
                LOG.info("Deleting {}", file);
                file.delete();
                // java.io.File has a lot of quirks, might be better to use NIO Path API
                // Files.delete(file.toPath());
                n += 1;
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return n;
    }

    private static Path orgPath (UserIdentity user) {
        return Path.of(BASE_PATH, user.organization);
    }

    private static Path orgPath (UserIdentity user, String objectName) {
        if (user == null) {
            // Special case: accommodate R5 components that are not aware of users and per-organization directories.
            for (String org : knownOrganizations) {
                Path path = Path.of(BASE_PATH, org, objectName);
                if (Files.exists(path)) {
                    return path;
                }
            }
            LOG.error("Object not found in any known organization directory, using library root: {}", objectName);
            return Path.of(BASE_PATH, objectName);
        }
        return Path.of(BASE_PATH, user.organization, objectName);
    }

    public static void storeMetadata (FileMetadata metadata, UserIdentity user) {
        JsonStore.storeJson(metadata, metadata.fileId + ".meta.json", user);
    }

    /// Create a temporary file to hold content until it's moved into the storage area. Besides
    /// simplifying the method signature by having only one parameter, the main purpose of this
    /// method is to keep createTempFile from throwing a checked exception.
    public static File makeTempFile (String fileId) {
        try {
            return File.createTempFile(fileId, null);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static <T> T readJsonFile (File file, Class<T> type) {
        // Be careful to use ObjectMapper from JettyUtil, not one in a similarly named dependency class like JsonUtil.
        try {
            return JettyUtil.objectMapper.readValue(file, type);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static <T> T readJsonPath (Path path, Class<T> type) {
        return readJsonFile(path.toFile(), type);
    }

    public static JsonNode readJsonTree (UserIdentity user, FileMetadata metadata) {
        Path jsonFilePath = orgPath(user, metadata.fileId);
        try {
            return JettyUtil.objectMapper.readTree(jsonFilePath.toFile());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}