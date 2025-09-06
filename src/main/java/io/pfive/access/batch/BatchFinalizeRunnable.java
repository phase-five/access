// Copyright 2023-2025 Phase Five LLC.  For license terms, see LICENSE.txt in the repository root.

package io.pfive.access.batch;

import io.pfive.access.authentication.UserIdentity;
import io.pfive.access.background.ProgressSink;
import io.pfive.access.filepool.FileMetadata;
import io.pfive.access.filepool.FileType;
import io.pfive.access.store.JsonStore;
import org.geotools.coverage.grid.GridCoverageFactory;
import org.geotools.coverage.grid.io.AbstractGridFormat;
import org.geotools.gce.geotiff.GeoTiffFormat;
import org.geotools.gce.geotiff.GeoTiffWriteParams;
import org.geotools.gce.geotiff.GeoTiffWriter;
import org.opengis.coverage.grid.GridCoverage;
import org.opengis.parameter.GeneralParameterValue;
import org.opengis.parameter.ParameterValueGroup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import javax.media.jai.RasterFactory;
import java.awt.image.BufferedImage;
import java.awt.image.DataBuffer;
import java.awt.image.DataBufferInt;
import java.awt.image.WritableRaster;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.invoke.MethodHandles;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static io.pfive.access.sparse.SparseOneToManyProcessor.STANDARD_CUTOFFS;
import static io.pfive.access.sparse.SparseOneToManyProcessor.STANDARD_PERCENTILES;

/// Given a completed batch job and its results, save the results in several formats (png, tiff).
/// and save metadata about that job. We may want to save metadata earlier in the process so we can
/// monitor partially completed analyses, though if we allow only one batch running at a time we can
/// easily monitor without having the partially completed analysis in the list of batches.]
/// TODO rename to something other than Finalize (Complete) to avoid confusion
public class BatchFinalizeRunnable implements Runnable {

    private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
    private static final int N_PROGRESS_STEPS = STANDARD_PERCENTILES.length * STANDARD_CUTOFFS.length * 2 + 1;

    private final BatchJob job;
    private final BatchJobResults results;
    private final UserIdentity user;
    private final ProgressSink progress;

    public BatchFinalizeRunnable (BatchJob job, BatchJobResults results) {
        this.job = job;
        this.results = results;
        this.user = job.user;
        // Batch job and file saving are using the same ID, maybe better to keep separate.
        // The visual effect of one replacing the other is nice though.
        this.progress = new ProgressSink(job.id, user);
    }

    @Override
    public void run () {
        try {
            progress.beginTask("Writing batch results files", N_PROGRESS_STEPS);
            writeImages();
            writeMetadata();
            // State may only transition to IN_PROGRESS from COMPLETED.
            // progress.completed();
            LOG.info("Batch job completed.");
        } catch (Throwable throwable) {
            final String errorMessage = "Error while saving batch results.";
            progress.error(errorMessage);
            LOG.error(errorMessage, throwable);
        }
    }

    /// Each batch results entry has at least 10 auxiliary files for combinations of percentiles and
    /// travel time cutoffs. It may be a good idea to store them in a different directory than the
    /// main JsonStore. Even if the original R5 output is retained to produce images on demand, this
    /// still requires one file per percentile. Three files or ten produce similar problems.
    private void writeImages() throws Exception {
        final String DEST_NAME_TEMPLATE = "%s_P%d_C%d.%s";
        File tempTiffZip = JsonStore.makeTempFile(job.id);
        int width = job.originCells.nCellsWide();
        int height = job.originCells.nCellsHigh();
        // Write multiple files to a ZipOutputStream. Another option is the ZipFileSystem provider.
        try (ZipOutputStream tiffZipOut = new ZipOutputStream(new FileOutputStream(tempTiffZip))) {
            for (int ip = 0; ip < STANDARD_PERCENTILES.length; ip++) {
                for (int ic = 0; ic < STANDARD_CUTOFFS.length; ic++) {
                    int p = STANDARD_PERCENTILES[ip];
                    int c = STANDARD_CUTOFFS[ic];
                    String tiffName = DEST_NAME_TEMPLATE.formatted(job.id, p, c, "tiff");
                    String pngName = DEST_NAME_TEMPLATE.formatted(job.id, p, c, "png");
                    int[][] access = results.extractAccess(width, height, ip, ic);
                    File tempPngFile = JsonStore.makeTempFile(job.id);
                    tiffZipOut.putNextEntry(new ZipEntry(tiffName));
                    writePng(access, tempPngFile);
                    writeGeoTiff(access, tiffZipOut);
                    // TODO also try writing GeoPackage from GridCoverage as for GeoTiff?
                    JsonStore.storeFile(tempPngFile.toPath(), pngName, user);
                }
            }
        }
        JsonStore.storeFile(tempTiffZip.toPath(), job.id + ".geotiffs.zip", user);
    }

