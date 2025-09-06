// Copyright 2023-2025 Phase Five LLC.  For license terms, see LICENSE.txt in the repository root.

package io.pfive.access.util;

/// The return value of an operation, containing either a result or an error.
///
/// Java has Optional and Either. The former is just like null in that it doesn't tell you why the
/// object is not present (failure or success with no object). The latter depends on the order (left
/// and right) so is not typesafe. We may want to have an Error super type, the most basic of which
/// just holds a string. But may also want to accommodate exceptions as error types. We also need an
/// equivalent of the ? operator in Rust, to propagate the error result to the caller. If all
/// references were just type Error this would be simpler.
///
/// This is the monomorphic, two-field implementation without virtual method calls. See Ret for a
/// polymorphic alternative. This could share an interface or abstract superclass with Ret, and the
/// JVM would probably devirtualize it.
public class Result<T> {

    public final T result;
    public final Err error;

    /// Private constructor, to be called only by factory methods that impose constraints (only one
    /// of result or error may be set at a time.
    private Result (T result, Err error) {
        this.result = result;
        this.error = error;
    }

    public static <T> Result ok (T result) {
        checkNotNull(result);
        return new Result(result, null);
    }

    public static Result err (Err error) {
        checkNotNull(error);
        return new Result(null, error);
    }

    public static Result err (String message) {
        return err(new Err(message));
    }

    public boolean isErr () {
        return error != null;
    }

    public boolean isOk () {
        return result != null;
    }

    public void throwIfError () {
        if (isErr()) {
            throw new RuntimeException(error.message);
        }
    }

    /// Create a new instance to propagate error up the stack, since the calling function will often
    /// have different parametric types.
    public Result propagate () {
        return Result.err(this.error);
    }

    // Possible to return a cast of the same error to avoid copies?
    public <X> Result propagateCast () {
        return (Result<X>) this;
    }

    private static void checkNotNull (Object o) {
        if (o == null) {
            throw new IllegalArgumentException("Result value must not be null.");
        }
    }

    // Java already defines an Error type which is a "very bad exception you should not catch".
    // Use a different name, scoped within Result to avoid confusion.

    /// When a Result of an operation is failure, an Error instance must be provided to explain why.
    /// Subclasses can be used to distinguish between kinds of errors. As a wrapper this creates
    /// indirection and tiny wrapper classes, but extending String seems like a bad idea (and is not
    /// possible because it's a final class). You could somehow work with CharSequence instead.
    private static class Err {
        public final String message;
        public Err (String message) {
            this.message = message;
        }
    }


}
