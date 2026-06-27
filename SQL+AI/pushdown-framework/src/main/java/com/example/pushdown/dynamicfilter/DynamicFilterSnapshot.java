package com.example.pushdown.dynamicfilter;

import com.example.pushdown.expression.ConnectorExpression;
import java.util.Optional;

/**
 * Immutable snapshot of dynamic filter state at a point in time.
 * Passed to split sources for partition/file pruning.
 */
public record DynamicFilterSnapshot(
    Optional<ConnectorExpression> predicate,
    boolean complete,
    boolean degraded,
    boolean error
) {
    public static DynamicFilterSnapshot of(ConnectorExpression predicate, boolean complete,
                                            boolean degraded, boolean error) {
        return new DynamicFilterSnapshot(Optional.of(predicate), complete, degraded, error);
    }

    public static DynamicFilterSnapshot empty() {
        return new DynamicFilterSnapshot(Optional.empty(), false, false, false);
    }

    public static DynamicFilterSnapshot errorState() {
        return new DynamicFilterSnapshot(Optional.empty(), false, false, true);
    }
}
