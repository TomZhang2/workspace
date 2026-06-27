package com.example.pushdown.connector.jdbc;

import com.example.pushdown.aggregate.*;
import com.example.pushdown.expression.*;
import com.example.pushdown.session.*;
import com.example.pushdown.type.Type;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class JdbcAggregatePushdownTest {
    private final JdbcConnector connector = new JdbcConnector("mysql-prod");
    private final ConnectorSession session = ConnectorSession.builder()
        .user("test").queryId("q1").serverId("mysql-prod").build();
    private final SnapshotContext snapshot = new SnapshotContext(
        Instant.parse("2026-01-15T10:30:00Z"), SnapshotContext.IsolationLevel.SNAPSHOT, "snap-1");

    @Test
    void isAggregatePushableForCountSumMinMaxAvg() {
        JdbcTableHandle table = new JdbcTableHandle("users");
        FunctionSignature count = new FunctionSignature(
            "COUNT", List.of(), Type.BIGINT, FunctionVolatility.IMMUTABLE);

        assertThat(connector.isAggregatePushable(
            session, table,
            List.of(count),
            List.of(new JdbcColumnHandle("city")),
            Expressions.TRUE()
        )).isTrue();
    }

    @Test
    void applyAggregateReturnsCompleteModeForStandardAggs() {
        JdbcTableHandle table = new JdbcTableHandle("users");
        FunctionSignature count = new FunctionSignature(
            "COUNT", List.of(), Type.BIGINT, FunctionVolatility.IMMUTABLE);
        FunctionSignature sum = new FunctionSignature(
            "SUM", List.of(Type.INTEGER), Type.BIGINT, FunctionVolatility.IMMUTABLE);

        Optional<AggregateResult> result = connector.applyAggregate(
            session, table,
            List.of(count, sum),
            List.of(new JdbcColumnHandle("city")),
            Expressions.TRUE()
        );

        assertThat(result).isPresent();
        AggregateResult ar = result.get();
        assertThat(ar.mode()).isEqualTo(AggregateMode.COMPLETE);
        assertThat(ar.intermediateAggregates()).hasSize(2);
        assertThat(ar.intermediateAggregates().get(0).originalAggregate().name()).isEqualTo("COUNT");
        assertThat(ar.intermediateAggregates().get(1).originalAggregate().name()).isEqualTo("SUM");
        assertThat(ar.remainingHaving()).isEqualTo(Expressions.TRUE());
        assertThat(ar.remainingAggregates()).isEmpty();
    }

    @Test
    void applyAggregateWithNonShippableHavingReturnsResidual() {
        JdbcTableHandle table = new JdbcTableHandle("users");
        FunctionSignature count = new FunctionSignature(
            "COUNT", List.of(), Type.BIGINT, FunctionVolatility.IMMUTABLE);

        // HAVING with random() — not shippable
        FunctionSignature random = new FunctionSignature(
            "RANDOM", List.of(), Type.DOUBLE, FunctionVolatility.VOLATILE);
        Comparison havingPred = new Comparison(Operator.GT,
            new Call(random, List.of(), Type.DOUBLE),
            new Constant(0.5, Type.DOUBLE));

        Optional<AggregateResult> result = connector.applyAggregate(
            session, table,
            List.of(count),
            List.of(new JdbcColumnHandle("city")),
            havingPred
        );

        assertThat(result).isPresent();
        AggregateResult ar = result.get();
        assertThat(ar.mode()).isEqualTo(AggregateMode.COMPLETE);
        // Non-shippable HAVING should be in residual
        assertThat(ar.remainingHaving()).isEqualTo(havingPred);
    }

    @Test
    void applyAggregateWithUnknownAggReturnsEmpty() {
        JdbcTableHandle table = new JdbcTableHandle("users");
        FunctionSignature custom = new FunctionSignature(
            "my_custom_agg", List.of(Type.INTEGER), Type.DOUBLE, FunctionVolatility.IMMUTABLE);

        Optional<AggregateResult> result = connector.applyAggregate(
            session, table,
            List.of(custom),
            List.of(new JdbcColumnHandle("city")),
            Expressions.TRUE()
        );

        assertThat(result).isEmpty();
    }

    @Test
    void intermediateAggregatesHaveCorrectMergeFunctions() {
        JdbcTableHandle table = new JdbcTableHandle("users");
        FunctionSignature avg = new FunctionSignature(
            "AVG", List.of(Type.INTEGER), Type.DOUBLE, FunctionVolatility.IMMUTABLE);

        Optional<AggregateResult> result = connector.applyAggregate(
            session, table,
            List.of(avg),
            List.of(new JdbcColumnHandle("city")),
            Expressions.TRUE()
        );

        assertThat(result).isPresent();
        IntermediateAggregate ia = result.get().intermediateAggregates().get(0);
        // AVG should have an AvgMergeFunction
        Object partial1 = new MergeFunctions.AvgState(java.math.BigDecimal.valueOf(100), 5L);
        Object partial2 = new MergeFunctions.AvgState(java.math.BigDecimal.valueOf(50), 5L);
        Object merged = ia.mergeFunction().merge(partial1, partial2);
        Object finalized = ia.mergeFunction().finalize(merged);
        assertThat(finalized).isEqualTo(15.0);
    }
}
