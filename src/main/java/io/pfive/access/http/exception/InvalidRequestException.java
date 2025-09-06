// Copyright 2023-2025 Phase Five LLC.  For license terms, see LICENSE.txt in the repository root.

package io.pfive.access.http.exception;

/// Throw this to bail out of the request and respond with a "400 Bad Request" code and the given message.
public class InvalidRequestException extends HttpServerException {
    public InvalidRequestException (String message) {
        super("Invalid request: " + message);
    }

    @Override
    public ErrorType errorType () {
        return ErrorType.REQUEST;
    }

}
