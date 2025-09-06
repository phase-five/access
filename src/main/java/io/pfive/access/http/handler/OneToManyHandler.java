// Copyright 2023-2025 Phase Five LLC.  For license terms, see LICENSE.txt in the repository root.

package io.pfive.access.http.handler;

import io.pfive.access.authentication.UserIdentity;
import io.pfive.access.batch.BatchJob;
import io.pfive.access.batch.BatchJobManager;
import io.pfive.access.http.model.OneToManyOptions;
import io.pfive.access.sparse.SparseOneToManyProcessor;
import io.pfive.access.util.JettyUtil;
import io.pfive.access.util.MilliTimer;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.MethodHandles;
import java.nio.ByteBuffer;

import static io.pfive.access.http.handler.EventSourceHandler.statusEvent;
import static io.pfive.access.util.JettyUtil.BBOX_HEADER_KEY;

/// Handles HTTP requests asking for one-to-many travel time and access to opportunities data.
public class OneToManyHandler extends Handler.Abstract {

    private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    private final BatchJobManager batchJobManager;

    public OneToManyHandler (BatchJobManager batchJobManager) {
        this.batchJobManager = batchJobManager;
    }

    @Override
    public boolean handle (Request request, Response response, Callback callback) throws Exception {
        final UserIdentity user = JettyUtil.extractUserIdentity(request);
        // We could deserialize the options out of a JSON request body like other handlers do.
        // However, parameters in body instead of URL or query parameters might interfere with image response caching.
        OneToManyOptions options = new OneToManyOptions(request);

        // If we have reached this point, the request seems valid.
        // We can start a batch job without thousands of worker tasks failing.
        if (options.batch) {
            batchJobManager.addJob(new BatchJob(user, options));
        }

        // TODO report progress and preload (or save) network and linkages, instead of lazy loading on demand.
        var timer = new MilliTimer();
        SparseOneToManyProcessor processor = new SparseOneToManyProcessor(options, user);
        processor.process();
        if (processor.accessBins == null) {
            return JettyUtil.respondBadRequest("No result.", response, callback);
        }
        response.getHeaders().add(HttpHeader.CONTENT_TYPE, "image/png");
        response.getHeaders().add(BBOX_HEADER_KEY, processor.getBboxHeaderValue());
        // Ensure cache hits after initial request to inspect geographic bounds headers.
        // Implementing HEAD requests could also work for this purpose.
        response.getHeaders().add(HttpHeader.CACHE_CONTROL, "private, max-age=120, immutable");
        // In principle, it's possible to stream back a chunked response without a content-length header.
        // Response.asBufferedOutputStream will buffer the whole response to get a content-length header.
        // Anecdotally, response times were also slower with writing to the Response's buffered output stream.
        // So we just manage our own ByteBuffer.
        response.write(true,  ByteBuffer.wrap(processor.pngBytes), callback);
        statusEvent(user, options.batch ? "Started batch job." : "One-to-many search finished. " + timer.getElapsedString());
        return true;
    }

}
