// Copyright 2023-2025 Phase Five LLC.  For license terms, see LICENSE.txt in the repository root.

package io.pfive.access.sparse;

import java.util.Arrays;
import java.util.function.ToIntFunction;

/// Various static helper functions to assist in computing the memory consumption of class instances.
/// Typically used by implementations of the [ByteSize] interface.
public abstract class ByteSizeUtil {

    public static final int OBJECT_BYTES = 16;
    public static final int ARRAY_BYTES = OBJECT_BYTES + 8;
    public static final int OBJECT_REFERENCE_BYTES = 6;

    public static int intArrayFieldBytes (int[] array) {
        if (array == null) return OBJECT_REFERENCE_BYTES;
        return OBJECT_REFERENCE_BYTES + intArrayBytes(array);
    }

    public static int floatArrayFieldBytes (float[] array) {
        if (array == null) return OBJECT_REFERENCE_BYTES;
        return OBJECT_REFERENCE_BYTES + floatArrayBytes(array);
    }

    public static <T extends ByteSize> int objectArrayFieldBytes (T[] array) {
        return objectArrayFieldBytes(array, T::byteSize);
    }

    public static <T> int objectArrayFieldBytes (T[] array, ToIntFunction<T> byteSizeEstimator) {
        if (array == null) return OBJECT_REFERENCE_BYTES;
        else return OBJECT_REFERENCE_BYTES + objectArrayBytes(array, byteSizeEstimator);
    }

    public static <T> int objectArrayBytes (T[] array, ToIntFunction<T> byteSizeEstimator) {
        int bytes = ARRAY_BYTES + array.length * OBJECT_REFERENCE_BYTES;
        for (T item : array) if (item != null) bytes += byteSizeEstimator.applyAsInt(item);
        return bytes;
    }

    public static <T extends ByteSize> int objectArrayBytes (T[] array) {
        return objectArrayBytes(array, T::byteSize);
    }

    public static int intArrayBytes (int[] array) {
        return array.length * Integer.BYTES + ARRAY_BYTES;
    }

    public static int floatArrayBytes (float[] array) {
        return array.length * Float.BYTES + ARRAY_BYTES;
    }

    /// Returns the number of elements that are not Integer.MAX_VALUE which we use as a stand-in
    /// for Double.POSITIVE_INFINITY. Serves as an indicator of how much space is "wasted" in a
    /// data structure over a packed implementation. AKA load factor, fill factor, or fill ratio.
    public static double proportionNonMax (int[] array) {
        return nNonMax(array) / (double) array.length;
    }

    public static int percentNonMax (int[] array) {
        return (int) (proportionNonMax(array) * 100.0);
    }

    public static int nNonMax (int[] durations) {
        return (int) Arrays.stream(durations).filter(d -> d < Integer.MAX_VALUE).count();
    }

    public static double proportionFinite (float[] array) {
        double nFinite = 0;
        for (float f : array) if (Float.isFinite(f)) nFinite += 1;
        return nFinite / array.length;
    }

    public static <T> double proportionNonNull (T[] array) {
        int nNonNull = 0;
        for (var item : array) if (item != null) nNonNull += 1;
        return nNonNull / (double) array.length;
    }

    public static <T> int percentNonNull (T[] array) {
        return (int) (proportionNonNull(array) * 100.0);
    }
}
