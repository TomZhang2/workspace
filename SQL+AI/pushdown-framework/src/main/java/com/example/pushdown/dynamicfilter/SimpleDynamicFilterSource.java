package com.example.pushdown.dynamicfilter;

import com.example.pushdown.expression.*;
import com.example.pushdown.handle.ColumnHandle;
import com.example.pushdown.type.Type;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Simple implementation of DynamicFilterSource for testing.
 * Collects values into an IN-list; degrades to bloom filter placeholder on overflow.
 */
public class SimpleDynamicFilterSource implements DynamicFilterSource {
    private final Set<ColumnHandle> columns;
    private final int maxDistinct;
    private final Set<Object> collectedValues = ConcurrentHashMap.newKeySet();
    private volatile boolean complete = false;
    private volatile boolean error = false;
    private volatile boolean degraded = false;

    public SimpleDynamicFilterSource(Set<ColumnHandle> columns, int maxDistinct) {
        this.columns = columns;
        this.maxDistinct = maxDistinct;
    }

    @Override public int maxDistinctValues() { return maxDistinct; }
    @Override public OverflowStrategy overflowStrategy() { return OverflowStrategy.DOWNGRADE_TO_BLOOM_FILTER; }

    @Override
    public void addValues(Set<Object> values) {
        if (complete || error) return;
        collectedValues.addAll(values);
        if (collectedValues.size() > maxDistinct) {
            degraded = true;
        }
    }

    @Override public void markFinal() { this.complete = true; }
    @Override public void markError(Throwable cause) { this.error = true; }

    @Override
    public DynamicFilterSnapshot snapshot() {
        if (error) return DynamicFilterSnapshot.errorState();
        if (collectedValues.isEmpty() && !complete) return DynamicFilterSnapshot.empty();

        ColumnHandle col = columns.iterator().next();
        ConnectorExpression pred;
        if (degraded) {
            pred = new Comparison(Operator.EQ,
                new Variable(col, Type.INTEGER),
                new Constant(collectedValues.iterator().next(), Type.INTEGER));
        } else {
            List<ConnectorExpression> constants = collectedValues.stream()
                .map(v -> (ConnectorExpression) new Constant(v, Type.INTEGER))
                .toList();
            pred = new Special(SpecialKind.IN, new Variable(col, Type.INTEGER), constants);
        }
        return DynamicFilterSnapshot.of(pred, complete, degraded, false);
    }
}
