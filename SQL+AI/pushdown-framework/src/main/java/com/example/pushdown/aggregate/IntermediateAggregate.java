package com.example.pushdown.aggregate;

import com.example.pushdown.expression.FunctionSignature;
import com.example.pushdown.type.Type;

/**
 * Describes one aggregate that has been pushed down to a source as a
 * partial / intermediate computation.
 *
 * <ul>
 *   <li>{@link #originalAggregate()} is the user-facing {@link FunctionSignature}
 *       (e.g. {@code AVG(integer) -> double}).</li>
 *   <li>{@link #intermediateType()} is the {@link Type} of the partial state
 *       returned by the source (e.g. for {@code AVG} the source emits both a
 *       sum and a count, so this is {@link Type#UNKNOWN} when the framework
 *       treats the carrier as opaque).</li>
 *   <li>{@link #mergeFunction()} knows how to combine two intermediate states
 *       of this aggregate and produce the final value.</li>
 *   <li>{@link #intermediateColumnAlias()} is the alias the pushed plan uses
 *       for the intermediate column, so the engine can find it again when
 *       merging results from multiple sources.</li>
 * </ul>
 */
public interface IntermediateAggregate {

    FunctionSignature originalAggregate();

    Type intermediateType();

    MergeFunction mergeFunction();

    String intermediateColumnAlias();

    static IntermediateAggregate of(FunctionSignature original, Type intermediateType,
                                     MergeFunction mergeFn, String alias) {
        return new ImmutableIntermediateAggregate(original, intermediateType, mergeFn, alias);
    }

    record ImmutableIntermediateAggregate(
        FunctionSignature originalAggregate,
        Type intermediateType,
        MergeFunction mergeFunction,
        String intermediateColumnAlias
    ) implements IntermediateAggregate {}
}
