package com.example.pushdown.deparse;

import com.example.pushdown.connector.mock.MockColumnHandle;
import com.example.pushdown.connector.mock.MockTableHandle;
import com.example.pushdown.expression.*;
import com.example.pushdown.mode.PushdownMode;
import com.example.pushdown.result.*;
import com.example.pushdown.type.Type;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class DefaultSqlDeparserTest {
    private final SqlDeparser deparser = new DefaultSqlDeparser();

    @Test
    void deparseSimpleSelectWithWhere() {
        Comparison pred = new Comparison(Operator.EQ,
            new Variable(new MockColumnHandle("age"), Type.INTEGER),
            new Constant(18, Type.INTEGER));
        ConjunctPushdown cp = FilterResults.conjunct(pred, Optional.of(pred), Expressions.TRUE(), PushdownMode.EXACT);

        PushedPlan plan = PushedPlan.builder()
            .tableHandle(new MockTableHandle("users"))
            .projections(List.of(
                new Variable(new MockColumnHandle("name"), Type.VARCHAR),
                new Variable(new MockColumnHandle("age"), Type.INTEGER)
            ))
            .conjunctResults(List.of(cp))
            .build();

        DeparsedQuery query = deparser.deparseSelectStmt(plan, SqlDialect.MYSQL);

        // Should be: SELECT `name`, `age` FROM `users` WHERE `age` = 18
        assertThat(query.sql()).contains("SELECT");
        assertThat(query.sql()).contains("`name`");
        assertThat(query.sql()).contains("`age`");
        assertThat(query.sql()).contains("FROM `users`");
        assertThat(query.sql()).contains("WHERE");
        assertThat(query.sql()).contains("`age` = 18");
        assertThat(query.fetchSize()).isEqualTo(100);
    }

    @Test
    void deparseWithAnsiDialectUsesDoubleQuotes() {
        Comparison pred = new Comparison(Operator.EQ,
            new Variable(new MockColumnHandle("age"), Type.INTEGER),
            new Constant(18, Type.INTEGER));
        ConjunctPushdown cp = FilterResults.conjunct(pred, Optional.of(pred), Expressions.TRUE(), PushdownMode.EXACT);

        PushedPlan plan = PushedPlan.builder()
            .tableHandle(new MockTableHandle("users"))
            .projections(List.of(new Variable(new MockColumnHandle("name"), Type.VARCHAR)))
            .conjunctResults(List.of(cp))
            .build();

        DeparsedQuery query = deparser.deparseSelectStmt(plan, SqlDialect.ANSI);

        assertThat(query.sql()).contains("\"name\"");
        assertThat(query.sql()).contains("\"users\"");
    }

    @Test
    void deparseWithStringLiteral() {
        Comparison pred = new Comparison(Operator.EQ,
            new Variable(new MockColumnHandle("name"), Type.VARCHAR),
            new Constant("Bob", Type.VARCHAR));
        ConjunctPushdown cp = FilterResults.conjunct(pred, Optional.of(pred), Expressions.TRUE(), PushdownMode.EXACT);

        PushedPlan plan = PushedPlan.builder()
            .tableHandle(new MockTableHandle("users"))
            .projections(List.of(new Variable(new MockColumnHandle("name"), Type.VARCHAR)))
            .conjunctResults(List.of(cp))
            .build();

        DeparsedQuery query = deparser.deparseSelectStmt(plan, SqlDialect.MYSQL);

        assertThat(query.sql()).contains("'Bob'");
    }

    @Test
    void deparseWithLimit() {
        PushedPlan plan = PushedPlan.builder()
            .tableHandle(new MockTableHandle("users"))
            .projections(List.of(new Variable(new MockColumnHandle("name"), Type.VARCHAR)))
            .conjunctResults(List.of())
            .limit(Optional.of(10L))
            .build();

        DeparsedQuery query = deparser.deparseSelectStmt(plan, SqlDialect.MYSQL);

        assertThat(query.sql()).contains("LIMIT 10");
    }

    @Test
    void deparseWithGroupBy() {
        Variable nameCol = new Variable(new MockColumnHandle("category"), Type.VARCHAR);
        PushedPlan plan = PushedPlan.builder()
            .tableHandle(new MockTableHandle("products"))
            .projections(List.of(nameCol))
            .conjunctResults(List.of())
            .groupingKeys(Optional.of(List.of(new MockColumnHandle("category"))))
            .build();

        DeparsedQuery query = deparser.deparseSelectStmt(plan, SqlDialect.MYSQL);

        assertThat(query.sql()).contains("GROUP BY");
        assertThat(query.sql()).contains("`category`");
    }

    @Test
    void deparseWithOrderBy() {
        Variable nameCol = new Variable(new MockColumnHandle("name"), Type.VARCHAR);
        PushedPlan plan = PushedPlan.builder()
            .tableHandle(new MockTableHandle("users"))
            .projections(List.of(nameCol))
            .conjunctResults(List.of())
            .sortItems(Optional.of(List.of(
                new SortItem(nameCol, SortItem.SortDirection.ASC, SortItem.NullOrdering.LAST)
            )))
            .build();

        DeparsedQuery query = deparser.deparseSelectStmt(plan, SqlDialect.MYSQL);

        assertThat(query.sql()).contains("ORDER BY");
        assertThat(query.sql()).contains("`name` ASC");
        assertThat(query.sql()).contains("NULLS LAST");
    }

    @Test
    void deparseOnlyExactConjunctsInWhere() {
        Comparison exactPred = new Comparison(Operator.EQ,
            new Variable(new MockColumnHandle("age"), Type.INTEGER), new Constant(18, Type.INTEGER));
        Comparison inMemoryPred = new Comparison(Operator.EQ,
            new Variable(new MockColumnHandle("name"), Type.VARCHAR), new Constant("Bob", Type.VARCHAR));
        ConjunctPushdown cpExact = FilterResults.conjunct(exactPred, Optional.of(exactPred), Expressions.TRUE(), PushdownMode.EXACT);
        ConjunctPushdown cpInMemory = FilterResults.conjunct(inMemoryPred, Optional.empty(), inMemoryPred, PushdownMode.IN_MEMORY);

        PushedPlan plan = PushedPlan.builder()
            .tableHandle(new MockTableHandle("users"))
            .projections(List.of(new Variable(new MockColumnHandle("name"), Type.VARCHAR)))
            .conjunctResults(List.of(cpExact, cpInMemory))
            .build();

        DeparsedQuery query = deparser.deparseSelectStmt(plan, SqlDialect.MYSQL);

        // EXACT conjunct should be in WHERE; IN_MEMORY should NOT
        assertThat(query.sql()).contains("`age` = 18");
        assertThat(query.sql()).doesNotContain("'Bob'");
    }

    @Test
    void deparseFunctionCall() {
        FunctionSignature upper = new FunctionSignature("UPPER", List.of(Type.VARCHAR), Type.VARCHAR, FunctionVolatility.IMMUTABLE);
        Call upperCall = new Call(upper, List.of(new Variable(new MockColumnHandle("name"), Type.VARCHAR)), Type.VARCHAR);
        Comparison pred = new Comparison(Operator.EQ, upperCall, new Constant("BOB", Type.VARCHAR));
        ConjunctPushdown cp = FilterResults.conjunct(pred, Optional.of(pred), Expressions.TRUE(), PushdownMode.EXACT);

        PushedPlan plan = PushedPlan.builder()
            .tableHandle(new MockTableHandle("users"))
            .projections(List.of(new Variable(new MockColumnHandle("name"), Type.VARCHAR)))
            .conjunctResults(List.of(cp))
            .build();

        DeparsedQuery query = deparser.deparseSelectStmt(plan, SqlDialect.MYSQL);

        assertThat(query.sql()).contains("UPPER(`name`)");
        assertThat(query.sql()).contains("'BOB'");
    }
}
