// Copyright 2023-2025 Phase Five LLC.  For license terms, see LICENSE.txt in the repository root.

package io.pfive.access.http.handler;

import io.pfive.access.http.exception.HttpServerException;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.invoke.MethodHandles;

import static io.pfive.access.util.JettyUtil.respond;
import static io.pfive.access.util.JettyUtil.respondServerError;

/// This handler wraps other handlers to catch any exceptions they produce. These exceptions are
/// converted into messages in the response body with appropriate HTTP response codes. This allows
/// bailing out of simple checks and problems without yielding an ugly general-purpose 500 server
/// error page. It also avoids chains of conditional return statements passing error objects back up
/// the stack, making use of the Java exception mechanism to jump up the stack.
public class ExceptionHandler extends Handler.Wrapper {

    private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    public ExceptionHandler (Handler handler) {
        super(handler);
    }

    @Override
    public boolean handle (Request request, Response response, Callback callback) throws Exception {
        try {
            return super.handle(request, response, callback);
        } catch (HttpServerException e) {
            // This will handle the entire hierarchy of HttpServerExceptions with different HTTP
            // response codes. Their messages include some context about the type of error, so don't
            // include class name in response. These errors are expected in normal operation,
            // so don't log stack traces.
            return respond(e.errorType().httpCode, e.getMessage(), response, callback);
        } catch (Throwable t) {
            // Catch-all for other Exceptions and Throwables that are unexpected and not associated
            // with HTTP codes. Here we want to log the whole stack trace and return the exception
            // type to facilitate debugging.
            return respondServerError(condenseAndLog(t), response, callback);
        }
    }

    public static String condenseAndLog (Throwable throwable) {
        StringWriter stringWriter = new StringWriter();
        PrintWriter printWriter = new PrintWriter(stringWriter);
        throwable.printStackTrace(printWriter);
        String trace = stringWriter.toString();
        LOG.info("Reporting error in HTTP response: \n" + trace);
        return briefThrowableMessage(throwable);
    }

    /// Create a one-line message consisting of only the exception class name and its message (if
    /// any). Some exceptions may be constructed with no message so getMessage returns null.
    public static String briefThrowableMessage (Throwable throwable) {
        String message = throwable.getMessage();
        String className = throwable.getClass().getSimpleName();
        if (message == null) {
            return className;
        } else {
            return className + ": " + message;
        }
    }

}
