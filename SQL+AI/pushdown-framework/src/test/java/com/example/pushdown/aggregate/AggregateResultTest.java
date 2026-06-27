package com.example.pushdown.aggregate;

import com.example.pushdown.connector.mock.MockColumnHandle;
import com.example.pushdown.connector.mock.MockTableHandle;
import com.example.pushdown.expression.*;
import com.example.pushdown.type.Type;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class AggregateResultTest {
    @Test
    void aggregateResultCompleteMode() {
        FunctionSignature count = new FunctionSignature(
            "COUNT", List.of(), Type.BIGINT, FunctionVolatility.IMMUTABLE);
        IntermediateAggregate ia = IntermediateAggregate.of(
            count, Type.BIGINT, MergeFunctions.count(), "count_partial");

        AggregateResult result = AggregateResult.of(
            new MockTableHandle("users"),
            AggregateMode.COMPLETE,
            List.of(ia),
            Expressions.TRUE(),  // no remaining HAVING
            List.of()             // no remaining aggregates
        );

        assertThat(result.mode()).isEqualTo(AggregateMode.COMPLETE);
        assertThat(result.intermediateAggregates()).hasSize(1);
        assertThat(result.remainingHaving()).isEqualTo(Expressions.TRUE());
        assertThat(result.remainingAggregates()).isEmpty();
    }

    @Test
    void aggregateResultPartialModeWithResidualHaving() {
        Comparison havingResidual = new Comparison(Operator.GT,
            new Variable(new MockColumnHandle("count_val"), Type.BIGINT),
            new Constant(10, Type.INTEGER));

        AggregateResult result = AggregateResult.of(
            new MockTableHandle("users"),
            AggregateMode.PARTIAL,
            List.of(),
            havingResidual,
            List.of()
        );

        assertThat(result.mode()).isEqualTo(AggregateMode.PARTIAL);
        assertThat(result.remainingHaving()).isEqualTo(havingResidual);
    }

    @Test
    void aggregateModeThreeValues() {
        assertThat(AggregateMode.values())
            .containsExactlyInAnyOrder(AggregateMode.NONE, AggregateMode.PARTIAL, AggregateMode.COMPLETE);
    }
}
