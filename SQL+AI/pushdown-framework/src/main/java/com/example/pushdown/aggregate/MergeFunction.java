package com.example.pushdown.aggregate;

import java.util.List;

/**
 * Defines how to merge partial aggregate states produced by a pushed-down
 * aggregate. The framework pushes an aggregate to the source which returns
 * partial (intermediate) states; the engine then merges them via this
 * function before {@link #finalize} produces the final user-visible value.
 *
 * <p>Semantics:
 * <ul>
 *   <li>{@link #merge} combines two intermediate states into one. Must be
 *       associative (and ideally commutative for parallel merging).</li>
 *   <li>{@link #mergeAll} folds a non-empty list of partials left-to-right.</li>
 *   <li>{@link #serialize} / {@link #deserialize} round-trip an intermediate
 *       state through bytes (for shuffle/buffer between stage 1 and the
 *       merge step).</li>
 *   <li>{@link #finalize} converts the merged intermediate state into the
 *       final user-visible result (e.g. AVG divides sum by count).</li>
 * </ul>
 */
public interface MergeFunction {

    Object merge(Object partial1, Object partial2);

    /**
     * Fold a non-empty list of partials using {@link #merge}.
     *
     * @throws IllegalArgumentException if {@code partials} is empty
     */
    default Object mergeAll(List<Object> partials) {
        if (partials.isEmpty()) {
            throw new IllegalArgumentException("Cannot merge empty partial list");
        }
        Object result = partials.get(0);
        for (int i = 1; i < partials.size(); i++) {
            result = merge(result, partials.get(i));
        }
        return result;
    }

    byte[] serialize(Object intermediateState);

    Object deserialize(byte[] data);

    Object finalize(Object intermediateState);
}
