// Copyright 2023-2025 Phase Five LLC.  For license terms, see LICENSE.txt in the repository root.

package io.pfive.access.util;

import java.util.function.Function;

/// The return value of an operation, containing either a result or an error.
///
/// Java has Optional and Either. The former is just like null in that it doesn't tell you why the
/// object is not present (failure or success with no object). The latter depends on the order (left
/// and right) so is not typesafe. We may want to have an Error super type, the most basic of which
/// just holds a string. But may also want to accommodate exceptions as error types. We also need an
/// equivalent of the ? operator in Rust, to propagate the error result to the caller. If all
/// references were just type Error this would be simpler.
///
/// Ok and Err are subclasses of abstract Ret as this avoids having an empty field in every
/// instance. I suppose this is trading off polymorphic virtual method calls against size of extra
/// fields. I'm not sure if the JVM can de-virtualize this in JIT compilation. De-virtualization may
/// only apply to monomorphic call sites. Or it may be able to inline both method bodies and choose
/// between them without even creating the object.
///
/// https://news.ycombinator.com/item?id=10725903 As oddly inefficient as Java often is, the JVM JIT
/// is really impressive in dynamic optimization, de-optimization, and re-optimization. If the
/// language just gets more compact compound value types this will be an amazing tool.
///
/// There used to be a special HttpErr subclass that allowed triggering more specific exceptions
/// within an HTTP server. This coupled the function returning it too tightly to one context of
/// usage, so it was replaced with getOrThrow().
public abstract class Ret<T> {

    public boolean isErr () {
        return false;
    }

    public boolean isOk () {
        return false;
    }

    /// If the return value is Ok, returns the value. If it is an error, throw an exception.
    public final T get () {
        return getOrThrow(MissingReturnValueException::new);
    }

    public abstract T getOrThrow (Function<String, RuntimeException> exceptionFactory);

    /// Returns the error message. Will throw an exception if a return value is present instead of error.
    public abstract String errorMessage ();


    // Convenience factory methods to allow static imports and creating return values without using the 'new' operator.
    // That is: return ok(x); or return err("Description"); rather than return new Ret.Ok(x);

    public static <T> Ok ok (T result) {
        return new Ok(result);
    }

    // We might want a variant of this that holds on to an exception instance.
    public static Err err (String message) {
        return new Err(message);
    }

    /// Create a new instance of an Err (or Ok), generally to propagate errors up the stack as in
    /// the Rust ? operator. This is necessary where the calling function has a different parametric
    /// type of Ret than its callee.
    public abstract Ret propagate ();

    // Is it possible to do this by casting the same error to avoid copies?
    // public <X> Ret propagateCast () { return (Ret<X>) this; }

    /// An Ok or Err instance should never wrap a null reference. The whole point is to eliminate
    /// null references.
    private static void checkNotNull (Object o) {
        if (o == null) {
            throw new IllegalArgumentException("Supplied result or error must not be null.");
        }
    }

    // Java already defines an Error type, which essentially means "very bad exception you should not catch".
    // Use a different name (Err), scoped as an inner class of Ret to avoid confusion with this Error type.

    /// When a Result of an operation is failure, an Err instance must be provided to explain why.
    /// Subclasses could be used to distinguish between kinds of errors. As a wrapper this creates
    /// indirection and tiny wrapper classes, but extending String seems like a bad idea (and is not
    /// possible because it's a final class). It might be possible to work with CharSequence
    /// instead. This is parameterized T only to match the method signature of get(), even though T
    /// is never used due to throw.
    public static class Err<T> extends Ret<T> {
        public final String message; // Cannot be null.
        public Err (String message) {
            checkNotNull(message);
            this.message = message;
        }

        @Override
        public boolean isErr () {
            return true;
        }

        @Override
        public Ret propagate () {
            return new Err(this.message);
        }

        @Override
        public T getOrThrow (Function<String, RuntimeException> exceptionFactory) {
            throw exceptionFactory.apply(message);
        }

        @Override
        public String errorMessage () {
            return message;
        }

        @Override
        public String toString () {
            return "Err<%s>".formatted(message);
        }
    }

    public static class Ok<T> extends Ret<T> {
        public final T result; // Cannot be null.
        public Ok (T result) {
            checkNotNull(result);
            this.result = result;
        }

        @Override
        public boolean isOk () {
            return true;
        }

        @Override
        public Ret propagate () {
            // Propagating success is not likely to be widely used, but should allow appropriate type checking errors.
            return new Ok(result);
        }

        @Override
        public T getOrThrow (Function<String, RuntimeException> exceptionFactory) {
            return result;
        }

        @Override
        public String errorMessage () {
            throw new IllegalStateException("This is not an error, so has no error message.");
        }

        @Override
        public String toString () {
            return "Ok<%s>".formatted(result.toString());
        }
    }

}
