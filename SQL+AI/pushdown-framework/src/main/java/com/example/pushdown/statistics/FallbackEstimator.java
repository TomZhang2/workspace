package com.example.pushdown.statistics;

import com.example.pushdown.expression.Comparison;
import com.example.pushdown.expression.ConnectorExpression;
import com.example.pushdown.expression.Logical;
import com.example.pushdown.expression.LogicalOperator;
import com.example.pushdown.expression.Operator;
import com.example.pushdown.expression.Special;
import com.example.pushdown.expression.Variable;
import java.util.Optional;

public class FallbackEstimator {

    private static final double DEFAULT_EQUALITY = 0.005;
    private static final double DEFAULT_RANGE = 0.33;
    private static final double DEFAULT_IS_NULL = 0.05;
    private static final double DEFAULT_LIKE = 0.1;
    private static final double DEFAULT_UNKNOWN = 0.1;

    public double estimateSelectivity(ConnectorExpression predicate, Optional<TableStatistics> stats) {
        return stats
            .map(s -> estimateWithStats(predicate, s))
            .orElseGet(() -> estimateWithoutStats(predicate));
    }

    private double estimateWithoutStats(ConnectorExpression expr) {
        if (expr instanceof Comparison cmp) {
            return cmp.op() == Operator.EQ ? DEFAULT_EQUALITY : DEFAULT_RANGE;
        }
        if (expr instanceof Special s) {
            return switch (s.kind()) {
                case IS_NULL, IS_NOT_NULL -> DEFAULT_IS_NULL;
                case LIKE -> DEFAULT_LIKE;
                default -> DEFAULT_UNKNOWN;
            };
        }
        if (expr instanceof Logical log) {
            if (log.op() == LogicalOperator.AND) {
                return log.terms().stream()
                    .mapToDouble(this::estimateWithoutStats)
                    .reduce(1.0, (a, b) -> a * b);
            }
            if (log.op() == LogicalOperator.OR) {
                return 1.0 - log.terms().stream()
                    .mapToDouble(t -> 1.0 - estimateWithoutStats(t))
                    .reduce(1.0, (a, b) -> a * b);
            }
        }
        return DEFAULT_UNKNOWN;
    }

    private double estimateWithStats(ConnectorExpression expr, TableStatistics stats) {
        if (expr instanceof Comparison cmp) {
            if (cmp.left() instanceof Variable var) {
                ColumnStatistics colStats = stats.columnStats().get(var.column());
                if (colStats != null && cmp.op() == Operator.EQ) {
                    long ndv = colStats.distinctValuesCount();
                    if (ndv > 0) {
                        return 1.0 / ndv;
                    }
                }
                if (colStats != null && cmp.op().isRange()
                        && colStats.minValue().isPresent() && colStats.maxValue().isPresent()) {
                    return DEFAULT_RANGE; // simplified — could compute range overlap
                }
            }
            return cmp.op() == Operator.EQ ? DEFAULT_EQUALITY : DEFAULT_RANGE;
        }
        if (expr instanceof Logical log && log.op() == LogicalOperator.AND) {
            return log.terms().stream()
                .mapToDouble(t -> estimateWithStats(t, stats))
                .reduce(1.0, (a, b) -> a * b);
        }
        return estimateWithoutStats(expr);
    }
}
