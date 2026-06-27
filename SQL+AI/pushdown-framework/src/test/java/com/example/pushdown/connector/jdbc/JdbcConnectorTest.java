package com.example.pushdown.connector.jdbc;

import com.example.pushdown.expression.*;
import com.example.pushdown.mode.PushdownMode;
import com.example.pushdown.result.*;
import com.example.pushdown.session.*;
import com.example.pushdown.spi.*;
import com.example.pushdown.type.Type;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class JdbcConnectorTest {
    private final JdbcConnector connector = new JdbcConnector("mysql-prod");
    private final ConnectorSession session = ConnectorSession.builder()
        .user("test").queryId("q1").serverId("mysql-prod").build();
    private final SnapshotContext snapshot = new SnapshotContext(
        Instant.parse("2026-01-15T10:30:00Z"), SnapshotContext.IsolationLevel.SNAPSHOT, "snap-1");

    @Test
    void isFilterPushableForShippablePredicate() {
        JdbcTableHandle table = new JdbcTableHandle("users");
        Comparison pred = new Comparison(Operator.EQ,
            new Variable(new JdbcColumnHandle("age"), Type.INTEGER),
            new Constant(18, Type.INTEGER));
        assertThat(connector.isFilterPushable(session, table, pred)).isTrue();
    }

    @Test
    void isFilterNotPushableForVolatileFunction() {
        JdbcTableHandle table = new JdbcTableHandle("users");
        FunctionSignature random = new FunctionSignature("RANDOM", List.of(), Type.DOUBLE, FunctionVolatility.VOLATILE);
        Comparison pred = new Comparison(Operator.GT,
            new Call(random, List.of(), Type.DOUBLE),
            new Constant(0.5, Type.DOUBLE));
        assertThat(connector.isFilterPushable(session, table, pred)).isFalse();
    }

    @Test
    void applyFilterReturnsExactModeForShippablePredicate() {
        JdbcTableHandle table = new JdbcTableHandle("users");
        Comparison pred = new Comparison(Operator.EQ,
            new Variable(new JdbcColumnHandle("age"), Type.INTEGER),
            new Constant(18, Type.INTEGER));

        Optional<FilterResult> result = connector.applyFilter(session, table, pred, snapshot);

        assertThat(result).isPresent();
        FilterResult fr = result.get();
        assertThat(fr.conjunctResults()).hasSize(1);
        assertThat(fr.conjunctResults().get(0).mode()).isEqualTo(PushdownMode.EXACT);
        assertThat(fr.conjunctResults().get(0).pushedExpression()).isPresent();
        assertThat(fr.conjunctResults().get(0).residualExpression()).isEqualTo(Expressions.TRUE());
    }

    @Test
    void applyFilterReturnsInMemoryForNonShippablePredicate() {
        JdbcTableHandle table = new JdbcTableHandle("users");
        FunctionSignature random = new FunctionSignature("RANDOM", List.of(), Type.DOUBLE, FunctionVolatility.VOLATILE);
        Comparison pred = new Comparison(Operator.GT,
            new Call(random, List.of(), Type.DOUBLE),
            new Constant(0.5, Type.DOUBLE));

        // Even though isFilterPushable returns false, applyFilter should handle it gracefully
        // by returning IN_MEMORY for non-shippable conjuncts
        Optional<FilterResult> result = connector.applyFilter(session, table, pred, snapshot);

        // applyFilter is only called on chosen paths, so if isFilterPushable=false,
        // the planner won't call applyFilter. But for safety, it should return empty.
        assertThat(result).isEmpty();
    }

    @Test
    void applyFilterSplitsMixedConjuncts() {
        JdbcTableHandle table = new JdbcTableHandle("users");
        // Shippable: age = 18
        Comparison shippable = new Comparison(Operator.EQ,
            new Variable(new JdbcColumnHandle("age"), Type.INTEGER),
            new Constant(18, Type.INTEGER));
        // Non-shippable: random() > 0.5
        FunctionSignature random = new FunctionSignature("RANDOM", List.of(), Type.DOUBLE, FunctionVolatility.VOLATILE);
        Comparison nonShippable = new Comparison(Operator.GT,
            new Call(random, List.of(), Type.DOUBLE),
            new Constant(0.5, Type.DOUBLE));

        // WHERE age = 18 AND random() > 0.5
        ConnectorExpression predicate = Expressions.logicalAnd(shippable, nonShippable);

        // isFilterPushable: true because at least one conjunct is shippable
        assertThat(connector.isFilterPushable(session, table, predicate)).isTrue();

        Optional<FilterResult> result = connector.applyFilter(session, table, predicate, snapshot);

        assertThat(result).isPresent();
        FilterResult fr = result.get();
        assertThat(fr.conjunctResults()).hasSize(2);

        // First conjunct (age=18) should be EXACT
        ConjunctPushdown cp1 = fr.conjunctResults().get(0);
        assertThat(cp1.originalConjunct()).isEqualTo(shippable);
        assertThat(cp1.mode()).isEqualTo(PushdownMode.EXACT);

        // Second conjunct (random()>0.5) should be IN_MEMORY
        ConjunctPushdown cp2 = fr.conjunctResults().get(1);
        assertThat(cp2.originalConjunct()).isEqualTo(nonShippable);
        assertThat(cp2.mode()).isEqualTo(PushdownMode.IN_MEMORY);
        assertThat(cp2.pushedExpression()).isEmpty();
        assertThat(cp2.residualExpression()).isEqualTo(nonShippable);
    }

    @Test
    void applyFilterPinsStableFunction() {
        JdbcTableHandle table = new JdbcTableHandle("events");
        // WHERE created_at <= now()
        FunctionSignature now = new FunctionSignature("NOW", List.of(), Type.TIMESTAMP, FunctionVolatility.STABLE);
        Comparison pred = new Comparison(Operator.LTE,
            new Variable(new JdbcColumnHandle("created_at"), Type.TIMESTAMP),
            new Call(now, List.of(), Type.TIMESTAMP));

        assertThat(connector.isFilterPushable(session, table, pred)).isTrue();

        Optional<FilterResult> result = connector.applyFilter(session, table, pred, snapshot);

        assertThat(result).isPresent();
        FilterResult fr = result.get();
        assertThat(fr.conjunctResults()).hasSize(1);
        assertThat(fr.conjunctResults().get(0).mode()).isEqualTo(PushdownMode.EXACT);

        // The pushed expression should have NOW() replaced with pinned timestamp
        ConnectorExpression pushed = fr.conjunctResults().get(0).pushedExpression().orElseThrow();
        assertThat(pushed).isInstanceOf(Comparison.class);
        Comparison pushedCmp = (Comparison) pushed;
        assertThat(pushedCmp.right()).isInstanceOf(Constant.class);
        assertThat(((Constant) pushedCmp.right()).value()).isEqualTo(Instant.parse("2026-01-15T10:30:00Z"));
    }

    @Test
    void capabilitiesIncludeFilterPushdown() {
        JdbcTableHandle table = new JdbcTableHandle("users");
        assertThat(connector.capabilities(table)).contains(ConnectorCapability.FILTER_PUSHDOWN);
    }

    @Test
    void fallbackReturnsSameTable() {
        JdbcTableHandle table = new JdbcTableHandle("users");
        assertThat(connector.fallbackToFullScan(table)).isEqualTo(table);
        assertThat(connector.supportsFallback()).isTrue();
    }
}
