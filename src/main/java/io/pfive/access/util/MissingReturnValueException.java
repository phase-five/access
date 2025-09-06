// Copyright 2023-2025 Phase Five LLC.  For license terms, see LICENSE.txt in the repository root.

package io.pfive.access.util;

/// Thrown when someone calls get() on an Err instead of an Ok variant of Ret. This is not
/// necessarily wrong, it's a reasonable way to expose an error message to the caller as an
/// exception.
public class MissingReturnValueException extends RuntimeException {
    public MissingReturnValueException (String message) {
        super(message);
    }
}
