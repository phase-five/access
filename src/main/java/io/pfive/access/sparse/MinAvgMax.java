// Copyright 2023-2025 Phase Five LLC.  For license terms, see LICENSE.txt in the repository root.

package io.pfive.access.sparse;

/// This class holds N parallel arrays and performs repetitive operations on them independently.
/// It factors out certain repetitive loops and type specifications that cluttered classes carrying
/// data through stages of a tile-processing pipeline.
///
/// Like TransitTimes, Durations and AccessBins are two or three dimensional with multiple
/// percentiles and maybe multiple destination sets. The logic to apply any operations
/// independently across all the axes are supplied in this base class, as long as they are all
/// using the same primitive types (int or float).
///
/// This is arguably a stylistic choice, but seems to significantly simplify expressing the
/// calculations across percentiles or a fixed set of min/avg/max fields. This base class may be
/// named MinAvgMax, MinMeanMax, or Percentiles in the future and will be initialized with varying
/// array lengths (constant or dynamic according to the subclass).
///
/// In practice TransitTimes are not simple MinAvgMax because travel times for every departure
/// minute must be propagated to the destination cells separately (but only for the non-extrema).
/// They may eventually be MinAvgMax with an extra field for original per-departure-minute data.
///
/// Eventually if we don't allow arbitrary length arrays of percentiles but stick with min/avg/max,
/// we may want to nest MinAvgMax instances for different opportunity types in another array.
///
/// This was first implemented with the operator defined on the MinAvgMax class, but was refactored
/// to have Operator, Producer, and Consumer inner interfaces. Classes implementing Operator define
/// a family of operators from one MinAvgMax subclass to anther. Defining these transforms in the
/// MinAvgMax itself makes it impossible to terminate the chain of transformations (every MinAvgMax
/// type has to provide a mapping into another such type, not a final output type).
public abstract class MinAvgMax {
    public final int[] min;
    public final int[] avg;
    public final int[] max;

    public MinAvgMax (int[] min, int[] avg, int[] max) {
        this.min = min;
        this.avg = avg;
        this.max = max;
    }

    /// Perform an element-wise add of the other MinAvgMax instance into this one. The  type bounds
    /// are not as tight as they should be, but parameterizing subclasses with their own names is
    /// a bit ridiculous.
    /// NOTE: This method mutates the instance rather than creating a new one, as an optimization
    /// for tight inner loops hit billions of times in many-to-many analyses. It is possible that
    /// this would work just as well as a pure function creating new immutable instances.
    public void accumulate (MinAvgMax other) {
        accumulateOne(other.min, this.min);
        accumulateOne(other.avg, this.avg);
        accumulateOne(other.max, this.max);
    }

    /// Null-safe element wise addition of src elements into corresponding dest elements.
    private static void accumulateOne (int[] src, int[] dest) {
        if (src != null && dest != null) for (int i = 0; i < src.length; i++) dest[i] += src[i];
    }

    /// Destructively merge b into a. Non-null values always supersede null ones.
    /// Destructive merge (as opposed to returning a new instance) avoids unnecessary creation of
    /// large arrays, and is typically used in cases where the input arrays are discarded after merging.
    /// No, destructive merging is not possible because either parameter may be null, so there's nothing
    /// to return into. We need value classes.
    // public static <T extends MinAvgMax> T mergeMin (T a, T b) {
    //     if (a == null) return b;
    //     if (b == null) return a;
    //     return a.create(mergeMin(a.min, b.min), mergeMin(a.avg, b.avg), mergeMin(a.max, b.max));
    // }

    /// Defines a family of operators that map elements of one MinAvgMax subtype T to elements of
    /// another MinAvgMax subtype U. Practically this is used to evaluate street vertex times with
    /// connectors, evaluate transit stop times with egress tables, then combine the resulting
    /// street and transit durations with opportunity counts into access tables.
    public interface Operator<T extends MinAvgMax, U extends MinAvgMax> {
        /// Create a new instance of the result type. Works around Java type erasure, but also
        /// provides a mechanism to augment instances with information other than the raw int arrays.
        U createResult (int[] min, int[] avg, int[] max);

        /// Return null to indicate cases where no results will affect downstream computation.
        /// The min, avg, and max fields of T are transformed independently of one another by
        /// applying this function, with the exception that if a min or avg field evaluates
        /// to null, computation short-circuits and all higher fields will also be left null.
        int[] applyOne (int[] intArray);

        /// This method is used to apply a full-array transform to each of three fields independently.
        /// Return null if the output contains no values that affect downstream computation.
        /// Individual fields (avg and max) may also be set to null to skip travel time propagation
        /// and indicator construction. It might be better if this were more typesafe, with three
        /// Durations fields in a `MultiDurations extends MinAvgMax<Durations>` and this method
        /// mapping from Durations to Access, and used repeatedly by a method mapping MultiDurations
        /// to MultiAccessBins. It should eventually iterate over additional fields or dimensions,
        /// for example several sets of opportunity density data, each with their own N percentiles
        /// of travel time.
        default U apply (T in) {
            if (in == null) return null;
            int[] min = applyOne(in.min);
            // If min will have no effect, entire structure will have no effect downstream.
            if (min == null) return null;
            int[] avg = applyOne(in.avg);
            // If avg will have no effect, max will also have no effect downstream.
            int[] max = (avg == null) ? null : applyOne(in.max);
            return createResult(min, avg, max);
        }
    }

    /// Interface for functions that produce a new MinAvgMax instance from some other type.
    @FunctionalInterface
    public interface Producer<T, U extends MinAvgMax> {
        U eval (T argument);
    }

    /// Interface for functions that consume a MinAvgMax instance and yield some other type.
    @FunctionalInterface
    public interface Consumer<T extends MinAvgMax, U> {
        U eval (T argument);
    }
}
