// Copyright 2023-2025 Phase Five LLC.  For license terms, see LICENSE.txt in the repository root.

package io.pfive.access.sparse;

import ch.qos.logback.core.AppenderBase;

import static io.pfive.access.http.handler.EventSourceHandler.sendEventToAllUsers;

/// A Logback appender that sends all log messages to all web clients.
/// This reveals a lot of activity in code that doesn't do progress callbacks.
/// TODO keep a scrollback buffer and allow fetching over HTTP for web UI status page.
public class ServerSentEventLogAppender<E> extends AppenderBase<E> {
    @Override
    protected void append (E event) {
        // TODO introduce new "log" message type.
        sendEventToAllUsers("status", event.toString());
    }
}
