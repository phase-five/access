// Copyright 2023-2025 Phase Five LLC.  For license terms, see LICENSE.txt in the repository root.

package io.pfive.access.http.exception;

/// Auth is intentionally ambiguous between authentication and authorization, because HTTP uses the
/// word authorization in its headers when it really means authentication.
public class AuthException extends HttpServerException {
    public AuthException (String message) {
        super("Authentication: " + message);
    }

    @Override
    public ErrorType errorType () {
        return ErrorType.AUTHENTICATION;
    }

}
