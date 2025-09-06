// Copyright 2023-2025 Phase Five LLC.  For license terms, see LICENSE.txt in the repository root.

package io.pfive.access.sparse;

import static com.conveyal.gtfs.util.Util.human;

/// Interface for classes that can estimate memory consumption of their instances.
/// Sizes are recursive, so should only be defined on acyclic object graphs.
public interface ByteSize {

    /// Estimate the memory consumed by the instance in bytes.
    /// Implementations can use helper functions in [ByteSizeUtil].
    int byteSize ();

    /// Convenience wrapper method to return a human-readable string of the size in bytes.
    default String humanByteSize () {
        return human(byteSize());
    }

}
