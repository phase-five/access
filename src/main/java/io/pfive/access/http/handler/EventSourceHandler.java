// Copyright 2023-2025 Phase Five LLC.  For license terms, see LICENSE.txt in the repository root.

package io.pfive.access.http.handler;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;
import com.google.common.collect.Multimaps;
import io.pfive.access.authentication.UserIdentity;
import io.pfive.access.util.JettyUtil;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.MethodHandles;
import java.util.List;
import java.util.Map;

/// This Jetty HTTP handler provides Server-Sent Events (SSE), a standard approach to server-push
/// messaging that is simple, text-based, and unidirectional. See:
/// https://html.spec.whatwg.org/multipage/server-sent-events.html
/// Whenever the client navigates to a new page, closes, or reloads, the connection will be closed
/// by the client. TODO Investigate how this interacts with persistent/keepalive connections.
public class EventSourceHandler extends Handler.Abstract {

    private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    private static String EVENT_STREAM_CONTENT_TYPE = "text/event-stream";

    /// A Multimap from username to N EventSource instances, each representing a held-open
    /// connection from a browser tab. Thread-safety requirements: Both Jetty async callbacks and
    /// virtual threads map to multiple OS threads (not one). So this map must tolerate reads,
    /// writes, and removals from multiple concurrent threads.
    public static Multimap<String, EventSource> eventSources = Multimaps.synchronizedListMultimap(
          ArrayListMultimap.create());

    @Override
    public boolean handle (Request request, Response response, Callback callback) throws Exception {
        UserIdentity user = JettyUtil.extractUserIdentity(request);
        response.getHeaders().add(HttpHeader.CONTENT_TYPE, EVENT_STREAM_CONTENT_TYPE);
        response.setStatus(HttpStatus.OK_200);
        EventSource eventSource = new EventSource(user, response, callback);
        eventSources.put(user.email, eventSource);
        eventSource.sendConnectMessage();
        // Inform Jetty we are handling this request and will eventually invoke
        // callback.succeeded() or callback.failed().
        return true;
    }

    /// Best effort, no real error handling or delivery guarantees. A concurrent modification
    /// exception may occur here if a write fails (e.g due to client disconnect) and the callback
    /// that removes the EventSource is not deferred by Jetty, but occurs synchronously within this
    /// loop. We make a protective copy to avoid concurrent modification. This whole process is not
    /// as efficient as it could be, because the event Strings are repeatedly generated and
    /// converted to UTF-8 for each EventSource, and those steps are interleaved with locked writes.
    /// It would be slightly more efficient to prepare the event once as a ByteBuffer, then submit
    /// it in a loop to each EventSource. But this would be a micro-optimization for an edge case
    /// where users have very many tabs open. But: this could also allow for methods that serialize
    /// objects as JSON direct to ByteBuffers rather than Strings.
    /// Previously, in order to ensure messages went out immediately we were writing
    /// BufferedContentSink.FLUSH_BUFFER as in ContentSinkOutputStream.flush. This seemed to be
    /// causing errors in the browser-side EventSource. Although Response is a Content.Sink, it's
    /// not clear whether its's a BufferedContentSink. It's possible the write of the entire
    /// ByteBuffer occurs immediately.
    public static void sendEvent (UserIdentity user, String eventType, String data) {
        for (EventSource es : List.copyOf(eventSources.get(user.email))) {
            es.sendEvent(eventType, data);
        }
    }

    public static void sendEventToAllUsers (String eventType, String data) {
        for (EventSource es : List.copyOf(eventSources.values())) {
            es.sendEvent(eventType, data);
        }
    }

    public static void statusEvent (UserIdentity user, String status) {
        sendEvent(user, "status", status);
    }

    public static void sendMapAsEvent (UserIdentity user, String messageType, Map<String, Object> map) {
        sendJsonEvent(user, messageType, map);
    }

    public static void sendJsonEvent (UserIdentity user, String messageType, Object object) {
        try {
            String json = JettyUtil.objectMapper.writeValueAsString(object);
            EventSourceHandler.sendEvent(user, messageType, json);
        } catch (JsonProcessingException e) {
            LOG.error("Sending JSON event failed.", e);
        }
    }
}
