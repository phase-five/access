// Copyright 2023-2025 Phase Five LLC.  For license terms, see LICENSE.txt in the repository root.

package io.pfive.access.util;

import java.util.HexFormat;
import java.util.UUID;

public abstract class RandomId {
    /// This ID should not be case-sensitive in case it is used as a filename on a filesystem that
    /// is not case-sensitive. This is why the method is distinct from authentication tokens where
    /// we're trying to pack in maximum entropy. It might be nice to make these more human-readable
    /// (in small blocks and pronounceable).
    public static String createRandomStringId () {
        return UUID.randomUUID().toString().replaceAll("-", "");
    }

    public static boolean validRandomStringId (String id) {
        if (id == null || id.length() != 32) return false;
        for (int i = 0; i < id.length(); i++) {
            if (!HexFormat.isHexDigit(id.charAt(i))) return false;
        }
        return true;
    }
}
