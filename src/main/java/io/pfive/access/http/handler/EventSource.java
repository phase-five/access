// Copyright 2023-2025 Phase Five LLC.  For license terms, see LICENSE.txt in the repository root.

package io.pfive.access.http.handler;

import io.pfive.access.authentication.UserIdentity;
import io.pfive.access.util.JettyUtil;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.io.content.BufferedContentSink;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.thread.AutoLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.MethodHandles;
import java.nio.ByteBuffer;

import static io.pfive.access.util.JettyUtil.wrapFormat;
import static io.pfive.access.util.RandomId.createRandomStringId;

/// Holds the Jetty Response and Callback for a single held-open connection to which the server is
/// going to send events. A single user may have several of these at once if they have several tabs
/// open. It also implements Jetty Callback so it can supply itself to write operations, ensuring
/// the connection stays open. Locking is applied over the smallest reasonable critical section of
/// each write operation. Jetty's AutoLock apparently cooperates better with virtual threads, but
/// we have currently disabled virtual threads so are using standard synchronized blocks.
public class EventSource implements Callback {

    private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    private final String tabId;
    private final UserIdentity user;
    private final Response response;
    private final Callback originalCallback;

    public EventSource (UserIdentity user, Response response, Callback callback) {
        this.tabId = createRandomStringId();
        this.user = user;
        this.response = response;
        this.originalCallback = callback;
    }

    /// Send a server-sent-events "message" to the client. This is a block containing only one or
    /// more "data" lines and no event type, and terminated with a blank line.
    /// TODO handle line breaks in data string.
    void sendMessage (String data) {
        ByteBuffer bytes = JettyUtil.wrapString("data:" + data + "\n\n");
        synchronized (this) {
            response.write(false, bytes, this);
        }
    }

    /// Send an server-sent event to the client. This is a block of several lines with an "event"
    /// line and one or more "data" lines, terminated with a blank line. Lock must be held while
    /// sending all lines to avoid interleaving messages sent by different threads.
    /// TODO handle line breaks in data string.
    void sendEvent (String eventType, String data) {
        ByteBuffer bytes = JettyUtil.wrapString("event: " + eventType + "\ndata:" + data + "\n\n");
        synchronized (this) {
            response.write(false, bytes, this);
        }
    }

    /// Call immediately after a client tab establishes a connection to share its server-generated tab ID.
    void sendConnectMessage () {
        sendEvent("connect", tabId);
        LOG.debug("New client connection from {} assigned tab ID {}.", user, tabId);
    }

    /// JETTY CALLBACK IMPLEMENTATION FOR WRITES ///

    @Override
    public InvocationType getInvocationType() {
        return InvocationType.NON_BLOCKING;
    }

    @Override
    public void succeeded () {
        // Do nothing after successful write, we will be writing an endless stream of events.
        // If we signal success to the originalCallback the connection will be closed as the response is complete.
    }

    @Override
    public void failed (Throwable x) {
        LOG.debug("Event write failed on tab {}, probably due to client disconnect.", tabId);
        boolean removed = EventSourceHandler.eventSources.get(user.email)
            .removeIf(es -> (es.tabId == this.tabId));
        if (removed) {
            LOG.debug("Removed parked response for tab {}.", tabId);
        } else {
            LOG.debug("No parked response found for tab {}.", tabId);
        }
        // Should we actually call succeeded() since this is a normal path of execution?
        // This might cause further connection closure / failures.
        originalCallback.failed(x);
    }

}
