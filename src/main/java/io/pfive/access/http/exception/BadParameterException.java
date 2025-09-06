// Copyright 2023-2025 Phase Five LLC.  For license terms, see LICENSE.txt in the repository root.

package io.pfive.access.http.exception;

public class BadParameterException extends InvalidRequestException {
    public BadParameterException (String parameterName, String parameterValue) {
        super(String.format("Bad parameter name/value: '%s'='%s'", parameterName, parameterValue));
    }
}
