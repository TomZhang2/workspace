package com.example.pushdown.aggregate;

import com.example.pushdown.expression.ConnectorExpression;
import com.example.pushdown.expression.FunctionSignature;
import com.example.pushdown.handle.TableHandle;
import java.util.List;

/**
 * Outcome of an aggregate pushdown attempt on a single source.
 *
 * <ul>
 *   <li>{@link #pushedTable()} — the (possibly rewritten) table handle the
 *       engine should scan to obtain the intermediate rows.</li>
 *   <li>{@link #mode()} — {@link AggregateMode#PARTIAL} or
 *       {@link AggregateMode#COMPLETE}; {@link AggregateMode#NONE} is not
 *       returned because the connector signals "not pushable" with
 *       {@code Optional.empty()} instead.</li>
 *   <li>{@link #intermediateAggregates()} — one {@link IntermediateAggregate}
 *       per pushed aggregate; the engine uses these to find the intermediate
 *       columns and merge them.</li>
 *   <li>{@link #remainingHaving()} — the residual HAVING predicate the engine
 *       must still evaluate after the source returns rows. Equals
 *       {@code TRUE} when the source handled all of HAVING.</li>
 *   <li>{@link #remainingAggregates()} — aggregates that could not be pushed
 *       (e.g. non-shippable UDAF); the engine must compute them in memory.
 *       Empty when every aggregate was pushed.</li>
 * </ul>
 */
public interface AggregateResult {

    TableHandle pushedTable();

    AggregateMode mode();

    List<IntermediateAggregate> intermediateAggregates();

    ConnectorExpression remainingHaving();

    List<FunctionSignature> remainingAggregates();

    static AggregateResult of(TableHandle table, AggregateMode mode,
                               List<IntermediateAggregate> intermediates,
                               ConnectorExpression remainingHaving,
                               List<FunctionSignature> remainingAggs) {
        return new ImmutableAggregateResult(table, mode, intermediates, remainingHaving, remainingAggs);
    }

    record ImmutableAggregateResult(
        TableHandle pushedTable,
        AggregateMode mode,
        List<IntermediateAggregate> intermediateAggregates,
        ConnectorExpression remainingHaving,
        List<FunctionSignature> remainingAggregates
    ) implements AggregateResult {}
}