    /// Make a PNG file with the given accessibility values coded into the RGB channels.
    /// This can be used directly for raster display on a web map, but is in any case a good way to
    /// store or transmit the information compactly and losslessly using PNG compression concepts.
    /// Image TYPE_INT_RGB is packed into ints without alpha. It should be able to represent all
    /// accessibility values up to ~17 million, as the lower three bytes of our access indicator
    /// values will fit into R G and B (from most to least significant). Using type RGBA will yield
    /// a fully transparent image because alpha is stored in the most significant byte (layout is
    /// actually ARGB) and the most significant bits of our indicator value are usually zero.
    /// The Javadoc on DirectColorModel (mentioned in BufferedImage.TYPE_INT_RGB) says is defaults
    /// to an sRGB color space with samples packed into a single 32-bit int in this order: 0xAARRGGBB.
    /// You can apparently also make a BufferedImage out of a WritableRaster. This might allow using
    /// the same raster created for GeoTiff when writing PNG.
    /// TODO geolocation tags. Consult code that writes GeoPNG from single-origin analysis.
    /// @param values accessibility in (y, x) axis order with y increasing toward south.
    private void writePng (int[][] values, File file) throws Exception {
        int width = job.originCells.nCellsWide();
        int height = job.originCells.nCellsHigh();
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        int[] pixels = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                // Incoming values use (y, x) axis order with y axis increasing toward the south.
                // Image pixels have x increasing faster than y, which groups pixels by rows.
                pixels[y * width + x] = values[y][x];
            }
        }
        try (OutputStream pngOut = new BufferedOutputStream(new FileOutputStream(file))) {
            ImageIO.write(image, "png", pngOut);
        }
        progress.increment();
    }

    /// Write a GeoTiff file containing the given accessibility values and geographic location tags.
    /// Writes are performed to the supplied OutputStream without closing it. This allows packing
    /// many GeoTiff files into a single archive.
    /// TODO Try adding percentiles and cutoffs as multiple bands all in the same GeoTIFF.
    /// @param values accessibility in (y, x) axis order with y increasing toward south.
    private void writeGeoTiff (int[][] values, OutputStream outStream) throws IOException {
        int width = job.originCells.nCellsWide();
        int height = job.originCells.nCellsHigh();
        // Based on GridCoverage.create(name, matrix, envelope) as example of creating GridCoverage
        final WritableRaster raster = RasterFactory.createBandedRaster(
              DataBuffer.TYPE_INT, width, height, 1, null
        );
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                raster.setSample(x, y, 0, values[y][x]);
            }
        }
        var gcf = new GridCoverageFactory();
        var referencedEnvelope = job.originCells.wgsBounds().toReferencedEnvelope();
        GridCoverage coverage = gcf.create("BATCH", raster, referencedEnvelope);
        var params = new GeoTiffWriteParams();
        params.setCompressionMode(GeoTiffWriteParams.MODE_EXPLICIT);
        params.setCompressionType("LZW");
        ParameterValueGroup paramGroup = new GeoTiffFormat().getWriteParameters();
        paramGroup.parameter(AbstractGridFormat.GEOTOOLS_WRITE_PARAMS.getName().toString()).setValue(params);
        GeoTiffWriter writer = new GeoTiffWriter(outStream);
        writer.write(coverage, paramGroup.values().toArray(new GeneralParameterValue[1]));
        writer.dispose();
        // DO NOT CLOSE OUTPUT STREAM. We are writing these one after another to a ZIP output stream.
        progress.increment();
    }

    /// Store metadata JSON for the collection of batch outputs.
    private void writeMetadata () {
        FileMetadata metadata = new FileMetadata(job.id, FileType.BATCH);
        // TODO derive name from network and destination names
        String name = "Batch " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        metadata.name = name;
        metadata.addSource(FileType.NETWORK, job.options.networkId);
        // NOTE in the future this may not always be a grid, it might be non-gridded points.
        metadata.addSource(FileType.GRID, job.options.destinationId);
        metadata.wgsBounds = job.origins.grid().wgsBounds();
        JsonStore.storeMetadata(metadata, user);
        progress.increment();
    }

}
