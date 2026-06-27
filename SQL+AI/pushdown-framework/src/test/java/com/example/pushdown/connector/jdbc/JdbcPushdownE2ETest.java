package com.example.pushdown.connector.jdbc;

import com.example.pushdown.deparse.*;
import com.example.pushdown.expression.*;
import com.example.pushdown.mode.PushdownMode;
import com.example.pushdown.planner.*;
import com.example.pushdown.result.*;
import com.example.pushdown.session.*;
import com.example.pushdown.type.Type;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class JdbcPushdownE2ETest {
    private final JdbcConnector connector = new JdbcConnector("mysql-prod");
    private final PushdownPlanner planner = new PushdownPlanner(connector);
    private final SqlDeparser deparser = new DefaultSqlDeparser();
    private final ConnectorSession session = ConnectorSession.builder()
        .user("alice").queryId("q-001").serverId("mysql-prod").build();
    private final SnapshotContext snapshot = new SnapshotContext(
        Instant.parse("2026-01-15T10:30:00Z"), SnapshotContext.IsolationLevel.SNAPSHOT, "snap-1");

    @Test
    void fullPipelineGeneratesCorrectSql() {
        JdbcTableHandle table = new JdbcTableHandle("users");
        // WHERE age = 18
        Comparison pred = new Comparison(Operator.EQ,
            new Variable(new JdbcColumnHandle("age"), Type.INTEGER),
            new Constant(18, Type.INTEGER));

        // Plan + execute
        Optional<FilterResult> result = planner.planAndExecuteFilter(session, table, pred, snapshot);
        assertThat(result).isPresent();

        // Build PushedPlan from result
        FilterResult fr = result.get();
        PushedPlan plan = PushedPlan.builder()
            .tableHandle(table)
            .projections(List.of(
                new Variable(new JdbcColumnHandle("name"), Type.VARCHAR),
                new Variable(new JdbcColumnHandle("age"), Type.INTEGER)
            ))
            .conjunctResults(fr.conjunctResults())
            .build();

        // Deparse to SQL
        DeparsedQuery query = deparser.deparseSelectStmt(plan, SqlDialect.MYSQL);

        // Verify SQL
        assertThat(query.sql()).contains("SELECT");
        assertThat(query.sql()).contains("`name`");
        assertThat(query.sql()).contains("`age`");
        assertThat(query.sql()).contains("FROM `users`");
        assertThat(query.sql()).contains("WHERE");
        assertThat(query.sql()).contains("`age` = 18");
    }

    @Test
    void mixedConjunctsGenerateSqlWithOnlyExactInWhere() {
        JdbcTableHandle table = new JdbcTableHandle("users");
        // WHERE age = 18 AND random() > 0.5
        Comparison shippable = new Comparison(Operator.EQ,
            new Variable(new JdbcColumnHandle("age"), Type.INTEGER),
            new Constant(18, Type.INTEGER));
        FunctionSignature random = new FunctionSignature("RANDOM", List.of(), Type.DOUBLE, FunctionVolatility.VOLATILE);
        Comparison nonShippable = new Comparison(Operator.GT,
            new Call(random, List.of(), Type.DOUBLE),
            new Constant(0.5, Type.DOUBLE));
        ConnectorExpression predicate = Expressions.logicalAnd(shippable, nonShippable);

        Optional<FilterResult> result = planner.planAndExecuteFilter(session, table, predicate, snapshot);
        assertThat(result).isPresent();

        FilterResult fr = result.get();
        PushedPlan plan = PushedPlan.builder()
            .tableHandle(table)
            .projections(List.of(new Variable(new JdbcColumnHandle("name"), Type.VARCHAR)))
            .conjunctResults(fr.conjunctResults())
            .build();

        DeparsedQuery query = deparser.deparseSelectStmt(plan, SqlDialect.MYSQL);

        // EXACT conjunct (age=18) should be in WHERE
        assertThat(query.sql()).contains("`age` = 18");
        // IN_MEMORY conjunct (random()>0.5) should NOT be in WHERE
        assertThat(query.sql()).doesNotContain("RANDOM");
    }

    @Test
    void stableFunctionPinnedInGeneratedSql() {
        JdbcTableHandle table = new JdbcTableHandle("events");
        // WHERE created_at <= now()
        FunctionSignature now = new FunctionSignature("NOW", List.of(), Type.TIMESTAMP, FunctionVolatility.STABLE);
        Comparison pred = new Comparison(Operator.LTE,
            new Variable(new JdbcColumnHandle("created_at"), Type.TIMESTAMP),
            new Call(now, List.of(), Type.TIMESTAMP));

        Optional<FilterResult> result = planner.planAndExecuteFilter(session, table, pred, snapshot);
        assertThat(result).isPresent();

        FilterResult fr = result.get();
        PushedPlan plan = PushedPlan.builder()
            .tableHandle(table)
            .projections(List.of(new Variable(new JdbcColumnHandle("event_id"), Type.INTEGER)))
            .conjunctResults(fr.conjunctResults())
            .build();

        DeparsedQuery query = deparser.deparseSelectStmt(plan, SqlDialect.MYSQL);

        // NOW() should be replaced with pinned timestamp literal
        assertThat(query.sql()).doesNotContain("NOW()");
        // Should contain the pinned timestamp as a literal
        assertThat(query.sql()).contains("2026-01-15T10:30:00Z");
    }

    @Test
    void functionCallInWhereGeneratesCorrectSql() {
        JdbcTableHandle table = new JdbcTableHandle("users");
        // WHERE UPPER(name) = 'BOB'
        FunctionSignature upper = new FunctionSignature("UPPER", List.of(Type.VARCHAR), Type.VARCHAR, FunctionVolatility.IMMUTABLE);
        Call upperCall = new Call(upper, List.of(new Variable(new JdbcColumnHandle("name"), Type.VARCHAR)), Type.VARCHAR);
        Comparison pred = new Comparison(Operator.EQ, upperCall, new Constant("BOB", Type.VARCHAR));

        Optional<FilterResult> result = planner.planAndExecuteFilter(session, table, pred, snapshot);
        assertThat(result).isPresent();

        FilterResult fr = result.get();
        PushedPlan plan = PushedPlan.builder()
            .tableHandle(table)
            .projections(List.of(new Variable(new JdbcColumnHandle("name"), Type.VARCHAR)))
            .conjunctResults(fr.conjunctResults())
            .build();

        DeparsedQuery query = deparser.deparseSelectStmt(plan, SqlDialect.MYSQL);

        assertThat(query.sql()).contains("UPPER(`name`)");
        assertThat(query.sql()).contains("'BOB'");
    }
}
