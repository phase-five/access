// Copyright 2023-2025 Phase Five LLC.  For license terms, see LICENSE.txt in the repository root.

package io.pfive.access.store;

import io.pfive.access.filepool.FileMetadata;

import java.nio.file.Path;

public record FileWithMetadata(Path path, FileMetadata fileMetadata) {

}
