// Copyright 2023-2025 Phase Five LLC.  For license terms, see LICENSE.txt in the repository root.

package io.pfive.access.background;

import com.conveyal.r5.analyst.progress.ProgressListener;

import java.io.IOException;
import java.io.InputStream;

/// We want to allow setting the expected file size in advance and reporting EOF as completed.
/// Catching and reporting errors would also be good. Can also be implemented using
/// `ProxyInputStream(final InputStream proxy, final IOConsumer<IOException> exceptionHandler)`
public class TrackedInputStream extends InputStream {

    private final InputStream inner;
    private final int expectedSize;
    private final ProgressListener progressListener;

    public TrackedInputStream (InputStream inputStream, long expectedSize, ProgressListener progressListener) {
        this.inner = inputStream;
        this.progressListener = progressListener;
        if (expectedSize > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Cannot track progress when reading files over 2GB.");
        }
        this.expectedSize = (int) expectedSize;
        progressListener.beginTask("Reading", (int) expectedSize);
    }

    @Override
    public int read () throws IOException {
        return increment(inner.read());
    }

    // When overriding, do not defer to the superclass methods as those fall back on single-character reads.
    // Wrapping implementations of read(buf, offset, len) is vastly more efficient.

    /// Increment by a number of bytes read, handling negative numbers that indicate end-of-stream.
    /// TODO catch and report exceptions and negative counts to the progress listener.
    /// TODO extend r5 progressListener to add progressListener.error() and done()
    /// Perhaps using `IntFunction` arguments.
    public int increment (int n) {
        if (n >= 0) progressListener.increment(n);
        return n;
    }

    public long increment (long n) {
        if (n > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Cannot track progress when seeking over 2GB.");
        }
        if (n >= 0) progressListener.increment((int) n);
        return n;
    }

    public byte[] increment (byte[] bytes) {
        progressListener.increment(bytes.length);
        return bytes;
    }

    @Override
    public int read (byte[] b) throws IOException {
        return increment(inner.read(b));
    }

    @Override
    public int read (byte[] b, int off, int len) throws IOException {
        return increment(inner.read(b, off, len));
    }

    @Override
    public byte[] readAllBytes () throws IOException {
        return increment(inner.readAllBytes());
    }

    @Override
    public byte[] readNBytes (int len) throws IOException {
        return increment(inner.readNBytes(len));
    }

    @Override
    public int readNBytes (byte[] b, int off, int len) throws IOException {
        return increment(inner.readNBytes(b, off, len));
    }

    @Override
    public long skip (long n) throws IOException {
        return increment(inner.skip(n));
    }

    @Override
    public void skipNBytes (long n) throws IOException {
        inner.skipNBytes(n); // Blocking.
        increment(n);
    }

    @Override
    public void close () throws IOException {
        inner.close();
    }

}
