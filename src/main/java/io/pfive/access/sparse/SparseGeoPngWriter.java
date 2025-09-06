// Copyright 2023-2025 Phase Five LLC.  For license terms, see LICENSE.txt in the repository root.

package io.pfive.access.sparse;

import ar.com.hjg.pngj.FilterType;
import ar.com.hjg.pngj.ImageInfo;
import ar.com.hjg.pngj.ImageLineHelper;
import ar.com.hjg.pngj.ImageLineInt;
import ar.com.hjg.pngj.PngWriter;
import ar.com.hjg.pngj.chunks.PngChunkTextVar;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.util.Arrays;

/// REPLACES GeoPngWriter to use post-adapter approach native data structures.
/// This is mostly a copy of GeoPngWriter with a few targeted changes.
/// Creates a GeoPng from a TravelTimeResult, including some text chunks containing georeferencing
/// data and accessibility results. TODO: A Wgs84Extents instance is needed for the geographic info.
public class SparseGeoPngWriter {
    final int cols;
    final int rows;
    final Durations durations;
    final AccessBins accessBins;

    public SparseGeoPngWriter (int cols, int rows, Durations durations, AccessBins accessBins) {
        this.cols = cols;
        this.rows = rows;
        this.durations = durations;
        this.accessBins = accessBins;
    }

    public byte[] writeBytes () {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        streamPng(baos);
        return baos.toByteArray();
    }

    /// Clamp values to acceptable range for bytes. 255 is unreached.
    private int getVal (int[] seconds, int index) {
        int v = seconds[index]; // TODO PERCENTILE
        v /= 60;
        if (v > 120) {
            return 255;
        } else if (v < 0) {
            throw new RuntimeException("Values are expected to be non-negative");
        } else {
            return v;
        }
    }

    /// The PNGJ library appears to reverse the meaning of iTXt language tag and translated key.
    /// While the library is otherwise simple and efficient, it appears to be unmaintained.
    /// https://github.com/leonbloy/pngj This method specifies that we want uncompressed Latin1,
    /// which creates simpler tEXt chunks instead of iTXt.
    private static void addSimpleTextTag (PngWriter png, String key, String value) {
        png.getMetadata().setText(key, value, true, false);
    }

    /// Closes stream when done.
    /// With FILTER_ADAPTIVE_FAST:
    /// - HUFFMAN_ONLY gives about 850kb on Aix-Marseille travel times.
    /// - FILTERED level 9 gives 130kb in 260 ms
    /// - FILTERED level 4 gives 140kb in 210 ms
    /// - FILTERED level 0 gives 145kb in 220 ms
    /// - DEFAULT_STRATEGY level 9 gives 130kb in 280 ms
    /// - DEFAULT_STRATEGY level 5 gives 100-150kb in 210 ms
    /// - DEFAULT_STRATEGY level 1 gives 160kb in 200 ms
    /// - DEFAULT_STRATEGY level 0 (no compression) gives 6MB in 200 ms
    /// - DEFAULT_STRATEGY default level gives 130kb in 210-290 ms
    /// - FILTER_ADAPTIVE_FULL adds 80 ms without improving file sizes.
    public void streamPng (OutputStream outputStream) {
        ImageInfo imi = new ImageInfo(cols, rows, 8, false); // 8 bits per channel, no alpha
        PngWriter png = new PngWriter(outputStream, imi);
        png.setFilterType(FilterType.FILTER_ADAPTIVE_FAST);
        png.setCompLevel(4);
        // png.getPixelsWriter().setDeflaterStrategy(HUFFMAN_ONLY);
        // png.setFilterType(FilterType.FILTER_PAETH);
        // png.setCompLevel(BEST_SPEED);
        png.getMetadata().setDpi(100.0);
        png.getMetadata().setTimeNow(0); // 0 seconds from now = now

        addSimpleTextTag(png, PngChunkTextVar.KEY_Title, "Travel time raster");
        addSimpleTextTag(png, "CRS", "WGS84");
        addSimpleTextTag(png, "minX", "0");
        addSimpleTextTag(png, "minY", "0");
        addSimpleTextTag(png, "maxX", "0");
        addSimpleTextTag(png, "maxY", "0");

        // Including these access values within the PNG may also slow down encoding / decoding a bit and add latency
        // by not allowing the image and charts to be fetched and built in parallel.
        // To see a list of tTxt and zTxt chunks: pngcheck -c -v -t ~/Downloads/otm.png

        // Use Latin-1 charset because PNGJ library does not support compressed UTF-8 iTXt chunks.
        // We don't yet need any characters outside Latin-1 for our JSON metadata which is only brackets and numbers.
        // Conveniently, the Java string representation of integer arrays is readable as JSON.
        // png.getMetadata().setText("access", ACCESS_STRING, true, true);
        // png.getMetadata().setText("density", DENSITY_STRING, true, true);
        {
            // TODO SWITCH TO FLOATS
            int[][][] densityArray = new int[][][] {accessBins.density()};
            int[][][] accessArray = new int[][][] {accessBins.cumulative()};
            int[][][] dualArray = new int[][][] {accessBins.dual()};
            png.getMetadata().setText("access", Arrays.deepToString(accessArray), true, true);
            png.getMetadata().setText("density", Arrays.deepToString(densityArray), true, true);
            png.getMetadata().setText("dual", Arrays.deepToString(dualArray), true, true);
        }
        // ImageLineInt appears to be reusable for successive rows.
        ImageLineInt iline = new ImageLineInt(imi);
        for (int row = rows - 1; row >= 0; row--) {
            for (int col = 0; col < cols; col++) {
                int i = row * cols + col;
                int r = getVal(durations.min, i);
                int g = getVal(durations.avg, i);
                int b = getVal(durations.max, i);
                ImageLineHelper.setPixelRGB8(iline, col, r, g, b);
            }
            png.writeRow(iline);
        }
        png.end(); // Closes the OutputStream wrapped by the PngWriter.
    }

}
