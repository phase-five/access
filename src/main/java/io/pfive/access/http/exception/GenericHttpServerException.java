// Copyright 2023-2025 Phase Five LLC.  For license terms, see LICENSE.txt in the repository root.

package io.pfive.access.http.exception;

/// This allows for automated mapping of missing return values to appropriate HTTP response codes.
public class GenericHttpServerException extends HttpServerException {

    ErrorType errorType;

    public GenericHttpServerException (String message, ErrorType errorType) {
        super(errorType.name() + ": " + message);
        this.errorType = errorType;
    }

    @Override
    public ErrorType errorType () {
        return errorType;
    }
}
