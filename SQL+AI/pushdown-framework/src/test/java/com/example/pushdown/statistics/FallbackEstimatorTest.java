package com.example.pushdown.statistics;

import com.example.pushdown.connector.mock.MockColumnHandle;
import com.example.pushdown.expression.*;
import com.example.pushdown.type.Type;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class FallbackEstimatorTest {
    private final FallbackEstimator estimator = new FallbackEstimator();

    @Test
    void equalitySelectivityDefault() {
        Comparison eq = new Comparison(Operator.EQ,
            new Variable(new MockColumnHandle("age"), Type.INTEGER),
            new Constant(18, Type.INTEGER));
        double sel = estimator.estimateSelectivity(eq, Optional.empty());
        assertThat(sel).isCloseTo(0.005, within(0.001));
    }

    @Test
    void rangeSelectivityDefault() {
        Comparison gt = new Comparison(Operator.GT,
            new Variable(new MockColumnHandle("age"), Type.INTEGER),
            new Constant(18, Type.INTEGER));
        double sel = estimator.estimateSelectivity(gt, Optional.empty());
        assertThat(sel).isCloseTo(0.33, within(0.01));
    }

    @Test
    void isNullSelectivityDefault() {
        Special isNull = new Special(SpecialKind.IS_NULL,
            new Variable(new MockColumnHandle("name"), Type.VARCHAR), List.of());
        double sel = estimator.estimateSelectivity(isNull, Optional.empty());
        assertThat(sel).isCloseTo(0.05, within(0.01));
    }

    @Test
    void andSelectivityMultiplies() {
        Comparison a = new Comparison(Operator.EQ,
            new Variable(new MockColumnHandle("a"), Type.INTEGER), new Constant(1, Type.INTEGER));
        Comparison b = new Comparison(Operator.EQ,
            new Variable(new MockColumnHandle("b"), Type.INTEGER), new Constant(2, Type.INTEGER));
        Logical and = new Logical(LogicalOperator.AND, List.of(a, b));

        double sel = estimator.estimateSelectivity(and, Optional.empty());
        // 0.005 * 0.005 = 0.000025
        assertThat(sel).isCloseTo(0.000025, within(0.000001));
    }

    @Test
    void equalityWithStatsUsesNDV() {
        Comparison eq = new Comparison(Operator.EQ,
            new Variable(new MockColumnHandle("age"), Type.INTEGER),
            new Constant(18, Type.INTEGER));

        TableStatistics stats = TableStatistics.of(10000, Map.of(
            new MockColumnHandle("age"), ColumnStatistics.of(100, 0, 0, 100, Type.INTEGER)
        ));

        double sel = estimator.estimateSelectivity(eq, Optional.of(stats));
        // sel = 1/NDV = 1/100 = 0.01
        assertThat(sel).isCloseTo(0.01, within(0.001));
    }
}
