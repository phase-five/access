// Copyright 2023-2025 Phase Five LLC.  For license terms, see LICENSE.txt in the repository root.

package io.pfive.access.http.exception;

/// Superclass for all exceptions representing problems occurring on an HTTP server, which can be
/// translated into HTTP response codes and messages. Throwing exceptions avoids calling Jetty
/// helper functions with relatively complicated signatures every place we want to return an error
/// from a handler. They can all be caught and dealt with in a wrapping handler. It's tempting to
/// indicate the different types of errors with an enum, so they could even be included in Ret.Err
/// and those errors mapped to HTTP exceptions when we attempt to get() them. It could be possible
/// to use both (the usual Java subclass exception types, but also give them codes that are more
/// strongly typed than ints.)
public abstract class HttpServerException extends RuntimeException {
    public HttpServerException (String message) {
        super(message);
    }
    public abstract ErrorType errorType ();
    public enum ErrorType {
        REQUEST(400),
        // INTERNAL_SERVER(500), // Internal server errors should always be unexpected, so not created intentionally.
        AUTHENTICATION (401);
        public final int httpCode;
        ErrorType (int httpCode) {
            this.httpCode = httpCode;
        }
    }
}
