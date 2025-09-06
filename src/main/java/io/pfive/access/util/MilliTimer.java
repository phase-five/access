// Copyright 2023-2025 Phase Five LLC.  For license terms, see LICENSE.txt in the repository root.

package io.pfive.access.util;

public class MilliTimer {
    private long startTime = System.currentTimeMillis();

    public long getElapsedMillis () {
        return System.currentTimeMillis() - startTime;
    }

    public String getElapsedString () {
        return String.format("%5.3f sec", getElapsedMillis() / 1000.0D);
    }
}
